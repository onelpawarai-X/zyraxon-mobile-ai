package ai.zyraxon.assistant.core

/**
 * A small JSON reader/writer over plain Kotlin values (Map, List, String, Long/Double, Boolean, null).
 * The engines that store their state (skills, rules, ledger, index) use it so they run and are tested
 * on a computer, without Android's org.json.
 */
object MiniJson {
    fun parse(src: String): Any? {
        val p = P(src); p.ws(); val v = p.value(); p.ws()
        require(p.i == src.length) { "extra text after JSON at ${p.i}" }
        return v
    }

    fun stringify(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    fun list(v: Any?): List<Any?> = (v as? List<Any?>) ?: emptyList()
    fun str(v: Any?, d: String = ""): String = when (v) { null -> d; is String -> v; is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString(); else -> v.toString() }
    fun long(v: Any?, d: Long = 0): Long = when (v) { is Long -> v; is Int -> v.toLong(); is Double -> v.toLong(); is String -> v.toLongOrNull() ?: d; else -> d }
    fun int(v: Any?, d: Int = 0): Int = long(v, d.toLong()).toInt()
    fun dbl(v: Any?, d: Double = 0.0): Double = when (v) { is Double -> v; is Long -> v.toDouble(); is Int -> v.toDouble(); is String -> v.toDoubleOrNull() ?: d; else -> d }
    fun bool(v: Any?, d: Boolean = false): Boolean = when (v) { is Boolean -> v; is String -> v == "true"; else -> d }

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Double -> sb.append(if (v.isNaN() || v.isInfinite()) "0" else if (v % 1.0 == 0.0 && Math.abs(v) < 1e15) v.toLong().toString() else v.toString())
            is Float -> write(v.toDouble(), sb)
            is Map<*, *> -> { sb.append('{'); var f = true; for ((k, x) in v) { if (!f) sb.append(','); f = false; quote(k.toString(), sb); sb.append(':'); write(x, sb) }; sb.append('}') }
            is Iterable<*> -> { sb.append('['); var f = true; for (x in v) { if (!f) sb.append(','); f = false; write(x, sb) }; sb.append(']') }
            else -> quote(v.toString(), sb)
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\"); c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t"); c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws(); require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws(); if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = string(); ws(); expect(':'); m[k] = value(); ws(); if (s[i] == ',') { i++; continue }; expect('}'); break }; m }
                '[' -> { i++; val l = ArrayList<Any?>(); ws(); if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i] == ',') { i++; continue }; expect(']'); break }; l }
                '"' -> string()
                't' -> { lit("true"); true }
                'f' -> { lit("false"); false }
                'n' -> { lit("null"); null }
                else -> if (c == '-' || c.isDigit()) number() else error("bad JSON at $i")
            }
        }
        fun expect(c: Char) { require(i < s.length && s[i] == c) { "expected $c at $i" }; i++ }
        fun lit(w: String) { require(s.startsWith(w, i)) { "bad literal at $i" }; i += w.length }
        fun number(): Any {
            val st = i; if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
        fun string(): String {
            expect('"'); val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                if (c == '"') break
                if (c != '\\') { sb.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            }
            return sb.toString()
        }
    }
}
