package ai.zyraxon.assistant.mcp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns every MCP server the assistant knows about: which are connected, what
 * tools each one offers, and the tokens needed to call them.
 *
 * The important promise is the one the user asked for: a connected server's tools
 * are injected into **every** turn automatically, so the model can use them
 * without being told they exist. [allTools] is what the engine reads each round.
 *
 * State is persisted so a restart keeps the connections. Tokens live in the same
 * private SharedPreferences the rest of the app uses.
 */
class McpManager(private val context: Context) {

    companion object {
        private const val TAG = "McpManager"
        private const val PREFS = "zyraxon_mcp"
        private const val KEY_SERVERS = "servers"
        private const val KEY_TOOLS = "tools"
        private const val KEY_STATE = "conn_state"

        private const val K_ACCESS = "access"
        private const val K_REFRESH = "refresh"
        private const val K_EXPIRES = "expires"
        private const val K_CLIENT_ID = "client_id"
        private const val K_CLIENT_SECRET = "client_secret"
        private const val K_AUTHZ = "authz"
        private const val K_TOKEN_EP = "token_ep"
        private const val K_REG_EP = "reg_ep"
        private const val K_SCOPES = "scopes"
        private const val K_PAT = "pat"
        private const val K_ACCOUNT = "account"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _connections = MutableStateFlow<List<McpConnection>>(emptyList())
    val connections: StateFlow<List<McpConnection>> = _connections.asStateFlow()

    /** Serialises OAuth flows so two taps cannot fight over the loopback port. */
    private val flowLock = Mutex()

    /**
     * Where a half-finished OAuth flow lives. It outlives the call that started
     * it, because the user may take minutes in the browser before returning.
     */
    private val finishScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The loopback server currently listening, if a flow is in progress. */
    @Volatile private var pendingServer: McpOAuthServer? = null

    init {
        _connections.value = loadConnections()
    }

    // ---- public surface -----------------------------------------------------

    /** Every tool from every connected server, in OpenAI function shape. */
    fun allTools(): List<McpTool> =
        _connections.value.filter { it.status == McpStatus.CONNECTED }.flatMap { it.tools }

    /** The servers, catalogue first, then anything the user added. */
    fun servers(): List<McpServer> =
        _connections.value.map { it.server }

    fun connection(id: String): McpConnection? =
        _connections.value.firstOrNull { it.server.id == id }

    fun connectedCount(): Int = _connections.value.count { it.status == McpStatus.CONNECTED }

    fun totalToolCount(): Int = allTools().size

    // ---- connect (token / none) --------------------------------------------

    /**
     * Connects a server that authenticates with a static token, or with nothing
     * at all. The token is verified immediately by listing tools, so a bad token
     * fails here instead of silently later.
     */
    suspend fun connectWithToken(server: McpServer, token: String?): McpConnection =
        withContext(Dispatchers.IO) {
            update(server.id) { it.copy(status = McpStatus.CONNECTING, error = "") }
            token?.takeIf { it.isNotBlank() }?.let { prefs.edit().putString(patKey(server.id), it).apply() }

            try {
                val tools = fetchTools(server)
                val done = McpConnection(
                    server = server,
                    status = McpStatus.CONNECTED,
                    tools = tools,
                    account = accountLabel(server, token)
                )
                persistServer(server)
                update(server.id) { done }
                cacheTools(server.id, tools)
                Log.i(TAG, "${server.id}: connected, ${tools.size} tools")
                done
            } catch (e: Exception) {
                val failed = McpConnection(
                    server = server,
                    status = if (e is McpAuthRequired) McpStatus.NEEDS_AUTH else McpStatus.FAILED,
                    error = e.message.orEmpty()
                )
                update(server.id) { failed }
                Log.w(TAG, "${server.id}: connect failed — ${e.message}")
                failed
            }
        }

    // ---- connect (OAuth) ---------------------------------------------------

    /**
     * Starts the OAuth flow and returns the URL to open in the browser.
     *
     * The flow then continues in the background: the loopback server waits for the
     * redirect, the code is exchanged, tools are fetched. The UI observes
     * [connections] to learn when it finished — nothing else has to be called.
     *
     * [onProgress] reports each stage so the screen can say what is happening.
     */
    suspend fun beginOAuth(
        server: McpServer,
        onProgress: (String) -> Unit = {}
    ): String = flowLock.withLock {
        withContext(Dispatchers.IO) {
            update(server.id) { it.copy(status = McpStatus.CONNECTING, error = "") }

            val listener = McpOAuthServer.bind()
            pendingServer = listener
            val redirectUri = listener.redirectUri

            onProgress("সার্ভারের তথ্য খোঁজা হচ্ছে…")
            val endpoints = McpOAuth.discover(server.url)

            onProgress("এই ফোনকে ক্লায়েন্ট হিসেবে নিবন্ধন করা হচ্ছে…")
            val client = if (endpoints.registrationEndpoint.isNotBlank()) {
                McpOAuth.register(endpoints.registrationEndpoint, redirectUri)
            } else {
                throw McpError("এই সার্ভার ডায়নামিক ক্লায়েন্ট নিবন্ধন সাপোর্ট করে না")
            }
            storeEndpoints(server.id, endpoints, client)

            val verifier = McpOAuth.newCodeVerifier()
            val state = McpOAuth.newState()
            prefs.edit().putString("verifier_${server.id}", verifier)
                .putString("state_${server.id}", state).apply()

            val scopes = server.scopes.ifEmpty { defaultScopes(server, endpoints) }
            val url = McpOAuth.buildAuthorizationUrl(
                endpoints = endpoints,
                clientId = client.clientId,
                redirectUri = redirectUri,
                state = state,
                challenge = McpOAuth.codeChallenge(verifier),
                scopes = scopes,
                resource = server.url
            )

            // Wait for the redirect, then finish the exchange. This runs in its
            // own coroutine because `beginOAuth` returns as soon as the browser
            // opens — the user may take minutes to tap Allow.
            finishScope.launch {
                try {
                    val code = withContext(Dispatchers.IO) { listener.awaitCode() }
                    if (prefs.getString("state_${server.id}", null) != state) {
                        throw McpError("state মেলেনি — নিরাপত্তার জন্য বাতিল করা হলো")
                    }
                    val tokens = McpOAuth.exchangeCode(
                        endpoints, client, code, redirectUri, verifier, server.url
                    )
                    storeTokens(server.id, tokens)
                    val tools = fetchTools(server)
                    persistServer(server)
                    update(server.id) {
                        it.copy(
                            status = McpStatus.CONNECTED,
                            tools = tools,
                            error = "",
                            account = accountLabel(server, tokens.accessToken)
                        )
                    }
                    cacheTools(server.id, tools)
                    Log.i(TAG, "${server.id}: OAuth connected, ${tools.size} tools")
                } catch (e: Exception) {
                    update(server.id) {
                        it.copy(status = McpStatus.NEEDS_AUTH, error = e.message.orEmpty())
                    }
                    Log.w(TAG, "${server.id}: OAuth failed — ${e.message}")
                } finally {
                    listener.close()
                    pendingServer = null
                }
            }

            url
        }
    }

    /** True while a browser redirect is still being awaited. */
    fun isAwaitingRedirect(): Boolean = pendingServer != null

    /** Gives up on a flow the user abandoned, freeing the port. */
    fun cancelPending() {
        pendingServer?.close()
        pendingServer = null
    }

    // ---- disconnect --------------------------------------------------------

    fun disconnect(serverId: String) {
        cancelPending()
        val server = connection(serverId)?.server
        clearAuth(serverId)
        prefs.edit().remove(toolsKey(serverId)).apply()
        if (server?.custom == true) {
            removeCustomServer(serverId)
        } else {
            update(serverId) { it.copy(status = McpStatus.DISCONNECTED, tools = emptyList(), error = "", account = "") }
        }
    }

    // ---- custom servers ----------------------------------------------------

    fun addCustomServer(url: String, name: String, token: String?): McpServer {
        val normalized = normalizeUrl(url)
        val server = McpCatalog.custom(normalized, token).let {
            if (name.isBlank()) it else it.copy(name = name, id = "custom_" + name.lowercase()
                .replace(Regex("[^a-z0-9]"), "_").take(28))
        }
        persistServer(server)
        token?.takeIf { it.isNotBlank() }?.let { prefs.edit().putString(patKey(server.id), it).apply() }
        update(server.id) { McpConnection(server) }
        return server
    }

    fun removeCustomServer(serverId: String) {
        clearAuth(serverId)
        prefs.edit().remove(toolsKey(serverId)).apply()
        val servers = loadServerList().filterNot { it.id == serverId }
        saveServerList(servers)
        _connections.value = _connections.value.filterNot { it.server.id == serverId }
    }

    /** Accepts a bare host and fills in the usual MCP path. */
    private fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        if (uri?.path.isNullOrBlank() || uri?.path == "/") {
            url = url.trimEnd('/') + "/mcp"
        }
        return url
    }

