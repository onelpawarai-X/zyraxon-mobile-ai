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

    /**
     * The first piece is released as soon as a couple of words are there, so the
     * voice starts while the model is still writing. This is the number the user
     * hears as "it follows my words".
     */
    private const val FIRST_RELEASE_MIN = 12

    /** The first release must not cut mid-word this early. */
    private const val FIRST_CUT_MIN = 8

    /**
     * Every later piece is allowed to grow to a full clause before it is sent.
     *
     * This is the fix for the voice stopping after a word or two. Each piece is a
     * separate request to the speech endpoint, and that request takes about a
     * second to produce its first byte. Releasing a piece every dozen characters
     * asked for a new one roughly every second of speech — the request rate sat
     * level with the endpoint's own latency, so any jitter left the player with
     * nothing queued and the reply fell silent. Fewer, longer pieces keep several
     * seconds of audio in hand at all times, and the voice runs without a seam.
     */
    private const val LATER_RELEASE_MIN = 80

    /** A later release still cuts on a word boundary, never mid-word. */
    private const val LATER_CUT_MIN = 40

    /** A clause must be at least this long before a comma can release it. */
    private const val CLAUSE_MIN = 24

    /** The comma must not be so early that it releases a two-word fragment. */
    private const val COMMA_MIN = 10

    /**
     * Removes and returns the next piece worth speaking from [buffer], or an
     * empty string when there is nothing ready yet.
     *
     * [first] is true until the reply's opening piece has been released. The first
     * piece is deliberately short so speech begins almost at once; the rest are
     * long so the queue always stays ahead of the player.
     */
    fun take(buffer: StringBuilder, first: Boolean = false): String {
        if (buffer.isEmpty()) return ""
        // A sentence end always releases, first piece or not: it is both the most
        // natural place to pause and the earliest point the text is certainly
        // complete. The *first* end is used, not the last, or the opening sentence
        // would wait behind text that has not been written yet.
        val sentenceEnd = buffer.indexOfFirst { it in "।!?;\n." }
        if (sentenceEnd >= 0) {
            val ready = buffer.substring(0, sentenceEnd + 1)
            buffer.delete(0, sentenceEnd + 1)
            return ready
        }
        if (first) {
            if (buffer.length >= FIRST_RELEASE_MIN) {
                val space = buffer.lastIndexOf(' ')
                if (space >= FIRST_CUT_MIN) {
                    val ready = buffer.substring(0, space + 1)
                    buffer.delete(0, space + 1)
                    return ready
                }
            }
            return ""
        }
        if (buffer.length >= CLAUSE_MIN) {
            val comma = buffer.lastIndexOfAny(charArrayOf(',', '，', '—', ':'))
            if (comma >= COMMA_MIN) {
                val ready = buffer.substring(0, comma + 1)
                buffer.delete(0, comma + 1)
                return ready
            }
        }
        // No punctuation yet and the run is long: release it at the last whole
        // word. Long enough to be worth a request, short enough that a model
        // writing one endless sentence is still heard as it goes.
        if (buffer.length >= LATER_RELEASE_MIN) {
            val space = buffer.lastIndexOf(' ')
            if (space >= LATER_CUT_MIN) {
                val ready = buffer.substring(0, space + 1)
                buffer.delete(0, space + 1)
                return ready
            }
        }
        return ""
    }
}
