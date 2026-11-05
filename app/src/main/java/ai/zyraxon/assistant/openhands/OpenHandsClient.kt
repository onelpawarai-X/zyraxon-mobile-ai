package ai.zyraxon.assistant.openhands

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
    private val http: OkHttpClient = defaultHttp()
) {
    companion object {
        const val CLOUD = "https://app.all-hands.dev/api/v1"

        /** How long a first turn may wait for its sandbox before giving up. */
        private const val START_TIMEOUT_MS = 180_000L

        /** How many socket reconnects a single turn tolerates before it fails. */
        private const val MAX_RECONNECTS = 8

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
            .build()
    }

    private fun req(path: String, key: String): Request.Builder =
        Request.Builder().url(if (path.startsWith("http")) path else CLOUD + path)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")

    private suspend fun call(
        key: String,
        method: String,
        path: String,
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val b = req(path, key).method(
            method,
            body?.toString()?.toRequestBody("application/json".toMediaType())
        )
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw OhAuthError("কী গ্রহণ করা হয়নি (HTTP ${r.code})")
            if (!r.isSuccessful) throw OhApiError(r.code, text.take(400).ifBlank { "HTTP ${r.code}" })
            if (text.isBlank()) JSONObject() else JSONObject(text)
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
        onProgress("কনভার্সেশন তৈরি হচ্ছে…")
        var reuse = reuseSandboxId
        repeat(2) { attempt ->
            val task = OhTask.from(createTask(key, title, suffix, reuse, model, clientTools))
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < START_TIMEOUT_MS) {
                val first = firstItem(call(key, "GET", "/app-conversations/start-tasks?ids=${task.id}"))
                if (first != null) {
                    val t = OhTask.from(first)
                    if (t.isReady) {
                        onProgress("স্যান্ডবক্স চালু হচ্ছে…")
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
                        throw OhApiError(0, t.detail ?: "কনভার্সেশন তৈরি ব্যর্থ")
                    }
                    onProgress(stageLabel(t.status))
                }
                delay(2000)
            }
        }
        throw OhApiError(0, "কনভার্সেশন সময়মতো তৈরি হয়নি")
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

    /**
     * Turns the phone's tool schemas into the cloud's `ClientToolSpec` shape.
     *
     * The app already describes every tool it can run — that is what the local
     * model is offered — so the same descriptions are reused rather than written
     * twice. [allow] narrows that to the subset the bridge really executes on this
     * device, so the agent is never handed a tool that nobody will answer.
     */
    fun clientToolSpecs(schemas: JSONArray, allow: Set<String>): JSONArray {
        val out = JSONArray()
        for (i in 0 until schemas.length()) {
            val fn = schemas.optJSONObject(i)?.optJSONObject("function") ?: continue
            val name = fn.optString("name")
            // Only tools this app can actually execute are published. Offering the
            // cloud a name nothing here answers would leave the agent waiting on a
            // result that never arrives.
            if (name.isBlank() || name !in allow) continue
            out.put(
                JSONObject()
                    .put("name", name)
                    .put("description", fn.optString("description"))
                    .put("parameters", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
            )
        }
        return out
    }

    /** The live state of one conversation, including its rotating session key. */
    suspend fun getConversation(key: String, id: String): OhConversation {
        val o = call(key, "GET", "/app-conversations?ids=$id")
        val first = firstItem(o) ?: throw OhApiError(0, "কনভার্সেশন পাওয়া যায়নি")
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
     */
    suspend fun sendMessage(key: String, id: String, text: String) {
        call(
            key, "POST", "/app-conversations/$id/send-message",
            JSONObject().put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                .put("run", true)
        )
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
                if (r.code == 401 || r.code == 403) throw OhAuthError("সেশন কী আর বৈধ নয়")
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
                    if (e.optString("kind") == "ActionEvent") add(e.optString("id"))
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
    fun events(conv: OhConversation, keepAlive: () -> Boolean = { true }): Flow<OhEvent> = callbackFlow {
        var attempt = 0
        var socket: WebSocket? = null
        var closed = AtomicBoolean(false)
        var lastError: Exception? = null
        // Attaching the socket replays the stored log, so nothing that arrives
        // before the server's first state frame is news. The listener opens
        // immediately after the subscribe call, which is well before that frame.
        val ready = AtomicBoolean(false)
        val replaySeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun open() {
            closed = AtomicBoolean(false)
            ready.set(false)
            val url = conv.agentBase.replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://") + "/sockets/events/${conv.id}"
            val listener = object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    val e = OhEvent.parse(text) ?: return
                    // The server replays the stored conversation before anything
                    // live. The replay ends with a state frame — `full_state` on
                    // attach, or an `execution_status` update — and that frame is
                    // the boundary: everything before it is history.
                    val isStateFrame = e is OhEvent.Status ||
                        (e is OhEvent.Other && e.kind == "ConversationStateUpdateEvent")
                    if (isStateFrame) {
                        if (!ready.get()) {
                            // Still the replay. Its status describes the previous
                            // run, so forwarding it would end this turn before it
                            // started.
                            ready.set(true)
                            return
                        }
                        if (e is OhEvent.Status) trySend(e)
                        return
                    }
                    if (!ready.get()) {
                        // Replayed action: the tool it names may already have run
                        // before the socket dropped, so it is never run twice.
                        if (e is OhEvent.Action && e.id.isNotBlank()) replaySeen.add(e.id)
                        return
                    }
                    // Belt and braces: a replay that arrives late is still caught by
                    // the id, which the bridge seeds with everything already stored.
                    if (e is OhEvent.Action && e.id.isNotBlank() && !replaySeen.add(e.id)) return
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
                Request.Builder().url(url).header("X-Session-API-Key", conv.sessionKey).build(),
                listener
            )
        }

        open()
        // A dropped socket does not end the turn: the work continues on the cloud,
        // so the flow reconnects rather than leaving the screen frozen.
        val watcher = launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000)
                if (!keepAlive()) break
                if (closed.get()) {
                    attempt++
                    if (attempt > MAX_RECONNECTS) {
                        close(lastError ?: OhApiError(0, "লাইভ সংযোগ বিচ্ছিন্ন"))
                        break
                    }
                    delay(backoffMs(attempt))
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

    /** Waits until the sandbox is RUNNING, which is when the socket will accept. */
    suspend fun awaitRunning(key: String, id: String, timeoutMs: Long = 120_000): OhConversation {
        val started = System.currentTimeMillis()
        var conv = getConversation(key, id)
        while (conv.sandboxStatus != "RUNNING" && System.currentTimeMillis() - started < timeoutMs) {
            delay(1500)
            conv = getConversation(key, id)
        }
        return conv
    }

    private fun backoffMs(attempt: Int): Long =
        (500L * (1 shl (attempt - 1).coerceAtMost(4))).coerceAtMost(8000L)

    private fun stageLabel(status: String): String = when (status) {
        "SETTING_UP_SKILLS" -> "সেটআপ হচ্ছে…"
        "STARTING_CONVERSATION" -> "কনভার্সেশন চালু হচ্ছে…"
        "WORKING" -> "কাজ শুরু হচ্ছে…"
        "PREPARING_REPOSITORY" -> "রিপোজিটরি প্রস্তুত হচ্ছে…"
        else -> "অপেক্ষা করা হচ্ছে…"
    }

    /** Both `[item]` and `{"items":[…]}` are returned by the cloud for one id. */
    private fun firstItem(o: JSONObject): JSONObject? {
        o.optJSONArray("items")?.let { if (it.length() > 0) return it.optJSONObject(0) }
        return null
    }
}
