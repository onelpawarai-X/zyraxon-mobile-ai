package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.model.TerminalLine
import ai.zyraxon.assistant.model.TerminalSession
import ai.zyraxon.assistant.shell.AnsiText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.delay
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TerminalBg = Color(0xFF040001)
private val TerminalChrome = Color(0xFF1C0208)
private val TerminalEdge = Color(0xFF8A0C20)
private val TerminalPrompt = Color(0xFFFF1E44)
private val TerminalText = Color(0xFFF5E4E8)
private val TerminalDim = Color(0xFF9E6572)
private val TerminalError = Color(0xFFFF2244)
private val TerminalCyan = Color(0xFFFF4D6D)

/**
 * The Terminal tab: a real terminal, wearing a blood-red skin.
 *
 * What it does is exactly what a terminal does: one continuous black surface of
 * monospace text, each command echoed on a prompt line with its output directly
 * beneath it, the live prompt always the last line, a block cursor, long-press
 * to select and copy, ↑/↓ for history, and a row of the symbols a phone keyboard
 * hides (`/ ~ | > $`). Lines run through the bundled busybox shell (and the
 * ZYRAXON Linux system once installed) — the assistant runs lines through the
 * very same scrollback.
 *
 * The horror is full strength: falling code over blood veins, splatter, eyes
 * that watch from the dark, scanlines, a heartbeat-pulsing vignette and border,
 * dripping blood, a glitching title. The text sits on a dark glass panel so it
 * still reads like any real terminal. No fake system claims — the only facts
 * shown are real: the working directory and the busy state.
 *
 * The ✕ returns to the main menu without killing the session, so scrollback and
 * working directory are still there when the user comes back. A sidebar lists the
 * open shells; each keeps its own scrollback and working directory.
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
    var veins by remember { mutableStateOf(true) }
    // Pure atmosphere: a line that changes under the cursor. Nothing here claims
    // anything about the system — the only facts shown are cwd and busy state.
    val whispers = remember {
        listOf("☠ BLOOD MODE", "IT IS WATCHING", "YOU ARE NOT ALONE", "THE SHELL REMEMBERS", "DO NOT STOP TYPING", "IT HUNGERS FOR COMMANDS")
    }
    var whisper by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3800)
            whisper = (whisper + 1) % whispers.size
        }
    }
    var sidebar by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val promptText = "root@zyraxon:$cwd# "

    // Follow the output as it streams in, not only when a new line is added.
    val tailLength = lines.lastOrNull()?.output?.length ?: 0
    LaunchedEffect(lines.size, tailLength, busy, activeId) {
        scroll.scrollTo(scroll.maxValue)
    }
    // The prompt is back when a command ends, so the keyboard is too.
    LaunchedEffect(busy, activeId) {
        if (!busy) runCatching { focus.requestFocus() }
    }

    fun submit() {
        val line = input
        if (line.isBlank()) return
        history = (history + line).takeLast(100)
        historyIndex = -1
        input = ""
        onRun(line)
    }

    fun recall(step: Int) {
        if (history.isEmpty()) return
        val next = when {
            step < 0 && historyIndex == -1 -> history.lastIndex
            step < 0 -> maxOf(0, historyIndex - 1)
            historyIndex == -1 -> -1
            historyIndex + 1 > history.lastIndex -> -1
            else -> historyIndex + 1
        }
        historyIndex = next
        input = if (next == -1) "" else history[next]
    }

    Box(modifier = modifier.fillMaxSize().background(TerminalBg)) {
        if (veins) {
            MatrixRainBackground(Modifier.fillMaxSize())
            BloodVeins(Modifier.fillMaxSize(), seed = 9)
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
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
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
                        text = "☠ ZYRAXON TERMINAL ☠",
                        color = BoneWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.5.sp,
                        style = TextStyle(shadow = BloodGlow),
                        modifier = Modifier.horrorFlicker().glitchShake()
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
                    Text(
                        text = if (veins) "gore:on" else "gore:off",
                        color = TerminalDim,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { veins = !veins }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
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

                // The terminal surface. Scrollback and the live prompt are one
                // continuous column of text; a tap anywhere brings the keyboard up.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0x99030001))
                        .verticalScroll(scroll)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { runCatching { focus.requestFocus() } }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    SelectionContainer {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (lines.isEmpty()) {
                                Text(
                                    text = welcomeBanner(),
                                    color = TerminalPrompt,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 11.sp,
                                    style = TextStyle(shadow = BloodGlowSoft)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = welcomeBody(),
                                    color = TerminalDim,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            for (line in lines) {
                                TerminalEntry(line)
                            }
                        }
                    }
                    // A terminal shows no prompt while a command is still running.
                    if (!busy) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "☠ $promptText",
                                color = TerminalPrompt,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 17.sp,
                                style = TextStyle(shadow = BloodGlowSoft)
                            )
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focus),
                                textStyle = TextStyle(
                                    color = TerminalText,
                                    fontSize = 12.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 17.sp
                                ),
                                cursorBrush = SolidColor(TerminalPrompt),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    imeAction = ImeAction.Send,
                                    capitalization = KeyboardCapitalization.None
                                ),
                                keyboardActions = KeyboardActions(onSend = { submit() })
                            )
                            Text(
                                text = "↵ UNLEASH",
                                color = TerminalPrompt,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                style = TextStyle(shadow = BloodGlowSoft),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x33FF1E44))
                                    .bloodPulseBorder(RoundedCornerShape(6.dp), 0.8.dp)
                                    .clickable { submit() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // The symbols a phone keyboard buries, plus history. Like Termux's
                // extra-keys row — real keys, no fake commands.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TerminalChrome)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val keys = listOf("↑", "↓", "/", "~", "-", "_", "|", ">", "<", "&", "$", "*", ".", "\"", "'", "\\")
                    for (key in keys) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0x33FF1E44))
                                .bloodPulseBorder(RoundedCornerShape(5.dp), 0.7.dp)
                                .clickable {
                                    when (key) {
                                        "↑" -> recall(-1)
                                        "↓" -> recall(1)
                                        else -> input += key
                                    }
                                    runCatching { focus.requestFocus() }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = key,
                                color = TerminalText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                // Real facts (the working directory) next to the whisper.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TerminalChrome)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "CWD: $cwd",
                        color = TerminalCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "· ${whispers[whisper]}",
                        color = TerminalPrompt,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        style = TextStyle(shadow = BloodGlowSoft)
                    )
                }
            }
        }
        // The horror: everything below is drawn on top, takes no touches, and is
        // layered so the middle stays readable while the edges are consumed.
        BloodSplatter(Modifier.fillMaxSize(), seed = 31)
        WatchingEyes(Modifier.fillMaxSize(), seed = 77, pairs = 4)
        Scanlines(Modifier.fillMaxSize())
        HorrorVignette(Modifier.fillMaxSize(), intensity = 1.3f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .bloodPulseBorder(RoundedCornerShape(0.dp), 3.dp)
        )
        BloodDrips(Modifier.align(Alignment.TopStart), seed = 21, count = 20, maxLength = 90.dp)
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

/**
 * One command and what it printed: the prompt line, then the output directly
 * under it with no card, no gap and no label — the way a real terminal's
 * scrollback reads. A command that printed nothing shows nothing.
 */
