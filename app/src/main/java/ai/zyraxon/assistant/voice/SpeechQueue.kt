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
 * Every field is guarded by [lock]: text is appended from the thread that is
 * writing the answer while the engine's own callback drains the queue, and an
 * unguarded deque mutated from both at once corrupts its links — heard as the
 * voice stopping dead mid-answer. The drain order stays testable without a phone.
 */
class SpeechQueue {

    private val lock = Any()
    private val pending = ArrayDeque<SpeechUtterance>()

    /** The piece the engine is speaking right now, or null when it is idle. */
    private var _activeId: String? = null
    val activeId: String? get() = synchronized(lock) { _activeId }

    val size: Int get() = synchronized(lock) { pending.size }

    fun isEmpty(): Boolean = synchronized(lock) { pending.isEmpty() && _activeId == null }

    /** Appends [utterances] to the back of the queue, preserving their order. */
    fun enqueue(utterances: List<SpeechUtterance>) = synchronized(lock) { pending.addAll(utterances) }

    /**
     * The next piece to speak, or null when there is nothing to do or a piece is
     * still playing. The caller marks it spoken with [markSpoken].
     */
    fun takeNext(): SpeechUtterance? = synchronized(lock) {
        if (_activeId != null) return null
        pending.removeFirstOrNull()
    }

    /** Records that [id] is now being spoken, so no second piece starts. */
    fun markSpoken(id: String) = synchronized(lock) { _activeId = id }

    /**
     * True while [id] is the piece the engine is speaking. The speech watchdog
     * uses this to tell a stalled engine from one that finished normally.
     */
    fun isAwaiting(id: String): Boolean = synchronized(lock) { _activeId == id }

    /**
     * The piece with [id] finished. Only the piece that is actually active is
     * cleared, so a stale callback from an interrupted utterance cannot free the
     * queue early and let two voices overlap.
     */
    fun onFinished(id: String?) = synchronized(lock) {
        if (id != null && id == _activeId) _activeId = null
    }

    /** Drops everything, for when the user starts a new reply or cuts speech off. */
    fun clear() = synchronized(lock) {
        pending.clear()
        _activeId = null
    }
}
