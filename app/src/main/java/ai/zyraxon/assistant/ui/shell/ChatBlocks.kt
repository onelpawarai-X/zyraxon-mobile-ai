// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.shell

import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import ai.zyraxon.assistant.ui.theme.Glass
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours for code, independent of the app theme: code is always read on a dark pane. */
internal object CodeColors {
    val bg = Color(0xFF0D1117)
    val bar = Color(0xFF161B22)
    val text = Color(0xFFE6EDF3)
    val dim = Color(0xFF7D8590)
    val keyword = Color(0xFFFF7B72)
    val string = Color(0xFFA5D6FF)
    val comment = Color(0xFF8B949E)
    val number = Color(0xFF79C0FF)
    val green = Color(0xFF3FB950)
    val add = Color(0x223FB950)
}

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "return", "if", "else", "for", "while", "when", "import", "package",
    "function", "const", "let", "async", "await", "export", "default", "new", "this", "def", "from", "as", "in",
    "is", "not", "and", "or", "try", "catch", "except", "finally", "throw", "public", "private", "static", "void",
    "int", "string", "bool", "true", "false", "null", "None", "True", "False", "select", "where", "interface",
    "extends", "implements", "struct", "enum", "type", "use", "pub", "mut", "impl", "fn", "go", "func", "range"
)

/** A light syntax colouring: strings, comments, numbers and common keywords. */
internal fun highlight(line: String, lang: String): AnnotatedString {
    if (line.length > 600) return AnnotatedString(line)
    val l = lang.lowercase()
    val commentStart = when (l) {
        "py", "python", "sh", "bash", "shell", "yaml", "yml", "toml", "rb", "ruby", "dockerfile" -> "#"
        "html", "xml", "svg" -> "<!--"
        "css" -> "/*"
        else -> "//"
    }
    return buildAnnotatedString {
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            if (line.startsWith(commentStart, i)) {
                withStyle(SpanStyle(color = CodeColors.comment, fontStyle = FontStyle.Italic)) { append(line.substring(i)) }
                return@buildAnnotatedString
            }
            if (c == '"' || c == '\'' || c == '`') {
 var j = i + 1
 while (j < n && line[j] != c) { if (line[j] == '\\') j++; j++ }
 j = minOf(j + 1, n)
 withStyle(SpanStyle(color = CodeColors.string)) { append(line.substring(i, j)) }
 i = j
 } else if (c.isDigit()) {
 var j = i
 while (j < n && (line[j].isDigit() || line[j] == '.')) j++
 withStyle(SpanStyle(color = CodeColors.number)) { append(line.substring(i, j)) }
 i = j
 } else if (c.isLetter() || c == '_') {
 var j = i
 while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
 val w = line.substring(i, j)
 if (w in KEYWORDS) withStyle(SpanStyle(color = CodeColors.keyword)) { append(w) } else append(w)
 i = j
 } else { append(c); i++ }
 }
 }
}