    // ---- tool calling ------------------------------------------------------

    /**
     * Runs one tool the model asked for. The server is found from the `serverId__`
     * prefix the tool name carries, so a call can never be routed to the wrong
     * server even when two servers expose the same tool name.
     */
    suspend fun callTool(qualifiedName: String, args: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val separator = qualifiedName.indexOf("__")
            if (separator <= 0) {
                return@withContext err("টুলের নাম ভুল: $qualifiedName")
            }
            val serverId = qualifiedName.substring(0, separator)
            val toolName = qualifiedName.substring(separator + 2)

            val conn = connection(serverId) ?: return@withContext err("$serverId কানেক্টেড নেই")
            if (conn.status != McpStatus.CONNECTED) {
                return@withContext err("$serverId কানেক্টেড নেই — MCP ট্যাব থেকে কানেক্ট করো")
            }

            try {
                val token = awaitFreshToken(conn.server)
                val client = McpClient(conn.server.url, token)
                client.connect()
                val raw = client.callTool(toolName, args)
                update(serverId) { it.copy(lastUsedAt = System.currentTimeMillis()) }
                shapeResult(serverId, toolName, raw)
            } catch (e: McpAuthRequired) {
                update(serverId) { it.copy(status = McpStatus.NEEDS_AUTH, error = e.message.orEmpty()) }
                err("$serverId এর অনুমতি শেষ হয়ে গেছে — MCP ট্যাবে গিয়ে আবার কানেক্ট করো")
            } catch (e: Exception) {
                err("$serverId/$toolName ব্যর্থ: ${e.message}")
            }
        }

