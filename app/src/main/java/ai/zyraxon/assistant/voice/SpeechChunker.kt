// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.voice

/**
 * Decides when streaming text has formed a natural, complete phrase worth speaking.
 *
 * Avoids word-by-word token chopping which causes robotic audio jitter.
 * Ensures speech begins promptly at natural phrase or sentence boundaries.
 */
object SpeechChunker {

    private const val FIRST_RELEASE_MIN = 20
    private const val FIRST_CUT_MIN = 14

    private const val LATER_RELEASE_MIN = 50
    private const val LATER_CUT_MIN = 35

    private const val CLAUSE_MIN = 24
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

        // 1. Sentence end is always the best cut point
        val sentenceEnd = sentenceEnd(buffer)
        if (sentenceEnd >= 0) {
            val ready = buffer.substring(0, sentenceEnd + 1)
            buffer.delete(0, sentenceEnd + 1)
            return ready
        }

        // 2. First chunk: wait for a natural clause or minimum coherent length
        if (first) {
            if (buffer.length >= FIRST_RELEASE_MIN) {
                // Check for a comma or clause pause first
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

        // 3. Natural clause pause (comma, dash, colon)
        if (buffer.length >= CLAUSE_MIN) {
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

        // 4. Long run without punctuation
        if (buffer.length >= LATER_RELEASE_MIN) {
            val space = buffer.lastIndexOf(' ')
            if (space >= LATER_CUT_MIN && !insideLink(buffer, space)) {
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