/** A code box: header with the language or path and a copy button, then numbered lines. */
@Composable
fun CodeBox(
 code: String,
 lang: String = "",
 title: String = "",
 live: Boolean = false,
 modifier: Modifier = Modifier,
 maxHeight: Int = 280
) {
 val clip = LocalClipboardManager.current
 val ctx = LocalContext.current
 val lines = remember(code) { code.split('\n') }
 val vScroll = rememberScrollState()
 val hScroll = rememberScrollState()
 LaunchedEffect(code.length, live) { if (live) vScroll.scrollTo(vScroll.maxValue) }
 Column(
 modifier
 .fillMaxWidth()
 .clip(RoundedCornerShape(12.dp))
 .background(CodeColors.bg)
 .border(1.dp, if (live) CodeColors.green.copy(alpha = 0.7f) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
 ) {
 Row(
 Modifier.fillMaxWidth().background(CodeColors.bar).padding(horizontal = 10.dp, vertical = 5.dp),
 verticalAlignment = Alignment.CenterVertically,
 horizontalArrangement = Arrangement.spacedBy(5.dp)
 ) {
 if (live) AppIcon(AppIcons.editNote, null, size = 13.dp, tint = CodeColors.green)
 Text(
 title.ifBlank { lang.ifBlank { "code" } },
 color = CodeColors.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
 maxLines = 1, modifier = Modifier.weight(1f)
 )
 if (live) Text(ai.zyraxon.assistant.i18n.t("লিখছে… {0} লাইন", lines.size), color = CodeColors.green, fontSize = 10.5.sp)
 else Text(
 ai.zyraxon.assistant.i18n.t("কপি"), color = CodeColors.dim, fontSize = 11.sp,
 modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
 clip.setText(AnnotatedString(code))
 android.widget.Toast.makeText(ctx, ai.zyraxon.assistant.i18n.t("কপি হয়েছে"), android.widget.Toast.LENGTH_SHORT).show()
 }.padding(horizontal = 6.dp, vertical = 2.dp)
 )
 }
 Box(Modifier.heightIn(max = maxHeight.dp).verticalScroll(vScroll)) {
 Row(Modifier.horizontalScroll(hScroll).padding(vertical = 6.dp)) {
 Column(Modifier.padding(start = 8.dp, end = 8.dp)) {
 // Only the last 400 lines are numbered and coloured while typing; the box stays smooth.
 val shown = if (live && lines.size > 400) lines.takeLast(400) else lines
 val offset = lines.size - shown.size
 shown.forEachIndexed { idx, line ->
 Row {
 Text(
 "${offset + idx + 1}".padStart(3), color = CodeColors.dim.copy(alpha = 0.6f),
 fontSize = 11.sp, fontFamily = FontFamily.Monospace
 )
 Spacer(Modifier.width(8.dp))
 Text(
 if (live) AnnotatedString(line) else highlight(line, lang),
 color = CodeColors.text, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
 softWrap = false
 )
 }
 }
 }
 }
 }
 }
}

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
internal fun stripAnsi(s: String): String = ANSI.replace(s, "")

