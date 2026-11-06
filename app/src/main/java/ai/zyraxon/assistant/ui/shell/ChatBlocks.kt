package ai.zyraxon.assistant.ui.shell

import ai.zyraxon.assistant.ui.theme.Glass
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
import androidx.compose.ui.text.SpanStyle
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                (if (live) "✍️ " else "") + title.ifBlank { lang.ifBlank { "code" } },
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
    data class Bullet(val text: String, val marker: String) : Block()
    data class Quote(val text: String) : Block()
    data class Para(val text: String) : Block()
    object Rule : Block()
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
        when {
            t.startsWith("### ") -> { flush(); out.add(Block.Heading(3, t.removePrefix("### "))) }
            t.startsWith("## ") -> { flush(); out.add(Block.Heading(2, t.removePrefix("## "))) }
            t.startsWith("# ") -> { flush(); out.add(Block.Heading(1, t.removePrefix("# "))) }
            t.startsWith("- ") || t.startsWith("* ") -> { flush(); out.add(Block.Bullet(t.drop(2), "•")) }
            Regex("^\\d+[.)] .*").matches(t) -> {
                flush(); val m = t.substringBefore(' '); out.add(Block.Bullet(t.substringAfter(' '), m))
            }
            t.startsWith("> ") -> { flush(); out.add(Block.Quote(t.removePrefix("> "))) }
            t == "---" || t == "***" -> { flush(); out.add(Block.Rule) }
            t.isBlank() -> flush()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flush()
    return out
}

private fun inline(text: String, color: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            text.startsWith("**", i) -> {
                val e = text.indexOf("**", i + 2)
                if (e > 0) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, e)) }; i = e + 2 }
                else { append(text[i]); i++ }
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
                if (e > 0) { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, e)) }; i = e + 1 }
                else { append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val mid = text.indexOf("](", i)
                val end = if (mid > 0) text.indexOf(')', mid) else -1
                if (mid > 0 && end > 0) {
                    withStyle(SpanStyle(color = Glass.pink, fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 1, mid)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

/** Renders model text: paragraphs, headings, lists, quotes, inline code and fenced code boxes. */
@Composable
fun MarkdownText(text: String, color: Color = Glass.textPrimary, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = if (Glass.isLight) Color(0x14000000) else Color(0x33FFFFFF)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is Block.Code -> CodeBox(b.text, b.lang, live = b.open, maxHeight = 320)
            is Block.Heading -> Text(
                inline(b.text, color, codeBg), color = color, fontWeight = FontWeight.Bold,
                fontSize = when (b.level) { 1 -> 18.sp; 2 -> 16.sp; else -> 14.5.sp }
            )
            is Block.Bullet -> Row {
                Text(b.marker, color = Glass.pink, fontSize = 14.sp, modifier = Modifier.width(22.dp))
                Text(inline(b.text, color, codeBg), color = color, fontSize = 14.sp, lineHeight = 20.sp)
            }
            is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(Glass.pink))
                Text(inline(b.text, Glass.textSecondary, codeBg), color = Glass.textSecondary, fontSize = 13.5.sp,
                    modifier = Modifier.padding(start = 8.dp))
            }
            is Block.Para -> Text(inline(b.text, color, codeBg), color = color, fontSize = 14.sp, lineHeight = 21.sp)
            Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.borderSoft))
        }
    }
}
