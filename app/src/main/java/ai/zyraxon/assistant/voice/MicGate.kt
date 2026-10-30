package ai.zyraxon.assistant.voice

/**
 * Decides when the microphone closes and reopens around the assistant's speech.
 *
 * This exists because the naive rule — close the ear while the engine is talking,
 * open it when the engine is idle — is wrong for a *streamed* answer. A streamed
 * answer arrives sentence by sentence with a gap between each one, so the engine
 * is briefly idle after every sentence. Judging from the engine alone made the ear
 * reopen in each gap and close again for the next sentence: off, on, off, on, the
 * churn the user heard dozens of times a minute.
 *
 * The reply, not the engine, is the real unit. [beginReply] opens one, [endReply]
 * closes it, and in between the ear closes on the first word and stays closed
 * until the last word — one close, one open, however many sentences there are.
 *
 * The methods return whether the caller should act now, so this stays pure and
 * testable without a phone, an engine, or a recognizer.
 */
class MicGate {

    /** True from [beginReply] until [endReply] or [reset]. */
    var replyActive: Boolean = false
        private set

    /** True between the first and last utterance of one spoken reply. */
    var burstActive: Boolean = false
        private set

    /** Opens a spoken reply. */
    fun beginReply() {
        replyActive = true
    }

    /**
     * The first word of a reply has been spoken. Returns true the first time only,
     * so the ear closes once even though every sentence reports a start.
     */
    fun onUtteranceStart(): Boolean {
        if (burstActive) return false
        burstActive = true
        return true
    }

    /**
     * A sentence finished. Returns true when the ear should reopen: only when the
     * engine has nothing queued *and* the reply is not still streaming.
     */
    fun onUtteranceDone(engineBusy: Boolean): Boolean {
        if (engineBusy) return false
        if (replyActive) return false
        if (!burstActive) return false
        burstActive = false
        return true
    }

    /**
     * The reply is finished. Returns true when the ear should reopen now, which is
     * the case only if the last word has already played. If the engine is still
     * talking, the reopen is left to the final [onUtteranceDone].
     */
    fun endReply(engineBusy: Boolean): Boolean {
        replyActive = false
        if (engineBusy) return false
        if (!burstActive) return false
        burstActive = false
        return true
    }

    /** The assistant was cut off. The next reply must pause the ear again. */
    fun reset() {
        burstActive = false
        replyActive = false
    }
}
