package ai.zyraxon.assistant.brain

import ai.zyraxon.assistant.core.MiniJson
import java.io.File
import java.text.Normalizer
import kotlin.math.ln

data class Chunk(val id: Int, val key: String, val kind: String, val title: String, val text: String, val timeMs: Long = 0)
data class Hit(val key: String, val kind: String, val title: String, val snippet: String, val score: Double, val timeMs: Long)

/** Words for Bangla and English text: digits unified, suffixes cut, so "ভাড়ার" finds "ভাড়া". */
object Tokenizer {
    private val bnDigits = "০১২৩৪৫৬৭৮৯"
    private val suffixes = listOf("গুলোর", "গুলোতে", "গুলোকে", "গুলো", "গুলির", "গুলি", "দেরকে", "দের", "েরা", "ের", "েতে", "ের", "কে", "তে", "টির", "টার", "টি", "টা", "রা", "য়", "র", "ে", "ও")
        .distinct().sortedByDescending { it.length }

    fun stem(w: String): String {
        if (w.isEmpty() || w[0].code !in 0x0980..0x09FF) return stemEn(w)
        var s = w
        for (suf in suffixes) if (s.endsWith(suf) && s.length - suf.length >= 2) { s = s.dropLast(suf.length); break }
        return s
    }

    private fun stemEn(w: String): String {
        if (w.length <= 3) return w
        return when {
            w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
            w.endsWith("ing") && w.length > 5 -> w.dropLast(3)
            w.endsWith("ed") && w.length > 4 -> w.dropLast(2)
            w.endsWith("es") && w.length > 4 && (w.dropLast(2).let { it.endsWith("s") || it.endsWith("x") || it.endsWith("z") || it.endsWith("ch") || it.endsWith("sh") }) -> w.dropLast(2)
            w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }
    }

    fun tokens(text: String): List<String> {
        val n = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
            .replace("‌", "").replace("‍", "")
        val sb = StringBuilder(n.length); for (c in n) { val i = bnDigits.indexOf(c); sb.append(if (i >= 0) ('0' + i) else c) }
        val out = ArrayList<String>(); val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { val w = cur.toString(); if (w.length > 1 || w[0].isDigit()) out += stem(w); cur.clear() } }
        for (c in sb) { if (c.isLetterOrDigit() || Character.getType(c) == Character.NON_SPACING_MARK.toInt() || Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt()) cur.append(c) else flush() }
        flush(); return out
    }
}

/** An offline search index over the phone's files, messages and notes: BM25 with Bangla-aware words. */
class Brain {
    private val chunks = LinkedHashMap<Int, Chunk>()
    private val postings = HashMap<String, HashMap<Int, Int>>()   // term -> chunk -> term frequency
    private val lens = HashMap<Int, Int>()
    private val byKey = HashMap<String, MutableList<Int>>()
    private var nextId = 1
    private var totalLen = 0L

    val size get() = chunks.size
    val sources get() = byKey.size

    private fun split(text: String, size: Int = 600, overlap: Int = 120): List<String> {
        val t = text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (t.length <= size) return if (t.isEmpty()) emptyList() else listOf(t)
        val out = ArrayList<String>(); var i = 0
        while (i < t.length) {
            var e = minOf(t.length, i + size)
            if (e < t.length) { val br = t.lastIndexOfAny(charArrayOf('\n', '।', '.', '?', '!'), e).takeIf { it > i + size / 2 }; if (br != null) e = br + 1 }
            out += t.substring(i, e).trim()
            if (e >= t.length) break
            i = maxOf(e - overlap, i + 1)
        }
        return out.filter { it.isNotEmpty() }
    }

    /** Replaces everything indexed for [key] (a file path, an SMS id…) with [text]. */
    fun upsert(key: String, kind: String, title: String, text: String, timeMs: Long = 0) {
        remove(key)
        val ids = ArrayList<Int>()
        for (part in split(text)) {
            val id = nextId++
            val c = Chunk(id, key, kind, title, part, timeMs); chunks[id] = c; ids += id
            val toks = Tokenizer.tokens(title + " " + title + " " + part)
            lens[id] = toks.size; totalLen += toks.size
            for ((t, n) in toks.groupingBy { it }.eachCount()) postings.getOrPut(t) { HashMap() }[id] = n
        }
        if (ids.isNotEmpty()) byKey[key] = ids
    }

