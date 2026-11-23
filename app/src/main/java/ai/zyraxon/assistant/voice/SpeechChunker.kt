// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.voice

/**
 * Decides when streaming text has formed a natural, complete phrase worth speaking.
 *
 * Two jobs, with opposite goals, and the split between them is the whole point.
 *
 * The first piece is cut early — a clause, not a sentence — so the voice starts
 * within a moment of the first words arriving. Waiting for a full sentence here
 * would leave a second of silence after every question.
 *
 * Every piece after that is cut at a sentence end and nowhere else. The old
 * behaviour kept cutting at commas and at forty characters, which produced a
 * string of short fragments: the answer was spoken, but it sounded stitched
 * together, one clipped phrase at a time, with a pause where the joins were.
 * Letting the buffer grow to a whole sentence costs a little latency on each
 * piece and buys speech that sounds like one person talking. The thresholds
 * below are safety valves for text with no punctuation at all, not the normal
 * path.
 */
object SpeechChunker {

    private const val FIRST_RELEASE_MIN = 22
    private const val FIRST_CUT_MIN = 16

    /** A clause this long is a safe pause once the voice is already running. */
    private const val LATER_CLAUSE_MIN = 180

    /** Past this, cut at a word boundary even mid-sentence, or a run-on never speaks. */
    private const val LATER_FORCE_MIN = 420

    private const val CLAUSE_MIN = 18
    private const val COMMA_MIN = 14

    fun take(buffer: StringBuilder, first: Boolean = false): String {
        if (buffer.isEmpty()) return ""

        // Skip leading unclosed tool indicators or code markers
        val raw = buffer.toString()
        if (raw.startsWith("⚙") || raw.startsWith("```") || raw.startsWith("<tool_call>")) {
            val endBlock = raw.indexOf('\n')
            if (endBlock >= 0) {
                buffer.delete(0, endBlock + 1)
                return take(buffer, first)
            }
            return "" // Wait for the line/block to finish
        }

        // 1. Sentence end is always the best cut point, and after the first piece
        //    it is the only one: a whole sentence is what sounds unbroken.
        val sentenceEnd = sentenceEnd(buffer)
        if (sentenceEnd >= 0) {
            val ready = buffer.substring(0, sentenceEnd + 1)
            buffer.delete(0, sentenceEnd + 1)
            return ready
        }

        // 2. First chunk: wait for a natural clause or coherent phrase
        if (first) {
            if (buffer.length >= FIRST_RELEASE_MIN) {
                var comma = buffer.lastIndexOfAny(charArrayOf(',', '，', '—', ':'))
                while (comma >= COMMA_MIN && insideLink(buffer, comma)) {
                    comma = buffer.lastIndexOfAny(charArrayOf(',', '，', '—', ':'), comma - 1)
                }
                if (comma >= COMMA_MIN) {
                    val ready = buffer.substring(0, comma + 1)
                    buffer.delete(0, comma + 1)
                    return ready
                }

                val space = buffer.lastIndexOf(' ')
                if (space >= FIRST_CUT_MIN) {
                    val ready = buffer.substring(0, space + 1)
                    buffer.delete(0, space + 1)
                    return ready
                }
            }
            return ""
        }

        // 3. A clause, but only a genuinely long one. This is the exception for
        //    text that runs on without a full stop, not the everyday cut.
        if (buffer.length >= LATER_CLAUSE_MIN) {
            var comma = buffer.lastIndexOfAny(charArrayOf(',', '，', '—', ':'))
            while (comma >= COMMA_MIN && insideLink(buffer, comma)) {
                comma = buffer.lastIndexOfAny(charArrayOf(',', '，', '—', ':'), comma - 1)
            }
            if (comma >= COMMA_MIN) {
                val ready = buffer.substring(0, comma + 1)
                buffer.delete(0, comma + 1)
                return ready
            }
        }

        // 4. Last resort: a very long run with no punctuation at all. Cut at a
        //    word boundary so it still speaks, but only once it is too long to
        //    hold any further.
        if (buffer.length >= LATER_FORCE_MIN) {
            val space = buffer.lastIndexOf(' ')
            if (space >= COMMA_MIN && !insideLink(buffer, space)) {
                val ready = buffer.substring(0, space + 1)
                buffer.delete(0, space + 1)
                return ready
            }
        }

        return ""
    }

    private fun sentenceEnd(buffer: StringBuilder): Int {
        for (i in buffer.indices) {
            val c = buffer[i]
            if (c == '।' || c == '\n') return i
            if (c in ".!?;") {
                val next = buffer.getOrNull(i + 1) ?: return -1
                if (next.isWhitespace()) {
                    if (!insideLink(buffer, i)) return i
                }
            }
        }
        return -1
    }

    private fun insideLink(buffer: StringBuilder, index: Int): Boolean {
        var start = index
        while (start > 0 && !buffer[start - 1].isWhitespace()) start--
        var end = index
        while (end < buffer.length && !buffer[end].isWhitespace()) end++
        val token = buffer.substring(start, end)
        return token.contains("://") || token.startsWith("www.", ignoreCase = true) ||
            token.startsWith("http", ignoreCase = true)
    }
}
