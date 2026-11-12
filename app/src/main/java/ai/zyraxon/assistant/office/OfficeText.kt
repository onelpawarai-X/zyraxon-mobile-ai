package ai.zyraxon.assistant.office

import ai.zyraxon.assistant.office.OfficeFiles.DocBlock
import ai.zyraxon.assistant.office.OfficeFiles.Slide

/**
 * Turns the plain text a model writes (Markdown, CSV) into the blocks, sheets
 * and slides [OfficeFiles] writes out. Models write Markdown far more reliably
 * than a hand-built JSON tree, so the tool accepts it directly.
 */
object OfficeText {

    private fun stripInline(s: String): String =
        s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?<![_\\w])__([^_\\n]+?)__(?![_\\w])"), "$1")
            .replace(Regex("`([^`]+)`"), "$1").replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)"), "$1 ($2)").trim()

    private fun isSeparator(line: String): Boolean {
        val t = line.trim()
        return t.contains('-') && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
    }

    private fun cells(line: String): List<String> =
        line.trim().trim('|').split('|').map { stripInline(it) }

    private val BULLET = Regex("^\\s*[-*•]\\s+(.*)")
    private val NUMBERED = Regex("^\\s*\\d+[.)]\\s+(.*)")

    fun docFromMarkdown(md: String): List<DocBlock> {
        val out = mutableListOf<DocBlock>()
        val lines = md.replace("\r\n", "\n").split('\n')
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out.add(DocBlock.Para(stripInline(para.toString()))); para.setLength(0) }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.isEmpty() -> flush()
                t.startsWith("### ") -> { flush(); out.add(DocBlock.Heading(3, stripInline(t.drop(4)))) }
                t.startsWith("## ") -> { flush(); out.add(DocBlock.Heading(2, stripInline(t.drop(3)))) }
                t.startsWith("# ") -> { flush(); out.add(DocBlock.Heading(1, stripInline(t.drop(2)))) }
                t == "---" || t == "***" || t.equals("<pagebreak>", true) || t == "\\pagebreak" -> { flush(); out.add(DocBlock.PageBreak) }
                t.contains('|') && i + 1 < lines.size && isSeparator(lines[i + 1]) -> {
                    flush()
                    val rows = mutableListOf(cells(t))
                    i += 2
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows.add(cells(lines[i])); i++ }
                    out.add(DocBlock.Table(rows))
                    continue
                }
                BULLET.matches(line) || NUMBERED.matches(line) -> {
                    flush()
                    val numbered = NUMBERED.matches(line)
                    val items = mutableListOf<String>()
                    while (i < lines.size) {
                        val m = (if (numbered) NUMBERED else BULLET).find(lines[i]) ?: break
                        items.add(stripInline(m.groupValues[1])); i++
                    }
                    out.add(DocBlock.Bullets(items, numbered))
                    continue
                }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(t) }
            }
            i++
        }
        flush()
        return out
    }

    /** `# Title` (or `## Title`) starts a slide; bullet lines and plain lines are its points. `Notes:` lines become notes. */
    fun slidesFromMarkdown(md: String): List<Slide> {
        val slides = mutableListOf<Slide>()
        var title: String? = null
        val bullets = mutableListOf<String>()
        val notes = StringBuilder()
        fun flush() {
            if (title != null) slides.add(Slide(title!!, bullets.toList(), notes.toString().trim()))
            title = null; bullets.clear(); notes.setLength(0)
        }
        for (raw in md.replace("\r\n", "\n").split('\n')) {
            val t = raw.trim()
            when {
                t.isEmpty() -> Unit
                t.startsWith("# ") || t.startsWith("## ") -> { flush(); title = stripInline(t.trimStart('#').trim()) }
                t.startsWith("### ") -> bullets.add(stripInline(t.trimStart('#').trim()))
                t.startsWith("Notes:", true) -> notes.append(t.substringAfter(':').trim()).append('\n')
                else -> {
                    val m = BULLET.find(raw) ?: NUMBERED.find(raw)
                    if (title == null) title = stripInline(t) else bullets.add(stripInline(m?.groupValues?.get(1) ?: t))
                }
            }
        }
        flush()
        return slides
    }

    /** Comma, semicolon or tab separated text → rows. Quoted cells may hold the separator. */
    fun rowsFromCsv(text: String): List<List<String>> {
        val lines = text.replace("\r\n", "\n").split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val sep = listOf(',', ';', '\t').maxByOrNull { c -> lines.first().count { it == c } } ?: ','
        return lines.map { line ->
            val cellsOut = mutableListOf<String>()
            val cur = StringBuilder()
            var q = false
            var k = 0
            while (k < line.length) {
                val ch = line[k]
                when {
                    ch == '"' && q && k + 1 < line.length && line[k + 1] == '"' -> { cur.append('"'); k++ }
                    ch == '"' -> q = !q
                    ch == sep && !q -> { cellsOut.add(cur.toString().trim()); cur.setLength(0) }
                    else -> cur.append(ch)
                }
                k++
            }
            cellsOut.add(cur.toString().trim())
            cellsOut
        }
    }

    /** Pipe-table Markdown → rows (for a sheet written as a table). */
    fun rowsFromMarkdownTable(md: String): List<List<String>> {
        val lines = md.split('\n').filter { it.contains('|') && !isSeparator(it) }
        return lines.map { cells(it) }
    }
}
