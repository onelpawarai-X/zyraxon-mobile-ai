// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.cloud

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
class CloudBridge(
    private val client: CloudClient,
    private val store: CloudStore,
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
        existing: (suspend ((String) -> Unit) -> CloudConversation?)? = null,
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
                store.unbind(chatId)
                trySend(ZyraxonAiEngine.REASONING_PREFIX +
                    "আগের স্যান্ডবক্স আর নেই — নতুন মেশিনে কথোপকথন আবার গড়া হচ্ছে…")
            }
        }

        if (conv == null) {
            coldStart = true
            // Held across "no binding yet, so start one" for the same reason as in
            // [prewarm]: the prewarm that runs when a chat opens, the send that
            // follows it, and the account resync can all reach this branch before
            // any of them has written a binding, and each would otherwise start its
            // own conversation. The first through the lock starts one; the rest find
            // that binding and adopt it.
            conv = chatStartLock(chatId).withLock {
                val bound = store.conversationFor(chatId)
                if (bound != null) {
                    withContext(Dispatchers.IO) {
                        runCatching { client.ensureRunning(apiKey, bound) }.getOrNull()
                    }
                } else {
                    // A sandbox that is up right now is reused, which is what makes
                    // the second chat of a day instant. Only a live one is offered:
                    // an id remembered from an earlier chat may since have been
                    // reclaimed, and handing a dead one over spends a whole failed
                    // start. A cold start is ~28s and always succeeds; a bad reuse is
                    // slower than that and can leave a stray sandbox behind.
                    val warm = withContext(Dispatchers.IO) {
                        client.findWarmSandbox(apiKey)
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
                    store.bind(chatId, started.id, started.sandboxId)
                    store.lastConversationId = started.id
                    started
                }
            } ?: throw CloudApiError(0, "কনভার্সেশন তৈরি করা যায়নি")
        } else {
            // Remembered here too, not only on a cold start: the heartbeat and the
            // refresh button both need to know which conversation to keep awake.
            conv?.let { store.lastConversationId = it.id }
        }

        val live = conv!!
        // Everything the conversation already recorded, so a replayed action is
        // recognised as history and not run a second time.
        val history = client.seenActionIds(apiKey, live)
        // Where this turn begins. Read before anything is sent, so the REST tail
        // can tell this turn's events from the previous turn's: without it a
        // stored `finished` from the last turn would be read as *this* turn
        // ending, and a turn that never ran would be declared over.
        val turnStart = client.latestTimestamp(live)
        // The chat is now working, and it says so on disk before the first byte is
        // sent. The cloud does not stop when the phone does, so this flag is the
        // only thing a relaunch has to go on: without it a chat whose turn was
        // still running on the other side opened looking idle, and the user could
        // neither see it nor wait for it.
        store.setRunning(chatId, true)
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
            // The cloud is done with this chat, and that must outlive the process
            // too: the flag is what tells a relaunch whether to keep showing the
            // chat as working. Clearing it only in memory meant a chat reopened
            // after a finished turn still claimed to be running.
            store.setRunning(chatId, false)
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
                    is CloudEvent.Delta -> {
                        if (e.reasoning.isNotEmpty()) {
                            sawDeltaReasoning.set(true)
                            trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                        }
                        if (e.text.isNotEmpty()) {
                            sawDeltaText.set(true)
                            trySend(e.text)
                        }
                    }

                    is CloudEvent.Message -> {
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
                        // A whole message ends its phase, so the next phase shows too.
                        if (e.role == "assistant") {
                            sawDeltaReasoning.set(false)
                            sawDeltaText.set(false)
                        }
                    }

                    is CloudEvent.Action -> {
                        if (e.thinking.isNotBlank() && !sawDeltaReasoning.get()) {
                            trySend(ZyraxonAiEngine.REASONING_PREFIX + e.thinking)
                        }
                        // A tool call ends the phase: the next one's thinking and text
                        // are new, never mistaken for a repeat of this one.
                        sawDeltaReasoning.set(false)
                        sawDeltaText.set(false)
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

                    is CloudEvent.Status -> {
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

                    is CloudEvent.Failure -> close(CloudApiError(0, e.detail.ifBlank { e.code }))
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
            // Where the read resumes from. The turn boundary is the floor — an
            // event older than this turn belongs to a turn already on screen — but
            // a cursor written down by an earlier run can be *newer*, and then it
            // is the one to use: it marks the last event this chat ever handled, so
            // taking the floor instead would re-read a whole turn's worth of events
            // and show the user their own answer twice.
            var cursor = laterOf(store.cursorFor(chatId).orEmpty(), turnStart)
            store.setCursor(chatId, cursor)
            var quiet = 0
            // The cursor is inclusive, so the very first read hands back the
            // event the cursor *is* — the previous turn's last one. It is not
            // this turn's and must not be replayed as though it were.
            var skipCursorEvent = cursor.isNotBlank()
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
                    if (at.isNotBlank()) {
                        cursor = at
                        // Written down as the read advances, not at the end of the
                        // turn. A turn can be interrupted, killed or simply outlive
                        // the process, and the cursor is what a relaunch resumes
                        // from; saving it only on a clean finish would lose the
                        // whole turn every time the phone went away first.
                        store.setCursor(chatId, at)
                    }
                    if (skip && at == turnStart) continue
                    // The socket is authoritative and already delivered these;
                    // only an event it did not is worth replaying.
                    if (e.id.isNotBlank() && !delivered.add(e.id)) continue
                    when (e) {
                        is CloudEvent.Delta -> {
                            if (e.reasoning.isNotEmpty()) {
                                sawDeltaReasoning.set(true)
                                trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                            }
                            if (e.text.isNotEmpty()) {
                                sawDeltaText.set(true)
                                trySend(e.text)
                            }
                        }
                        is CloudEvent.Message -> {
                            if (e.role == "assistant" && e.reasoning.isNotEmpty() && !sawDeltaReasoning.get()) {
                                sawDeltaReasoning.set(true)
                                trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                            }
                            if (e.role == "assistant" && e.text.isNotEmpty() && !sawDeltaText.get()) {
                                sawDeltaText.set(true)
                                trySend(e.text)
                            }
                            if (e.role == "assistant") {
                                sawDeltaReasoning.set(false)
                                sawDeltaText.set(false)
                            }
                        }
                        is CloudEvent.Status -> {
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
                        is CloudEvent.Failure -> close(CloudApiError(0, e.detail.ifBlank { e.code }))
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
            runCatching { client.neverConfirm(apiKey, live) }
            client.sendMessage(apiKey, live.id, seed + prompt + screenDigest()) { stage ->
                onProgress(stage)
                trySend(ZyraxonAiEngine.REASONING_PREFIX + "$stage")
            }
        }
        // Words typed while the turn runs go to the cloud conversation at once, not
        // when the whole job is over. The agent reads them at its next step, so a
        // correction or a new request changes what it does right now.
        val injector = launch {
            while (isActive) {
                delay(350)
                val late = drainInjections()
                if (late.isEmpty()) continue
                val text = late.joinToString("\n\n")
                var sent = false
                var tries = 0
                while (!sent && tries < 4 && isActive) {
                    tries++
                    sent = withContext(Dispatchers.IO) {
                        runCatching {
                            client.sendMessage(apiKey, live.id, text) { stage ->
                                trySend(ZyraxonAiEngine.REASONING_PREFIX + "$stage")
                            }
                        }.isSuccess
                    }
                    if (!sent) delay(1000L * tries)
                }
            }
        }
        awaitClose {
            watcher.cancel()
            fallback.cancel()
            injector.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * What a chat missed while the phone was away.
     *
     * @param events the cloud's events after the chat's cursor, oldest first.
     * @param cursor where the read finished — the chat's new cursor.
     * @param running true while the cloud still reports the turn going.
     */
    data class CatchUp(
        val events: List<CloudEvent>,
        val cursor: String,
        val running: Boolean
    ) {
        val isEmpty: Boolean get() = events.isEmpty()
    }

    /**
     * Reads back what a chat's cloud conversation produced while the phone was gone.
     *
     * A turn does not stop because the phone did: the sandbox keeps working on the
     * other side. The read cursor used to be a local `var` inside a turn, so closing
     * the app — or losing the network with the app still in the background — threw it
     * away, and every event the cloud went on producing was never asked for again.
     * The cursor now lives on disk, and this is the read that resumes from it.
     *
     * Also reports whether the turn is still going, which is what lets a relaunched
     * chat keep showing it as working instead of opening as though nothing had
     * happened. The status is taken from the last lifecycle event in the read, and
     * falls back to what the conversation itself reports when the read holds none.
     *
     * Returns null when there is nothing to resume — no key, no network, no binding,
     * or a chat that has never been read on this phone — so a caller treats it as
     * "nothing to do" rather than an error.
     */
    suspend fun catchUp(apiKey: String, chatId: String): CatchUp? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !online()) return@withContext null
        val convId = store.conversationFor(chatId) ?: return@withContext null
        val from = store.cursorFor(chatId).orEmpty()
        // A chat with no cursor has never been read here. Its transcript is pulled
        // whole by the history import, and reading the entire log here as well would
        // show every message twice.
        if (from.isBlank()) return@withContext null
        val conv = runCatching { client.ensureRunning(apiKey, convId) }.getOrNull()
            ?: return@withContext null
        val batch = client.eventsSince(conv, from)
        var cursor = from
        var lastStatus = ""
        val out = ArrayList<CloudEvent>(batch.size)
        // The read is inclusive, so its first page opens with the very event the
        // cursor is — one already shown. Skipped by timestamp, the same way the live
        // tail does it: an event sharing the boundary's timestamp is a repeat far
        // more often than it is news, and a repeat shown twice is worse than the
        // rare token it might cost.
        for ((at, e) in batch) {
            if (at.isNotBlank()) cursor = at
            if (at == from) continue
            if (e is CloudEvent.Status && e.value.isNotBlank()) lastStatus = e.value
            out.add(e)
        }
        if (cursor != from) store.setCursor(chatId, cursor)
        val running = when {
            lastStatus.isNotBlank() -> lastStatus == "running" || lastStatus == "working"
            else -> conv.executionStatus == "running" || conv.executionStatus == "working"
        }
        CatchUp(out, cursor, running)
    }

    /**
     * Follows a cloud turn that is already running, as a token stream.
     *
     * This is [runTurn] without the sending. A turn the user started does not end
     * when the phone goes away — the sandbox carries on — so when the app comes
     * back the only thing missing is a reader. This is that reader: it attaches the
     * same event socket and runs the same REST tail, so the rest of a long job
     * streams onto the phone exactly as it would have had the app never closed.
     *
     * Nothing is sent and nothing is re-run: a phone tool the cloud asked for
     * while the phone was away is not replayed, because running a user's tool
     * twice is far worse than a stale line in the transcript. The turn's own
     * cursor is the floor, so only genuinely new events are shown.
     *
     * The flow ends when the cloud says the turn is finished, or when the log has
     * been silent long enough that the turn must be over.
     */
    fun attachTurn(apiKey: String, chatId: String): Flow<String> = channelFlow {
        if (apiKey.isBlank() || !online()) return@channelFlow
        val convId = store.conversationFor(chatId) ?: return@channelFlow
        val conv = withContext(Dispatchers.IO) {
            runCatching { client.ensureRunning(apiKey, convId) }.getOrNull()
        } ?: return@channelFlow
        if (conv.sandboxStatus != "RUNNING") return@channelFlow

        val delivered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val sawRunning = java.util.concurrent.atomic.AtomicBoolean(false)
        val lastStatus = java.util.concurrent.atomic.AtomicReference("")
        var ended = false

        fun endIfDone() {
            if (ended) return
            ended = true
            store.setRunning(chatId, false)
            close()
        }

        // A turn with no socket yet cannot be assumed running: it is only running
        // once the log says so, or once the conversation itself reports it.
        sawRunning.set(conv.executionStatus == "running" || conv.executionStatus == "working")
        if (conv.executionStatus.isNotBlank()) lastStatus.set(conv.executionStatus)

        val watcher = launch {
            client.events(apiKey, conv, { !ended }, reconnect = {
                client.ensureRunning(apiKey, conv.id) { }
            }).collect { e ->
                if (e.id.isNotBlank() && !delivered.add(e.id)) return@collect
                when (e) {
                    is CloudEvent.Delta -> {
                        if (e.reasoning.isNotEmpty()) trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                        if (e.text.isNotEmpty()) trySend(e.text)
                    }
                    is CloudEvent.Message -> {
                        if (e.role == "assistant") {
                            if (e.reasoning.isNotEmpty()) trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                            if (e.text.isNotEmpty()) trySend(e.text)
                        }
                    }
                    is CloudEvent.Status -> {
                        if (e.value == "running" || e.value == "working") sawRunning.set(true)
                        if (e.value.isNotBlank()) lastStatus.set(e.value)
                        if ((e.value == "finished" || e.value == "idle") && sawRunning.get()) endIfDone()
                    }
                    is CloudEvent.Failure -> endIfDone()
                    else -> Unit
                }
            }
        }

        // The REST tail under the socket, for the same reason [runTurn] has one: a
        // network that drops the socket without a close frame would otherwise leave
        // the screen frozen while the cloud worked on.
        val fallback = launch {
            var cursor = store.cursorFor(chatId).orEmpty()
            var quiet = 0
            // The read is inclusive, so its first page opens with the very event the
            // cursor is. Kept as its own fixed value: `cursor` moves as the page is
            // walked, and comparing against the moved value would skip every event.
            val from = cursor
            var skipCursorEvent = from.isNotBlank()
            while (!ended) {
                delay(2000)
                val batch = withContext(Dispatchers.IO) { client.eventsSince(conv, cursor) }
                if (batch.isEmpty()) {
                    quiet++
                    // Silence does not end a turn by itself — a long command says
                    // nothing for minutes — so it ends only when the last word was
                    // that it had finished.
                    if (quiet >= 4) {
                        val last = lastStatus.get()
                        if ((last == "finished" || last == "idle") && sawRunning.get()) endIfDone()
                        quiet = 0
                    }
                    continue
                }
                quiet = 0
                val skip = skipCursorEvent
                skipCursorEvent = false
                for ((at, e) in batch) {
                    if (at.isNotBlank()) {
                        cursor = at
                        store.setCursor(chatId, at)
                    }
                    if (skip && at == from) continue
                    if (e.id.isNotBlank() && !delivered.add(e.id)) continue
                    when (e) {
                        is CloudEvent.Delta -> {
                            if (e.reasoning.isNotEmpty()) trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                            if (e.text.isNotEmpty()) trySend(e.text)
                        }
                        is CloudEvent.Message -> {
                            if (e.role == "assistant") {
                                if (e.reasoning.isNotEmpty()) trySend(ZyraxonAiEngine.REASONING_PREFIX + e.reasoning)
                                if (e.text.isNotEmpty()) trySend(e.text)
                            }
                        }
                        is CloudEvent.Status -> {
                            if (e.value == "running" || e.value == "working") sawRunning.set(true)
                            if (e.value.isNotBlank()) lastStatus.set(e.value)
                            if ((e.value == "finished" || e.value == "idle") && sawRunning.get()) endIfDone()
                        }
                        is CloudEvent.Failure -> endIfDone()
                        else -> Unit
                    }
                }
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
    ): CloudConversation? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !online()) return@withContext null
        // A chat may be prewarmed, sent into and resynced all within a second of
        // opening, and each of those paths used to start its own conversation
        // when it found no binding yet — three sandboxes and three conversations
        // for one message. The lock makes the first one win and the rest wait for
        // its binding rather than race it.
        chatStartLock(chatId).withLock {
            val bound = store.conversationFor(chatId)
            if (bound != null) {
                return@withLock runCatching {
                    client.ensureRunning(apiKey, bound, onStage)
                }.getOrNull()
            }
            runCatching {
                // Only a sandbox the account reports as up is reused; a remembered
                // id is not offered, because a reclaimed one costs a failed start.
                val warm = client.findWarmSandbox(apiKey)
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
    }

    /**
     * One lock per chat, held across "look for a binding, and start one if there
     * is none". Every path that can create a conversation takes it, so a chat can
     * only ever have one start in flight: the others see the binding the winner
     * wrote and wake that conversation instead of building a second.
     *
     * Keyed by chat id and never removed, which is deliberate — the map holds one
     * small object per chat the user has opened, and dropping a lock while a start
     * is still in flight would let the next caller slip past it.
     */
    private val chatStartLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private fun chatStartLock(chatId: String): Mutex =
        chatStartLocks.computeIfAbsent(chatId) { Mutex() }

    /**
     * Wakes the conversation behind a chat, for callers outside a turn (the app
     * coming back to the foreground, the overlay waking up). Returns null when
     * there is nothing to wake or the phone is offline.
     */
    suspend fun wake(apiKey: String, chatId: String, onStage: (String) -> Unit = {}): CloudConversation? {
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
    /**
     * Stops the cloud turn behind a chat, leaving the conversation and its
     * history intact.
     *
     * The phone's Stop button only cancels the local collection of the stream;
     * the agent server keeps working — running commands, calling tools, spending
     * the user's time — and the answer arrives into a chat that has moved on.
     * This is what makes Stop actually stop: the running turn is interrupted at
     * the server, which is the only place that can end it.
     *
     * Returns false when there is nothing to interrupt or the phone is offline,
     * so the caller can tell "stopped" from "there was nothing running".
     */
    suspend fun interruptCloud(apiKey: String, chatId: String): Boolean {
        val id = store.conversationFor(chatId) ?: return false
        if (apiKey.isBlank() || !online()) return false
        return runCatching {
            val conv = client.getConversation(apiKey, id)
            client.interrupt(apiKey, conv)
            true
        }.getOrDefault(false)
    }

    /**
     * Ends the cloud side of a chat the user deleted or cleared: the running turn
     * is interrupted, the conversation is deleted, and the binding is dropped, so
     * the chat can never come back showing "still running" from the server.
     */
    suspend fun forget(apiKey: String, chatId: String, deleteRemote: Boolean = true) {
        val id = store.conversationFor(chatId)
        if (id != null && apiKey.isNotBlank() && online()) {
            runCatching {
                val conv = client.getConversation(apiKey, id)
                runCatching { client.interrupt(apiKey, conv) }
                if (deleteRemote) client.deleteConversation(apiKey, id)
            }
        }
        store.unbind(chatId)
    }

    suspend fun remoteTitle(apiKey: String, conversationId: String): String? =
        runCatching { client.getConversation(apiKey, conversationId).title }.getOrNull()

    /**
     * A one-line, human-readable record of a phone tool the agent asked for.
     *
     * It travels in the reasoning envelope, never as answer text. The line is
     * machine bookkeeping — a tool name and a status — and as answer text the
     * speaker read it aloud ("open_app ok — …") and the user saw it as part of
     * the reply. Reasoning is shown on screen and never spoken, which is what
     * this line is: something to read, not something to say.
     */
    private fun toolNote(name: String, result: JSONObject): String {
        val ok = result.optBoolean("ok", false)
        val summary = result.safe("summary")
            .ifBlank { result.safe("error") }
            .ifBlank { result.toString() }
        return ZyraxonAiEngine.REASONING_PREFIX +
            "\n\n`$name` ${if (ok) "ok" else "failed"} \u2014 ${summary.take(200)}\n\n"
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
            append("\nContinue the task with this result. Call this tool again only if the screen shows the step did not take effect.")
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

/**
 * Whichever of two event timestamps is later, treating a blank one as absent.
 *
 * Both come from the same service in the same ISO form, so comparing them as text
 * orders them correctly; parsing would only add a failure mode for a shape the
 * cloud is not obliged to keep. Used to decide where a resume starts: the later of
 * the last cursor this chat ever handled and the boundary of the turn starting now.
 */
internal fun laterOf(a: String, b: String): String = when {
    a.isBlank() -> b
    b.isBlank() -> a
    a >= b -> a
    else -> b
}
