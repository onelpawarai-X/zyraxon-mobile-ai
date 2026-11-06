package ai.zyraxon.assistant.openhands

import ai.zyraxon.assistant.core.safe

import ai.zyraxon.assistant.ai.ZyraxonAiEngine
import ai.zyraxon.assistant.model.AgentMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        screenDigest: () -> String = { "" },
        /**
         * Words the user typed while the turn was running. They are queued rather
         * than dropped, and are handed back here when the turn ends so they start
         * the next turn instead of vanishing.
         */
        drainInjections: () -> List<String> = { emptyList() },
        /**
         * What this chat said before, as text. It is used **only** when the cloud
         * conversation has to be built from nothing — a brand-new chat, or one
         * whose sandbox was reclaimed and could not be brought back. An existing
         * conversation already holds its own history, so seeding it again would
         * repeat everything; a fresh one holds none, and without this the agent
         * would meet the user for the first time mid-task.
         */
        historySeed: () -> String = { "" }
    ): Flow<String> = channelFlow {
        // Resolved inside the flow, not by the caller: waking a sleeping sandbox
        // takes tens of seconds, and this is the only place where those seconds
        // can be narrated to the user while they happen.
        var conv = existing?.invoke { stage ->
            onProgress(stage)
            trySend(ZyraxonAiEngine.REASONING_PREFIX + "$stage")
        }

        // ---- the conversation, brought to life ----------------------------
        // Whether the prompt at the end must carry this chat's past: only a
        // conversation built from nothing needs it. One that already exists holds
        // its own history, and seeding it again would repeat everything.
        var coldStart = false

        // A stored conversation can be gone for good — the cloud side deleted it,
        // or its sandbox was reclaimed and the resume did not bring it back. That
        // is not a reason to lose the chat: the binding is dropped and a fresh
        // conversation is built, seeded with this chat's history, so the agent
        // still knows what was said and simply has a new machine underneath it.
        if (conv != null) {
            // The session key rotates whenever a sandbox resumes, so the one held
            // from last time is refreshed before anything is sent. The cloud pauses
            // an idle sandbox on its own, and a paused one refuses every call, so
            // this is where the chat is brought back to life rather than failing:
            // measured 13-26s to wake, shown to the user as it happens.
            val revived = withContext(Dispatchers.IO) {
                client.ensureRunning(apiKey, conv!!.id) { stage ->
                    onProgress(stage)
                    trySend(ZyraxonAiEngine.REASONING_PREFIX + "$stage")
                }
            }
            if (revived != null && revived.sandboxStatus == "RUNNING") {
                conv = revived
            } else {
                store.unbind(chatId, keepWarm = false)
                trySend(ZyraxonAiEngine.REASONING_PREFIX +
                    "আগের স্যান্ডবক্স আর নেই — নতুন মেশিনে কথোপকথন আবার গড়া হচ্ছে…")
            }
        }

        if (conv == null) {
            coldStart = true
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
                    // not a hang - and it is the same block that then fills with the
                    // agent's own reasoning.
                    onProgress = { stage ->
                        onProgress(stage)
                        trySend(ZyraxonAiEngine.REASONING_PREFIX + "$stage")
                    }
                )
            }
            conv = started
            store.bind(chatId, started.id, started.sandboxId)
            store.lastConversationId = started.id
        } else {
            // Remembered here too, not only on a cold start: the heartbeat and the
            // refresh button both need to know which conversation to keep awake.
            conv?.let { store.lastConversationId = it.id }
        }

        val live = conv!!
        // Everything the conversation already recorded, so a replayed action is
        // recognised as history and not run a second time.
        val history = client.seenActionIds(live)
        // Where this turn begins. Read before anything is sent, so the REST tail
        // can tell this turn's events from the previous turn's: without it a
        // stored `finished` from the last turn would be read as *this* turn
        // ending, and a turn that never ran would be declared over.
        val turnStart = client.latestTimestamp(live)
        // The socket is attached first, then the message is sent. The other order
        // loses the opening of the turn: the cloud starts answering the instant a
        // message arrives, and tokens produced before anything is listening are
        // simply not delivered.
        //
        // One flag makes the loop safe. The cloud answers a phone tool with a
        // placeholder ("dispatched to client") and ends that run, so the real
        // outcome has to be handed back as a fresh message — which means one turn
        // can end more than once. [pendingTool] says a run is about to be restarted
        // and must not be treated as the end. Nothing counts the phone tools a turn
        // uses: the cloud's runtime has no limit, so the phone does not either.
        var pendingTool = false

        // Completed the moment the event socket is accepted. The first message
        // waits on it so the answer cannot start before anything is listening —
        // the tokens produced in that gap are never delivered, and that gap is
        // exactly the first seconds of every turn.
        val socketOpen = CompletableDeferred<Unit>()

        // Every event the live socket has already handed over. The REST safety
        // net reads the same log, and this is what keeps it from showing a token
        // twice.
        val delivered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        // The cloud streams the answer token by token and then records the whole
        // answer again as one assistant message. Appending both would show every
        // reply twice, so the finished message is only used when nothing was
        // streamed for it — a provider that does not stream, or a tool-only step.
        val sawDeltaText = java.util.concurrent.atomic.AtomicBoolean(false)
        val sawDeltaReasoning = java.util.concurrent.atomic.AtomicBoolean(false)
        // A stored "finished" from the turn before can be replayed before this
        // turn has ever run, and acting on it would end the turn on the phone
        // while the cloud was still working. A finish only counts once this turn
        // has actually been seen running.
        val sawRunning = java.util.concurrent.atomic.AtomicBoolean(false)
        // The last liveness the conversation reported, as the REST tail saw it.
        // A *quiet* log is not the same as a finished turn: the turn is only over
        // when the last thing recorded says it is. Only the tail writes this,
        // because only the tail knows which events belong to this turn.
        val lastStatus = java.util.concurrent.atomic.AtomicReference("")

        // The turn is over — unless the user typed something while it ran. Those
        // words were queued, not dropped, and are sent here as the next turn so a
        // mid-task message is answered instead of lost.
        fun finishTurn() {
            val late = drainInjections()
            if (late.isEmpty()) {
                close()
                return
            }
            launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        client.sendMessage(apiKey, live.id, late.joinToString("\n\n") + screenDigest()) { stage ->
                            onProgress(stage)
                            trySend(ZyraxonAiEngine.REASONING_PREFIX + "$stage")
                        }
                    }
                }
            }
        }

        val watcher = launch {
            // If the socket dies because the sandbox slept, the same wake that a
            // cold message uses is run again before reconnecting, so a long job
            // survives the phone being idle rather than dying with the socket.
            client.events(apiKey, live, { true }, reconnect = {
                client.ensureRunning(apiKey, live.id) { stage ->
                    onProgress(stage)
                    trySend(ZyraxonAiEngine.REASONING_PREFIX + "$stage")
                }
            }, onOpen = { socketOpen.complete(Unit) }).collect { e ->
                // Marked as delivered by the socket so the REST safety net does not
                // show it a second time.
                if (e.id.isNotBlank()) delivered.add(e.id)
                when (e) {
                    is OhEvent.Delta -> {
                        if (e.reasoning.isNotEmpty()) {
                            sawDeltaReasoning.set(true)
                            trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                        }
                        if (e.text.isNotEmpty()) {
                            sawDeltaText.set(true)
                            trySend(e.text)
                        }
                    }

                    is OhEvent.Message -> {
                        // Only a message that was not already streamed is appended;
                        // otherwise the answer would appear twice.
                        if (e.role == "assistant" && e.reasoning.isNotEmpty() && !sawDeltaReasoning.get()) {
                            sawDeltaReasoning.set(true)
                            trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                        }
                        if (e.role == "assistant" && e.text.isNotEmpty() && !sawDeltaText.get()) {
                            sawDeltaText.set(true)
                            trySend(e.text)
                        }
                    }

                    is OhEvent.Action -> {
                        // A phone-owned tool runs here; the cloud's own tools are
                        // left alone, because their result is already on the way.
                        val alreadyDone = e.callId.isNotBlank() && e.callId in history
                        // A tool the phone owns runs here. The name arrives
                        // prefixed, so it is stripped back to the app's own name;
                        // the check is against the live registry rather than a
                        // written-out list, so a tool the app can run is never
                        // silently skipped and a name it cannot run is never
                        // awaited forever.
                        //
                        // There is no cap on how many phone tools one turn may
                        // run. A cap was here, and it ended long automations
                        // silently: past it the call was skipped and the turn
                        // simply stopped. The cloud's own runtime has no such
                        // limit, so neither does the phone.
                        val tool = localName(e.name)
                        val runnable = tool !in CLOUD_EXCLUDED && (
                            e.name.contains("__") ||
                                ai.zyraxon.assistant.ai.ToolRegistry.allowed(mode, tool)
                            )
                        if (runnable && !alreadyDone) {
                            val result = runCatching { onToolCall(tool, e.args) }
                                .getOrElse { JSONObject().put("ok", false).put("error", it.message) }
                            trySend(toolNote(tool, result))
                            // The agent must learn what really happened, so the
                            // outcome is sent back as the next thing it sees.
                            pendingTool = true
                            val delivered = withContext(Dispatchers.IO) {
                                runCatching {
                                    client.sendMessage(
                                        apiKey, live.id, toolResult(e.name, result) + screenDigest()
                                    ) { stage ->
                                        trySend(ZyraxonAiEngine.REASONING_PREFIX + "$stage")
                                    }
                                }.isSuccess
                            }
                            if (!delivered) finishTurn()
                        }
                    }

                    is OhEvent.Status -> {
                        // Deliberately *not* recorded as the tail's last status:
                        // this socket replays state on attach, and a replayed
                        // `finished` must not be able to end a turn that never ran.
                        // Only the tail, which reads strictly after the turn's own
                        // start, is allowed to say the turn is over.
                        if (e.value == "running" || e.value == "working") {
                            sawRunning.set(true)
                        } else if (e.value == "finished" || e.value == "idle") {
                            // A finish that follows a phone tool is the end of the
                            // placeholder run, not of the turn: the result message
                            // has just started the real one.
                            if (!sawRunning.get()) Unit
                            else if (pendingTool) pendingTool = false else finishTurn()
                        }
                    }

                    is OhEvent.Failure -> close(OhApiError(0, e.detail.ifBlank { e.code }))
                    else -> Unit
                }
            }
        }

        // Safety net under the socket. A network that drops the WebSocket without
        // a close frame leaves the screen frozen while the cloud works on, so the
        // REST event log is read on a timer and any event the socket did not
        // deliver is replayed through the very same handling.
        //
        // The read is by *timestamp cursor*, not "the newest N". A fixed window is
        // wrong in a way that only shows up when it matters: once the conversation
        // holds more events than the window, everything produced between two reads
        // is skipped — so a long answer would lose exactly the tokens that took
        // longest to arrive. The cursor cannot skip anything, and it is what lets
        // this run at any length with no ceiling.
        val fallback = launch {
            var cursor = turnStart
            var quiet = 0
            // The cursor is inclusive, so the very first read hands back the
            // event the cursor *is* — the previous turn's last one. It is not
            // this turn's and must not be replayed as though it were.
            var skipCursorEvent = turnStart.isNotBlank()
            while (isActive) {
                delay(2000)
                val batch = client.eventsSince(live, cursor)
                if (batch.isEmpty()) {
                    // Nothing new. Silence on its own does not end a turn — the
                    // cloud can be running a long command with nothing to say — so
                    // the turn ends here only when the last thing it *did* say was
                    // that it had finished. That is the missed-`finished` backstop.
                    quiet++
                    if (quiet >= 4) {
                        val last = lastStatus.get()
                        if ((last == "finished" || last == "idle") && !pendingTool) finishTurn()
                        quiet = 0
                    }
                    continue
                }
                quiet = 0
                // One-shot: only the first read can contain the cursor event.
                val skip = skipCursorEvent
                skipCursorEvent = false
                for ((at, e) in batch) {
                    if (at.isNotBlank()) cursor = at
                    if (skip && at == turnStart) continue
                    // The socket is authoritative and already delivered these;
                    // only an event it did not is worth replaying.
                    if (e.id.isNotBlank() && !delivered.add(e.id)) continue
                    when (e) {
                        is OhEvent.Delta -> {
                            if (e.reasoning.isNotEmpty()) {
                                sawDeltaReasoning.set(true)
                                trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                            }
                            if (e.text.isNotEmpty()) {
                                sawDeltaText.set(true)
                                trySend(e.text)
                            }
                        }
                        is OhEvent.Message -> {
                            if (e.role == "assistant" && e.reasoning.isNotEmpty() && !sawDeltaReasoning.get()) {
                                sawDeltaReasoning.set(true)
                                trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                            }
                            if (e.role == "assistant" && e.text.isNotEmpty() && !sawDeltaText.get()) {
                                sawDeltaText.set(true)
                                trySend(e.text)
                            }
                        }
                        is OhEvent.Status -> {
                            if (e.value == "running" || e.value == "working") {
                                sawRunning.set(true)
                            }
                            // The tail's own last status. It is only read from a
                            // window that starts after this turn began, so it
                            // cannot be the previous turn's.
                            if (e.value.isNotBlank()) lastStatus.set(e.value)
                            if (e.value == "finished" || e.value == "idle") {
                                if (sawRunning.get()) {
                                    if (pendingTool) pendingTool = false else finishTurn()
                                }
                            }
                        }
                        is OhEvent.Failure -> close(OhApiError(0, e.detail.ifBlank { e.code }))
                        else -> Unit
                    }
                }
            }
        }
        // The first message must not be sent into a stream that is not listening
        // yet: the cloud answers the instant the message lands, and every token it
        // produces before the socket is up is never delivered. The socket signals
        // its acceptance, and this waits for it — but only briefly, so a socket
        // that cannot be opened at all still lets the turn run and be read by the
        // REST fallback instead of failing outright.
        val seed = if (coldStart) historySeed() else ""
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000) { socketOpen.await() }
            runCatching { client.neverConfirm(live) }
            client.sendMessage(apiKey, live.id, seed + prompt + screenDigest()) { stage ->
                onProgress(stage)
                trySend(ZyraxonAiEngine.REASONING_PREFIX + "$stage")
            }
        }
        awaitClose {
            watcher.cancel()
            fallback.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Builds the conversation and brings its sandbox to RUNNING **before** the
     * user says anything, and binds it to [chatId].
     *
     * Provisioning is the slow part of a cloud turn — measured at tens of
     * seconds. Doing it here, in the background while the app is open, means the
     * first message finds a live machine and streams immediately instead of
     * waiting on a cold start. Idempotent: a chat that already has a bound
     * conversation is only woken, never duplicated.
     */
    suspend fun prewarm(
        apiKey: String,
        chatId: String,
        suffix: String,
        clientTools: JSONArray? = null,
        onStage: (String) -> Unit = {}
    ): OhConversation? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !online()) return@withContext null
        val bound = store.conversationFor(chatId)
        if (bound != null) {
            return@withContext runCatching {
                client.ensureRunning(apiKey, bound, onStage)
            }.getOrNull()
        }
        runCatching {
            val warm = client.findWarmSandbox(apiKey) ?: store.takeWarmSandbox()
            val started = client.startConversation(
                key = apiKey,
                title = "ZYRAXON",
                suffix = suffix,
                reuseSandboxId = warm,
                clientTools = clientTools,
                onProgress = onStage
            )
            store.bind(chatId, started.id, started.sandboxId)
            store.lastConversationId = started.id
            started
        }.getOrNull()
    }

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
        val summary = result.safe("summary")
            .ifBlank { result.safe("error") }
            .ifBlank { result.toString() }
        return "\n\n`$name` ${if (ok) "ok" else "failed"} \u2014 ${summary.take(200)}\n\n"
    }

    /**
     * The tool's real outcome, phrased so the agent treats it as the result it is
     * waiting for rather than as a new request from the user. The cloud already
     * produced its own placeholder observation, so this message is what corrects
     * the record with what actually happened on the phone.
     */
    private fun toolResult(name: String, result: JSONObject): String {
        val ok = result.optBoolean("ok", false)
        val summary = result.safe("summary").ifBlank { result.toString() }
        val data = result.opt("data")
        return buildString {
            append("[phone tool result] ").append(name).append(" ")
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
         * Marks a tool as the phone's. Every phone tool is published under this
         * prefix and no other name is ever sent.
         *
         * The prefix is not cosmetic. The cloud agent already has its own tools
         * called `read_file`, `write_file`, `git`, `terminal_run` and more, and
         * handing it a second tool with the same name makes its own server reject
         * the whole conversation with a 422 before it starts — measured live: the
         * four colliding names fail, four made-up names do not. `phone_` cannot
         * collide with anything, because nothing else uses it.
         */
        const val PHONE_PREFIX = "phone_"

        /** The name the cloud sees for a phone tool, and the reverse of it. */
        fun publishedName(tool: String): String =
            if (tool.contains("__")) tool else PHONE_PREFIX + tool

        /** The phone's real tool name behind a name the cloud sent back. */
        fun localName(published: String): String =
            if (published.contains("__")) published else published.removePrefix(PHONE_PREFIX)

        /**
         * The phone's own tools, as the cloud should see them: every tool the app
         * can really run in [mode], minus the few the cloud must not be handed,
         * each renamed with [PHONE_PREFIX]. MCP tools are appended by the caller,
         * which knows the live connections.
         */
        fun phoneTools(mode: AgentMode): JSONArray {
            val out = JSONArray()
            val schemas = ai.zyraxon.assistant.ai.ToolRegistry.schemas(mode)
            for (i in 0 until schemas.length()) {
                val fn = schemas.optJSONObject(i)?.optJSONObject("function") ?: continue
                val name = fn.safe("name")
                if (name.isBlank() || name in CLOUD_EXCLUDED) continue
                out.put(
                    JSONObject()
                        .put("name", publishedName(name))
                        .put("description", fn.safe("description"))
                        .put("parameters", fn.optJSONObject("parameters")
                            ?: JSONObject().put("type", "object"))
                )
            }
            return out
        }
    }
}
