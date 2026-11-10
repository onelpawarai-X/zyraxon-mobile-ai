package ai.zyraxon.assistant.cloud

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
class CloudAuthError(message: String) : Exception(message)

/** The cloud answered, but with a refusal we cannot retry through. */
class CloudApiError(val code: Int, message: String) : Exception(message)

/**
 * Everything that talks to the cloud, in one place.
 *
 * Two hosts are involved and they are not interchangeable: the account API
 * (the gateway's `/api/oh/v1`) creates and lists conversations, while each
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
class CloudClient(
    private val http: OkHttpClient = defaultHttp(),
    /** The account API root. Overridable so a test can point at its own server. */
    private val base: String = CLOUD
) {
    companion object {
        /**
         * ZYRAXON's own gateway, not the upstream it fronts. Every cloud call the
         * app makes goes here, and this server forwards it and rewrites the
         * addresses in the reply, so no upstream host is ever named on the phone
         * or shown to the user. The per-conversation agent server the gateway
         * hands back lives under `<this base>/../agent`.
         */
        const val CLOUD = "https://zyraxonai.lovable.app/api/oh/v1"

        /** Methods OkHttp requires a body on, even when there is nothing to say. */
        private val BODIED_METHODS = setOf("POST", "PUT", "PATCH")

        /** How long a first turn may wait for its sandbox before giving up. */
        private const val START_TIMEOUT_MS = 180_000L

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

        /**
         * The most events one page of the log may hold. The service refuses a
         * larger `limit` with a 422, so every read is capped here and the rest of
         * the window is walked with `page_id`.
         */
        const val EVENTS_PAGE = 100

        /**
         * How far back the "already recorded" action scan reads. A tool call that
         * happened earlier than this is not re-run; it is only used to avoid
         * repeating the agent's own recent steps, not to replay a whole chat.
         */
        private const val ACTION_LOOKBACK = 300

        /**
         * The most events one catch-up read may return. Big enough that an answer
         * streamed while the socket was down is recovered whole, and paged past
         * the service's own 100-event page limit.
         */
        private const val TAIL_PAGE = 400
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
            if (r.code == 401 || r.code == 403) throw CloudAuthError(t("কী গ্রহণ করা হয়নি (HTTP {0})", r.code))
            if (!r.isSuccessful) throw CloudApiError(r.code, text.take(400).ifBlank { "HTTP ${r.code}" })
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
    ): CloudConversation {
        onProgress(t("কনভার্সেশন তৈরি হচ্ছে…"))
        var reuse = reuseSandboxId
        repeat(2) { attempt ->
            val task = CloudTask.from(createTask(key, title, suffix, reuse, model, clientTools))
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < START_TIMEOUT_MS) {
                val first = firstItem(call(key, "GET", "/app-conversations/start-tasks?ids=${task.id}"))
                if (first != null) {
                    val t = CloudTask.from(first)
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
                        throw CloudApiError(0, t.detail ?: ai.zyraxon.assistant.i18n.t("কনভার্সেশন তৈরি ব্যর্থ"))
                    }
                    onProgress(stageLabel(t.status))
                }
                delay(2000)
            }
        }
        throw CloudApiError(0, t("কনভার্সেশন সময়মতো তৈরি হয়নি"))
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
    suspend fun getConversation(key: String, id: String): CloudConversation {
        if (id.isBlank() || id == "null") throw CloudApiError(0, t("কনভার্সেশন আইডি এখনও তৈরি হয়নি"))
        val o = call(key, "GET", "/app-conversations?ids=$id")
        val first = firstItem(o) ?: throw CloudApiError(0, t("কনভার্সেশন পাওয়া যায়নি"))
        return CloudConversation.from(first)
    }

    suspend fun listConversations(key: String, limit: Int = 50): List<CloudConversation> {
        val o = call(key, "GET", "/app-conversations/search?limit=$limit")
        val arr = o.optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(CloudConversation::from) }
    }

    /**
     * Every conversation on the account, newest first, walking every page.
     *
     * The search endpoint caps a page, so an account with more than one page of
     * history would otherwise only ever show its newest slice — and the very
     * conversations the user wants back are the older ones. `next_page_id` is
     * followed until the service stops offering one.
     */
    suspend fun allConversations(key: String, max: Int = 500): List<CloudConversation> {
        val out = ArrayList<CloudConversation>()
        var page: String? = null
        while (out.size < max) {
            val want = (max - out.size).coerceIn(1, 100)
            val path = buildString {
                append("/app-conversations/search?limit=").append(want)
                if (page != null) append("&page_id=").append(page)
            }
            val o = call(key, "GET", path)
            val arr = o.optJSONArray("items") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(CloudConversation.from(it)) }
            }
            page = o.optString("next_page_id").takeIf { it.isNotBlank() && it != "null" } ?: break
        }
        return out
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
        } catch (e: CloudApiError) {
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
    ): CloudConversation {
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
    suspend fun interrupt(apiKey: String, conv: CloudConversation) {
        agentPost(apiKey, conv, "/api/conversations/${conv.id}/interrupt")
    }

    /**
     * Turns off every approval prompt. The cloud defaults to asking before risky
     * actions; a phone user driving their own device should not be interrupted by
     * a dialog for every command, so this is set once per conversation.
     */
    suspend fun neverConfirm(apiKey: String, conv: CloudConversation) {
        agentPost(
            apiKey, conv, "/api/conversations/${conv.id}/confirmation_policy",
            JSONObject().put("policy", JSONObject().put("kind", "NeverConfirm"))
        )
    }

    /**
     * A call to the conversation's own agent server.
     *
     * The session key proves the caller may drive *this* conversation, but it
     * cannot say where that conversation is: the host is the account API's
     * knowledge. The gateway in front of both therefore needs the account token
     * too, and it is sent here alongside the session key. Both are the caller's
     * own credentials; the gateway stores neither.
     */
    private suspend fun agentPost(apiKey: String, conv: CloudConversation, path: String, body: JSONObject? = null) {
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(conv.agentBase + path)
                .header("X-Session-API-Key", conv.sessionKey)
                .header("Authorization", "Bearer $apiKey")
                .method("POST", (body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
            http.newCall(b.build()).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw CloudAuthError(t("সেশন কী আর বৈধ নয়"))
                if (!r.isSuccessful) throw CloudApiError(r.code, r.body?.string()?.take(300) ?: "HTTP ${r.code}")
            }
        }
    }

    /**
     * The ids of every action the conversation has already recorded.
     *
     * A reconnecting phone would otherwise re-run whatever the agent asked for
     * last time — opening an app the user never mentioned again. Reading the log
     * first gives an exact list to ignore, which is steadier than guessing from
     * timestamps or from how far a replay has got.
     *
     * The window is paged rather than one capped page: the service refuses
     * `limit` above [EVENTS_PAGE] with a 422, and a single page of the *oldest*
     * events was all that came back before, so on a long conversation every
     * recent call looked new and was run a second time on the phone.
     */
    suspend fun seenActionIds(apiKey: String, conv: CloudConversation): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            buildSet {
                for (e in eventsWindow(apiKey, conv, limit = ACTION_LOOKBACK)) {
                    // The tool call's own id, not the event's: it is what a live
                    // ActionEvent carries, so the two can be compared.
                    if (e.str("kind") == "ActionEvent") {
                        val cid = e.optJSONObject("tool_call")?.str("id").orEmpty()
                        if (cid.isNotBlank()) add(cid)
                    }
                }
            }
        }.getOrDefault(emptySet())
    }

    /**
     * The conversation's recorded events, newest first, walking every page.
     *
     * Two service behaviours are handled here. A page holds at most
     * [EVENTS_PAGE] events and a larger `limit` is refused outright, so a long
     * chat only comes back whole if its `next_page_id` is followed. And the
     * order matters: read oldest-first, a window of N events is the *start* of
     * the conversation, which is exactly the wrong end — the newest reply is the
     * one that has to be seen, and on a chat longer than one page it never was.
     */
    private fun eventsWindow(apiKey: String, conv: CloudConversation, limit: Int): List<JSONObject> {
        val out = ArrayList<JSONObject>(limit)
        var page: String? = null
        while (out.size < limit) {
            val want = (limit - out.size).coerceIn(1, EVENTS_PAGE)
            val url = buildString {
                append(conv.agentBase)
                append("/api/conversations/").append(conv.id)
                append("/events/search?limit=").append(want)
                append("&sort_order=TIMESTAMP_DESC")
                if (page != null) append("&page_id=").append(page)
            }
            val o = JSONObject(
                Request.Builder().url(url)
                    .header("X-Session-API-Key", conv.sessionKey)
                    // The gateway resolves the conversation's host from the
                    // account token, so every agent read carries it too.
                    .header("Authorization", "Bearer $apiKey").build()
                    .let { http.newCall(it).execute().use { r -> r.body?.string() ?: "{}" } }
            )
            val items = o.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let { out.add(it) }
            }
            page = o.optString("next_page_id").takeIf { it.isNotBlank() && it != "null" } ?: break
        }
        return out
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
        conv: CloudConversation,
        keepAlive: () -> Boolean = { true },
        /**
         * Called before a reconnect. The common reason a socket dies mid-turn is
         * that the sandbox went to sleep, and its host — and therefore the socket
         * URL and the session key — only come back with it. Returning the woken
         * conversation re-points the socket at the live host; returning null keeps
         * the old one and lets the retry fail on its own.
         */
        reconnect: (suspend () -> CloudConversation?)? = null,
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
    ): Flow<CloudEvent> = callbackFlow {
        var current = conv
        var attempt = 0
        var socket: WebSocket? = null
        var closed = AtomicBoolean(false)
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
                    // A healthy socket resets the retry count, so a long session
                    // with occasional drops never accumulates its way to a
                    // permanent give-up.
                    attempt = 0
                    onOpen()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    val e = CloudEvent.parse(text) ?: return
                    if (e.id.isNotBlank() && !seen.add(e.id)) return
                    trySend(e)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                    closed.set(true)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    closed.set(true)
                }
            }
            socket = http.newWebSocket(
                Request.Builder().url(url)
                    .header("X-Session-API-Key", current.sessionKey)
                    // The gateway needs the account token to resolve which host
                    // this conversation lives on before it can open the socket.
                    .header("Authorization", "Bearer $apiKey").build(),
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
                    // The retry never gives up on its own: the turn is still
                    // running on the cloud, and ending the flow here would end the
                    // turn on the phone while the work went on. The backoff is
                    // simply capped, so a long outage costs one slow retry rather
                    // than a screen that stops listening.
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
     * The most recent events the conversation has recorded, read over REST, in
     * reading order (oldest of the window first).
     *
     * This is the safety net under the socket: a phone whose network drops the
     * WebSocket silently would otherwise show a frozen screen while the cloud
     * kept working. The window is the *newest* [limit] events, paged, because a
     * single capped page of the oldest ones is not a safety net at all — the
     * newest reply is the thing that was missing.
     */
    suspend fun recentEvents(apiKey: String, conv: CloudConversation, limit: Int = 40): List<CloudEvent> =
        withContext(Dispatchers.IO) {
            runCatching {
                eventsWindow(apiKey, conv, limit = limit.coerceIn(1, EVENTS_PAGE))
                    .asReversed()
                    .mapNotNull { CloudEvent.parse(it.toString()) }
            }.getOrDefault(emptyList())
        }

    /**
     * One recorded event together with the timestamp it was recorded at.
     *
     * The timestamp is the cursor: it is what the next read asks the service for
     * ("everything after this"), so the live REST tail can follow a conversation
     * of any length without ever re-reading the events it has already handled.
     */
    data class CloudRecorded(val at: String, val event: CloudEvent)

    /**
     * The events recorded after [since], oldest first — a true tail of the log.
     *
     * This is the read a dropped socket is repaired with. Asking by cursor rather
     * than "the newest N" is what makes it correct at every length: a fixed window
     * silently skips everything that arrived between two reads once the
     * conversation outgrows the window, which is precisely when a long-running
     * answer is most likely to be missed. Pages are walked so the service's own
     * 100-event page limit is not a ceiling on how much can be caught up.
     */
    suspend fun eventsSince(conv: CloudConversation, since: String, limit: Int = TAIL_PAGE): List<CloudRecorded> =
        withContext(Dispatchers.IO) {
            runCatching { readSince(conv, since, limit) }.getOrDefault(emptyList())
        }

    private fun readSince(conv: CloudConversation, since: String, limit: Int): List<CloudRecorded> {
        val out = ArrayList<CloudRecorded>(limit)
        var page: String? = null
        while (out.size < limit) {
            val want = (limit - out.size).coerceIn(1, EVENTS_PAGE)
            val url = buildString {
                append(conv.agentBase)
                append("/api/conversations/").append(conv.id)
                append("/events/search?limit=").append(want)
                append("&sort_order=TIMESTAMP")
                // Inclusive on purpose. Two events can share a timestamp, and a
                // strict `gt` would drop the second one for good — a lost token
                // that no retry can bring back. Re-reading the cursor event costs
                // one frame per poll and the caller's id de-duplication discards
                // it, which is a far better trade than silently losing an answer.
                // Encoded because an ISO timestamp carries colons, which are not
                // legal raw in a query value.
                if (since.isNotBlank()) {
                    append("&timestamp__gte=")
                    append(java.net.URLEncoder.encode(since, "UTF-8"))
                }
                if (page != null) append("&page_id=").append(page)
            }
            val o = JSONObject(
                Request.Builder().url(url)
                    .header("X-Session-API-Key", conv.sessionKey).build()
                    .let { http.newCall(it).execute().use { r -> r.body?.string() ?: "{}" } }
            )
            val items = o.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val e = items.optJSONObject(i) ?: continue
                // A record with no timestamp is still returned so nothing is
                // dropped; the caller's id de-duplication keeps it from being
                // shown twice.
                CloudEvent.parse(e.toString())?.let { out.add(CloudRecorded(e.str("timestamp"), it)) }
            }
            page = o.optString("next_page_id").takeIf { it.isNotBlank() && it != "null" } ?: break
        }
        return out
    }

    /**
     * The timestamp of the newest event on record, or "" when there is none.
     *
     * This is the boundary a turn starts from: the socket is attached and this is
     * read *before* the first message is sent, so everything the REST tail later
     * sees past this point belongs to the turn that is starting. It is what makes
     * "the agent has finished" a statement about *this* turn rather than about the
     * one before it — a stale `finished` cannot end a turn that never ran.
     */
    suspend fun latestTimestamp(conv: CloudConversation): String = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(
                Request.Builder()
                    .url(conv.agentBase + "/api/conversations/${conv.id}/events/search?limit=1&sort_order=TIMESTAMP_DESC")
                    .header("X-Session-API-Key", conv.sessionKey).build()
                    .let { http.newCall(it).execute().use { r -> r.body?.string() ?: "{}" } }
            )
            o.optJSONArray("items")?.optJSONObject(0)?.str("timestamp").orEmpty()
        }.getOrDefault("")
    }

    /** Waits until the sandbox is RUNNING, which is when the socket will accept. */
    suspend fun awaitRunning(key: String, id: String, timeoutMs: Long = 120_000): CloudConversation {
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
