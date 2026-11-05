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
    private val store: OpenHandsStore
) {

    /**
     * One user turn.
     *
     * @param existing a conversation already bound to this chat. When present the
     *   cold start is skipped entirely — this is what makes the second message in
     *   a chat instant while the first one shows real progress.
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
        existing: OhConversation? = null,
        onToolCall: suspend (name: String, args: JSONObject) -> JSONObject = { _, _ -> JSONObject() },
        onProgress: (String) -> Unit = {}
    ): Flow<String> = channelFlow {
        var conv = existing

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
            // from last time is refreshed before anything is sent.
            conv = withContext(Dispatchers.IO) { client.awaitRunning(apiKey, conv!!.id) }
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
            client.events(live) { true }.collect { e ->
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
                        if (e.name in PHONE_TOOLS && !alreadyDone && toolRounds < MAX_TOOL_ROUNDS) {
                            toolRounds++
                            val result = runCatching { onToolCall(e.name, e.args) }
                                .getOrElse { JSONObject().put("ok", false).put("error", it.message) }
                            trySend(toolNote(e.name, result))
                            // The agent must learn what really happened, so the
                            // outcome is sent back as the next thing it sees.
                            pendingTool = true
                            val delivered = withContext(Dispatchers.IO) {
                                runCatching {
                                    client.sendMessage(apiKey, live.id, toolResult(e.name, result))
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
            client.sendMessage(apiKey, live.id, prompt)
        }
        awaitClose { watcher.cancel() }
    }.flowOn(Dispatchers.IO)

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
         * Tools that only exist on the phone. The cloud has its own terminal and
         * files; these are the ones that must run here, on the device the user is
         * holding, and are therefore handled locally.
         */
        val PHONE_TOOLS = setOf(
            "open_app", "list_apps", "launch_url", "read_screen", "screen_shot",
            "tap", "tap_text", "swipe", "type_text", "press_key", "notify",
            "clipboard_read", "clipboard_write", "read_sms", "share",
            "look_camera", "read_media", "call_state", "call_answer", "call_hangup",
            "call_dial", "open_settings", "device_lock", "voice_change",
            "setup_shizuku", "live_voice", "look_at_screen"
        )
    }
}
