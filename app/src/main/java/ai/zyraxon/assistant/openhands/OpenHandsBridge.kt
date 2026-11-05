package ai.zyraxon.assistant.openhands

import ai.zyraxon.assistant.ai.ZyraxonAiEngine
import ai.zyraxon.assistant.model.AgentMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a cloud conversation into the token stream the rest of the app already
 * understands.
 *
 * The engine's contract is a `Flow<String>` where ordinary chunks are answer text
 * and chunks carrying [ZyraxonAiEngine.REASONING_PREFIX] are thinking. Keeping
 * that contract means every existing screen, bubble and thinking panel works with
 * a cloud conversation unchanged — the app does not learn a second protocol.
 *
 * Tool calls are split by ownership. A tool the cloud runs itself (its terminal,
 * its files, its git) is only displayed; its result arrives on the same socket as
 * an observation. A tool that belongs to the phone is executed here, on the
 * device, because that is the only place it can run.
 */
class OpenHandsBridge(
    private val client: OpenHandsClient,
    private val store: OpenHandsStore,
    /** False while the phone has no usable network. A wake is not attempted then. */
    private val online: () -> Boolean = { true }
) {

    /**
     * One user turn.
     *
     * @param existing resolves a conversation already bound to this chat, and is
     *   invoked inside the flow so a wake it triggers is narrated to the user.
     *   When it returns one the cold start is skipped entirely — this is what
     *   makes the second message in a chat instant while the first shows progress.
     * @param onToolCall runs a phone-owned tool; the cloud never executes these.
     * @param onProgress the cloud's own provisioning stage, for the first turn.
     */
    fun runTurn(
        apiKey: String,
        chatId: String,
        prompt: String,
        mode: AgentMode,
        systemSuffix: String,
        clientTools: JSONArray? = null,
        existing: (suspend ((String) -> Unit) -> OhConversation?)? = null,
        onToolCall: suspend (name: String, args: JSONObject) -> JSONObject = { _, _ -> JSONObject() },
        onProgress: (String) -> Unit = {},
        /**
         * The phone's screen as text, refreshed as the turn runs. The cloud agent
         * is driven by a model with no eyes (measured live: a DeepSeek text
         * model), so a screenshot is a blob it cannot read. This digest carries
         * every element with its own coordinate instead, and it is re-read for
         * every step — the initial message and every tool result — so the agent
         * acts on the phone as it is now, not as it was when the turn began.
         */
        screenDigest: () -> String = { "" }
    ): Flow<String> = channelFlow {
        // Resolved inside the flow, not by the caller: waking a sleeping sandbox
        // takes tens of seconds, and this is the only place where those seconds
        // can be narrated to the user while they happen.
        var conv = existing?.invoke { stage ->
            onProgress(stage)
            trySend(ZyraxonAiEngine.REASONING_PREFIX + "⏳ $stage")
        }

        // ---- cold start, shown as it happens -------------------------------
        if (conv == null) {
            // Prefer a sandbox the account says is up right now; fall back to the
            // one this chat used before only if the list could not be read.
            val warm = withContext(Dispatchers.IO) {
                client.findWarmSandbox(apiKey) ?: store.takeWarmSandbox()
            }
            val started = withContext(Dispatchers.IO) {
                client.startConversation(
                    key = apiKey,
                    title = prompt.take(60).replace('\n', ' ').trim().ifBlank { "নতুন চ্যাট" },
                    suffix = systemSuffix,
                    reuseSandboxId = warm,
                    clientTools = clientTools,
                    // The cold start is shown where thinking will appear, because
                    // that is exactly what it is: the turn working before it can
                    // answer. A user who sees the real stage knows the wait is work,
                    // not a hang — and it is the same block that then fills with the
                    // agent's own reasoning.
                    onProgress = { stage ->
                        onProgress(stage)
                        trySend(ZyraxonAiEngine.REASONING_PREFIX + "⏳ $stage")
                    }
                )
            }
            conv = started
            store.bind(chatId, started.id, started.sandboxId)
            store.lastConversationId = started.id
        } else {
            // The session key rotates whenever a sandbox resumes, so the one held
            // from last time is refreshed before anything is sent. The cloud
            // pauses an idle sandbox on its own, and a paused one refuses every
            // call, so this is where the chat is brought back to life rather than
            // failing: measured 13–26s to wake, shown to the user as it happens.
            conv = withContext(Dispatchers.IO) {
                client.ensureRunning(apiKey, conv!!.id) { stage ->
                    onProgress(stage)
                    trySend(ZyraxonAiEngine.REASONING_PREFIX + "⏳ $stage")
                }
            }
            // Remembered here too, not only on a cold start: the heartbeat and the
            // refresh button both need to know which conversation to keep awake.
            conv?.let { store.lastConversationId = it.id }
        }

        val live = conv!!
        // Everything the conversation already recorded, so a replayed action is
        // recognised as history and not run a second time.
        val history = client.seenActionIds(live)
        // The socket is attached first, then the message is sent. The other order
        // loses the opening of the turn: the cloud starts answering the instant a
        // message arrives, and tokens produced before anything is listening are
        // simply not delivered.
        //
        // Two counters make the loop safe. The cloud answers a phone tool with a
        // placeholder ("dispatched to client") and ends that run, so the real
        // outcome has to be handed back as a fresh message — which means one turn
        // can end more than once. [pendingTool] says a run is about to be restarted
        // and must not be treated as the end, and [toolRounds] stops an agent that
        // calls phone tools forever.
        var pendingTool = false
        var toolRounds = 0

        val watcher = launch {
            // If the socket dies because the sandbox slept, the same wake that a
            // cold message uses is run again before reconnecting, so a long job
            // survives the phone being idle rather than dying with the socket.
            client.events(live, { true }, reconnect = {
                client.ensureRunning(apiKey, live.id) { stage ->
                    onProgress(stage)
                    trySend(ZyraxonAiEngine.REASONING_PREFIX + "⏳ $stage")
                }
            }).collect { e ->
                when (e) {
                    is OhEvent.Delta -> {
                        if (e.reasoning.isNotEmpty()) trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                        if (e.text.isNotEmpty()) trySend(e.text)
                    }

                    is OhEvent.Message -> {
                        if (e.role == "assistant" && e.reasoning.isNotEmpty()) {
                            trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                        }
                        if (e.role == "assistant" && e.text.isNotEmpty()) trySend(e.text)
                    }

                    is OhEvent.Action -> {
                        // A phone-owned tool runs here; the cloud's own tools are
                        // left alone, because their result is already on the way.
                        val alreadyDone = e.id.isNotBlank() && e.id in history
                        // A tool the phone owns runs here. The check is against the
                        // live registry rather than a written-out list, so a tool
                        // the app can run is never silently skipped, and a name the
                        // app cannot run is never awaited forever.
                        val runnable = e.name !in CLOUD_EXCLUDED && (
                            e.name.contains("__") ||
                                ai.zyraxon.assistant.ai.ToolRegistry.allowed(mode, e.name)
                            )
                        if (runnable && !alreadyDone && toolRounds < MAX_TOOL_ROUNDS) {
                            toolRounds++
                            val result = runCatching { onToolCall(e.name, e.args) }
                                .getOrElse { JSONObject().put("ok", false).put("error", it.message) }
                            trySend(toolNote(e.name, result))
                            // The agent must learn what really happened, so the
                            // outcome is sent back as the next thing it sees.
                            pendingTool = true
                            val delivered = withContext(Dispatchers.IO) {
                                runCatching {
                                    client.sendMessage(
                                        apiKey, live.id, toolResult(e.name, result) + screenDigest()
                                    ) { stage ->
                                        trySend(ZyraxonAiEngine.REASONING_PREFIX + "⏳ $stage")
                                    }
                                }.isSuccess
                            }
                            if (!delivered) close()
                        }
                    }

                    is OhEvent.Status -> {
                        if (e.value == "finished" || e.value == "idle") {
                            // A finish that follows a phone tool is the end of the
                            // placeholder run, not of the turn: the result message
                            // has just started the real one.
                            if (pendingTool) pendingTool = false else close()
                        }
                    }

                    is OhEvent.Failure -> close(OhApiError(0, e.detail.ifBlank { e.code }))
                    else -> Unit
                }
            }
        }
        // A moment for the socket to be accepted before the message can start the
        // answer; without it the very first tokens race the connection.
        delay(600)
        withContext(Dispatchers.IO) {
            runCatching { client.neverConfirm(live) }
            client.sendMessage(apiKey, live.id, prompt + screenDigest()) { stage ->
                onProgress(stage)
                trySend(ZyraxonAiEngine.REASONING_PREFIX + "⏳ $stage")
            }
        }
        awaitClose { watcher.cancel() }
    }.flowOn(Dispatchers.IO)

    /**
     * Wakes the conversation behind a chat, for callers outside a turn (the app
     * coming back to the foreground, the overlay waking up). Returns null when
     * there is nothing to wake or the phone is offline.
     */
    suspend fun wake(apiKey: String, chatId: String, onStage: (String) -> Unit = {}): OhConversation? {
        val id = store.conversationFor(chatId) ?: return null
        if (apiKey.isBlank() || !online()) return null
        return runCatching {
            client.ensureRunning(apiKey, id, onStage)
        }.getOrNull()
    }

    /**
     * The name of a conversation to show in the phone's own chat list, taken from
     * the cloud when one exists so a chat renamed on either side agrees.
     */
    suspend fun remoteTitle(apiKey: String, conversationId: String): String? =
        runCatching { client.getConversation(apiKey, conversationId).title }.getOrNull()

    /** A one-line, human-readable record of a phone tool the agent asked for. */
    private fun toolNote(name: String, result: JSONObject): String {
        val ok = result.optBoolean("ok", false)
        val summary = result.optString("summary")
            .ifBlank { result.optString("error") }
            .ifBlank { result.toString() }
        return "\n\n`$name` ${if (ok) "✓" else "✗"} ${summary.take(200)}\n\n"
    }

    /**
     * The tool's real outcome, phrased so the agent treats it as the result it is
     * waiting for rather than as a new request from the user. The cloud already
     * produced its own placeholder observation, so this message is what corrects
     * the record with what actually happened on the phone.
     */
    private fun toolResult(name: String, result: JSONObject): String {
        val ok = result.optBoolean("ok", false)
        val summary = result.optString("summary").ifBlank { result.toString() }
        val data = result.opt("data")
        return buildString {
            append("[phone tool result] ").append(name).append(" → ")
            append(if (ok) "success" else "failure").append("\n")
            append(summary.take(1500))
            if (data != null && data.toString() != "null") {
                append("\ndata: ").append(data.toString().take(2500))
            }
            append("\nContinue the task with this result; do not call this tool again for the same step.")
        }
    }

    companion object {
        /**
         * How many phone tools one turn may run. Each one costs a round trip to the
         * cloud, so a generous cap keeps a long automation working while still
         * stopping a loop that would never end.
         */
        private const val MAX_TOOL_ROUNDS = 25

        /**
         * Tools the cloud is NOT offered, because the cloud already owns them or
         * because their result could never come back into the conversation.
         *
         * Everything else in the live registry is published. The list used to be
         * written out by hand, and it drifted: it offered the cloud a
         * `look_at_screen` tool that had no implementation anywhere, and it hid
         * the browser, the terminal, the file tools and every publishing tool, so
         * a cloud turn could drive WhatsApp but could not build anything. Taking
         * the list from the registry means a capability added to the app is
         * reachable from the cloud the moment it exists.
         */
        private val CLOUD_EXCLUDED = setOf(
            // The cloud has its own brain and its own delegation; running another
            // agent on the phone would nest two agents over one screen.
            "on_device_model", "sub_agent",
            // Changing which MCP servers exist mid-turn would pull tools out from
            // under the run that is using them.
            "mcp_connect", "mcp_disconnect",
            // The cloud conversation already had its approvals turned off; a
            // second approval channel is a loop.
            "request_permission",
            // A job scheduled for later outlives the turn, and its result has no
            // way back into the conversation — the cloud would be told it started
            // and never learn it finished.
            "schedule_command",
            // A live voice session would take over the turn that is already
            // streaming text to the phone.
            "live_voice"
        )

        /**
         * The phone's own tools, as the cloud should see them: every tool the app
         * can really run in [mode], minus the few the cloud must not be handed.
         * MCP tools are appended by the caller, which knows the live connections.
         */
        fun phoneTools(mode: AgentMode): JSONArray {
            val out = JSONArray()
            for (s in ai.zyraxon.assistant.ai.ToolRegistry.schemas(mode)) {
                val name = s.optJSONObject("function")?.optString("name") ?: continue
                if (name in CLOUD_EXCLUDED) continue
                out.put(s)
            }
            return out
        }
    }
}
