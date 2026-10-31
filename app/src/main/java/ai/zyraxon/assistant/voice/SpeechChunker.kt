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
    private const val CLAUSE_MIN = 24

    /** The comma must not be so early that it releases a two-word fragment. */
    private const val COMMA_MIN = 10

    /**
     * How long a run may grow with no punctuation before it is released at a word
     * boundary. A model that writes one long sentence would otherwise stay silent
     * until its full stop, which is the "not real-time" the user reported; this
     * starts the voice a few words in instead. Kept short so the voice follows the
     * words closely, but never so short that a single word is spoken alone.
     */
    private const val WORD_RELEASE_MIN = 24

    /** The word boundary must not sit so early that it releases a fragment. */
    private const val WORD_CUT_MIN = 12

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
        // No punctuation yet and the run is getting long: release it at the last
        // whole word, so the voice keeps pace instead of waiting for a full stop
        // that may be a whole sentence away.
        if (buffer.length >= WORD_RELEASE_MIN) {
            val space = buffer.lastIndexOf(' ')
            if (space >= WORD_CUT_MIN) {
                val ready = buffer.substring(0, space + 1)
                buffer.delete(0, space + 1)
                return ready
            }
        }
        return ""
    }
}