/** A terminal-style box: the command after a prompt, then its output. */
@Composable
fun TerminalBox(command: String, output: String, running: Boolean, ok: Boolean = true, modifier: Modifier = Modifier) {
 val vScroll = rememberScrollState()
 LaunchedEffect(output.length) { if (running) vScroll.scrollTo(vScroll.maxValue) }
 Column(
 modifier
 .fillMaxWidth()
 .clip(RoundedCornerShape(12.dp))
 .background(Color(0xFF0A0A0C))
 .border(1.dp, if (running) CodeColors.green.copy(alpha = 0.7f) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
 .padding(10.dp)
 ) {
 Row(verticalAlignment = Alignment.Top) {
 Text("$ ", color = CodeColors.green, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
 Text(command, color = Color(0xFFE6EDF3), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
 }
 if (output.isNotBlank()) {
 Spacer(Modifier.height(6.dp))
 Box(Modifier.heightIn(max = 220.dp).verticalScroll(vScroll)) {
 Text(
 stripAnsi(output.takeLast(6000)),
 color = if (ok) Color(0xFFB8C0CC) else Color(0xFFFF8B8B),
 fontSize = 11.sp, fontFamily = FontFamily.Monospace
 )
 }
 }
 if (running) Text("▍", color = CodeColors.green, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
 }
}

// ----------------------------------------------------------------- markdown ---

private sealed class Block {
 data class Code(val lang: String, val text: String, val open: Boolean) : Block()
 data class Heading(val level: Int, val text: String) : Block()
 data class Bullet(val text: String, val marker: String, val depth: Int, val checked: Boolean?) : Block()
 data class Quote(val text: String) : Block()
 data class Para(val text: String) : Block()
 /** A pipe table: the header cells and every body row, already split. */
 data class Table(val header: List<String>, val rows: List<List<String>>) : Block()
 object Rule : Block()
}

/** Splits one `| a | b |` line into its cells, dropping the outer pipes. */
private fun tableCells(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.dropLast(1)
    return s.split('|').map { it.trim() }
}

/**
 * The `|---|:--:|` line under a table header. A line of dashes and colons is a
 * separator; anything else means the pipe line above it was ordinary text.
 */
private fun isTableSeparator(line: String): Boolean {
    val cells = tableCells(line)
    if (cells.isEmpty()) return false
    return cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } }
}

/** A leading run of `- `, `* `, `+ ` or `1. `, with the indent depth it sat at. */
private fun bulletAt(line: String): Triple<String, Int, String>? {
    val indent = line.takeWhile { it == ' ' || it == '\t' }.let { it.count { c -> c == ' ' } + it.count { c -> c == '\t' } * 4 }
    val t = line.trimStart()
    for (p in listOf("- ", "* ", "+ ")) {
        if (t.startsWith(p)) return Triple(t.drop(p.length), indent / 2, "•")
    }
    val m = Regex("^(\\d+)[.)] +(.*)").find(t) ?: return null
    return Triple(m.groupValues[2], indent / 2, m.groupValues[1] + ".")
}

private fun parseBlocks(src: String): List<Block> {
 val out = mutableListOf<Block>()
 val lines = src.split('\n')
 var i = 0
 val para = StringBuilder()
 fun flush() { if (para.isNotBlank()) out.add(Block.Para(para.toString().trim())); para.setLength(0) }
 while (i < lines.size) {
 val line = lines[i]
 val t = line.trimStart()
 if (t.startsWith("```")) {
 flush()
 val lang = t.removePrefix("```").trim()
 val body = StringBuilder()
 i++
 var closed = false
 while (i < lines.size) {
 if (lines[i].trimStart().startsWith("```")) { closed = true; break }
 body.append(lines[i]).append('\n'); i++
 }
 out.add(Block.Code(lang, body.toString().trimEnd('\n'), !closed))
 i++
 continue
 }
 // A pipe table: a header row, a separator row, then body rows. Models emit
 // these constantly for comparisons, and without this the pipes and dashes
 // were rendered as literal text — the broken "box" the user reported.
 if (t.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
 flush()
 val header = tableCells(t)
 val rows = mutableListOf<List<String>>()
 i += 2
 while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
 rows.add(tableCells(lines[i])); i++
 }
 out.add(Block.Table(header, rows))
 continue
 }
 when {
 t.startsWith("### ") -> { flush(); out.add(Block.Heading(3, t.removePrefix("### "))) }
 t.startsWith("## ") -> { flush(); out.add(Block.Heading(2, t.removePrefix("## "))) }
 t.startsWith("# ") -> { flush(); out.add(Block.Heading(1, t.removePrefix("# "))) }
 bulletAt(line) != null -> {
 flush()
 val (body, depth, marker) = bulletAt(line)!!
 val box = Regex("^\\[( |x|X)\\] +(.*)").find(body)
 if (box != null) out.add(Block.Bullet(box.groupValues[2], "☑", depth, box.groupValues[1].lowercase() == "x"))
 else out.add(Block.Bullet(body, marker, depth, null))
 }
 t.startsWith("> ") -> { flush(); out.add(Block.Quote(t.removePrefix("> "))) }
 t == "---" || t == "***" || t == "___" -> { flush(); out.add(Block.Rule) }
 t.isBlank() -> flush()
 else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
 }
 i++
 }
 flush()
 return out
}

@Composable
private fun TableRow(cells: List<String>, columns: Int, header: Boolean, color: Color, codeBg: Color, onOpen: (String) -> Unit) {
    val cellBg = if (Glass.isLight) Color(0x0D000000) else Color(0x1AFFFFFF)
    val headBg = if (Glass.isLight) Color(0x1A000000) else Color(0x33FFFFFF)
    Row(Modifier.background(if (header) headBg else Color.Transparent)) {
        for (c in 0 until columns) {
            val cell = cells.getOrNull(c).orEmpty()
            Box(
                Modifier
                    .width(150.dp)
                    .border(0.5.dp, Glass.borderSoft)
                    .background(if (header) headBg else cellBg)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    inline(cell, color, codeBg, onOpen), color = color,
                    fontSize = 12.5.sp, lineHeight = 17.sp,
                    fontWeight = if (header) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * A pipe table drawn as a real table. Cells wrap instead of clipping, the header
 * is tinted, and the whole thing scrolls sideways when it is wider than the chat
 * — a table that runs off the screen is the same as no table at all.
 */
@Composable
private fun TableBlock(block: Block.Table, color: Color, codeBg: Color, onOpen: (String) -> Unit) {
    val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val hScroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Glass.borderSoft, RoundedCornerShape(10.dp))
            .horizontalScroll(hScroll)
    ) {
        TableRow(block.header, columns, true, color, codeBg, onOpen)
        block.rows.forEach { TableRow(it, columns, false, color, codeBg, onOpen) }
    }
}

private val BARE_URL = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\"']+")

/** A URL starting exactly at [index], or null. Trailing sentence punctuation is dropped. */
private fun urlAt(text: String, index: Int): Pair<String, String>? {
    val m = BARE_URL.find(text, index) ?: return null
    if (m.range.first != index) return null
    var raw = m.value
    while (raw.isNotEmpty() && raw.last() in ".,;:!?)]}।॥—") raw = raw.dropLast(1)
    if (raw.length <= 8) return null
    val href = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
    return raw to href
}

/**
 * Appends [label] and marks that range as a link to [href]. The listener is left
 * null so Compose's own default applies: a tap goes to the platform, which opens
 * the address in the phone's real browser.
 */
private fun AnnotatedString.Builder.appendLink(label: String, href: String, onOpen: (String) -> Unit) {
    val style = SpanStyle(color = Glass.pink, fontWeight = FontWeight.SemiBold)
    val start = length
    withStyle(style) { append(label) }
    val listener = object : LinkInteractionListener {
        override fun onClick(link: LinkAnnotation) = onOpen(href)
    }
    addLink(LinkAnnotation.Url(href, TextLinkStyles(style), listener), start, length)
}

private fun inline(text: String, color: Color, codeBg: Color, onOpen: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            text.startsWith("**", i) -> {
                val e = text.indexOf("**", i + 2)
                if (e > 0) {
                    // Recurse, so a link inside a bold span is still a link: the
                    // model writes "**https://…**" as often as it writes it bare.
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(text.substring(i + 2, e), color, codeBg, onOpen)) }
                    i = e + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val e = text.indexOf('`', i + 1)
                if (e > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = color)) { append(" " + text.substring(i + 1, e) + " ") }
                    i = e + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '*' && i + 1 < n && text[i + 1] != ' ' -> {
                val e = text.indexOf('*', i + 1)
                if (e > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(text.substring(i + 1, e), color, codeBg, onOpen)) }
                    i = e + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val mid = text.indexOf("](", i)
                val end = if (mid > 0) text.indexOf(')', mid) else -1
                if (mid > 0 && end > 0) {
                    appendLink(text.substring(i + 1, mid), text.substring(mid + 2, end), onOpen)
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> {
                // A bare address the model wrote ("https://…", "www.…") is a link
                // too, not plain text. It is left as written and made tappable.
                val link = urlAt(text, i)
                if (link != null) { appendLink(link.first, link.second, onOpen); i += link.first.length }
                else { append(text[i]); i++ }
            }
        }
    }
}

