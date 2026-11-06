package ai.zyraxon.assistant.openhands

import ai.zyraxon.assistant.core.safe

import ai.zyraxon.assistant.i18n.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The key is missing or the cloud refused it. */
class OhAuthError(message: String) : Exception(message)

/** The cloud answered, but with a refusal we cannot retry through. */
class OhApiError(val code: Int, message: String) : Exception(message)

/**
 * Everything that talks to the cloud, in one place.
 *
 * Two hosts are involved and they are not interchangeable: the account API
 * (`app.all-hands.dev/api/v1`) creates and lists conversations, while each
 * conversation's own agent server (a per-sandbox host) carries the live socket,
 * the interrupt, and the confirmation policy. A conversation is addressed by its
 * id on both, but only the agent server knows what is happening right now.
 *
 * Two cloud behaviours are load-bearing and are encoded here rather than left to
 * the caller:
 *  - a sandbox can be created only a limited number of times a day, so a new
 *    chat reuses a warm sandbox when one is offered ([startConversation]);
 *  - the live socket exists only for a running sandbox, so the socket call waits
 *    for `RUNNING` before it opens instead of failing the turn.
 */
class OpenHandsClient(
    private val http: OkHttpClient = defaultHttp(),
    /** The account API root. Overridable so a test can point at its own server. */
    private val base: String = CLOUD
) {
    companion object {
        const val CLOUD = "https://app.all-hands.dev/api/v1"

        /** Methods OkHttp requires a body on, even when there is nothing to say. */
        private val BODIED_METHODS = setOf("POST", "PUT", "PATCH")

        /** How long a first turn may wait for its sandbox before giving up. */
        private const val START_TIMEOUT_MS = 180_000L

        /** How many socket reconnects a single turn tolerates before it fails. */
        private const val MAX_RECONNECTS = 8

        /**
         * How long a sleeping sandbox may take to wake. Measured 13–26s live.
         *
         * A system property overrides it so a unit test can watch the retry loop
         * without spending the real interval waiting.
         */
        private val WAKE_TIMEOUT_MS: Long =
            System.getProperty("zyraxon.wakeTimeoutMs")?.toLongOrNull() ?: 90_000L

        /**
         * No read timeout: a turn can think for minutes without a byte arriving,
         * and a timeout here would kill work that is still going well. The socket
         * has its own ping, so a dead connection is still noticed.
         */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            // HTTP/2 streams were being reset mid-answer with PROTOCOL_ERROR; plain
            // HTTP/1.1 does not have that failure and costs nothing noticeable here.
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun req(path: String, key: String): Request.Builder =
        Request.Builder().url(if (path.startsWith("http")) path else base + path)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")

    private suspend fun call(
        key: String,
        method: String,
        path: String,
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        // A POST/PUT/PATCH must carry a body: OkHttp refuses `method("POST", null)`
        // outright, and that refusal was being swallowed, so a body-less resume
        // never left the phone and a paused sandbox could not actually be woken.
        val json = body?.toString() ?: if (method in BODIED_METHODS) "{}" else null
        val b = req(path, key).method(
            method,
            json?.toRequestBody("application/json".toMediaType())
        )
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw OhAuthError(t("কী গ্রহণ করা হয়নি (HTTP {0})", r.code))
            if (!r.isSuccessful) throw OhApiError(r.code, text.take(400).ifBlank { "HTTP ${r.code}" })
            if (text.isBlank()) JSONObject()
            // A single-id lookup comes back as either {"items":[…]} or a bare […];
            // both are normalised to the first shape so every reader sees one form.
            else if (text.trimStart().startsWith("[")) JSONObject().put("items", JSONArray(text))
            else JSONObject(text)
        }
    }

    // ---- conversations -----------------------------------------------------

    /**
     * Creates an empty conversation and waits for its sandbox to come up.
     *
     * Empty on purpose. The first user message is sent later, once the live socket
     * is already attached, because a conversation created with a message starts
     * answering immediately — its first tokens would be produced before anything
     * was listening and the opening of the turn would be lost. Creating it bare
     * and sending afterwards means the whole turn is seen from its first token.
     *
     * [reuseSandboxId] is the other half: the account rations new sandboxes per
     * day, but a warm one is free and comes up in seconds. A remembered sandbox
     * can still be reclaimed, so a start that fails because of it is retried once
     * without it rather than failing the chat.
     */
    suspend fun startConversation(
        key: String,
        title: String,
        suffix: String?,
        reuseSandboxId: String? = null,
        model: String? = null,
        clientTools: JSONArray? = null,
        onProgress: (String) -> Unit = {}
    ): OhConversation {
        onProgress(t("কনভার্সেশন তৈরি হচ্ছে…"))
        var reuse = reuseSandboxId
        repeat(2) { attempt ->
            val task = OhTask.from(createTask(key, title, suffix, reuse, model, clientTools))
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < START_TIMEOUT_MS) {
                val first = firstItem(call(key, "GET", "/app-conversations/start-tasks?ids=${task.id}"))
                if (first != null) {
                    val t = OhTask.from(first)
                    if (t.isReady) {
                        onProgress(t("স্যান্ডবক্স চালু হচ্ছে…"))
                        return getConversation(key, t.conversationId!!)
                    }
                    if (t.isFailed) {
                        // A reclaimed sandbox is the one failure worth retrying, and
                        // only when one was reused in the first place.
                        if (reuse != null && attempt == 0 &&
                            t.detail?.contains("Sandbox not found", ignoreCase = true) == true
                        ) {
                            reuse = null
                            break
                        }
                        throw OhApiError(0, t.detail ?: ai.zyraxon.assistant.i18n.t("কনভার্সেশন তৈরি ব্যর্থ"))
                    }
                    onProgress(stageLabel(t.status))
                }
                delay(2000)
            }
        }
        throw OhApiError(0, t("কনভার্সেশন সময়মতো তৈরি হয়নি"))
    }

    private suspend fun createTask(
        key: String,
        title: String,
        suffix: String?,
        sandboxId: String?,
        model: String?,
        clientTools: JSONArray? = null
    ): JSONObject {
        val body = JSONObject().put("title", title)
        if (!suffix.isNullOrBlank()) body.put("system_message_suffix", suffix)
        if (!sandboxId.isNullOrBlank()) body.put("sandbox_id", sandboxId)
        if (!model.isNullOrBlank()) body.put("llm_model", model)
        // Phone tools are declared at creation, not later: the cloud registers
        // them as real tools of the conversation, so the agent sees them beside
        // its own from the first turn and can reach for them by itself.
        if (clientTools != null && clientTools.length() > 0) body.put("client_tools", clientTools)
        return call(key, "POST", "/app-conversations", body)
    }

    /** The live state of one conversation, including its rotating session key. */
    suspend fun getConversation(key: String, id: String): OhConversation {
        if (id.isBlank() || id == "null") throw OhApiError(0, t("কনভার্সেশন আইডি এখনও তৈরি হয়নি"))
        val o = call(key, "GET", "/app-conversations?ids=$id")
        val first = firstItem(o) ?: throw OhApiError(0, t("কনভার্সেশন পাওয়া যায়নি"))
        return OhConversation.from(first)
    }

    suspend fun listConversations(key: String, limit: Int = 50): List<OhConversation> {
        val o = call(key, "GET", "/app-conversations/search?limit=$limit")
        val arr = o.optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(OhConversation::from) }
    }

    /**
     * A sandbox that is up right now, if the account has one.
     *
     * Creating a sandbox is rationed per day while reusing a live one is free, so
     * this is asked before every cold start. It reads the conversation list rather
     * than trusting a remembered id, because a remembered sandbox may since have
     * been reclaimed — and starting against a dead one costs a whole failed attempt.
     */
    suspend fun findWarmSandbox(key: String): String? = runCatching {
        listConversations(key, limit = 20)
            .firstOrNull { it.sandboxStatus == "RUNNING" && it.sandboxId.isNotBlank() }
            ?.sandboxId
    }.getOrNull()

    suspend fun deleteConversation(key: String, id: String) {
        call(key, "DELETE", "/app-conversations/$id")
    }

    suspend fun renameConversation(key: String, id: String, title: String) {
        call(key, "PATCH", "/app-conversations/$id", JSONObject().put("title", title))
    }

    /**
     * Queues a user message. The account endpoint is used rather than the agent
     * server's, because this one also wakes a paused sandbox, which is exactly
     * what a message into an idle chat must do.
     *
     * A sleeping sandbox answers `409 Sandbox is PAUSED`, and it is the one
     * refusal worth waking through rather than surfacing: the user typed into a
     * chat that was alive a minute ago, and from their side nothing changed. The
     * wake is done here so every caller — the first message, a tool result handed
     * back mid-turn — gets the same recovery instead of each having to know about
     * it.
     */
    suspend fun sendMessage(
        key: String,
        id: String,
        text: String,
        onStage: (String) -> Unit = {}
    ) {
        val body = JSONObject().put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("run", true)
        try {
            call(key, "POST", "/app-conversations/$id/send-message", body)
        } catch (e: OhApiError) {
            if (e.code != 409) throw e
            val conv = runCatching { getConversation(key, id) }.getOrNull()
                ?: throw e
            if (conv.sandboxId.isBlank()) throw e
            onStage(t("স্যান্ডবক্স জাগানো হচ্ছে…"))
            resumeSandbox(key, conv.sandboxId)
            awaitRunning(key, id, WAKE_TIMEOUT_MS)
            onStage(t("স্যান্ডবক্স চালু — কাজ শুরু হচ্ছে…"))
            call(key, "POST", "/app-conversations/$id/send-message", body)
        }
    }

    /**
     * Wakes a sleeping sandbox. The cloud pauses an idle one on its own, so this
     * is the normal path after the phone has been in a pocket, not an error path.
     */
    suspend fun resumeSandbox(key: String, sandboxId: String) {
        runCatching { call(key, "POST", "/sandboxes/$sandboxId/resume") }
    }

    /**
     * The conversation, but only once its sandbox is actually RUNNING.
     *
     * A conversation outlives its sandbox: the id, the title and the whole event
     * log stay, while the machine behind it is paused and the session key stops
     * working. Everything that talks to the agent server — the live socket, the
     * interrupt — needs the machine up, so this wakes it and waits rather than
     * letting the first request fail and the chat look broken.
     *
     * Waking measured 13–26s against the live cloud, which is why the caller is
     * told what is happening instead of staring at a dead screen.
     */
    suspend fun ensureRunning(
        key: String,
        id: String,
        onStage: (String) -> Unit = {}
    ): OhConversation {
        var conv = getConversation(key, id)
        if (conv.sandboxStatus == "RUNNING") return conv
        if (conv.sandboxId.isBlank()) return conv
        onStage(t("স্যান্ডবক্স জাগানো হচ্ছে…"))
        resumeSandbox(key, conv.sandboxId)
        val started = System.currentTimeMillis()
        while (true) {
            delay(1500)
            conv = runCatching { getConversation(key, id) }.getOrElse { return conv }
            if (conv.sandboxStatus == "RUNNING") return conv
            if (System.currentTimeMillis() - started >= WAKE_TIMEOUT_MS) return conv
        }
    }

    // ---- agent server ------------------------------------------------------

    /** Stops the running turn but keeps the conversation and its history. */
    suspend fun interrupt(conv: OhConversation) {
        agentPost(conv, "/api/conversations/${conv.id}/interrupt")
    }

    /**
     * Turns off every approval prompt. The cloud defaults to asking before risky
     * actions; a phone user driving their own device should not be interrupted by
     * a dialog for every command, so this is set once per conversation.
     */
    suspend fun neverConfirm(conv: OhConversation) {
        agentPost(
            conv, "/api/conversations/${conv.id}/confirmation_policy",
            JSONObject().put("policy", JSONObject().put("kind", "NeverConfirm"))
        )
    }

    private suspend fun agentPost(conv: OhConversation, path: String, body: JSONObject? = null) {
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(conv.agentBase + path)
                .header("X-Session-API-Key", conv.sessionKey)
                .method("POST", (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            http.newCall(b.build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw OhAuthError(t("সেশন কী আর বৈধ নয়"))
                if (!r.isSuccessful) throw OhApiError(r.code, r.body?.string()?.take(300) ?: "HTTP ${r.code}")
            }
        }
    }

    /**
     * The ids of every action the conversation has already recorded.
     *
     * The cloud replays the whole event log whenever a socket attaches, so a
     * reconnecting phone would otherwise re-run whatever the agent asked for last
     * time — opening an app the user never mentioned again. Reading the log first
     * gives an exact list to ignore, which is steadier than guessing from
     * timestamps or from how far the replay has got.
     */
    suspend fun seenActionIds(conv: OhConversation): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(
                Request.Builder().url(conv.agentBase + "/api/conversations/${conv.id}/events/search?limit=100")
                    .header("X-Session-API-Key", conv.sessionKey).build()
                    .let { http.newCall(it).execute().use { r -> r.body?.string() ?: "{}" } }
            )
            val items = o.optJSONArray("items") ?: JSONArray()
            buildSet {
                for (i in 0 until items.length()) {
                    val e = items.optJSONObject(i) ?: continue
                    // The tool call's own id, not the event's: it is what a live
                    // ActionEvent carries, so the two can be compared.
                    if (e.safe("kind") == "ActionEvent") {
                        val cid = e.optJSONObject("tool_call")?.safe("id").orEmpty()
                        if (cid.isNotBlank()) add(cid)
                    }
                }
            }
        }.getOrDefault(emptySet())
    }

    // ---- live stream -------------------------------------------------------

    /**
     * The conversation's live events, as a cold flow.
     *
     * A socket rather than polling: the cloud pushes reasoning, answer tokens,
     * tool calls and status changes as they happen, so the phone shows the turn
     * as it is written instead of a second later. The flow reconnects on its own
     * while [keepAlive] is true, because a socket dropped mid-turn would otherwise
     * freeze the screen with the work still running on the other side.
     */
    fun events(
        apiKey: String,
        conv: OhConversation,
        keepAlive: () -> Boolean = { true },
        /**
         * Called before a reconnect. The common reason a socket dies mid-turn is
         * that the sandbox went to sleep, and its host — and therefore the socket
         * URL and the session key — only come back with it. Returning the woken
         * conversation re-points the socket at the live host; returning null keeps
         * the old one and lets the retry fail on its own.
         */
        reconnect: (suspend () -> OhConversation?)? = null,
        /**
         * Fired the moment the socket is accepted, every time it is (re)opened.
         *
         * The caller uses this to hold the first message until the stream is
         * actually listening: the cloud starts answering the instant a message
         * arrives, and tokens produced before the socket is up are simply never
         * delivered — which is the difference between a live screen and a silent
         * one for the first seconds of every turn.
         */
        onOpen: () -> Unit = {}
    ): Flow<OhEvent> = callbackFlow {
        var current = conv
        var attempt = 0
        var socket: WebSocket? = null
        var closed = AtomicBoolean(false)
        var lastError: Exception? = null
        // Every event carries its own id, and a duplicate delivery is dropped
        // here. Without this a repeated action would run its tool twice, and a
        // repeated token would be shown twice.
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun open() {
            closed = AtomicBoolean(false)
            // No `resend_mode`: the socket is attached *before* the message is
            // sent, so there is no history to catch up on. A replay is precisely
            // what breaks this — a stored `execution_status: finished` from the
            // previous turn arrives as though it were this turn ending, and the
            // turn is declared over before it starts. With nothing replayed,
            // every frame on this socket is live by construction.
            val url = current.agentBase.replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://") + "/sockets/events/${current.id}"
            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    onOpen()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    val e = OhEvent.parse(text) ?: return
                    if (e.id.isNotBlank() && !seen.add(e.id)) return
                    trySend(e)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                    lastError = Exception(t.message ?: "socket")
                    closed.set(true)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    closed.set(true)
                }
            }
            socket = http.newWebSocket(
                Request.Builder().url(url).header("X-Session-API-Key", current.sessionKey).build(),
                listener
            )
        }

        // The sandbox host is not live the instant the conversation is ready, so
        // the first attach waits for RUNNING and refreshes the session key (which
        // rotates on every resume) before it opens. Racing a host that is still
        // starting is the one connect that always fails, and its failure is
        // indistinguishable from a dead network.
        launch(Dispatchers.IO) {
            runCatching {
                val running = awaitRunning(apiKey, current.id)
                if (running.sandboxStatus == "RUNNING") current = running
                open()
            }.onFailure {
                // Could not even reach the host: let the reconnect loop take over
                // rather than leaving the flow attached to nothing.
                closed.set(true)
            }
        }
        // A dropped socket does not end the turn: the work continues on the cloud,
        // so the flow reconnects rather than leaving the screen frozen.
        val watcher = launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                if (!keepAlive()) break
                if (closed.get()) {
                    attempt++
                    if (attempt > MAX_RECONNECTS) {
                        close(lastError ?: OhApiError(0, t("লাইভ সংযোগ বিচ্ছিন্ন")))
                        break
                    }
                    delay(backoffMs(attempt))
                    // A sleeping sandbox takes the socket host with it, so the
                    // connection is only worth retrying against a woken one.
                    reconnect?.invoke()?.let { current = it }
                    open()
                }
            }
        }
        awaitClose {
            watcher.cancel()
            socket?.close(1000, null)
        }
    }.flowOn(Dispatchers.IO)

    // ---- helpers -----------------------------------------------------------

    /**
     * The most recent events the conversation has recorded, read over REST.
     *
     * This is the safety net under the socket: a phone whose network drops the
     * WebSocket silently would otherwise show a frozen screen while the cloud
     * kept working. The same events arrive here, so the caller can fill the gap
     * and the turn still ends when the cloud says it did.
     */
    suspend fun recentEvents(conv: OhConversation, limit: Int = 30): List<OhEvent> =
        withContext(Dispatchers.IO) {
            runCatching {
                val o = JSONObject(
                    Request.Builder()
                        .url(conv.agentBase + "/api/conversations/${conv.id}/events/search?limit=$limit")
                        .header("X-Session-API-Key", conv.sessionKey).build()
                        .let { http.newCall(it).execute().use { r -> r.body?.string() ?: "{}" } }
                )
                val items = o.optJSONArray("items") ?: JSONArray()
                (0 until items.length()).mapNotNull { i ->
                    items.optJSONObject(i)?.let { OhEvent.parse(it.toString()) }
                }
            }.getOrDefault(emptyList())
        }

    /** Waits until the sandbox is RUNNING, which is when the socket will accept. */
    suspend fun awaitRunning(key: String, id: String, timeoutMs: Long = 120_000): OhConversation {
        val started = System.currentTimeMillis()
        var conv = getConversation(key, id)
        while (conv.sandboxStatus != "RUNNING") {
            if (System.currentTimeMillis() - started >= timeoutMs) return conv
            delay(1500)
            conv = getConversation(key, id)
        }
        return conv
    }

    private fun backoffMs(attempt: Int): Long =
        (500L * (1 shl (attempt - 1).coerceAtMost(4))).coerceAtMost(8000L)

    private fun stageLabel(status: String): String = when (status) {
        "SETTING_UP_SKILLS" -> t("সেটআপ হচ্ছে…")
        "STARTING_CONVERSATION" -> t("কনভার্সেশন চালু হচ্ছে…")
        "WORKING" -> t("কাজ শুরু হচ্ছে…")
        "PREPARING_REPOSITORY" -> t("রিপোজিটরি প্রস্তুত হচ্ছে…")
        else -> t("অপেক্ষা করা হচ্ছে…")
    }

    /** Both `[item]` and `{"items":[…]}` are returned by the cloud for one id. */
    private fun firstItem(o: JSONObject): JSONObject? {
        o.optJSONArray("items")?.let { if (it.length() > 0) return it.optJSONObject(0) }
        return null
    }
}