    fun remove(key: String) {
        val ids = byKey.remove(key) ?: return
        for (id in ids) {
            chunks.remove(id); totalLen -= lens.remove(id) ?: 0
        }
        val dead = ids.toHashSet()
        val it = postings.entries.iterator()
        while (it.hasNext()) { val e = it.next(); e.value.keys.removeAll(dead); if (e.value.isEmpty()) it.remove() }
    }

    fun has(key: String) = byKey.containsKey(key)

    fun search(query: String, limit: Int = 8, kind: String = "", sinceMs: Long = 0): List<Hit> {
        val qt = Tokenizer.tokens(query).distinct()
        if (qt.isEmpty() || chunks.isEmpty()) return emptyList()
        val n = chunks.size.toDouble(); val avg = if (chunks.isEmpty()) 1.0 else totalLen.toDouble() / chunks.size
        val scores = HashMap<Int, Double>(); val matched = HashMap<Int, MutableSet<String>>()
        for (q in qt) {
            // exact term, plus longer words that start with it (a half-typed or inflected word)
            val terms = LinkedHashMap<String, Double>()
            if (postings.containsKey(q)) terms[q] = 1.0
            if (q.length >= 3) for (t in postings.keys) if (t != q && (t.startsWith(q) || (q.length >= 4 && q.startsWith(t) && t.length >= 3))) terms.putIfAbsent(t, 0.6)
            for ((t, w) in terms) {
                val p = postings[t] ?: continue
                val idf = ln(1 + (n - p.size + 0.5) / (p.size + 0.5))
                for ((id, tf) in p) {
                    val c = chunks[id] ?: continue
                    if (kind.isNotEmpty() && c.kind != kind) continue
                    if (sinceMs > 0 && c.timeMs in 1 until sinceMs) continue
                    val len = lens[id] ?: 1
                    val s = w * idf * (tf * 2.2) / (tf + 1.2 * (0.25 + 0.75 * len / avg))
                    scores[id] = (scores[id] ?: 0.0) + s; matched.getOrPut(id) { HashSet() } += q
                }
            }
        }
        // chunks that match more of the question rank higher, and one chunk per source is enough
        val ranked = scores.map { (id, s) -> id to s * (0.6 + 0.4 * (matched[id]?.size ?: 0) / qt.size) * (if ((matched[id]?.size ?: 0) == qt.size) 1.3 else 1.0) }
            .sortedByDescending { it.second }
        val seen = HashSet<String>(); val out = ArrayList<Hit>()
        for ((id, s) in ranked) {
            val c = chunks[id] ?: continue
            if (!seen.add(c.key)) continue
            out += Hit(c.key, c.kind, c.title, snippet(c.text, qt), s, c.timeMs)
            if (out.size >= limit) break
        }
        return out
    }

    private fun snippet(text: String, qt: List<String>): String {
        val low = text.lowercase()
        var pos = -1
        for (q in qt) { val i = low.indexOf(q); if (i >= 0 && (pos < 0 || i < pos)) pos = i }
        if (pos < 0) return text.take(220).replace('\n', ' ')
        val s = maxOf(0, pos - 70); val e = minOf(text.length, pos + 170)
        return (if (s > 0) "…" else "") + text.substring(s, e).replace('\n', ' ') + (if (e < text.length) "…" else "")
    }

    fun toJson(): String = MiniJson.stringify(chunks.values.map { mapOf("k" to it.key, "kind" to it.kind, "ti" to it.title, "tx" to it.text, "ms" to it.timeMs) })

    companion object {
        fun fromJson(src: String): Brain {
            val b = Brain(); if (src.isBlank()) return b
            val byKey = LinkedHashMap<String, StringBuilder>(); val meta = HashMap<String, Triple<String, String, Long>>()
            // chunks of one source were stored separately; re-add them as one source so ids and postings stay consistent
            for (e in MiniJson.list(MiniJson.parse(src))) {
                val m = MiniJson.obj(e); val k = MiniJson.str(m["k"])
                byKey.getOrPut(k) { StringBuilder() }.append(MiniJson.str(m["tx"])).append("\n\n")
                meta[k] = Triple(MiniJson.str(m["kind"]), MiniJson.str(m["ti"]), MiniJson.long(m["ms"]))
            }
            for ((k, v) in byKey) { val (kind, title, ms) = meta[k]!!; b.upsert(k, kind, title, v.toString(), ms) }
            return b
        }
        fun load(f: File): Brain = if (f.isFile) runCatching { fromJson(f.readText()) }.getOrDefault(Brain()) else Brain()
    }

    fun save(f: File) { f.parentFile?.mkdirs(); f.writeText(toJson()) }
}