/** Renders model text: paragraphs, headings, lists, quotes, inline code and fenced code boxes. */
@Composable
fun MarkdownText(text: String, color: Color = Glass.textPrimary, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // A tap opens the address in the phone's real browser (Chrome, or whatever
    // the user set as default) — never in the app's own preview, which is for the
    // pages the agent is building. NEW_TASK because the chat can be drawn from the
    // overlay service, where there is no activity to attach the intent to.
    val onOpen: (String) -> Unit = remember(context) {
        { href ->
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(href))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = if (Glass.isLight) Color(0x14000000) else Color(0x33FFFFFF)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is Block.Code -> CodeBox(b.text, b.lang, live = b.open, maxHeight = 320)
            is Block.Heading -> Text(
                inline(b.text, color, codeBg, onOpen), color = color, fontWeight = FontWeight.Bold,
                fontSize = when (b.level) { 1 -> 18.sp; 2 -> 16.sp; else -> 14.5.sp }
            )
            is Block.Bullet -> Row(Modifier.padding(start = (b.depth.coerceAtMost(4) * 14).dp)) {
                Text(b.marker, color = Glass.pink, fontSize = 14.sp, modifier = Modifier.width(22.dp))
                Text(inline(b.text, color, codeBg, onOpen), color = color, fontSize = 14.sp, lineHeight = 20.sp)
            }
            is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(Glass.pink))
                Text(inline(b.text, Glass.textSecondary, codeBg, onOpen), color = Glass.textSecondary, fontSize = 13.5.sp,
                    modifier = Modifier.padding(start = 8.dp))
            }
            is Block.Table -> TableBlock(b, color, codeBg, onOpen)
            is Block.Para -> {
                Text(inline(b.text, color, codeBg, onOpen), color = color, fontSize = 14.sp, lineHeight = 21.sp)
                mediaLinks(b.text).forEach { (kind, url) -> MediaCard(kind, url) }
            }
            Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.borderSoft))
        }
    }
}


