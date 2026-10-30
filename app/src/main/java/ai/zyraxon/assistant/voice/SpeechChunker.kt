package ai.zyraxon.assistant.voice

/**
 * Decides when a growing answer has enough text to start speaking.
 *
 * The answer streams in token by token. Waiting for a full stop means a long
 * clause stays silent, so a sentence end releases the text, and a comma inside a
 * long run releases it early too. The user asked for speech that follows the
 * words almost as fast as they are written, which is what this makes possible.
 */
object SpeechChunker {

    /** A clause must be at least this long before a comma can release it. */
    private const val CLAUSE_MIN = 60

    /** The comma must not be so early that it releases a two-word fragment. */
    private const val COMMA_MIN = 20

    /**
     * Removes and returns the next piece worth speaking from [buffer], or an
     * empty string when there is nothing ready yet.
     */
    fun take(buffer: StringBuilder): String {
        if (buffer.isEmpty()) return ""
        // The *first* sentence end, not the last: releasing up to the last one
        // would hold the first sentence back behind text that has not been written
        // yet, which is exactly the delay the user complained about.
        val sentenceEnd = buffer.indexOfFirst { it in "।!?;\n." }
        if (sentenceEnd >= 0) {
            val ready = buffer.substring(0, sentenceEnd + 1)
            buffer.delete(0, sentenceEnd + 1)
            return ready
        }
        if (buffer.length >= CLAUSE_MIN) {
            val comma = buffer.lastIndexOfAny(charArrayOf(',', '，', '—', ':'))
            if (comma >= COMMA_MIN) {
                val ready = buffer.substring(0, comma + 1)
                buffer.delete(0, comma + 1)
                return ready
            }
        }
        return ""
    }
}
