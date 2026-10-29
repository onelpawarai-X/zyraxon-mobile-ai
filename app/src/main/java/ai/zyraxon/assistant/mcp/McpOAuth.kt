package ai.zyraxon.assistant.mcp

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** The four endpoints an OAuth flow needs, discovered rather than hard-coded. */
data class OAuthEndpoints(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String,
    val scopesSupported: List<String>
)

/** A dynamic client registration result. */
data class OAuthClient(val clientId: String, val clientSecret: String?)

/** The tokens a successful flow yields. */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis in 1..now
}

/**
 * OAuth 2.1 for MCP servers, done the way the live servers actually demand.
 *
 * Everything here was checked against Canva, Cloudflare and Supabase:
 *  - all three want a **loopback** redirect (`http://127.0.0.1:port`), not an app
 *    scheme, so a local HTTP server catches the code;
 *  - all three support **dynamic client registration**, so no client id has to be
 *    baked into the APK;
 *  - all three support **PKCE S256**, which is what makes a public client safe;
 *  - Canva and Cloudflare sit behind a browser check, so a desktop Chrome
 *    User-Agent is sent on every request.
 */
object McpOAuth {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private val random = SecureRandom()

    // ---- PKCE ---------------------------------------------------------------

    fun newCodeVerifier(): String {
        val bytes = ByteArray(48).also(random::nextBytes)
        return b64Url(bytes)
    }

    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return b64Url(digest)
    }

    fun newState(): String = b64Url(ByteArray(24).also(random::nextBytes))

    private fun b64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    // ---- discovery ----------------------------------------------------------

    /**
     * Finds the authorization server for an MCP endpoint.
     *
     * Supabase publishes protected-resource metadata under a path that includes
     * the endpoint path (`/.well-known/oauth-protected-resource/mcp`) and points
     * at a *different* host (`api.supabase.com`), so both the conventional paths
     * and the cross-host hop have to be handled.
     */
    suspend fun discover(serverUrl: String): OAuthEndpoints = withContext(Dispatchers.IO) {
        val uri = URI(serverUrl)
        val origin = "${uri.scheme}://${uri.authority}"
        val path = uri.path.orEmpty().trimEnd('/')

        // 1. Where is the authorization server?
        var authServer = origin
        val prmCandidates = buildList {
            if (path.isNotEmpty()) add("$origin/.well-known/oauth-protected-resource$path")
            add("$origin/.well-known/oauth-protected-resource")
        }
        for (candidate in prmCandidates) {
            val prm = getJson(candidate) ?: continue
            prm.optJSONArray("authorization_servers")
                ?.optString(0)?.takeIf { it.isNotBlank() }?.let { authServer = it }
            break
        }

        // 2. Its metadata. RFC 8414 says the issuer path is appended, so try both.
        val asCandidates = buildList {
            add("$authServer/.well-known/oauth-authorization-server")
            val issuerPath = runCatching { URI(authServer).path.orEmpty().trimEnd('/') }.getOrDefault("")
            if (issuerPath.isNotEmpty()) add("$authServer/.well-known/oauth-authorization-server$issuerPath")
        }
        for (candidate in asCandidates) {
            val md = getJson(candidate) ?: continue
            val authz = md.optString("authorization_endpoint")
            val token = md.optString("token_endpoint")
            if (authz.isBlank() || token.isBlank()) continue
            val scopes = md.optJSONArray("scopes_supported")
                ?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            return@withContext OAuthEndpoints(
                authorizationEndpoint = authz,
                tokenEndpoint = token,
                registrationEndpoint = md.optString("registration_endpoint"),
                scopesSupported = scopes
            )
        }
        throw McpError("OAuth মেটাডেটা পাওয়া যায়নি: $serverUrl")
    }

    // ---- dynamic client registration ---------------------------------------

    /**
     * Registers this install as its own OAuth client. No client id ships in the
     * APK, so there is nothing to revoke centrally and no secret to leak.
     */
    suspend fun register(
        registrationEndpoint: String,
        redirectUri: String,
        clientName: String = "ZYRAXON Android"
    ): OAuthClient = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("client_name", clientName)
            .put("redirect_uris", org.json.JSONArray(listOf(redirectUri)))
            .put("grant_types", org.json.JSONArray(listOf("authorization_code", "refresh_token")))
            .put("response_types", org.json.JSONArray(listOf("code")))
            .put("token_endpoint_auth_method", "none")
            .put("application_type", "native")

        val request = Request.Builder()
            .url(registrationEndpoint)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw McpError("ক্লায়েন্ট রেজিস্ট্রেশন ব্যর্থ (HTTP ${response.code}): ${text.take(200)}")
            }
            val obj = runCatching { JSONObject(text) }.getOrElse { throw McpError("রেজিস্ট্রেশন উত্তর পড়া যায়নি") }
            val id = obj.optString("client_id")
            if (id.isBlank()) throw McpError("client_id পাওয়া যায়নি")
            OAuthClient(id, obj.optString("client_secret").takeIf { it.isNotBlank() })
        }
    }

    // ---- authorize / exchange ----------------------------------------------

    fun buildAuthorizationUrl(
        endpoints: OAuthEndpoints,
        clientId: String,
        redirectUri: String,
        state: String,
        challenge: String,
        scopes: List<String>,
        resource: String
    ): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256"
        )
        if (scopes.isNotEmpty()) params["scope"] = scopes.joinToString(" ")
        if (resource.isNotBlank()) params["resource"] = resource
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return "${endpoints.authorizationEndpoint}?$query"
    }

    suspend fun exchangeCode(
        endpoints: OAuthEndpoints,
        client: OAuthClient,
        code: String,
        redirectUri: String,
        verifier: String,
        resource: String
    ): OAuthTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", client.clientId)
            .add("code_verifier", verifier)
        client.clientSecret?.let { form.add("client_secret", it) }
        if (resource.isNotBlank()) form.add("resource", resource)
        postToken(endpoints.tokenEndpoint, form.build())
    }

    suspend fun refresh(
        endpoints: OAuthEndpoints,
        client: OAuthClient,
        refreshToken: String,
        resource: String
    ): OAuthTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", client.clientId)
        client.clientSecret?.let { form.add("client_secret", it) }
        if (resource.isNotBlank()) form.add("resource", resource)
        postToken(endpoints.tokenEndpoint, form.build())
    }

    private fun postToken(tokenEndpoint: String, form: FormBody): OAuthTokens {
        val request = Request.Builder()
            .url(tokenEndpoint)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .post(form)
            .build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw McpError("টোকেন বিনিময় ব্যর্থ (HTTP ${response.code}): ${text.take(200)}")
            }
            val obj = runCatching { JSONObject(text) }.getOrElse { throw McpError("টোকেন উত্তর পড়া যায়নি") }
            val access = obj.optString("access_token")
            if (access.isBlank()) {
                throw McpError(obj.optString("error_description", "access_token পাওয়া যায়নি"))
            }
            val expiresIn = obj.optLong("expires_in", 3600L)
            OAuthTokens(
                accessToken = access,
                refreshToken = obj.optString("refresh_token"),
                // A minute of slack, so a token does not expire mid-call.
                expiresAtMillis = System.currentTimeMillis() + (expiresIn - 60L).coerceAtLeast(30L) * 1000L
            )
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun getJson(url: String): JSONObject? = try {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                val text = response.body?.string().orEmpty()
                runCatching { JSONObject(text) }.getOrNull()
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
