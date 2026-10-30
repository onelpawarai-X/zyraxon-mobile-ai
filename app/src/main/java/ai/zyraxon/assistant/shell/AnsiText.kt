package ai.zyraxon.assistant.shell

/**
 * Turns the escape sequences a real command prints into coloured spans.
 *
 * `ls --color`, `git status`, `grep --color` and every other tool that believes
 * it is talking to a terminal emit SGR sequences such as `\u001b[31m`. A plain
 * `Text` shows them as literal `[31m` garbage, which is what made the terminal
 * look broken. This parses them into [Span]s with a colour and the bold bit, and
 * drops the rest of the control bytes (cursor moves, erase-line, OSC titles) so
 * only the text and its colour survive.
 *
 * A pure function of a string, so it is testable without a screen.
 */
object AnsiText {

    /** One run of text with the attributes that were active while it was printed. */
    data class Span(
        val text: String,
        val color: Int?,
        val bold: Boolean
    )

    /** The 16 ANSI colours as ARGB, matching a dark terminal theme. */
    private val PALETTE = intArrayOf(
        0xFF3B4252.toInt(), // 0 black
        0xFFBF616A.toInt(), // 1 red
        0xFFA3BE8C.toInt(), // 2 green
        0xFFEBCB8B.toInt(), // 3 yellow
        0xFF81A1C1.toInt(), // 4 blue
        0xFFB48EAD.toInt(), // 5 magenta
        0xFF88C0D0.toInt(), // 6 cyan
        0xFFE5E9F0.toInt(), // 7 white
        0xFF4C566A.toInt(), // 8 bright black
        0xFFFF8B8B.toInt(), // 9 bright red
        0xFFB8E89C.toInt(), // 10 bright green
        0xFFFFE08A.toInt(), // 11 bright yellow
        0xFF9CC2E8.toInt(), // 12 bright blue
        0xFFD3A9CE.toInt(), // 13 bright magenta
        0xFFA8E0F0.toInt(), // 14 bright cyan
        0xFFFFFFFF.toInt()  // 15 bright white
    )

    /** The colour and weight that are active while parsing one line. */
    private class Cursor {
        var color: Int? = null
        var bold: Boolean = false
    }

    /**
     * Splits [input] into coloured spans. Sequences are stripped even when a
     * command only half-emits one, so no escape byte ever reaches the screen.
     */
    fun spans(input: String): List<Span> {
        if (input.isEmpty()) return emptyList()
        val out = ArrayList<Span>()
        val plain = StringBuilder()
        val cur = Cursor()
        var i = 0
        while (i < input.length) {
            if (input[i] == '\u001b') {
                if (plain.isNotEmpty()) {
                    out += Span(plain.toString(), cur.color, cur.bold)
                    plain.setLength(0)
                }
                i = consumeEscape(input, i, cur)
                continue
            }
            plain.append(input[i])
            i++
        }
        if (plain.isNotEmpty()) out += Span(plain.toString(), cur.color, cur.bold)
        return out
    }

    /**
     * Consumes one escape sequence starting at [start]. Returns the index just
     * past it. SGR (`m`) updates [cur]; everything else is dropped.
     */
    private fun consumeEscape(input: String, start: Int, cur: Cursor): Int {
        var i = start + 1
        if (i >= input.length) return i
        when (input[i]) {
            '[' -> {
                i++
                val params = StringBuilder()
                while (i < input.length && input[i] !in '@'..'~') {
                    params.append(input[i]); i++
                }
                if (i < input.length) {
                    val final = input[i]
                    if (final == 'm') applySgr(params.toString(), cur)
                    i++
                }
                return i
            }
            ']' -> {
                // OSC … BEL or ST — a window title, dropped whole.
                i++
                while (i < input.length && input[i] != '\u0007') {
                    if (input[i] == '\u001b' && i + 1 < input.length && input[i + 1] == '\\') {
                        return i + 2
                    }
                    i++
                }
                if (i < input.length) i++
                return i
            }
            else -> return i + 1
        }
    }

    private fun applySgr(params: String, cur: Cursor) {
        val codes = params.split(';').mapNotNull { it.toIntOrNull() }
        if (codes.isEmpty()) {
            cur.color = null
            cur.bold = false
            return
        }
        var k = 0
        while (k < codes.size) {
            when (val n = codes[k]) {
                0 -> { cur.color = null; cur.bold = false }
                1 -> cur.bold = true
                22 -> cur.bold = false
                30, 31, 32, 33, 34, 35, 36, 37 -> cur.color = PALETTE[n - 30]
                90, 91, 92, 93, 94, 95, 96, 97 -> cur.color = PALETTE[n - 90 + 8]
                39 -> cur.color = null
                38, 48 -> {
                    // 256-colour or truecolour: `38;5;n` or `38;2;r;g;b`.
                    if (k + 2 < codes.size && codes[k + 1] == 5) {
                        if (n == 38) cur.color = xterm256(codes[k + 2])
                        k += 2
                    } else if (k + 4 < codes.size && codes[k + 1] == 2) {
                        if (n == 38) cur.color = 0xFF000000.toInt() or (codes[k + 2] shl 16) or
                            (codes[k + 3] shl 8) or codes[k + 4]
                        k += 4
                    }
                }
            }
            k++
        }
    }

    /** The xterm 256-colour cube as ARGB. */
    private fun xterm256(n: Int): Int {
        if (n < 16) return PALETTE[n]
        if (n < 232) {
            val i = n - 16
            val r = (i / 36) % 6; val g = (i / 6) % 6; val bl = i % 6
            fun lvl(v: Int) = if (v == 0) 0 else 55 + v * 40
            return 0xFF000000.toInt() or (lvl(r) shl 16) or (lvl(g) shl 8) or lvl(bl)
        }
        val grey = 8 + (n - 232) * 10
        return 0xFF000000.toInt() or (grey shl 16) or (grey shl 8) or grey
    }

    /** The visible text with every escape sequence removed. */
    fun plain(input: String): String = spans(input).joinToString("") { it.text }
}
