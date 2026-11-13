// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

/**
 * Reads one string field out of a JSON object that is still being streamed.
 *
 * A model writes a file as the `content` argument of a tool call, and the
 * arguments arrive as an ever-growing prefix of a JSON document. The chat shows
 * the code as it is typed, so it needs the text of that field so far — even
 * though the closing quote, and the closing brace, have not arrived yet.
 */
object PartialJson {

    /** The value of [key] decoded so far, or null when the key has not started. */
    fun stringField(raw: String, key: String): String? {
        val needle = "\"$key\""
        var from = 0
        while (true) {
            val at = raw.indexOf(needle, from)
            if (at < 0) return null
            // Must be a key (followed by a colon), not a value that equals the name.
            var i = at + needle.length
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length) return null
            if (raw[i] != ':') { from = at + needle.length; continue }
            i++
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length) return ""
            if (raw[i] != '"') return null
 return decode(raw, i + 1)
 }
 }

 private fun decode(raw: String, start: Int): String {
 val sb = StringBuilder()
 var i = start
 while (i < raw.length) {
 val c = raw[i]
 if (c == '"') break
            if (c != '\\') { sb.append(c); i++; continue }
            if (i + 1 >= raw.length) break // a lone backslash: the escape is still arriving
            when (val e = raw[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '"' -> { sb.append('"'); i += 2 }
                'u' -> {
                    if (i + 6 > raw.length) break // the four hex digits are still arriving
                    val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                    if (code != null) sb.append(code.toChar())
                    i += 6
                }
                else -> { sb.append(e); i += 2 }
            }
        }
        return sb.toString()
    }
}
