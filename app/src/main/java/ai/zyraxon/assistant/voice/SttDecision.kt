package ai.zyraxon.assistant.voice

/**
 * What the assistant should do with one recognized phrase.
 *
 * This is the rule the user asked for, made explicit and testable: the
 * assistant's own spoken reply must never end up in the chat as if the user had
 * said it, and it must never stop the assistant mid-sentence. A short fragment
 * heard while the speaker is talking is the reply coming back through the mic —
 * the recognizer turns the assistant's own voice into text — so acting on every
 * one of those is what produced the off/on/off/on churn and the stray chat
 * bubbles.
 *
 * Pure: it reads only the phrase and two booleans, so it is testable without a
 * recognizer, an engine or a phone.
 */
object SttDecision {

    /** One-word phrases that genuinely mean "stop" and may cut the assistant off. */
    val STOP_COMMANDS = setOf(
        "থামো", "থাম", "শোনো", "শোন", "স্টপ", "বন্ধ",
        "stop", "wait", "shut", "hold", "hey"
    )

    /** What the caller should do with the phrase. */
    enum class Action {
        /** Drop it — it is the assistant's own voice, or too little to act on. */
        IGNORE,

        /** Show it in the box and stop the assistant; do not send it yet. */
        INTERRUPT,

        /** Show it in the box, and send it when auto-send is on. */
        SUBMIT
    }

    /**
     * Decides what to do with [text].
     *
     * @param isFinal true for a final result, false for a partial.
     * @param speakerActive true while the assistant is speaking (or its echo tail
     *   is still ringing), which is when the mic mostly hears the assistant.
     * @param repeatsReply true when [text] already appears in the last reply.
     */
    fun decide(
        text: String,
        isFinal: Boolean,
        speakerActive: Boolean,
        repeatsReply: Boolean
    ): Action {
        if (text.isBlank()) return Action.IGNORE

        // The reply coming back through the mic: never shown, never sent, and —
        // the part that was wrong — never allowed to cut the answer off.
        if (repeatsReply) return Action.IGNORE

        // While the assistant is speaking, its own voice is the loudest thing in
        // the room. A *final* result then is the assistant's own voice far more
        // often than it is the user, so it is never auto-sent. A partial still
        // gets through, so a real interruption can still land mid-answer.
        if (speakerActive && isFinal) return Action.IGNORE

        if (!isFinal) {
            // A partial only ever interrupts. While the assistant is speaking a
            // short partial is almost always a fragment of the reply, so it must
            // carry enough words to be a real interruption before it stops the
            // voice — otherwise every stray word tears the answer down.
            if (speakerActive && !isRealInterruption(text)) return Action.IGNORE
            return Action.INTERRUPT
        }

        return Action.SUBMIT
    }

    /**
     * True when a partial heard while the assistant is speaking is long enough to
     * be the user cutting in rather than a fragment of the reply coming back.
     * Two content words is the bar: "থামো" or "wait stop" gets through, but the
     * one-word slips the recognizer makes out of the speaker's own voice do not.
     */
    fun isRealInterruption(text: String): Boolean {
        val words = text.trim()
            .lowercase()
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.length >= 2 }
        if (words.size >= 2) return true
        return words.any { it in STOP_COMMANDS }
    }
}
