// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.viewer

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Turns a document into one self-contained HTML page for the Preview panel: Word, Excel and
 * PowerPoint files (read straight from their XML), CSV, Markdown, JSON, code and plain text.
 * Pure Kotlin — no Android classes — so it is tested on a computer. Images, PDF and web pages
 * are shown by the Preview itself (see [kindOf]).
 */
object DocViewer {
    enum class Kind { DOCX, XLSX, PPTX, TEXT, TABLE, MARKDOWN, HTML, IMAGE, PDF, UNKNOWN }

    fun kindOf(file: File): Kind = when (file.extension.lowercase()) {
        "docx", "dotx" -> Kind.DOCX
        "xlsx", "xlsm" -> Kind.XLSX
        "pptx", "potx" -> Kind.PPTX
        "csv", "tsv" -> Kind.TABLE
        "md", "markdown" -> Kind.MARKDOWN
        "html", "htm", "svg" -> Kind.HTML
        "png", "jpg", "jpeg", "gif", "webp", "bmp" -> Kind.IMAGE
        "pdf" -> Kind.PDF
        "txt", "log", "json", "xml", "yaml", "yml", "kt", "java", "js", "ts", "py", "sh", "css", "toml", "ini", "gradle", "c", "cpp", "h", "go", "rs", "sql", "env" -> Kind.TEXT
        else -> Kind.UNKNOWN
    }

    fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "pdf" -> "application/pdf"
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
        "svg" -> "image/svg+xml"; "html", "htm" -> "text/html"
        "csv" -> "text/csv"; "txt", "md", "log", "json", "xml" -> "text/plain"
        "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "zip" -> "application/zip"; "apk" -> "application/vnd.android.package-archive"
        else -> "*/*"
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun toHtml(file: File): String = when (kindOf(file)) {
        Kind.DOCX -> page(file.name, docx(file), "doc")
        Kind.XLSX -> page(file.name, xlsx(file), "sheet")
        Kind.PPTX -> page(file.name, pptx(file), "deck")
        Kind.TABLE -> page(file.name, tableOf(parseCsv(file.readText(), if (file.extension.equals("tsv", true)) '\t' else ',')), "sheet")
        Kind.MARKDOWN -> page(file.name, markdown(file.readText()), "doc")
        else -> page(file.name, "<pre>" + esc(file.readText().take(400_000)) + "</pre>", "code")
    }

