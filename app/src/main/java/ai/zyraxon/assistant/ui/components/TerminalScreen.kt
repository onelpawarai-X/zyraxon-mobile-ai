package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.model.TerminalLine
import ai.zyraxon.assistant.model.TerminalSession
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TerminalBg = Color(0xFF07090C)
private val TerminalChrome = Color(0xFF11151B)
private val TerminalEdge = Color(0xFF2A313C)
private val TerminalPrompt = Color(0xFF43E08A)
private val TerminalText = Color(0xFFD7E1EC)
private val TerminalDim = Color(0xFF7C8798)
private val TerminalError = Color(0xFFFF6B6B)
private val TerminalCyan = Color(0xFF4FD6E8)

/**
 * The Terminal tab: a real black terminal, not a log viewer.
 *
 * The user types a line and it runs through the bundled busybox shell; the
 * assistant runs lines through the very same scrollback. The ✕ in the header
 * returns to the app's main menu without killing the session, so scrollback and
 * working directory are still there when the user comes back.
 *
 * A sidebar on the left lists the open shells. The user can add one, switch
 * between them and close them, and each keeps its own scrollback and working
 * directory — so several tasks can run at once without mixing their output.
 */
@Composable
fun TerminalScreen(
    sessions: List<TerminalSession>,
    activeId: String,
    lines: List<TerminalLine>,
    cwd: String,
    busy: Boolean,
    onRun: (String) -> Unit,
    onClear: () -> Unit,
    onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<String>()) }
    var historyIndex by remember { mutableStateOf(-1) }
    var backdrop by remember { mutableStateOf(true) }
    var sidebar by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    LaunchedEffect(lines.size, busy, activeId) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Box(modifier = modifier.fillMaxSize().background(TerminalBg)) {
        if (backdrop) {
            MatrixRainBackground(Modifier.fillMaxSize())
        }
        Row(modifier = Modifier.fillMaxSize()) {
        if (sidebar) {
            TerminalSidebar(
                sessions = sessions,
                activeId = activeId,
                onNewSession = onNewSession,
                onSelectSession = onSelectSession,
                onCloseSession = onCloseSession,
                onCloseSidebar = { sidebar = false }
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
        // Header — the ✕ is the only way out, back to the app's main menu.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalChrome)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (busy) TerminalCyan else TerminalPrompt)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "☰",
                color = TerminalText,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { sidebar = !sidebar }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "ZYRAXON TERMINAL",
                color = TerminalText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "+",
                color = TerminalPrompt,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onNewSession() }
                    .padding(horizontal = 9.dp, vertical = 1.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (backdrop) "matrix" else "plain",
                color = TerminalDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { backdrop = !backdrop }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "clear",
                color = TerminalDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onClear() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "✕",
                color = TerminalError,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            )
        }

        // Scrollback
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = welcome(),
                    color = TerminalDim,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp
                )
            }
            for (line in lines) {
                TerminalEntry(line)
                Spacer(Modifier.height(10.dp))
            }
            if (busy) {
                Text(
                    text = "▊ running…",
                    color = TerminalCyan,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // The prompt. One line, monospace, Enter runs it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalChrome)
                .border(1.dp, TerminalEdge, RoundedCornerShape(0.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$ ",
                color = TerminalPrompt,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = TerminalText,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(TerminalPrompt),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None
                ),
                keyboardActions = KeyboardActions(onDone = {
                    if (input.isNotBlank()) {
                        history = (history + input).takeLast(100)
                        historyIndex = -1
                        onRun(input)
                        input = ""
                    }
                })
            )
            Text(
                text = "↵",
                color = TerminalDim,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        if (input.isNotBlank()) {
                            history = (history + input).takeLast(100)
                            historyIndex = -1
                            onRun(input)
                            input = ""
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalChrome)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = cwd,
                color = TerminalCyan,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "busybox sh · PATH ~/bin",
                color = TerminalDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        }
        }
    }
}

/**
 * The left sidebar: every open shell as a row, the active one highlighted, and
 * an ✕ to close each. Closing the last shell opens a fresh one instead, so the
 * terminal is never left without somewhere to type.
 */
@Composable
private fun TerminalSidebar(
    sessions: List<TerminalSession>,
    activeId: String,
    onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onCloseSidebar: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(168.dp)
            .fillMaxSize()
            .background(TerminalChrome)
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SHELLS",
                color = TerminalDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "‹",
                color = TerminalText,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onCloseSidebar() }
                    .padding(horizontal = 6.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        for (session in sessions) {
            val active = session.id == activeId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (active) TerminalBg else TerminalChrome)
                    .clickable { onSelectSession(session.id) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (active) "▸ " else "  ",
                    color = TerminalPrompt,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = session.title,
                    color = if (active) TerminalText else TerminalDim,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "✕",
                    color = TerminalDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onCloseSession(session.id) }
                        .padding(horizontal = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "+  new shell",
            color = TerminalPrompt,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onNewSession() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun TerminalEntry(line: TerminalLine) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = TerminalPrompt)) { append("$ ") }
                withStyle(SpanStyle(color = TerminalText)) { append(line.command) }
            },
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 17.sp
        )
        if (line.output.isNotEmpty()) {
            Text(
                text = line.output,
                color = if (line.isError) TerminalError else TerminalText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp
            )
        }
        if (line.exitCode != 0 || line.timedOut) {
            Text(
                text = if (line.timedOut) "exit: timeout" else "exit: ${line.exitCode}",
                color = TerminalError,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun welcome(): String = """
ZYRAXON TERMINAL — a real Unix shell on this phone.

~400 busybox commands are on PATH: sh, awk, sed, grep, tar, wget, curl,
find, xargs, sort, xxd, base64, gzip, diff, md5sum, and the rest.

git clone / git pull / git status work too (served by JGit), so you can
fetch a repository straight into ~. Example:
  git clone https://github.com/onelpawarai-X/zyraxon-mobile-ai

Working directory starts at ~ (the app's own storage). The assistant can
run lines here too, in this same shell.

Try:   busybox --list | head -40
""".trimIndent()
