// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.office

import ai.zyraxon.assistant.brand.BrandPalette
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes real Word, Excel and PowerPoint files with nothing but the JDK.
 *
 * A .docx / .xlsx / .pptx is a zip of small XML files, so the phone can make
 * them itself — no Python, no Linux install, no network. The files carry a
 * Bengali-capable font pair so Bengali text shows correctly in Word, Excel,
 * PowerPoint, LibreOffice and Google's viewers.
 */
object OfficeFiles {

    sealed class DocBlock {
        data class Heading(val level: Int, val text: String) : DocBlock()
        data class Para(val text: String, val bold: Boolean = false, val center: Boolean = false) : DocBlock()
        data class Bullets(val items: List<String>, val numbered: Boolean = false) : DocBlock()
        data class Table(val rows: List<List<String>>, val header: Boolean = true) : DocBlock()
        object PageBreak : DocBlock()
    }

    data class Sheet(val name: String, val rows: List<List<String>>)

    data class Slide(val title: String, val bullets: List<String>, val notes: String = "")

    // ---- shared helpers ------------------------------------------------------

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val FONT_LATIN = "Calibri"
    private const val FONT_CS = "Nirmala UI"

    fun esc(s: String): String {
        val b = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '&' -> b.append("&amp;")
                ch == '<' -> b.append("&lt;")
                ch == '>' -> b.append("&gt;")
                ch == '"' -> b.append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20 -> b.append(ch)
                else -> Unit // control characters are not legal in XML 1.0
            }
        }
        return b.toString()
    }

    private class Zip(out: OutputStream) {
        private val z = ZipOutputStream(out)
        fun put(name: String, text: String) {
            z.putNextEntry(ZipEntry(name))
            z.write(text.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        fun finish() { z.finish(); z.flush() }
    }

    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val REL_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    // ---- DOCX ----------------------------------------------------------------

    fun docx(title: String, blocks: List<DocBlock>, out: OutputStream, accent: String = "2F5496") {
        val ac = accent.removePrefix("#").uppercase()
        val z = Zip(out)
        z.put("[Content_Types].xml", XML +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "</Types>")
        z.put("_rels/.rels", XML + "<Relationships xmlns=\"$REL_NS\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL_DOC/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "</Relationships>")
        z.put("docProps/core.xml", XML +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>${esc(title)}</dc:title>" +
            "<dc:creator>ZYRAXON</dc:creator></cp:coreProperties>")
        z.put("word/_rels/document.xml.rels", XML + "<Relationships xmlns=\"$REL_NS\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL_DOC/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$REL_DOC/numbering\" Target=\"numbering.xml\"/>" +
            "</Relationships>")
        z.put("word/styles.xml", docxStyles(ac))
        z.put("word/numbering.xml", docxNumbering())
        val body = StringBuilder()
        for (b in blocks) body.append(docxBlock(b, ac))
        z.put("word/document.xml", XML +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>")
        z.finish()
    }

    private fun run(text: String, bold: Boolean = false, size: Int? = null, color: String? = null): String {
        val props = StringBuilder()
        props.append("<w:rFonts w:ascii=\"$FONT_LATIN\" w:hAnsi=\"$FONT_LATIN\" w:cs=\"$FONT_CS\"/>")
        if (bold) props.append("<w:b/><w:bCs/>")
        if (color != null) props.append("<w:color w:val=\"$color\"/>")
        if (size != null) props.append("<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>")
        val lines = text.split('\n')
        val inner = StringBuilder()
        lines.forEachIndexed { i, l ->
            if (i > 0) inner.append("<w:br/>")
            inner.append("<w:t xml:space=\"preserve\">${esc(l)}</w:t>")
        }
        return "<w:r><w:rPr>$props</w:rPr>$inner</w:r>"
    }

    private fun docxBlock(b: DocBlock, accent: String): String = when (b) {
        is DocBlock.Heading -> {
            val lvl = b.level.coerceIn(1, 3)
            "<w:p><w:pPr><w:pStyle w:val=\"Heading$lvl\"/></w:pPr>${run(b.text, bold = true)}</w:p>"
        }
        is DocBlock.Para ->
            "<w:p><w:pPr>${if (b.center) "<w:jc w:val=\"center\"/>" else ""}</w:pPr>${run(b.text, bold = b.bold)}</w:p>"
        is DocBlock.Bullets -> b.items.joinToString("") {
            "<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"0\"/>" +
                "<w:numId w:val=\"${if (b.numbered) 2 else 1}\"/></w:numPr></w:pPr>${run(it)}</w:p>"
        }
        is DocBlock.Table -> docxTable(b, accent)
        DocBlock.PageBreak -> "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"
    }

    private fun docxTable(t: DocBlock.Table, accent: String): String {
        val cols = t.rows.maxOfOrNull { it.size } ?: 1
        val width = 9638 / cols
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"9638\" w:type=\"dxa\"/><w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV"))
            sb.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>")
        sb.append("</w:tblBorders><w:tblCellMar><w:left w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"$width\"/>") }
        sb.append("</w:tblGrid>")
        t.rows.forEachIndexed { r, row ->
            sb.append("<w:tr>")
            for (c in 0 until cols) {
                val head = t.header && r == 0
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>")
                if (head) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${BrandPalette.tint("#$accent", 0.88).removePrefix("#")}\"/>")
                sb.append("</w:tcPr><w:p>${run(row.getOrElse(c) { "" }, bold = head)}</w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl><w:p/>")
        return sb.toString()
    }

    private fun docxStyles(accent: String): String {
        fun heading(n: Int, size: Int, color: String) =
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$n\"><w:name w:val=\"heading $n\"/><w:basedOn w:val=\"Normal\"/>" +
                "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"${if (n == 1) 360 else 240}\" w:after=\"120\"/>" +
                "<w:outlineLvl w:val=\"${n - 1}\"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val=\"$color\"/>" +
                "<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr></w:style>"
        return XML + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"$FONT_LATIN\" w:hAnsi=\"$FONT_LATIN\" w:eastAsia=\"$FONT_LATIN\" w:cs=\"$FONT_CS\"/>" +
            "<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/><w:lang w:val=\"en-US\" w:bidi=\"bn-BD\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            heading(1, 36, BrandPalette.shade("#$accent", 0.35).removePrefix("#")) + heading(2, 30, accent) + heading(3, 26, accent) +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/>" +
            "<w:qFormat/><w:pPr><w:ind w:left=\"720\"/><w:contextualSpacing/></w:pPr></w:style></w:styles>"
    }

    private fun docxNumbering(): String =
        XML + "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>" +
            "<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"&#8226;\"/><w:lvlJc w:val=\"left\"/>" +
            "<w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>" +
            "<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>" +
            "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/><w:lvlJc w:val=\"left\"/>" +
            "<w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num><w:num w:numId=\"2\"><w:abstractNumId w:val=\"1\"/></w:num></w:numbering>"

    // ---- XLSX ----------------------------------------------------------------

    private fun colName(i: Int): String {
        var n = i
        val sb = StringBuilder()
        do { sb.append('A' + n % 26); n = n / 26 - 1 } while (n >= 0)
        return sb.reverse().toString()
    }

    private fun sheetTitle(raw: String, taken: Set<String>): String {
        var t = raw.replace(Regex("[\\\\/?*\\[\\]:]"), " ").trim().ifBlank { "Sheet" }.take(31)
        var n = 2
        while (taken.any { it.equals(t, true) }) t = t.take(28) + " " + n++
        return t
    }

    fun xlsx(sheets: List<Sheet>, out: OutputStream, accent: String = "2F5496") {
        val ac = accent.removePrefix("#").uppercase()
        val list = sheets.ifEmpty { listOf(Sheet("Sheet1", emptyList())) }
        val names = mutableListOf<String>()
        list.forEach { names.add(sheetTitle(it.name, names.toSet())) }
        val z = Zip(out)
        z.put("[Content_Types].xml", XML +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            list.indices.joinToString("") {
                "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            } + "</Types>")
        z.put("_rels/.rels", XML + "<Relationships xmlns=\"$REL_NS\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL_DOC/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
        z.put("xl/workbook.xml", XML +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"$REL_DOC\"><sheets>" +
            names.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") +
            "</sheets></workbook>")
        z.put("xl/_rels/workbook.xml.rels", XML + "<Relationships xmlns=\"$REL_NS\">" +
            list.indices.joinToString("") {
                "<Relationship Id=\"rId${it + 1}\" Type=\"$REL_DOC/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>"
            } +
            "<Relationship Id=\"rId${list.size + 1}\" Type=\"$REL_DOC/styles\" Target=\"styles.xml\"/></Relationships>")
        z.put("xl/styles.xml", XML +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"$FONT_LATIN\"/></font>" +
            "<font><b/><sz val=\"11\"/><name val=\"$FONT_LATIN\"/></font></fonts>" +
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF${BrandPalette.tint("#$ac", 0.88).removePrefix("#")}\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>")
        list.forEachIndexed { i, s -> z.put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        z.finish()
    }

    private fun sheetXml(s: Sheet): String {
        val cols = s.rows.maxOfOrNull { it.size } ?: 0
        val sb = StringBuilder(XML)
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\">")
        if (s.rows.size > 1) sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        sb.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/>")
        if (cols > 0) {
            sb.append("<cols>")
            for (c in 0 until cols) {
                val longest = s.rows.maxOf { (it.getOrNull(c) ?: "").length }
                val w = (longest + 3).coerceIn(10, 60)
                sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$w\" customWidth=\"1\"/>")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        s.rows.forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, raw ->
                val ref = colName(c) + (r + 1)
                val style = if (r == 0 && s.rows.size > 1) " s=\"1\"" else ""
                val num = raw.trim().replace(",", "").toDoubleOrNull()
                when {
                    raw.isEmpty() -> Unit
                    raw.startsWith("=") && raw.length > 1 ->
                        sb.append("<c r=\"$ref\"$style><f>${esc(raw.substring(1))}</f></c>")
                    num != null && num.isFinite() && !raw.trim().startsWith("0") || raw.trim() == "0" ->
                        sb.append("<c r=\"$ref\"$style><v>${raw.trim().replace(",", "")}</v></c>")
                    else ->
                        sb.append("<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(raw)}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    // ---- PPTX ----------------------------------------------------------------

    private const val P_NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"$REL_DOC\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""

    fun pptx(slides: List<Slide>, out: OutputStream, accent: String = "2F5496") {
        val ac = accent.removePrefix("#").uppercase()
        val list = slides.ifEmpty { listOf(Slide("", emptyList())) }
        val z = Zip(out)
        z.put("[Content_Types].xml", XML +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            list.indices.joinToString("") {
                "<Override PartName=\"/ppt/slides/slide${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
            } + "</Types>")
        z.put("_rels/.rels", XML + "<Relationships xmlns=\"$REL_NS\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL_DOC/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>")
        z.put("ppt/presentation.xml", XML + "<p:presentation $P_NS>" +
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>" +
            list.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 2}\"/>" } +
            "</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        z.put("ppt/_rels/presentation.xml.rels", XML + "<Relationships xmlns=\"$REL_NS\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL_DOC/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
            list.indices.joinToString("") {
                "<Relationship Id=\"rId${it + 2}\" Type=\"$REL_DOC/slide\" Target=\"slides/slide${it + 1}.xml\"/>"
            } +
            "<Relationship Id=\"rId${list.size + 2}\" Type=\"$REL_DOC/theme\" Target=\"theme/theme1.xml\"/></Relationships>")
        z.put("ppt/slideMasters/slideMaster1.xml", XML + "<p:sldMaster $P_NS><p:cSld><p:bg><p:bgPr>" +
            "<a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>" +
            "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" " +
            "accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>")
        z.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", XML + "<Relationships xmlns=\"$REL_NS\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL_DOC/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$REL_DOC/theme\" Target=\"../theme/theme1.xml\"/></Relationships>")
        z.put("ppt/slideLayouts/slideLayout1.xml", XML + "<p:sldLayout $P_NS type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\">" +
            "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
        z.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", XML + "<Relationships xmlns=\"$REL_NS\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL_DOC/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>")
        z.put("ppt/theme/theme1.xml", pptTheme())
        list.forEachIndexed { i, s ->
            z.put("ppt/slides/slide${i + 1}.xml", slideXml(s, i == 0 && s.bullets.isEmpty(), ac))
            z.put("ppt/slides/_rels/slide${i + 1}.xml.rels", XML + "<Relationships xmlns=\"$REL_NS\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL_DOC/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>")
        }
        z.finish()
    }

    private fun slideXml(s: Slide, titleSlide: Boolean, accent: String): String {
        val dark = BrandPalette.shade("#$accent", 0.35).removePrefix("#")
        fun rpr(size: Int, bold: Boolean, color: String) =
            "<a:rPr lang=\"en-US\" sz=\"$size\" b=\"${if (bold) 1 else 0}\" dirty=\"0\"><a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill>" +
                "<a:latin typeface=\"$FONT_LATIN\"/><a:cs typeface=\"$FONT_CS\"/></a:rPr>"
        val sb = StringBuilder(XML)
        sb.append("<p:sld $P_NS><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>")
        // accent bar
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Accent\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>" +
            "<a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"12192000\" cy=\"180000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "<a:solidFill><a:srgbClr val=\"$accent\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>")
        if (titleSlide) {
            sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Title\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr>" +
                "<a:xfrm><a:off x=\"914400\" y=\"2200000\"/><a:ext cx=\"10363200\" cy=\"1800000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
                "<p:txBody><a:bodyPr wrap=\"square\" anchor=\"ctr\"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p><a:pPr algn=\"ctr\"/>" +
                "<a:r>${rpr(4400, true, dark)}<a:t>${esc(s.title)}</a:t></a:r></a:p></p:txBody></p:sp>")
        } else {
            sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Title\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr>" +
                "<a:xfrm><a:off x=\"609600\" y=\"380000\"/><a:ext cx=\"10972800\" cy=\"1000000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
                "<p:txBody><a:bodyPr wrap=\"square\" anchor=\"ctr\"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p>" +
                "<a:r>${rpr(3600, true, dark)}<a:t>${esc(s.title)}</a:t></a:r></a:p></p:txBody></p:sp>")
            val size = when { s.bullets.size > 8 -> 1800; s.bullets.size > 5 -> 2200; else -> 2600 }
            sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"4\" name=\"Body\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr>" +
                "<a:xfrm><a:off x=\"609600\" y=\"1550000\"/><a:ext cx=\"10972800\" cy=\"4700000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
                "<p:txBody><a:bodyPr wrap=\"square\"><a:normAutofit/></a:bodyPr><a:lstStyle/>")
            if (s.bullets.isEmpty()) sb.append("<a:p><a:endParaRPr lang=\"en-US\"/></a:p>")
            for (b in s.bullets) {
                sb.append("<a:p><a:pPr marL=\"342900\" indent=\"-342900\"><a:spcBef><a:spcPts val=\"900\"/></a:spcBef>" +
                    "<a:buFont typeface=\"Arial\"/><a:buChar char=\"&#8226;\"/></a:pPr>" +
                    "<a:r>${rpr(size, false, "262626")}<a:t>${esc(b)}</a:t></a:r></a:p>")
            }
            sb.append("</p:txBody></p:sp>")
        }
        sb.append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
        return sb.toString()
    }

    private fun pptTheme(): String {
        fun fill() = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        val line = "<a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>"
        return XML + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Zyraxon\"><a:themeElements>" +
            "<a:clrScheme name=\"Zyraxon\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1>" +
            "<a:dk2><a:srgbClr val=\"1F3864\"/></a:dk2><a:lt2><a:srgbClr val=\"E8EEF7\"/></a:lt2>" +
            "<a:accent1><a:srgbClr val=\"2F5496\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" +
            "<a:accent3><a:srgbClr val=\"70AD47\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
            "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"A5A5A5\"/></a:accent6>" +
            "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
            "<a:fontScheme name=\"Zyraxon\"><a:majorFont><a:latin typeface=\"$FONT_LATIN\"/><a:ea typeface=\"\"/><a:cs typeface=\"$FONT_CS\"/></a:majorFont>" +
            "<a:minorFont><a:latin typeface=\"$FONT_LATIN\"/><a:ea typeface=\"\"/><a:cs typeface=\"$FONT_CS\"/></a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"Zyraxon\"><a:fillStyleLst>${fill()}${fill()}${fill()}</a:fillStyleLst>" +
            "<a:lnStyleLst>$line$line$line</a:lnStyleLst>" +
            "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
            "<a:bgFillStyleLst>${fill()}${fill()}${fill()}</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"
    }
}
