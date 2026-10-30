package ai.zyraxon.assistant.voice

/**
 * Tells the assistant's own spoken reply apart from the user's words.
 *
 * The time-based echo guard in [NativeVoiceManager] only covers the moment the
 * engine is talking plus a short tail. The speaker's sound can reach the
 * recognizer a beat later, after that window closes, and then the assistant's
 * own sentence comes back as a final result and is posted into the chat as if
 * the user had said it. Comparing the text is the second net: if most of what
 * was heard already appears in the reply the user just heard, it is the reply.
 *
 * A pure function of two strings, so it is testable without a recognizer, an
 * engine or a phone.
 */
object EchoMatcher {

    /**
     * True when [heard] looks like a replay of [reply] rather than a new turn.
     * Short fragments are never treated as echo — a genuine "হ্যাঁ" or "okay"
     * must still reach the assistant — so at least two content words must match.
     */
    fun isOwnSpeech(heard: String, reply: String?): Boolean {
        if (reply.isNullOrBlank()) return false
        val heardWords = words(heard)
        if (heardWords.size < 2) return false
        val replyLower = reply.lowercase()
        val overlap = heardWords.count { replyLower.contains(it) }
        // Half the words matching is enough: a recognizer rarely transcribes the
        // speaker's own voice verbatim, but the content words land.
        return overlap * 2 >= heardWords.size
    }

    private fun words(text: String): List<String> =
        // \p{M} keeps the Bengali combining marks (the vowel signs and hasanta
        // that glue a word together); without it every conjunct splits the word
        // into single letters and nothing ever matches.
        text.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 3 }
}
