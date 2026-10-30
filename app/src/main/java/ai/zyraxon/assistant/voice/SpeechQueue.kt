package ai.zyraxon.assistant.voice

/**
 * The speech waiting to be spoken, drained one piece at a time.
 *
 * A piece is only handed to the engine when the previous one has finished,
 * because switching the engine's language or voice stops whatever is currently
 * playing. Handing every run to the engine up front — the old behaviour — cut
 * the answer off after a word or two: the voice switch for run two silenced run
 * one. Keeping the queue here and releasing one piece per `onDone` is what makes
 * a mixed Bangla/English answer play from its first word to its last.
 *
 * Pure state, so the drain order is testable without a phone or a speech engine.
 */
class SpeechQueue {

    private val pending = ArrayDeque<SpeechUtterance>()

    /** The piece the engine is speaking right now, or null when it is idle. */
    var activeId: String? = null
        private set

    val size: Int get() = pending.size

    fun isEmpty(): Boolean = pending.isEmpty() && activeId == null

    /** Appends [utterances] to the back of the queue, preserving their order. */
    fun enqueue(utterances: List<SpeechUtterance>) {
        pending.addAll(utterances)
    }

    /**
     * The next piece to speak, or null when there is nothing to do or a piece is
     * still playing. The caller marks it spoken with [markSpoken].
     */
    fun takeNext(): SpeechUtterance? {
        if (activeId != null) return null
        return pending.removeFirstOrNull()
    }

    /** Records that [id] is now being spoken, so no second piece starts. */
    fun markSpoken(id: String) {
        activeId = id
    }

    /**
     * The piece with [id] finished. Only the piece that is actually active is
     * cleared, so a stale callback from an interrupted utterance cannot free the
     * queue early and let two voices overlap.
     */
    fun onFinished(id: String?) {
        if (id != null && id == activeId) activeId = null
    }

    /** Drops everything, for when the user starts a new reply or cuts speech off. */
    fun clear() {
        pending.clear()
        activeId = null
    }
}