@Composable
private fun TerminalEntry(line: TerminalLine) {
    val quiet = line.output.isEmpty() || line.output == "(no output)"
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = TerminalPrompt, fontWeight = FontWeight.Bold)) { append("☠ root@zyraxon# ") }
                withStyle(SpanStyle(color = TerminalText)) { append(line.command) }
            },
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 17.sp
        )
        if (!quiet) {
            // The output is parsed for the colour codes a real tool prints, so
            // `ls --color` and `git status` look the way they do in any terminal
            // instead of showing raw escape bytes.
            Text(
                text = buildAnnotatedString {
                    for (span in AnsiText.spans(line.output)) {
                        val style = SpanStyle(
                            color = span.color?.let { Color(it) }
                                ?: if (line.isError) TerminalError else TerminalText,
                            fontWeight = if (span.bold) FontWeight.Bold else null
                        )
                        withStyle(style) { append(span.text) }
                    }
                },
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp
            )
        }
        if (line.running) {
            // A block marks a command that is still printing, exactly like a real
            // terminal's cursor — the output above keeps growing.
            Text(
                text = "█",
                color = TerminalPrompt,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp,
                modifier = Modifier.horrorFlicker()
            )
        }
        if (!line.running && line.timedOut) {
            Text(
                text = "[timed out]",
                color = TerminalError,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun welcomeBanner(): String = """
   ███████╗██╗   ██╗██████╗  █████╗ ██╗  ██╗ ██████╗ ███╗   ██╗
   ╚══███╔╝╚██╗ ██╔╝██╔══██╗██╔══██╗╚██╗██╔╝██╔═══██╗████╗  ██║
     ███╔╝  ╚████╔╝ ██████╔╝███████║ ╚███╔╝ ██║   ██║██╔██╗ ██║
    ███╔╝    ╚██╔╝  ██╔══██╗██╔══██║ ██╔██╗ ██║   ██║██║╚██╗██║
   ███████╗   ██║   ██║  ██║██║  ██║██╔╝ ██╗╚██████╔╝██║ ╚████║
   ╚══════╝   ╚═╝   ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═══╝
""".trimIndent()

private fun welcomeBody(): String = """
ZYRAXON TERMINAL — a real Unix shell on this phone.

~400 busybox commands are on PATH: sh, awk, sed, grep, tar, wget, curl,
find, xargs, sort, xxd, base64, gzip, diff, md5sum, and the rest.

git clone / git pull / git status work too (served by JGit), so you can
fetch a repository straight into ~. Example:
  git clone https://github.com/onelpawarai-X/zyraxon-mobile-ai

The full ZYRAXON Linux system is one command away — a complete Unix
userland with bash, apt, git, python, node and clang, installed straight
from the APK, so it needs no network at all:
  linux-install

Working directory starts at ~ (the app's own storage). The assistant can
run lines here too, in this same shell.

Try:   busybox --list | head -40
""".trimIndent()
