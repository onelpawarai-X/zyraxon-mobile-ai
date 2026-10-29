package ai.zyraxon.assistant.mcp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Owns every MCP server the assistant knows about: which are connected, what each
 * one offers (tools, resources, prompts), and the tokens needed to reach it.
 *
 * Two promises shape the design:
 *
 *  1. A connected server's tools are injected into **every** turn, so the model
 *     uses them without being told they exist. [allTools] is what the engine reads.
 *  2. A connection is **kept**, not remade. Each server owns one long-lived
 *     [McpClient]; a tool call reuses that session instead of re-initialising.
 *     Re-initialising was the reason tools seemed to disappear.
 *
 * Servers that advertise `listChanged` are refreshed from the notification channel,
 * so a tool added upstream shows up without the user reconnecting.
 */
class McpManager(
    private val context: Context,
    /** Runs a model for `sampling/createMessage`; supplied by the engine. */
    private val samplingRunner: (suspend (JSONObject) -> JSONObject)? = null
) {

    companion object {
        private const val TAG = "McpManager"
        private const val PREFS = "zyraxon_mcp"
        private const val KEY_SERVERS = "servers"
        private const val KEY_TOOLS = "tools"
        private const val KEY_RESOURCES = "resources"
        private const val KEY_PROMPTS = "prompts"
        private const val KEY_RESOURCE_TEXT = "resource_text"
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

    /**
     * One live client per server, kept across calls. This is what stops a tool
     * call from re-initialising the session and losing the server's state.
     */
    private val clients = java.util.concurrent.ConcurrentHashMap<String, McpClient>()

    /** Resource-change notes waiting to be injected into the next model turn. */
    private val resourceUpdates = java.util.concurrent.ConcurrentLinkedQueue<String>()

    init {
        _connections.value = loadConnections()
    }

    // ---- public surface -----------------------------------------------------

    /** Every tool from every connected server, in OpenAI function shape. */
    fun allTools(): List<McpTool> =
        _connections.value.filter { it.status == McpStatus.CONNECTED }.flatMap { it.tools }

    fun allResources(): List<McpResource> =
        _connections.value.filter { it.status == McpStatus.CONNECTED }.flatMap { it.resources }

    fun allPrompts(): List<McpPrompt> =
        _connections.value.filter { it.status == McpStatus.CONNECTED }.flatMap { it.prompts }

    /** The servers, catalogue first, then anything the user added. */
    fun servers(): List<McpServer> =
        _connections.value.map { it.server }

    fun connection(id: String): McpConnection? =
        _connections.value.firstOrNull { it.server.id == id }

    fun connectedCount(): Int = _connections.value.count { it.status == McpStatus.CONNECTED }

    fun totalToolCount(): Int = allTools().size

    // ---- the live client ----------------------------------------------------

    /**
     * The one client for a server, created on first use and reused after. The
     * session survives across tool calls, which is the whole point.
     */
    private fun clientFor(server: McpServer): McpClient =
        clients.getOrPut(server.id) {
            McpClient(
                endpoint = server.url,
                accessToken = storedToken(server),
                onNotification = { method, params -> onServerNotification(server.id, method, params) },
                onRequest = { method, params -> onServerRequest(server.id, method, params) }
            )
        }

    /** A server pushing a change means the cached list is stale — refresh it. */
    private fun onServerNotification(serverId: String, method: String, params: JSONObject) {
        when (method) {
            "notifications/tools/list_changed" -> refreshTools(serverId)
            "notifications/resources/list_changed" -> refreshResources(serverId)
            "notifications/prompts/list_changed" -> refreshPrompts(serverId)
            // The contents behind a subscribed uri changed; re-read so the next
            // agent turn sees fresh text rather than the copy read earlier.
            "notifications/resources/updated" -> refreshOneResource(serverId, params.optString("uri"))
            "notifications/message" -> Log.i(TAG, "$serverId: log ${params.optString("level")} ${params.optString("data")}")
            "notifications/progress" -> Log.i(TAG, "$serverId: progress ${params.optString("progressToken")}")
            "notifications/cancelled" -> Log.i(TAG, "$serverId: cancelled ${params.optString("requestId")}")
            else -> Log.i(TAG, "$serverId: notification $method")
        }
    }

    /** The server asking us to run a model (sampling), list roots, or elicit input. */
    private suspend fun onServerRequest(serverId: String, method: String, params: JSONObject): JSONObject =
        when (method) {
            "sampling/createMessage" -> {
                val runner = samplingRunner
                    ?: throw McpError("sampling সাপোর্ট নেই")
                Log.i(TAG, "$serverId: sampling request")
                runner(params)
            }
            // The spec lets a server ask which filesystem roots it may work in. The
            // assistant's workspace is the one honest answer; an empty list would
            // tell the server it has nowhere to write.
            "roots/list" -> JSONObject().put("roots", JSONArray().put(JSONObject()
                .put("uri", "file://${workspaceRoot().absolutePath}")
                .put("name", "ZYRAXON workspace")))
            // Elicitation asks the user for input mid-call. The agent has no modal
            // surface wired to it, so it answers "declined" — the spec's way of
            // saying no without breaking the server's request.
            "elicitation/create" -> JSONObject().put("action", "decline")
            // Ping is the spec's keep-alive; an empty result is the correct answer.
            "ping" -> JSONObject()
            else -> throw McpError("অজানা অনুরোধ: $method")
        }

    /** The workspace the assistant and its MCP servers may read and write. */
    private fun workspaceRoot(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "zyraxon").apply { mkdirs() }

    private fun refreshTools(serverId: String) {
        finishScope.launch {
            val conn = connection(serverId) ?: return@launch
            try {
                val client = clientFor(conn.server)
                client.ensureConnected()
                val tools = parseTools(serverId, conn.server, client.listTools())
                update(serverId) { it.copy(tools = tools) }
                cacheTools(serverId, tools)
                Log.i(TAG, "$serverId: tools refreshed — ${tools.size}")
            } catch (e: Exception) {
                Log.w(TAG, "$serverId: tool refresh failed — ${e.message}")
            }
        }
    }

    private fun refreshResources(serverId: String) {
        finishScope.launch {
            val conn = connection(serverId) ?: return@launch
            try {
                val client = clientFor(conn.server)
                client.ensureConnected()
                val resources = parseResources(serverId, conn.server, client)
                update(serverId) { it.copy(resources = resources) }
                cacheResources(serverId, resources)
            } catch (e: Exception) {
                Log.w(TAG, "$serverId: resource refresh failed — ${e.message}")
            }
        }
    }

    private fun refreshPrompts(serverId: String) {
        finishScope.launch {
            val conn = connection(serverId) ?: return@launch
            try {
                val client = clientFor(conn.server)
                client.ensureConnected()
                val prompts = parsePrompts(serverId, conn.server, client.listPrompts())
                update(serverId) { it.copy(prompts = prompts) }
                cachePrompts(serverId, prompts)
            } catch (e: Exception) {
                Log.w(TAG, "$serverId: prompt refresh failed — ${e.message}")
            }
        }
    }

    /**
     * Re-reads one subscribed resource. The refreshed text replaces the cached
     * copy so the next agent turn reads what the server has now, not what it had
     * when the resource was first listed.
     */
    private fun refreshOneResource(serverId: String, uri: String) {
        if (uri.isBlank()) return
        finishScope.launch {
            val conn = connection(serverId) ?: return@launch
            try {
                val client = clientFor(conn.server)
                val result = client.readResource(uri)
                val shaped = shapeResource(serverId, uri, result)
                cacheResourceText(serverId, uri, result)
                // The model is told about the change on its next turn, so a
                // subscription is worth something instead of silently updating a
                // cache nothing reads.
                resourceUpdates.add(
                    "[MCP] ${conn.server.name} — $uri বদলেছে:\n${shaped.optString("output").take(4000)}"
                )
                Log.i(TAG, "$serverId: resource updated — $uri")
            } catch (e: Exception) {
                Log.w(TAG, "$serverId: resource update failed — ${e.message}")
            }
        }
    }

    /**
     * Drains the notes about resources that changed since the last turn. The
     * engine injects them so the model learns about the change without being asked.
     */
    fun drainResourceUpdates(): List<String> {
        val out = mutableListOf<String>()
        while (true) out.add(resourceUpdates.poll() ?: break)
        return out
    }

    /** Subscribes to a resource's changes, when the server supports it. */
    suspend fun subscribeResource(serverId: String, uri: String): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = connection(serverId) ?: return@withContext fail("সার্ভার পাওয়া যায়নি")
            val client = clientFor(conn.server)
            if (!client.supportsResourceSubscribe) {
                return@withContext fail("${conn.server.name} subscribe সাপোর্ট করে না")
            }
            runCatching { client.subscribeResource(uri) }
                .fold(
                    { ok("$uri-তে সাবস্ক্রাইব করা হয়েছে") },
                    { fail("সাবস্ক্রাইব ব্যর্থ: ${it.message}") }
                )
        }

    suspend fun unsubscribeResource(serverId: String, uri: String): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = connection(serverId) ?: return@withContext fail("সার্ভার পাওয়া যায়নি")
            val client = clientFor(conn.server)
            runCatching { client.unsubscribeResource(uri) }
                .fold(
                    { ok("$uri থেকে আনসাবস্ক্রাইব করা হয়েছে") },
                    { fail("আনসাবস্ক্রাইব ব্যর্থ: ${it.message}") }
                )
        }

    /** Autocompletes a prompt argument or a resource uri through the server. */
    suspend fun complete(serverId: String, refType: String, refName: String, argName: String, value: String): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = connection(serverId) ?: return@withContext fail("সার্ভার পাওয়া যায়নি")
            val client = clientFor(conn.server)
            if (!client.supportsCompletion) {
                return@withContext fail("${conn.server.name} completion সাপোর্ট করে না")
            }
            runCatching {
                client.complete(refType, refName, JSONObject().put("name", argName).put("value", value))
            }.fold(
                { result ->
                    val values = result.optJSONObject("completion")
                        ?.optJSONArray("values") ?: JSONArray()
                    JSONObject()
                        .put("ok", true)
                        .put("summary", "$argName-এর ${values.length()}টি সাজেশন")
                        .put("values", values)
                },
                { fail("completion ব্যর্থ: ${it.message}") }
            )
        }

    /** Pings a server to confirm the session is still alive. */
    suspend fun ping(serverId: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = connection(serverId) ?: return@withContext fail("সার্ভার পাওয়া যায়নি")
        runCatching { clientFor(conn.server).ping() }
            .fold(
                { ok("${conn.server.name} সক্রিয়") },
                { fail("পিং ব্যর্থ: ${it.message}") }
            )
    }

    /** Sets a server's log level, so its `notifications/message` volume is tunable. */
    suspend fun setLogLevel(serverId: String, level: String): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = connection(serverId) ?: return@withContext fail("সার্ভার পাওয়া যায়নি")
            runCatching { clientFor(conn.server).setLogLevel(level) }
                .fold(
                    { ok("লগ লেভেল $level সেট করা হয়েছে") },
                    { fail("লগ লেভেল ব্যর্থ: ${it.message}") }
                )
        }

    private fun ok(summary: String) = JSONObject().put("ok", true).put("summary", summary)
    private fun fail(summary: String) = JSONObject().put("ok", false).put("summary", summary)

    // ---- connect (token / none) --------------------------------------------

    /**
     * Connects a server that authenticates with a static token, or with nothing
     * at all. The token is verified immediately by listing what the server has,
     * so a bad token fails here instead of silently later.
     */
    suspend fun connectWithToken(server: McpServer, token: String?): McpConnection =
        withContext(Dispatchers.IO) {
            update(server.id) { it.copy(status = McpStatus.CONNECTING, error = "") }
            token?.takeIf { it.isNotBlank() }?.let { prefs.edit().putString(patKey(server.id), it).apply() }
            // A new token means a new session.
            clients.remove(server.id)?.close()

            try {
                val client = clientFor(server)
                client.ensureConnected()
                val tools = parseTools(server.id, server, client.listTools())
                val resources = parseResources(server.id, server, client)
                val prompts = parsePrompts(server.id, server, client.listPrompts())
                val done = McpConnection(
                    server = server,
                    status = McpStatus.CONNECTED,
                    tools = tools,
                    resources = resources,
                    prompts = prompts,
                    account = accountLabel(server, token)
                )
                persistServer(server)
                update(server.id) { done }
                cacheTools(server.id, tools)
                cacheResources(server.id, resources)
                cachePrompts(server.id, prompts)
                Log.i(TAG, "${server.id}: connected — ${tools.size} tools, ${resources.size} resources, ${prompts.size} prompts")
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
     * redirect, the code is exchanged, the server's catalogue is fetched. The UI
     * observes [connections] to learn when it finished.
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
                    clients.remove(server.id)?.close()
                    val fresh = clientFor(server)
                    fresh.ensureConnected()
                    val tools = parseTools(server.id, server, fresh.listTools())
                    val resources = parseResources(server.id, server, fresh)
                    val prompts = parsePrompts(server.id, server, fresh.listPrompts())
                    persistServer(server)
                    update(server.id) {
                        it.copy(
                            status = McpStatus.CONNECTED,
                            tools = tools,
                            resources = resources,
                            prompts = prompts,
                            error = "",
                            account = accountLabel(server, tokens.accessToken)
                        )
                    }
                    cacheTools(server.id, tools)
                    cacheResources(server.id, resources)
                    cachePrompts(server.id, prompts)
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

    // ---- lifecycle ---------------------------------------------------------

    /**
     * Closes every session and stops the background scope. Without this the SSE
     * channels, and the threads behind them, outlive the assistant.
     */
    fun close() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        finishScope.cancel()
    }

    // ---- disconnect --------------------------------------------------------

    fun disconnect(serverId: String) {
        cancelPending()
        clients.remove(serverId)?.close()
        val server = connection(serverId)?.server
        clearAuth(serverId)
        prefs.edit().remove(toolsKey(serverId)).remove(resourcesKey(serverId))
            .remove(promptsKey(serverId)).apply()
        if (server?.custom == true) {
            removeCustomServer(serverId)
        } else {
            update(serverId) {
                it.copy(
                    status = McpStatus.DISCONNECTED,
                    tools = emptyList(), resources = emptyList(), prompts = emptyList(),
                    error = "", account = ""
                )
            }
        }
    }

    // ---- custom servers ----------------------------------------------------

    fun addCustomServer(url: String, name: String, token: String?): McpServer {
        val normalized = normalizeUrl(url)
        val server = McpCatalog.custom(normalized, token).let {
            if (name.isBlank()) it else it.copy(name = name, id = McpCatalog.slugId("custom", name))
        }
        persistServer(server)
        token?.takeIf { it.isNotBlank() }?.let { prefs.edit().putString(patKey(server.id), it).apply() }
        update(server.id) { McpConnection(server) }
        return server
    }

    fun removeCustomServer(serverId: String) {
        clients.remove(serverId)?.close()
        clearAuth(serverId)
        prefs.edit().remove(toolsKey(serverId)).remove(resourcesKey(serverId))
            .remove(promptsKey(serverId)).apply()
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
     *
     * The session is reused, not rebuilt — a tool call is one round trip.
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
                val client = clientFor(conn.server)
                client.updateToken(token)
                client.ensureConnected()
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

    // ---- resources ---------------------------------------------------------

    /** Reads a resource. [qualifiedUri] is `<serverId>__<uri>`. */
    suspend fun readResource(qualifiedUri: String): JSONObject =
        withContext(Dispatchers.IO) {
            val separator = qualifiedUri.indexOf("__")
            if (separator <= 0) return@withContext err("রিসোর্স ঠিকানা ভুল: $qualifiedUri")
            val serverId = qualifiedUri.substring(0, separator)
            val uri = qualifiedUri.substring(separator + 2)

            val conn = connection(serverId) ?: return@withContext err("$serverId কানেক্টেড নেই")
            if (conn.status != McpStatus.CONNECTED) return@withContext err("$serverId কানেক্টেড নেই")

            try {
                val token = awaitFreshToken(conn.server)
                val client = clientFor(conn.server)
                client.updateToken(token)
                client.ensureConnected()
                val raw = client.readResource(uri)
                shapeResource(serverId, uri, raw).also { cacheResourceText(serverId, uri, raw) }
            } catch (e: McpAuthRequired) {
                update(serverId) { it.copy(status = McpStatus.NEEDS_AUTH, error = e.message.orEmpty()) }
                err("$serverId এর অনুমতি শেষ — আবার কানেক্ট করো")
            } catch (e: Exception) {
                err("$serverId/$uri পড়া যায়নি: ${e.message}")
            }
        }

    // ---- prompts -----------------------------------------------------------

    /** Renders a prompt. [qualifiedName] is `<serverId>__<prompt>`. */
    suspend fun getPrompt(qualifiedName: String, args: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val separator = qualifiedName.indexOf("__")
            if (separator <= 0) return@withContext err("প্রম্পটের নাম ভুল: $qualifiedName")
            val serverId = qualifiedName.substring(0, separator)
            val name = qualifiedName.substring(separator + 2)

            val conn = connection(serverId) ?: return@withContext err("$serverId কানেক্টেড নেই")
            if (conn.status != McpStatus.CONNECTED) return@withContext err("$serverId কানেক্টেড নেই")

            try {
                val token = awaitFreshToken(conn.server)
                val client = clientFor(conn.server)
                client.updateToken(token)
                client.ensureConnected()
                val raw = client.getPrompt(name, args)
                shapePrompt(serverId, name, raw)
            } catch (e: McpAuthRequired) {
                update(serverId) { it.copy(status = McpStatus.NEEDS_AUTH, error = e.message.orEmpty()) }
                err("$serverId এর অনুমতি শেষ — আবার কানেক্ট করো")
            } catch (e: Exception) {
                err("$serverId/$name প্রম্পট ব্যর্থ: ${e.message}")
            }
        }

    // ---- result shaping ----------------------------------------------------

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
                "audio" -> {
                    val data = part.optString("data")
                    if (data.isNotBlank()) {
                        text.append("[অডিও: ").append(part.optString("mimeType", "audio")).append("]")
                            .append("\n")
                    }
                }
                // Embedded resource: a server can answer a tool with a document.
                "resource" -> {
                    val res = part.optJSONObject("resource") ?: continue
                    res.optString("text").takeIf { it.isNotBlank() }?.let { text.append(it).append("\n") }
                    res.optString("blob").takeIf { it.isNotBlank() }?.let {
                        text.append("[ব্লব: ").append(res.optString("mimeType", "binary"))
                            .append(", ").append(it.length).append(" অক্ষর]\n")
                    }
                }
                "resource_link" -> {
                    text.append("[রিসোর্স: ").append(part.optString("uri")).append("]\n")
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

    /** Flattens a `resources/read` answer into text plus any image. */
    private fun shapeResource(serverId: String, uri: String, raw: JSONObject): JSONObject {
        val contents = raw.optJSONArray("contents") ?: JSONArray()
        val text = StringBuilder()
        var imageDataUrl: String? = null

        for (i in 0 until contents.length()) {
            val c = contents.optJSONObject(i) ?: continue
            c.optString("text").takeIf { it.isNotBlank() }?.let { text.append(it).append("\n") }
            val blob = c.optString("blob")
            val mime = c.optString("mimeType", "application/octet-stream")
            if (blob.isNotBlank()) {
                if (mime.startsWith("image/") && imageDataUrl == null) {
                    imageDataUrl = "data:$mime;base64,$blob"
                } else {
                    text.append("[ব্লব ").append(mime).append(", ").append(blob.length).append(" অক্ষর]\n")
                }
            }
        }

        val body = text.toString().trim().ifBlank { "খালি রিসোর্স" }
        return JSONObject()
            .put("ok", true)
            .put("summary", "$serverId → $uri")
            .put("output", body.take(20000))
            .apply { imageDataUrl?.let { put("image_data_url", it) } }
    }

    /** Flattens a `prompts/get` answer into the prompt text. */
    private fun shapePrompt(serverId: String, name: String, raw: JSONObject): JSONObject {
        val messages = raw.optJSONArray("messages") ?: JSONArray()
        val text = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = m.opt("content")
            val body = when (content) {
                is JSONObject -> content.optString("text")
                is JSONArray -> (0 until content.length())
                    .mapNotNull { content.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }
                    .joinToString("\n")
                else -> content?.toString().orEmpty()
            }
            if (body.isNotBlank()) text.append("[").append(role).append("] ").append(body).append("\n\n")
        }
        val description = raw.optString("description")
        val body = text.toString().trim().ifBlank { description.ifBlank { "খালি প্রম্পট" } }
        return JSONObject()
            .put("ok", true)
            .put("summary", "$serverId → $name")
            .put("output", body.take(20000))
    }

    private fun err(message: String): JSONObject =
        JSONObject().put("ok", false).put("summary", message)

    // ---- token freshness ---------------------------------------------------

    /** The token that is already stored, without refreshing. */
    private fun storedToken(server: McpServer): String? =
        if (server.auth == McpAuth.TOKEN) {
            prefs.getString(patKey(server.id), null)
        } else {
            prefs.getString(accessKey(server.id), null) ?: prefs.getString(patKey(server.id), null)
        }

    /**
     * Returns a usable access token, refreshing it first when it has expired.
     * Without this a connection would silently die an hour after it was made.
     */
    private suspend fun awaitFreshToken(server: McpServer): String? {
        // A server that needs no auth must not be asked for a token, or every call
        // after connecting would fail with "permission expired".
        if (server.auth == McpAuth.NONE) return null

        val pat = prefs.getString(patKey(server.id), null)
        if (server.auth == McpAuth.TOKEN) return pat

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

    // ---- parsing -----------------------------------------------------------

    private fun parseTools(serverId: String, server: McpServer, defs: List<JSONObject>): List<McpTool> =
        defs.mapNotNull { def ->
            val name = def.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpTool(
                serverId = serverId,
                serverName = server.name,
                name = name,
                description = def.optString("description", def.optString("title")),
                inputSchema = def.optJSONObject("inputSchema") ?: JSONObject()
                    .put("type", "object").put("properties", JSONObject())
            )
        }

    private suspend fun parseResources(
        serverId: String,
        server: McpServer,
        client: McpClient
    ): List<McpResource> {
        val plain = client.listResources().map { r ->
            McpResource(
                serverId = serverId,
                serverName = server.name,
                uri = r.optString("uri"),
                name = r.optString("name", r.optString("uri")),
                description = r.optString("description"),
                mimeType = r.optString("mimeType")
            )
        }
        // Templates are surfaced too: the model can fill in the placeholders.
        val templates = runCatching { client.listResourceTemplates() }.getOrDefault(emptyList()).map { t ->
            McpResource(
                serverId = serverId,
                serverName = server.name,
                uri = t.optString("uriTemplate"),
                name = t.optString("name", t.optString("uriTemplate")),
                description = t.optString("description"),
                mimeType = t.optString("mimeType"),
                template = true
            )
        }
        return plain + templates
    }

    private fun parsePrompts(serverId: String, server: McpServer, defs: List<JSONObject>): List<McpPrompt> =
        defs.mapNotNull { def ->
            val name = def.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val argsArr = def.optJSONArray("arguments") ?: JSONArray()
            val args = (0 until argsArr.length()).mapNotNull { i ->
                val a = argsArr.optJSONObject(i) ?: return@mapNotNull null
                McpPromptArg(
                    name = a.optString("name"),
                    description = a.optString("description"),
                    required = a.optBoolean("required")
                )
            }
            McpPrompt(serverId, server.name, name, def.optString("description"), args)
        }

    // ---- persistence -------------------------------------------------------

    private fun loadConnections(): List<McpConnection> {
        val stored = loadServerList()
        val byId = (McpCatalog.builtIn + stored).distinctBy { it.id }.associateBy { it.id }
        val states = runCatching { JSONObject(prefs.getString(KEY_STATE, "{}").orEmpty()) }
            .getOrDefault(JSONObject())
        val toolCache = runCatching { JSONObject(prefs.getString(KEY_TOOLS, "{}").orEmpty()) }
            .getOrDefault(JSONObject())
        val resourceCache = runCatching { JSONObject(prefs.getString(KEY_RESOURCES, "{}").orEmpty()) }
            .getOrDefault(JSONObject())
        val promptCache = runCatching { JSONObject(prefs.getString(KEY_PROMPTS, "{}").orEmpty()) }
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
                tools = parseCachedTools(id, toolCache.optJSONArray(id), server),
                resources = parseCachedResources(id, resourceCache.optJSONArray(id), server),
                prompts = parseCachedPrompts(id, promptCache.optJSONArray(id), server),
                error = state?.optString("error").orEmpty(),
                account = prefs.getString(accountKey(id), "").orEmpty()
            )
        }
    }

    private fun parseCachedTools(serverId: String, arr: JSONArray?, server: McpServer): List<McpTool> {
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

    private fun parseCachedResources(serverId: String, arr: JSONArray?, server: McpServer): List<McpResource> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            McpResource(
                serverId = serverId,
                serverName = server.name,
                uri = o.optString("uri"),
                name = o.optString("name"),
                description = o.optString("description"),
                mimeType = o.optString("mimeType"),
                template = o.optBoolean("template")
            )
        }
    }

    private fun parseCachedPrompts(serverId: String, arr: JSONArray?, server: McpServer): List<McpPrompt> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val argsArr = o.optJSONArray("arguments") ?: JSONArray()
            McpPrompt(
                serverId = serverId,
                serverName = server.name,
                name = o.optString("name"),
                description = o.optString("description"),
                arguments = (0 until argsArr.length()).mapNotNull { j ->
                    val a = argsArr.optJSONObject(j) ?: return@mapNotNull null
                    McpPromptArg(a.optString("name"), a.optString("description"), a.optBoolean("required"))
                }
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
        val root = readCache(KEY_TOOLS)
        root.put(serverId, JSONArray().apply {
            tools.forEach { t ->
                put(JSONObject().put("name", t.name).put("description", t.description)
                    .put("inputSchema", t.inputSchema))
            }
        })
        writeCache(KEY_TOOLS, root)
    }

    private fun cacheResources(serverId: String, resources: List<McpResource>) {
        val root = readCache(KEY_RESOURCES)
        root.put(serverId, JSONArray().apply {
            resources.forEach { r ->
                put(JSONObject().put("uri", r.uri).put("name", r.name)
                    .put("description", r.description).put("mimeType", r.mimeType)
                    .put("template", r.template))
            }
        })
        writeCache(KEY_RESOURCES, root)
    }

    /**
     * Stores the text of one resource, keyed by `<serverId>__<uri>`. This is what a
     * subscribed resource's refreshed contents land in, and what lets a later turn
     * quote a resource without a second round trip.
     */
    private fun cacheResourceText(serverId: String, uri: String, result: JSONObject) {
        val root = readCache(KEY_RESOURCE_TEXT)
        root.put("${serverId}__$uri", shapeResource(serverId, uri, result))
        writeCache(KEY_RESOURCE_TEXT, root)
    }

    /** The last-read text of a resource, or null if it was never read. */
    fun cachedResourceText(qualifiedUri: String): JSONObject? =
        readCache(KEY_RESOURCE_TEXT).optJSONObject(qualifiedUri)

    private fun cachePrompts(serverId: String, prompts: List<McpPrompt>) {
        val root = readCache(KEY_PROMPTS)
        root.put(serverId, JSONArray().apply {
            prompts.forEach { p ->
                put(JSONObject().put("name", p.name).put("description", p.description)
                    .put("arguments", JSONArray().apply {
                        p.arguments.forEach { a ->
                            put(JSONObject().put("name", a.name).put("description", a.description)
                                .put("required", a.required))
                        }
                    }))
            }
        })
        writeCache(KEY_PROMPTS, root)
    }

    private fun readCache(key: String): JSONObject =
        runCatching { JSONObject(prefs.getString(key, "{}").orEmpty()) }.getOrDefault(JSONObject())

    private fun writeCache(key: String, value: JSONObject) {
        prefs.edit().putString(key, value.toString()).apply()
    }

    private fun update(serverId: String, transform: (McpConnection) -> McpConnection) {
        val current = _connections.value
        val index = current.indexOfFirst { it.server.id == serverId }
        val updated: List<McpConnection> = when {
            index >= 0 -> current.toMutableList().also { it[index] = transform(it[index]) }
            else -> {
                // A custom server is in neither the catalogue nor (yet) the list, so
                // it is also looked up in what was persisted. Without this fallback a
                // custom server could connect and still never appear.
                val known = current.firstOrNull { it.server.id == serverId }?.server
                    ?: McpCatalog.find(serverId)
                    ?: loadServerList().firstOrNull { it.id == serverId }
                    ?: return
                current + transform(McpConnection(known))
            }
        }
        _connections.value = updated
        updated.firstOrNull { it.server.id == serverId }?.let { persistState(serverId, it) }
    }

    private fun persistState(serverId: String, conn: McpConnection) {
        val root = readCache(KEY_STATE)
        root.put(serverId, JSONObject()
            .put("status", conn.status.name)
            .put("error", conn.error))
        writeCache(KEY_STATE, root)
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
    private fun resourcesKey(id: String) = "resources_$id"
    private fun promptsKey(id: String) = "prompts_$id"
}