    fun page(title: String, body: String, mode: String): String = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title><style>
*{box-sizing:border-box}body{margin:0;background:#e9ebf0;color:#1b1d24;font:16px/1.65 'Noto Sans Bengali','Nirmala UI',Calibri,system-ui,sans-serif;-webkit-text-size-adjust:100%}
.bar{position:sticky;top:0;background:#1b1d24;color:#fff;padding:8px 14px;font-size:13px;z-index:5;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.paper{background:#fff;max-width:860px;margin:12px auto;padding:28px 26px;border-radius:6px;box-shadow:0 1px 6px #0002;overflow-wrap:anywhere}
h1,h2,h3,h4{line-height:1.3;margin:1.1em 0 .4em}h1{font-size:1.7em}h2{font-size:1.4em}h3{font-size:1.2em}p{margin:.5em 0}
table{border-collapse:collapse;margin:.8em 0;max-width:100%}td,th{border:1px solid #c9ccd6;padding:6px 10px;vertical-align:top}th{background:#eef0f6}
.sheetwrap{overflow:auto;max-width:100%;background:#fff;margin:10px;border-radius:6px;box-shadow:0 1px 6px #0002}.sheetwrap table{margin:0;font-size:14px}
.sheetwrap td.n{text-align:right;font-variant-numeric:tabular-nums}.sheetwrap th.h{position:sticky;top:0}
.tab{margin:12px 14px 0;font-weight:700;color:#444}
.slide{background:#fff;max-width:860px;margin:14px auto;padding:30px 28px;border-radius:10px;box-shadow:0 1px 8px #0003;min-height:200px;overflow-wrap:anywhere;border-top:6px solid #6c4cff}
.slide .no{font-size:12px;color:#888}.slide h2{margin:.2em 0 .5em}.slide ul{margin:.3em 0 0;padding-left:1.2em}.notes{margin-top:14px;padding-top:10px;border-top:1px dashed #ccc;font-size:13px;color:#666}
pre{margin:0;padding:14px;white-space:pre-wrap;word-break:break-word;font:13px/1.5 ui-monospace,Menlo,monospace;background:#fff;min-height:100vh}
code{background:#eef0f6;padding:1px 5px;border-radius:4px}blockquote{margin:.6em 0;padding:.2em 1em;border-left:4px solid #c9ccd6;color:#555}
hr{border:0;border-top:2px dashed #ccd;margin:1.4em 0}img{max-width:100%}
</style></head><body><div class="bar">${esc(title)}</div>${if (mode == "doc") "<div class=\"paper\">$body</div>" else body}</body></html>"""

    // ---------------------------------------------------------------- shared XML helpers

    private fun dom(zip: ZipFile, name: String): Element? {
        val e = zip.getEntry(name) ?: return null
        val f = DocumentBuilderFactory.newInstance()
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        return zip.getInputStream(e).use { f.newDocumentBuilder().parse(it).documentElement }
    }

    private fun kids(n: Node, tag: String): List<Element> {
        val out = ArrayList<Element>(); var c = n.firstChild
        while (c != null) { if (c is Element && c.tagName == tag) out += c; c = c.nextSibling }
        return out
    }
    private fun first(n: Node, tag: String): Element? = kids(n, tag).firstOrNull()
    private fun all(n: Element, tag: String): List<Element> {
        val l = n.getElementsByTagName(tag); return (0 until l.length).map { l.item(it) as Element }
    }
    private fun textOf(n: Element, tag: String) = all(n, tag).joinToString("") { it.textContent }

    // ---------------------------------------------------------------- docx

    private fun docx(file: File): String = ZipFile(file).use { zip ->
        val root = dom(zip, "word/document.xml") ?: return "<p>খালি ডকুমেন্ট</p>"
        val body = first(root, "w:body") ?: return "<p></p>"
        val sb = StringBuilder(); var inList = false
        fun closeList() { if (inList) { sb.append("</ul>"); inList = false } }
        for (el in kids(body, "w:p") .let { _ -> childElements(body) }) {
            when (el.tagName) {
                "w:p" -> {
                    val style = first(el, "w:pPr")?.let { first(it, "w:pStyle")?.getAttribute("w:val") }.orEmpty()
                    val isLi = first(el, "w:pPr")?.let { first(it, "w:numPr") } != null || style.startsWith("List")
                    val html = runs(el)
                    if (el.getElementsByTagName("w:br").let { l -> (0 until l.length).any { (l.item(it) as Element).getAttribute("w:type") == "page" } }) { closeList(); sb.append("<hr>") }
                    if (html.isBlank()) { closeList(); continue }
                    val h = Regex("(?i)heading\\s*(\\d)").find(style)?.groupValues?.get(1)?.toIntOrNull()
                    when {
                        style.equals("Title", true) -> { closeList(); sb.append("<h1>$html</h1>") }
                        h != null -> { closeList(); sb.append("<h${h.coerceIn(1, 4)}>$html</h${h.coerceIn(1, 4)}>") }
                        isLi -> { if (!inList) { sb.append("<ul>"); inList = true }; sb.append("<li>$html</li>") }
                        else -> { closeList(); sb.append("<p>$html</p>") }
                    }
                }
                "w:tbl" -> {
                    closeList(); sb.append("<table>")
                    kids(el, "w:tr").forEachIndexed { i, tr ->
                        sb.append("<tr>")
                        kids(tr, "w:tc").forEach { tc ->
                            val cell = kids(tc, "w:p").joinToString("<br>") { runs(it) }
                            sb.append(if (i == 0) "<th>$cell</th>" else "<td>$cell</td>")
                        }
                        sb.append("</tr>")
                    }
                    sb.append("</table>")
                }
            }
        }
        closeList(); sb.toString()
    }

    private fun childElements(n: Node): List<Element> {
        val out = ArrayList<Element>(); var c = n.firstChild
        while (c != null) { if (c is Element) out += c; c = c.nextSibling }
        return out
    }

    private fun runs(p: Element): String {
        val sb = StringBuilder()
        for (r in all(p, "w:r")) {
            val t = StringBuilder()
            childElements(r).forEach { c ->
                when (c.tagName) { "w:t" -> t.append(esc(c.textContent)); "w:tab" -> t.append("&emsp;"); "w:br" -> if (c.getAttribute("w:type") != "page") t.append("<br>") }
            }
            if (t.isEmpty()) continue
            val pr = first(r, "w:rPr")
            var s = t.toString()
            if (pr != null) {
                if (first(pr, "w:b") != null) s = "<b>$s</b>"
                if (first(pr, "w:i") != null) s = "<i>$s</i>"
                if (first(pr, "w:u") != null) s = "<u>$s</u>"
            }
            sb.append(s)
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- xlsx

    private fun colIndex(ref: String): Int {
        var n = 0; for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break }; return n - 1
    }

    private fun xlsx(file: File): String = ZipFile(file).use { zip ->
        val shared = dom(zip, "xl/sharedStrings.xml")?.let { r -> all(r, "si").map { textOf(it, "t") } } ?: emptyList()
        val names = dom(zip, "xl/workbook.xml")?.let { r -> all(r, "sheet").map { it.getAttribute("name") } } ?: emptyList()
        val sheets = zip.entries().asSequence().map { it.name }.filter { Regex("xl/worksheets/sheet\\d+\\.xml").matches(it) }
            .sortedBy { Regex("\\d+").find(it.substringAfterLast('/'))!!.value.toInt() }.toList()
        val sb = StringBuilder()
        sheets.forEachIndexed { si, path ->
            val root = dom(zip, path) ?: return@forEachIndexed
            val rows = ArrayList<List<Pair<String, Boolean>>>()
            for (row in all(root, "row")) {
                val cells = ArrayList<Pair<String, Boolean>>()
                for (c in kids(row, "c")) {
                    val idx = colIndex(c.getAttribute("r")).coerceAtLeast(cells.size)
                    while (cells.size < idx) cells += "" to false
                    val t = c.getAttribute("t")
                    val v = first(c, "v")?.textContent.orEmpty()
                    val f = first(c, "f")?.textContent.orEmpty()
                    val text = when (t) {
                        "s" -> shared.getOrElse(v.toIntOrNull() ?: -1) { "" }
                        "inlineStr" -> textOf(c, "t")
                        else -> if (v.isBlank() && f.isNotBlank()) "=$f" else v
                    }
                    cells += text to (t != "s" && t != "inlineStr" && text.toDoubleOrNull() != null)
                }
                rows += cells
            }
            sb.append("<div class=\"tab\">").append(esc(names.getOrElse(si) { "Sheet${si + 1}" })).append("</div>")
            sb.append(grid(rows))
        }
        sb.toString()
    }

    private fun grid(rows: List<List<Pair<String, Boolean>>>): String {
        val sb = StringBuilder("<div class=\"sheetwrap\"><table>")
        rows.take(2000).forEachIndexed { i, r ->
            sb.append("<tr>")
            r.forEach { (v, num) ->
                if (i == 0) sb.append("<th class=\"h\">").append(esc(v)).append("</th>")
                else sb.append("<td").append(if (num) " class=\"n\"" else "").append(">").append(esc(v)).append("</td>")
            }
            sb.append("</tr>")
        }
        return sb.append("</table></div>").toString()
    }

    private fun tableOf(rows: List<List<String>>) = grid(rows.map { r -> r.map { it to (it.toDoubleOrNull() != null) } })

    fun parseCsv(text: String, sep: Char): List<List<String>> {
        val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cur = StringBuilder(); var q = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                q && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == sep -> { row.add(cur.toString()); cur.clear() }
                !q && (c == '\n' || c == '\r') -> { if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++; row.add(cur.toString()); cur.clear(); rows.add(row); row = ArrayList() }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) { row.add(cur.toString()); rows.add(row) }
        return rows
    }

    // ---------------------------------------------------------------- pptx

    private fun pptx(file: File): String = ZipFile(file).use { zip ->
        val slides = zip.entries().asSequence().map { it.name }.filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
            .sortedBy { Regex("\\d+").find(it.substringAfterLast('/'))!!.value.toInt() }.toList()
        val sb = StringBuilder()
        slides.forEachIndexed { i, path ->
            val root = dom(zip, path) ?: return@forEachIndexed
            val paras = all(root, "a:p").map { textOf(it, "a:t").trim() }.filter { it.isNotEmpty() }
            val num = Regex("\\d+").find(path.substringAfterLast('/'))!!.value
            val notes = dom(zip, "ppt/notesSlides/notesSlide$num.xml")?.let { r ->
                all(r, "a:p").map { textOf(it, "a:t").trim() }.filter { it.isNotEmpty() && !it.all(Char::isDigit) }.joinToString(" ")
            }.orEmpty()
            sb.append("<div class=\"slide\"><div class=\"no\">${i + 1} / ${slides.size}</div>")
            if (paras.isNotEmpty()) sb.append("<h2>").append(esc(paras.first())).append("</h2>")
            if (paras.size > 1) sb.append("<ul>").append(paras.drop(1).joinToString("") { "<li>${esc(it)}</li>" }).append("</ul>")
            if (notes.isNotBlank()) sb.append("<div class=\"notes\">").append(esc(notes)).append("</div>")
            sb.append("</div>")
        }
        sb.toString()
    }

    // ---------------------------------------------------------------- markdown (small, safe)

    fun markdown(src: String): String {
        val sb = StringBuilder(); var list = false; var code = false
        fun inline(s: String): String = esc(s)
            .replace(Regex("`([^`]+)`"), "<code>$1</code>")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
            .replace(Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?![*\\w])"), "<i>$1</i>")
            .replace(Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)"), "<a href=\"$2\">$1</a>")
        fun closeList() { if (list) { sb.append("</ul>"); list = false } }
        val lines = src.lines(); var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.trimStart().startsWith("```")) { closeList(); code = !code; sb.append(if (code) "<pre>" else "</pre>"); i++; continue }
            if (code) { sb.append(esc(l)).append('\n'); i++; continue }
            val t = l.trim()
            when {
                t.isEmpty() -> closeList()
                t.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("\\|?[\\s:|-]+\\|[\\s:|-]*")) -> {
                    closeList()
                    fun cells(x: String) = x.trim().trim('|').split('|').map { it.trim() }
                    sb.append("<table><tr>").append(cells(t).joinToString("") { "<th>${inline(it)}</th>" }).append("</tr>")
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { sb.append("<tr>").append(cells(lines[i]).joinToString("") { "<td>${inline(it)}</td>" }).append("</tr>"); i++ }
                    sb.append("</table>"); continue
                }
                t.startsWith("#") -> { closeList(); val n = t.takeWhile { it == '#' }.length.coerceIn(1, 4); sb.append("<h$n>").append(inline(t.dropWhile { it == '#' }.trim())).append("</h$n>") }
                t.startsWith("- ") || t.startsWith("* ") -> { if (!list) { sb.append("<ul>"); list = true }; sb.append("<li>").append(inline(t.drop(2))).append("</li>") }
                Regex("^\\d+[.)] ").containsMatchIn(t) -> { if (!list) { sb.append("<ul>"); list = true }; sb.append("<li>").append(inline(t.replace(Regex("^\\d+[.)] "), ""))).append("</li>") }
                t.startsWith(">") -> { closeList(); sb.append("<blockquote>").append(inline(t.trimStart('>', ' '))).append("</blockquote>") }
                t == "---" -> { closeList(); sb.append("<hr>") }
                else -> { closeList(); sb.append("<p>").append(inline(t)).append("</p>") }
            }
            i++
        }
        closeList(); if (code) sb.append("</pre>")
        return sb.toString()
    }
}