    /**
     * Turns an MCP tool result into the {ok, summary} shape the engine already
     * understands, so MCP tools and built-in tools look identical downstream.
     */
    private fun shapeResult(serverId: String, toolName: String, raw: JSONObject): JSONObject {
        val content = raw.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        var imageDataUrl: String? = null

        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text" -> text.append(part.optString("text")).append("\n")
                "image" -> {
                    val data = part.optString("data")
                    val mime = part.optString("mimeType", "image/png")
                    if (data.isNotBlank() && imageDataUrl == null) {
                        imageDataUrl = "data:$mime;base64,$data"
                    }
                }
                "resource" -> {
                    val res = part.optJSONObject("resource") ?: continue
                    res.optString("text").takeIf { it.isNotBlank() }?.let { text.append(it).append("\n") }
                }
            }
        }

        val isError = raw.optBoolean("isError")
        val body = text.toString().trim().ifBlank { if (isError) "সার্ভার ত্রুটি জানিয়েছে" else "সম্পন্ন" }

        return JSONObject()
            .put("ok", !isError)
            .put("summary", "$serverId → $toolName")
            .put("output", body.take(20000))
            .apply { imageDataUrl?.let { put("image_data_url", it) } }
    }

    private fun err(message: String): JSONObject =
        JSONObject().put("ok", false).put("summary", message)

    // ---- token freshness ---------------------------------------------------

    /**
     * Returns a usable access token, refreshing it first when it has expired.
     * Without this a connection would silently die an hour after it was made.
     */
    private suspend fun awaitFreshToken(server: McpServer): String? {
        val pat = prefs.getString(patKey(server.id), null)
        if (server.auth == McpAuth.TOKEN || (pat != null && server.auth == McpAuth.NONE)) return pat

        val access = prefs.getString(accessKey(server.id), null)
        val expires = prefs.getLong(expiresKey(server.id), 0L)
        if (access.isNullOrBlank()) {
            if (pat != null) return pat
            throw McpAuthRequired("${server.id}: টোকেন নেই")
        }
        if (expires == 0L || expires - 60_000L > System.currentTimeMillis()) return access

        val refresh = prefs.getString(refreshKey(server.id), null)
            ?: throw McpAuthRequired("${server.id}: রিফ্রেশ টোকেন নেই")
        val clientId = prefs.getString(clientIdKey(server.id), null)
            ?: throw McpAuthRequired("${server.id}: ক্লায়েন্ট আইডি হারিয়ে গেছে")

        val endpoints = OAuthEndpoints(
            authorizationEndpoint = prefs.getString(authzKey(server.id), "").orEmpty(),
            tokenEndpoint = prefs.getString(tokenEpKey(server.id), "").orEmpty(),
            registrationEndpoint = prefs.getString(regEpKey(server.id), "").orEmpty(),
            scopesSupported = emptyList()
        )
        val tokens = McpOAuth.refresh(
            endpoints,
            OAuthClient(clientId, prefs.getString(clientSecretKey(server.id), null)),
            refresh,
            server.url
        )
        storeTokens(server.id, tokens)
        Log.i(TAG, "${server.id}: token refreshed")
        return tokens.accessToken
    }

    // ---- tools fetch -------------------------------------------------------

    private suspend fun fetchTools(server: McpServer): List<McpTool> {
        val token = if (server.auth == McpAuth.TOKEN) {
            prefs.getString(patKey(server.id), null)
        } else {
            prefs.getString(accessKey(server.id), null) ?: prefs.getString(patKey(server.id), null)
        }
        val client = McpClient(server.url, token)
        client.connect()
        return client.listTools().mapNotNull { def ->
            val name = def.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpTool(
                serverId = server.id,
                serverName = server.name,
                name = name,
                description = def.optString("description", def.optString("title")),
                inputSchema = def.optJSONObject("inputSchema") ?: JSONObject()
                    .put("type", "object").put("properties", JSONObject())
            )
        }
    }

    // ---- persistence -------------------------------------------------------

    private fun loadConnections(): List<McpConnection> {
        val stored = loadServerList()
        val byId = (McpCatalog.builtIn + stored).distinctBy { it.id }.associateBy { it.id }
        val states = runCatching { JSONObject(prefs.getString(KEY_STATE, "{}").orEmpty()) }
            .getOrDefault(JSONObject())
        val toolCache = runCatching { JSONObject(prefs.getString(KEY_TOOLS, "{}").orEmpty()) }
            .getOrDefault(JSONObject())

        // Catalogue order first, then the user's own servers.
        val ordered = McpCatalog.builtIn.map { it.id } + stored.filter { it.custom }.map { it.id }
        return ordered.distinct().mapNotNull { id ->
            val server = byId[id] ?: return@mapNotNull null
            val state = states.optJSONObject(id)
            val status = state?.optString("status")
                ?.let { runCatching { McpStatus.valueOf(it) }.getOrNull() }
                ?: McpStatus.DISCONNECTED
            // A token that was never persisted cannot still be connected.
            val hasAuth = prefs.getString(accessKey(id), null) != null ||
                prefs.getString(patKey(id), null) != null
            val effective = if (status == McpStatus.CONNECTED && !hasAuth && server.auth != McpAuth.NONE) {
                McpStatus.NEEDS_AUTH
            } else status
            McpConnection(
                server = server,
                status = effective,
                tools = parseTools(id, toolCache.optJSONArray(id), server),
                error = state?.optString("error").orEmpty(),
                account = prefs.getString(accountKey(id), "").orEmpty()
            )
        }
    }

    private fun parseTools(serverId: String, arr: JSONArray?, server: McpServer): List<McpTool> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            McpTool(
                serverId = serverId,
                serverName = server.name,
                name = o.optString("name"),
                description = o.optString("description"),
                inputSchema = o.optJSONObject("inputSchema") ?: JSONObject()
            )
        }
    }

    private fun loadServerList(): List<McpServer> =
        runCatching {
            val arr = JSONArray(prefs.getString(KEY_SERVERS, "[]").orEmpty())
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(McpCatalog::fromJson) }
        }.getOrDefault(emptyList())

    private fun saveServerList(servers: List<McpServer>) {
        prefs.edit().putString(KEY_SERVERS, JSONArray().apply {
            servers.forEach { put(McpCatalog.toJson(it)) }
        }.toString()).apply()
    }

    private fun persistServer(server: McpServer) {
        val list = loadServerList().filterNot { it.id == server.id } + server
        saveServerList(list)
    }

    private fun cacheTools(serverId: String, tools: List<McpTool>) {
        val root = runCatching { JSONObject(prefs.getString(KEY_TOOLS, "{}").orEmpty()) }
            .getOrDefault(JSONObject())
        root.put(serverId, JSONArray().apply {
            tools.forEach { t ->
                put(JSONObject().put("name", t.name).put("description", t.description)
                    .put("inputSchema", t.inputSchema))
            }
        })
        prefs.edit().putString(KEY_TOOLS, root.toString()).apply()
    }

    private fun update(serverId: String, transform: (McpConnection) -> McpConnection) {
        val current = _connections.value
        val index = current.indexOfFirst { it.server.id == serverId }
        val updated: List<McpConnection> = when {
            index >= 0 -> current.toMutableList().also { it[index] = transform(it[index]) }
            else -> {
                val known = current.firstOrNull { it.server.id == serverId }?.server
                    ?: McpCatalog.find(serverId)
                    ?: return
                current + transform(McpConnection(known))
            }
        }
        _connections.value = updated
        updated.firstOrNull { it.server.id == serverId }?.let { persistState(serverId, it) }
    }

    private fun persistState(serverId: String, conn: McpConnection) {
        val root = runCatching { JSONObject(prefs.getString(KEY_STATE, "{}").orEmpty()) }
            .getOrDefault(JSONObject())
        root.put(serverId, JSONObject()
            .put("status", conn.status.name)
            .put("error", conn.error))
        prefs.edit().putString(KEY_STATE, root.toString()).apply()
        conn.account.takeIf { it.isNotBlank() }?.let {
            prefs.edit().putString(accountKey(serverId), it).apply()
        }
    }

    private fun storeEndpoints(id: String, e: OAuthEndpoints, c: OAuthClient) {
        prefs.edit()
            .putString(authzKey(id), e.authorizationEndpoint)
            .putString(tokenEpKey(id), e.tokenEndpoint)
            .putString(regEpKey(id), e.registrationEndpoint)
            .putString(clientIdKey(id), c.clientId)
            .putString(clientSecretKey(id), c.clientSecret)
            .putString(scopesKey(id), JSONArray(e.scopesSupported).toString())
            .apply()
    }

    private fun storeTokens(id: String, t: OAuthTokens) {
        prefs.edit()
            .putString(accessKey(id), t.accessToken)
            .putString(refreshKey(id), t.refreshToken)
            .putLong(expiresKey(id), t.expiresAtMillis)
            .apply()
    }

    private fun clearAuth(id: String) {
        prefs.edit()
            .remove(accessKey(id)).remove(refreshKey(id)).remove(expiresKey(id))
            .remove(clientIdKey(id)).remove(clientSecretKey(id))
            .remove(authzKey(id)).remove(tokenEpKey(id)).remove(regEpKey(id))
            .remove(patKey(id)).remove(accountKey(id))
            .remove("verifier_$id").remove("state_$id")
            .apply()
    }

    /** Which scopes to ask for when the server does not require a specific set. */
    private fun defaultScopes(server: McpServer, endpoints: OAuthEndpoints): List<String> {
        if (server.scopes.isNotEmpty()) return server.scopes
        return emptyList() // let the server choose its default set
    }

    private fun accountLabel(server: McpServer, token: String?): String =
        if (token.isNullOrBlank()) "" else "টোকেন ••••${token.takeLast(4)}"

    // ---- pref keys ---------------------------------------------------------

    private fun accessKey(id: String) = "access_$id"
    private fun refreshKey(id: String) = "refresh_$id"
    private fun expiresKey(id: String) = "expires_$id"
    private fun clientIdKey(id: String) = "client_id_$id"
    private fun clientSecretKey(id: String) = "client_secret_$id"
    private fun authzKey(id: String) = "authz_$id"
    private fun tokenEpKey(id: String) = "token_ep_$id"
    private fun regEpKey(id: String) = "reg_ep_$id"
    private fun scopesKey(id: String) = "scopes_$id"
    private fun patKey(id: String) = "pat_$id"
    private fun accountKey(id: String) = "account_$id"
    private fun toolsKey(id: String) = "tools_$id"
}
