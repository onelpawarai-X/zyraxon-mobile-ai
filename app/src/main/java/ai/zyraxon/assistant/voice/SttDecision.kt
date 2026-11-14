// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.voice

/**
 * What the assistant should do with one recognized phrase.
 *
 * The rule the user asked for, made explicit and testable: the assistant's own
 * spoken reply must never end up in the chat as if the user had said it — and the
 * user must still be able to talk while it is speaking. Those two are separated
 * by *when* a phrase arrives. The assistant's own voice comes back as a **final**
 * result (a whole sentence the recognizer closed), so finals during speech are
 * dropped. A person cutting in arrives as a **partial** and keeps growing as they
 * talk, so a partial that is long enough — or a stop command — is a real
 * interruption: the assistant stops, and the phrase is shown. Once it is quiet the
 * user's final arrives with the speaker off and is sent normally.
 *
 * Pure: it reads only the phrase and two booleans, so it is testable without a
 * recognizer, an engine or a phone.
 */
object SttDecision {

    /**
     * How long a partial must hold still before it is sent without waiting for
     * the recognizer's silence window. Short enough to feel instant, long enough
     * that a breath in the middle of a sentence is not mistaken for the end.
     */
    const val SETTLE_MS = 900L

    /** A settled fragment shorter than this is too little to be a whole turn. */
    const val SETTLE_MIN_CHARS = 8

    /**
     * How long after a settled partial its own final still counts as the same
     * turn. The final follows the partial by well under a second in practice; the
     * window only has to cover the recognizer's own silence timeout, and staying
     * short is what keeps a genuinely repeated phrase sendable.
     */
    const val DUPLICATE_WINDOW_MS = 6_000L

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
     * @param repeatsReply true when [text] mostly appears in the last reply.
     * @param echoTail true when the speaker has already stopped and only the short
     *   guard tail after the last word is still running. This is the window right
     *   after the assistant finishes, which is exactly when a person answers.
     */
    fun decide(
        text: String,
        isFinal: Boolean,
        speakerActive: Boolean,
        repeatsReply: Boolean,
        echoTail: Boolean = false
    ): Action {
        if (text.isBlank()) return Action.IGNORE

        // The reply coming back through the mic: never shown, never sent, and —
        // the part that was wrong — never allowed to cut the answer off.
        if (repeatsReply) return Action.IGNORE

        if (!speakerActive) {
            // The ear is hearing the room. A final phrase is the user's and is
            // sent; a partial only interrupts, never sent on its own.
            return if (isFinal) Action.SUBMIT else Action.INTERRUPT
        }

        // While the assistant is talking a *final* result is its own sentence
        // coming back through the mic — the user talking over it only ever
        // arrives as a partial — so it is dropped: never shown, never sent,
        // never allowed to cut the answer off.
        //
        // The echo tail is the exception, and it is the bug this guards. Once the
        // last word has played the speaker is silent, so a final arriving now is
        // the user answering — and dropping it is why the mic seemed deaf for the
        // second or two after every reply. A late-arriving piece of the
        // assistant's own sentence is still caught above, by the text match.
        if (isFinal) return if (echoTail) Action.SUBMIT else Action.IGNORE

        // A partial long enough to be a real sentence — or a stop command — is the
        // user cutting in. The assistant stops so the user can finish, and the
        // phrase is shown in the box; the final result then arrives once the
        // assistant is quiet and is sent normally. This is the interruption the
        // user asked for: the microphone stays open the whole time it talks.
        return if (isRealInterruption(text)) Action.INTERRUPT else Action.IGNORE
    }

    /**
     * True when a partial has stopped changing long enough to be treated as the
     * end of the turn, so the reply can start without waiting for the recognizer
     * to time the silence out itself.
     *
     * This is the zero-lag path. The engine only closes a phrase after its own
     * silence window runs out, and that window is a fixed slice of nothing on
     * every single turn. A phrase that has held perfectly still for [windowMs] is
     * one the user has finished saying; sending it then removes most of that
     * dead air. The recognizer's own final still arrives afterwards and carries
     * the same words, which the caller drops as a duplicate.
     */
    fun isSettled(unchangedForMs: Long, text: String, windowMs: Long = SETTLE_MS): Boolean {
        if (text.trim().length < SETTLE_MIN_CHARS) return false
        return unchangedForMs >= windowMs
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

    /**
     * True when a recognized phrase is the same turn as words already sent from a
     * settled partial, so sending it again would put one sentence in the chat
     * twice.
     *
     * This is the duplicate the zero-lag path creates: a partial that holds still
     * is sent without waiting for the engine's silence window, and the engine's
     * own final then arrives carrying the same words. The two are not always
     * byte-identical — an engine trims, or collapses a double space, between
     * them — so the comparison folds case and whitespace.
     *
     * [elapsedMs] bounds it. A phrase repeated on purpose later in the
     * conversation is a new turn, and only the final that follows its own partial
     * by less than [DUPLICATE_WINDOW_MS] is treated as the same one.
     */
    fun isSameTurn(
        text: String,
        previous: String,
        elapsedMs: Long,
        windowMs: Long = DUPLICATE_WINDOW_MS
    ): Boolean {
        if (previous.isBlank()) return false
        if (elapsedMs > windowMs) return false
        return normalize(text) == normalize(previous)
    }

    private fun normalize(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").lowercase()
}
