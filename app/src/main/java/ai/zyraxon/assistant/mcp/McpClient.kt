package ai.zyraxon.assistant.mcp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The server says we are not authenticated (or the token went stale). */
class McpAuthRequired(message: String) : Exception(message)

/** The server forgot our session; one reconnect fixes it. */
class McpSessionExpired(message: String) : Exception(message)

/** Anything else the server refused. */
class McpError(message: String) : Exception(message)

/**
 * A Streamable-HTTP MCP client that keeps one session open for its whole life.
 *
 * Why long-lived rather than a per-call helper: reconnecting means `initialize`
 * again, and a server is entitled to drop every tool, subscription and pending
 * request it held when that happens. Re-initialising before each tool call is
 * what made tools appear to vanish, so [ensureConnected] is idempotent and every
 * call reuses the same session.
 *
 * The session also opens a GET SSE channel, the only way a server can speak
 * first. That channel carries
 *  - `notifications/tools/list_changed` (and the resources/prompts equivalents),
 *    so a server that adds a tool is noticed without a reconnect;
 *  - `sampling/createMessage`, where the server asks *us* to run a model.
 */
class McpClient(
    private val endpoint: String,
    @Volatile private var accessToken: String?,
    private val clientVersion: String = "5.0.0",
    private val supportsSampling: Boolean = true,
    /** Called for server→client notifications (no id). */
    private val onNotification: ((String, JSONObject) -> Unit)? = null,
    /** Called for server→client requests (has id). Return the `result` payload. */
    private val onRequest: (suspend (String, JSONObject) -> JSONObject)? = null
) {

    companion object {
        private const val TAG = "McpClient"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Shared: MCP servers are often slow to answer a tools/list. */
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        /** The notification channel is meant to stay open, so it never times out. */
        private val longLived: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private val ids = AtomicInteger(0)
    @Volatile private var sessionId: String? = null
    @Volatile private var negotiatedProtocol: String = McpCatalog.PROTOCOL_VERSION
    @Volatile private var serverCapabilities: JSONObject = JSONObject()
    @Volatile private var serverInfo: JSONObject = JSONObject()
    @Volatile private var closed = false

    private val connectLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var channelJob: Job? = null
    @Volatile private var initialized: JSONObject? = null

    /**
     * The live channel call, kept so it can be cancelled. Coroutine cancellation
     * alone cannot stop a blocking socket read, so the call is cancelled instead:
     * that closes the socket and the read returns immediately. Without this the
     * channel thread outlives [close] and leaks for the life of the process.
     */
    @Volatile private var channelCall: okhttp3.Call? = null

    /** True once `initialize` succeeded. */
    val isConnected: Boolean get() = initialized != null && !closed

    val protocolVersion: String get() = negotiatedProtocol
    val info: JSONObject get() = serverInfo

    /** Whether the server advertised the capability — check before listing. */
    val supportsResources: Boolean get() = serverCapabilities.optJSONObject("resources") != null
    val supportsPrompts: Boolean get() = serverCapabilities.optJSONObject("prompts") != null
    val supportsTools: Boolean
        get() = serverCapabilities.optJSONObject("tools") != null || serverCapabilities.length() == 0

    fun supportsToolsListChanged(): Boolean =
        serverCapabilities.optJSONObject("tools")?.optBoolean("listChanged") == true

    fun supportsResourcesListChanged(): Boolean =
        serverCapabilities.optJSONObject("resources")?.optBoolean("listChanged") == true

    fun supportsPromptsListChanged(): Boolean =
        serverCapabilities.optJSONObject("prompts")?.optBoolean("listChanged") == true

    // ---- session ------------------------------------------------------------

    /** Runs `initialize` once and then reuses the session forever. */
    suspend fun ensureConnected(): JSONObject = connectLock.withLock {
        initialized ?: run {
            if (closed) throw McpError("$endpoint: ক্লায়েন্ট বন্ধ করা হয়েছে")
            val result = initialize()
            initialized = result
            startChannel()
            result
        }
    }

    /**
     * Runs [block] against a live session, re-establishing it once if the server
     * dropped it. The reconnect happens *outside* [ensureConnected]'s lock, so a
     * retry can never try to take a lock this coroutine already holds.
     */
    private suspend fun <T> withSession(block: suspend () -> T): T {
        ensureConnected()
        return try {
            block()
        } catch (e: McpSessionExpired) {
            Log.i(TAG, "$endpoint: session dropped — reconnecting once")
            forgetSession()
            ensureConnected()
            block()
        }
    }

    /** Swaps in a freshly refreshed token without dropping the session. */
    fun updateToken(token: String?) {
        accessToken = token
    }

    /** Drops the session so the next call re-initializes. Used after a 404. */
    private fun forgetSession() {
        initialized = null
        sessionId = null
        stopChannel()
    }

    /** Cancels the channel call so its blocking read returns and the thread ends. */
    private fun stopChannel() {
        channelCall?.cancel()
        channelCall = null
        channelJob?.cancel()
        channelJob = null
    }

    private suspend fun initialize(): JSONObject {
        val capabilities = JSONObject()
            .put("roots", JSONObject().put("listChanged", true))
        if (supportsSampling) capabilities.put("sampling", JSONObject())

        val result = callRaw("initialize", JSONObject()
            .put("protocolVersion", McpCatalog.PROTOCOL_VERSION)
            .put("capabilities", capabilities)
            .put("clientInfo", JSONObject()
                .put("name", "zyraxon-android")
                .put("version", clientVersion)))

        result.optString("protocolVersion").takeIf { it.isNotBlank() }?.let { negotiatedProtocol = it }
        serverCapabilities = result.optJSONObject("capabilities") ?: JSONObject()
        serverInfo = result.optJSONObject("serverInfo") ?: JSONObject()
        Log.i(TAG, "$endpoint: session up (protocol $negotiatedProtocol)")
        notify("notifications/initialized", JSONObject())
        return result
    }

    /** Opens the GET SSE channel so the server can send notifications and requests. */
    private fun startChannel() {
        if (channelJob?.isActive == true) return
        channelJob = scope.launch {
            try {
                val builder = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "text/event-stream")
                    .header("User-Agent", UA)
                    .header("MCP-Protocol-Version", negotiatedProtocol)
                    .get()
                sessionId?.let { builder.header("Mcp-Session-Id", it) }
                accessToken?.takeIf { it.isNotBlank() }?.let {
                    builder.header("Authorization", "Bearer $it")
                }
                val call = longLived.newCall(builder.build())
                channelCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        // Optional per spec: a server without a channel is fine,
                        // the manager then falls back to polling for tool changes.
                        Log.i(TAG, "$endpoint: no server channel (HTTP ${response.code})")
                        return@use
                    }
                    val contentType = response.header("Content-Type").orEmpty()
                    if (!contentType.contains("text/event-stream")) {
                        Log.i(TAG, "$endpoint: channel is not SSE ($contentType)")
                        return@use
                    }
                    val stream = response.body?.byteStream() ?: return@use
                    Log.i(TAG, "$endpoint: notification channel open")
                    pumpSse(stream, stopAfterFirst = false) { message ->
                        handleServerMessage(message)
                    }
                }
            } catch (e: Exception) {
                if (!closed) Log.w(TAG, "$endpoint: channel ended — ${e.message}")
            } finally {
                channelCall = null
            }
        }
    }

    /** A server→client message is a request when it carries an id, else a notification. */
    private fun handleServerMessage(message: JSONObject) {
        val method = message.optString("method")
        if (method.isBlank()) return
        val params = message.optJSONObject("params") ?: JSONObject()

        if (message.has("id") && !message.isNull("id")) {
            val id = message.opt("id")
            scope.launch {
                try {
                    val result = onRequest?.invoke(method, params) ?: JSONObject()
                    reply(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
                } catch (e: Exception) {
                    Log.w(TAG, "$endpoint: $method failed — ${e.message}")
                    reply(JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject()
                        .put("code", -32603)
                        .put("message", e.message ?: "client error")))
                }
            }
        } else {
            try {
                onNotification?.invoke(method, params)
            } catch (e: Exception) {
                Log.w(TAG, "$endpoint: notification $method threw — ${e.message}")
            }
        }
    }

    private suspend fun reply(envelope: JSONObject) {
        runCatching { post(envelope.toString(), expectResponse = false) }
            .onFailure { Log.w(TAG, "$endpoint: reply failed — ${it.message}") }
    }

    /** Closes the session. A best-effort DELETE tells the server to drop it. */
    fun close() {
        if (closed) return
        closed = true
        val session = sessionId
        stopChannel()
        if (session != null) {
            scope.launch {
                runCatching {
                    val builder = Request.Builder().url(endpoint)
                        .header("User-Agent", UA)
                        .header("MCP-Protocol-Version", negotiatedProtocol)
                        .header("Mcp-Session-Id", session)
                        .delete()
                    accessToken?.takeIf { it.isNotBlank() }?.let {
                        builder.header("Authorization", "Bearer $it")
                    }
                    http.newCall(builder.build()).execute().close()
                }
            }
        }
        scope.cancel()
    }

    // ---- reads --------------------------------------------------------------

    suspend fun listTools(): List<JSONObject> {
        if (!supportsTools) return emptyList()
        return listPaged("tools/list", "tools")
    }

    suspend fun listResources(): List<JSONObject> {
        if (!supportsResources) return emptyList()
        return listPaged("resources/list", "resources")
    }

    suspend fun listResourceTemplates(): List<JSONObject> {
        if (!supportsResources) return emptyList()
        return listPaged("resources/templates/list", "resourceTemplates")
    }

    suspend fun listPrompts(): List<JSONObject> {
        if (!supportsPrompts) return emptyList()
        return listPaged("prompts/list", "prompts")
    }

    suspend fun readResource(uri: String): JSONObject =
        call("resources/read", JSONObject().put("uri", uri))

    suspend fun getPrompt(name: String, args: JSONObject): JSONObject =
        call("prompts/get", JSONObject().put("name", name).put("arguments", args))

    /** Runs one tool. The raw MCP result is returned so the caller can reshape it. */
    suspend fun callTool(name: String, args: JSONObject): JSONObject =
        call("tools/call", JSONObject().put("name", name).put("arguments", args))

    private suspend fun listPaged(method: String, key: String): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        var cursor: String? = null
        var page = 0
        do {
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val result = call(method, params)
            val arr = result.optJSONArray(key) ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { all.add(it) }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            page++
        } while (cursor != null && page < 20)
        return all
    }

    // ---- transport ----------------------------------------------------------

    private suspend fun notify(method: String, params: JSONObject) {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        post(body.toString(), expectResponse = false)
    }

    private suspend fun call(method: String, params: JSONObject): JSONObject =
        withSession { callRaw(method, params) }

    /**
     * One JSON-RPC round trip, with no session handling of its own. `initialize`
     * goes through this rather than [call] because it runs while the connect lock
     * is held, and re-entering that lock would deadlock.
     */
    private suspend fun callRaw(method: String, params: JSONObject): JSONObject {
        val id = ids.incrementAndGet()
        val body = JSONObject()
            .put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val raw = post(body.toString(), expectResponse = true)
        val obj = raw ?: throw McpError("$method: সার্ভার খালি উত্তর দিয়েছে")
        obj.optJSONObject("error")?.let {
            throw McpError(it.optString("message", "MCP ত্রুটি ${it.optInt("code")}"))
        }
        return obj.optJSONObject("result") ?: JSONObject()
    }

    /** Returns the JSON-RPC envelope, or null when no response is expected. */
    private suspend fun post(payload: String, expectResponse: Boolean): JSONObject? =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("User-Agent", UA)
                .header("MCP-Protocol-Version", negotiatedProtocol)
                .post(payload.toRequestBody(JSON))
            accessToken?.takeIf { it.isNotBlank() }?.let {
                builder.header("Authorization", "Bearer $it")
            }
            sessionId?.let { builder.header("Mcp-Session-Id", it) }

            http.newCall(builder.build()).execute().use { response ->
                response.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }

                if (response.code == 401 || response.code == 403) {
                    throw McpAuthRequired("$endpoint: HTTP ${response.code} — আবার কানেক্ট করতে হবে")
                }
                // 404 while holding a session id means the server dropped it.
                if (response.code == 404 && sessionId != null) {
                    throw McpSessionExpired("$endpoint: সেশন শেষ (HTTP 404)")
                }
                if (!response.isSuccessful && response.code != 202) {
                    val detail = response.body?.string()?.take(300).orEmpty()
                    throw McpError("$endpoint: HTTP ${response.code} $detail")
                }
                if (!expectResponse || response.code == 202) return@withContext null

                val contentType = response.header("Content-Type").orEmpty()
                val stream = response.body?.byteStream() ?: return@withContext null
                if (contentType.contains("text/event-stream")) {
                    var found: JSONObject? = null
                    pumpSse(stream, stopAfterFirst = true) { found = it }
                    found
                } else {
                    val text = stream.bufferedReader().use(BufferedReader::readText).trim()
                    if (text.isEmpty()) null else runCatching { JSONObject(text) }.getOrNull()
                }
            }
        }

    /**
     * Reads SSE frames. A POST answer carries one `message` event, so
     * [stopAfterFirst] returns as soon as it arrives; the GET channel passes
     * false and keeps consuming until the stream ends or [closed] is set.
     */
    private fun pumpSse(
        stream: java.io.InputStream,
        stopAfterFirst: Boolean,
        onMessage: (JSONObject) -> Unit
    ) {
        var event = ""
        val data = StringBuilder()
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (stopAfterFirst || !closed) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                    line.isEmpty() -> {
                        var delivered = false
                        if (data.isNotEmpty() && (event.isEmpty() || event == "message")) {
                            runCatching { JSONObject(data.toString()) }.getOrNull()?.let {
                                onMessage(it)
                                delivered = true
                            }
                        }
                        event = ""
                        data.setLength(0)
                        if (stopAfterFirst && delivered) return
                    }
                }
            }
        }
    }
}