private val MEDIA_URL = Regex("https?://[^\\s)>\\]\"']+", RegexOption.IGNORE_CASE)

/** The video / audio / image addresses in a piece of text, in order, once each. */
private fun mediaLinks(text: String): List<Pair<String, String>> =
    MEDIA_URL.findAll(text).map { it.value.trimEnd('.', ',', ';', '!', '?') }.distinct().mapNotNull { u ->
        val path = u.substringBefore('?').substringBefore('#').lowercase()
        when {
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv") || path.endsWith(".mov") || path.endsWith(".3gp") -> "video" to u
            path.endsWith(".mp3") || path.endsWith(".wav") || path.endsWith(".m4a") || path.endsWith(".ogg") || path.endsWith(".aac") -> "audio" to u
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".webp") || path.endsWith(".gif") -> "image" to u
            else -> null
        }
    }.toList()

private fun downloadMedia(context: android.content.Context, url: String) {
    runCatching {
        val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "zyraxon_media" }
        val req = android.app.DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
        (context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
        android.widget.Toast.makeText(context, name, android.widget.Toast.LENGTH_SHORT).show()
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** A picture, video or sound the agent linked to, shown in the chat with play and download. */
@Composable
private fun MediaCard(kind: String, url: String) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass.rowFill(0.30f))
            .border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp)).padding(6.dp)
    ) {
        when (kind) {
            "image" -> coil.compose.AsyncImage(
                url, null, Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
            else -> androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxWidth().height(if (kind == "video") 230.dp else 70.dp),
                factory = { c ->
                    val vv = android.widget.VideoView(c)
                    val mc = android.widget.MediaController(c)
                    mc.setAnchorView(vv)
                    vv.setMediaController(mc)
                    vv.setVideoURI(Uri.parse(url))
                    vv.setOnPreparedListener { it.isLooping = false; vv.seekTo(1) }
                    vv
                },
                onRelease = { it.stopPlayback() }
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Glass.pink.copy(alpha = 0.18f))
                    .clickable { downloadMedia(context, url) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { Text(ai.zyraxon.assistant.i18n.Loc.t("ডাউনলোড"), color = Glass.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Glass.rowFill(0.45f))
                    .clickable {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { Text(ai.zyraxon.assistant.i18n.Loc.t("খোলো"), color = Glass.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
