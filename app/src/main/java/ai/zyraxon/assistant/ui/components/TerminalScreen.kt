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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import com.zyraxon.assistant.R
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
 * The Terminal's typeface. A monospace face carries the code, but no monospace
 * font has Bengali glyphs, so Bengali text would fall back to a face that does
 * not join the letters and renders as garbage. Both are therefore listed: the
 * monospace first for ASCII, and Noto Sans Bengali after it for anything the
 * first font does not cover. Compose falls through per glyph, so `echo` stays
 * monospaced while `বাংলা` is shaped correctly.
 */
private val TerminalFont: FontFamily by lazy {
    FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal, FontStyle.Normal),
        Font(R.font.noto_sans_bengali, FontWeight.Normal, FontStyle.Normal),
    )
}

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
    var input by remember { mutableStateOf(TextFieldValue("")) }
    // 0 = the phone keyboard, 1 = the terminal's own keyboard (no system IME).
    var kbMode by remember { mutableStateOf(0) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf(listOf<String>()) }
    var historyIndex by remember { mutableStateOf(-1) }
    var fx by remember { mutableStateOf(true) }
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

    fun setText(text: String, cursor: Int = text.length) {
        input = TextFieldValue(text, TextRange(cursor.coerceIn(0, text.length)))
    }

    fun submit() {
        val line = input.text
        if (line.isBlank()) return
        history = (history + line).takeLast(100)
        historyIndex = -1
        setText("")
        ctrl = false
        alt = false
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
        setText(if (next == -1) "" else history[next])
    }

    fun insert(text: String) {
        val t = input.text
        val a = minOf(input.selection.start, input.selection.end)
        val b = maxOf(input.selection.start, input.selection.end)
        setText(t.substring(0, a) + text + t.substring(b), a + text.length)
    }

    fun backspace() {
        val t = input.text
        val a = minOf(input.selection.start, input.selection.end)
        val b = maxOf(input.selection.start, input.selection.end)
        if (a != b) setText(t.removeRange(a, b), a)
        else if (a > 0) setText(t.removeRange(a - 1, a), a - 1)
    }

    fun moveCursor(pos: Int) {
        input = input.copy(selection = TextRange(pos.coerceIn(0, input.text.length)))
    }

    fun wordBack(from: Int): Int {
        val t = input.text
        var i = from.coerceIn(0, t.length)
        while (i > 0 && t[i - 1] == ' ') i--
        while (i > 0 && t[i - 1] != ' ') i--
        return i
    }

    fun wordForward(from: Int): Int {
        val t = input.text
        var i = from.coerceIn(0, t.length)
        while (i < t.length && t[i] == ' ') i++
        while (i < t.length && t[i] != ' ') i++
        return i
    }

    // Tab completion against the real directory listing. Only when the working
    // directory is a real absolute path; otherwise Tab does nothing, honestly.
    fun complete() {
        val t = input.text
        val cur = input.selection.start.coerceIn(0, t.length)
        val before = t.substring(0, cur)
        val tokStart = before.lastIndexOf(' ') + 1
        val tok = before.substring(tokStart)
        val slash = tok.lastIndexOf('/')
        val dirPart = if (slash >= 0) tok.substring(0, slash + 1) else ""
        val prefix = tok.substring(slash + 1)
        val dir = runCatching {
            when {
                dirPart.startsWith("/") -> java.io.File(dirPart)
                cwd.startsWith("/") -> java.io.File(cwd, dirPart)
                else -> null
            }
        }.getOrNull()
        if (dir == null || !dir.isDirectory) return
        val names = (dir.list() ?: emptyArray()).filter { it.startsWith(prefix) }.sorted()
        if (names.isEmpty()) return
        var common = names.first()
        for (n in names) {
            var k = 0
            while (k < common.length && k < n.length && common[k] == n[k]) k++
            common = common.substring(0, k)
        }
        val tail = if (names.size == 1) {
            if (java.io.File(dir, names[0]).isDirectory) "/" else " "
        } else ""
        val completed = dirPart + common + tail
        setText(t.substring(0, tokStart) + completed + t.substring(cur), tokStart + completed.length)
    }

    fun ctrlKey(ch: Char) {
        val t = input.text
        val cur = input.selection.start.coerceIn(0, t.length)
        when (ch.lowercaseChar()) {
            'c' -> setText("")
            'u' -> setText(t.substring(cur), 0)
            'k' -> setText(t.substring(0, cur))
            'a' -> moveCursor(0)
            'e' -> moveCursor(t.length)
            'l' -> onClear()
            'w' -> { val st = wordBack(cur); setText(t.removeRange(st, cur), st) }
            'p' -> recall(-1)
            'n' -> recall(1)
            'd' -> if (t.isEmpty()) onClose()
            else -> Unit
        }
    }

    fun typeKey(label: String) {
        val ch = label.singleOrNull()
        if (ctrl && ch != null && ch.isLetter()) {
            ctrlKey(ch)
            ctrl = false
            return
        }
        if (alt && ch != null && ch.isLetter()) {
            val cur = input.selection.start
            when (ch.lowercaseChar()) {
                'b' -> moveCursor(wordBack(cur))
                'f' -> moveCursor(wordForward(cur))
                'd' -> { val e = wordForward(cur); setText(input.text.removeRange(cur.coerceAtMost(e), e), cur.coerceAtMost(e)) }
                else -> insert(label)
            }
            alt = false
            return
        }
        insert(if (shift) label.uppercase() else label)
        if (shift) shift = false
    }

    fun pressExtra(key: String) {
        val cur = input.selection.start
        when (key) {
            "ESC" -> { setText(""); ctrl = false; alt = false }
            "TAB" -> complete()
            "CTRL" -> ctrl = !ctrl
            "ALT" -> alt = !alt
            "↑" -> recall(-1)
            "↓" -> recall(1)
            "←" -> moveCursor(if (alt) wordBack(cur) else cur - 1)
            "→" -> moveCursor(if (alt) wordForward(cur) else cur + 1)
            "HOME" -> moveCursor(0)
            "END" -> moveCursor(input.text.length)
            "PGUP" -> scope.launch { scroll.animateScrollBy(-600f) }
            "PGDN" -> scope.launch { scroll.animateScrollBy(600f) }
            else -> typeKey(key)
        }
        if (kbMode == 0) runCatching { focus.requestFocus() }
    }


    Box(modifier = modifier.fillMaxSize().background(TerminalBg)) {
        if (fx) {
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
                // Header — a real terminal's title bar: the shells as tabs, the
                // keyboard switch, and the ✕ back to the main menu.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TerminalChrome)
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "☰",
                        color = TerminalText,
                        fontSize = 15.sp,
                        fontFamily = TerminalFont,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { sidebar = !sidebar }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (session in sessions) {
                            val active = session.id == activeId
                            Text(
                                text = (if (active && busy) "● " else if (active) "☠ " else "") + session.title,
                                color = if (active) BoneWhite else TerminalDim,
                                fontSize = 11.5.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = TerminalFont,
                                maxLines = 1,
                                style = if (active) TextStyle(shadow = BloodGlowSoft) else TextStyle.Default,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (active) Color(0x55FF1E44) else Color.Transparent)
                                    .border(
                                        0.8.dp,
                                        if (active) TerminalPrompt else Color(0x338A0C20),
                                        RoundedCornerShape(7.dp)
                                    )
                                    .clickable { onSelectSession(session.id) }
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                        Text(
                            text = "+",
                            color = TerminalPrompt,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = TerminalFont,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onNewSession() }
                                .padding(horizontal = 9.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = if (kbMode == 0) "⌨" else "⌨•",
                        color = if (kbMode == 1) TerminalPrompt else TerminalDim,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { kbMode = 1 - kbMode }
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                    Text(
                        text = "fx",
                        color = if (fx) TerminalPrompt else TerminalDim,
                        fontSize = 11.sp,
                        fontFamily = TerminalFont,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { fx = !fx }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    Text(
                        text = "clear",
                        color = TerminalDim,
                        fontSize = 11.sp,
                        fontFamily = TerminalFont,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onClear() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    Text(
                        text = "✕",
                        color = TerminalError,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onClose() }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(TerminalEdge, TerminalPrompt, TerminalEdge)))
                )

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
                                    fontFamily = TerminalFont,
                                    lineHeight = 11.sp,
                                    style = TextStyle(shadow = BloodGlowSoft)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = welcomeBody(),
                                    color = TerminalDim,
                                    fontSize = 12.sp,
                                    fontFamily = TerminalFont,
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
                                fontFamily = TerminalFont,
                                lineHeight = 17.sp,
                                style = TextStyle(shadow = BloodGlowSoft)
                            )
                            if (kbMode == 1) {
                                // The terminal's own keyboard is in use, so the line is
                                // drawn here with a block cursor instead of a text field
                                // that would summon the phone's keyboard.
                                val t = input.text
                                val cur = input.selection.start.coerceIn(0, t.length)
                                Text(
                                    text = buildAnnotatedString {
                                        append(t.substring(0, cur))
                                        withStyle(SpanStyle(background = Color(0xAAFF1E44), color = Color.White)) {
                                            append(if (cur < t.length) t[cur].toString() else " ")
                                        }
                                        if (cur < t.length) append(t.substring(cur + 1))
                                    },
                                    color = TerminalText,
                                    fontSize = 12.5.sp,
                                    fontFamily = TerminalFont,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                BasicTextField(
                                    value = input,
                                    onValueChange = { nv ->
                                        if (ctrl && nv.text.length == input.text.length + 1 && nv.selection.start > 0) {
                                            ctrlKey(nv.text[nv.selection.start - 1])
                                            ctrl = false
                                        } else {
                                            input = nv
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(focus),
                                    textStyle = TextStyle(
                                        color = TerminalText,
                                        fontSize = 12.5.sp,
                                        fontFamily = TerminalFont,
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
                            }
                            Text(
                                text = "↵ UNLEASH",
                                color = TerminalPrompt,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = TerminalFont,
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

                // The terminal's own keyboard, like Termux's extra keys: two rows
                // of the keys a phone keyboard hides — ESC, TAB, CTRL, ALT, the
                // arrows, HOME/END, PGUP/PGDN — and, when ⌨ is on, a full
                // QWERTY below them so the system keyboard is never needed.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TerminalChrome)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (k in listOf("ESC", "/", "-", "HOME", "↑", "END", "PGUP")) {
                            KbKey(k, false) { pressExtra(k) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        KbKey("TAB", false) { pressExtra("TAB") }
                        KbKey("CTRL", ctrl) { pressExtra("CTRL") }
                        KbKey("ALT", alt) { pressExtra("ALT") }
                        for (k in listOf("←", "↓", "→", "PGDN")) {
                            KbKey(k, false) { pressExtra(k) }
                        }
                    }
                    if (kbMode == 1) {
                        val numbers = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
                        val rows = if (symbols) listOf(
                            listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"),
                            listOf("~", "`", "|", "\\", "{", "}", "[", "]", "<", ">"),
                            listOf("_", "=", "+", ":", ";", "\"", "'", "?", ",")
                        ) else listOf(
                            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                            listOf("z", "x", "c", "v", "b", "n", "m", ".", "-")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (k in numbers) KbKey(k, false) { typeKey(k) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (k in rows[0]) KbKey(if (shift && !symbols) k.uppercase() else k, false) { typeKey(k) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (k in rows[1]) KbKey(if (shift && !symbols) k.uppercase() else k, false) { typeKey(k) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!symbols) KbKey("⇧", shift, 1.4f) { shift = !shift } else Spacer(Modifier.weight(0.4f))
                            for (k in rows[2]) KbKey(if (shift && !symbols) k.uppercase() else k, false) { typeKey(k) }
                            KbKey("⌫", false, 1.4f) { backspace() }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            KbKey(if (symbols) "abc" else "#+=", false, 1.5f) { symbols = !symbols }
                            KbKey(",", false) { typeKey(",") }
                            KbKey("space", false, 4f) { insert(" ") }
                            KbKey(".", false) { typeKey(".") }
                            KbKey("↵", true, 1.5f) { submit() }
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
                        fontFamily = TerminalFont
                    )
                    Text(
                        text = "· ${whispers[whisper]}",
                        color = TerminalPrompt,
                        fontSize = 10.sp,
                        fontFamily = TerminalFont,
                        style = TextStyle(shadow = BloodGlowSoft)
                    )
                }
            }
        }
        // The skin: a faint scan, a heartbeat vignette and a pulsing red frame are
        // always on; "fx" adds the veins, falling code, eyes and slow drips.
        // None of it takes touches, and the middle stays readable.
        Scanlines(Modifier.fillMaxSize(), lineAlpha = 0.05f)
        HorrorVignette(Modifier.fillMaxSize(), intensity = 0.9f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .bloodPulseBorder(RoundedCornerShape(0.dp), 1.5.dp)
        )
        if (fx) {
            WatchingEyes(Modifier.fillMaxSize(), seed = 77, pairs = 2)
            BloodDrips(Modifier.align(Alignment.TopStart), seed = 21, count = 6, maxLength = 40.dp)
        }
    }
}

/** One key of the terminal's own keyboard. A lit key is a held modifier. */
@Composable
private fun RowScope.KbKey(label: String, active: Boolean, weight: Float = 1f, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(weight)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Color(0xAAFF1E44) else Color(0x33FF1E44))
            .border(0.8.dp, if (active) TerminalPrompt else Color(0x668A0C20), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (active) Color.White else TerminalText,
            fontSize = if (label.length > 2) 11.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = TerminalFont,
            maxLines = 1
        )
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
                fontFamily = TerminalFont
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "‹",
                color = TerminalText,
                fontSize = 15.sp,
                fontFamily = TerminalFont,
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
                    fontFamily = TerminalFont
                )
                Text(
                    text = session.title,
                    color = if (active) TerminalText else TerminalDim,
                    fontSize = 12.sp,
                    fontFamily = TerminalFont,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "✕",
                    color = TerminalDim,
                    fontSize = 11.sp,
                    fontFamily = TerminalFont,
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
            fontFamily = TerminalFont,
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
            fontFamily = TerminalFont,
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
                fontFamily = TerminalFont,
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
                fontFamily = TerminalFont,
                lineHeight = 17.sp,
                modifier = Modifier.horrorFlicker()
            )
        }
        if (!line.running && line.timedOut) {
            Text(
                text = "[timed out]",
                color = TerminalError,
                fontSize = 11.sp,
                fontFamily = TerminalFont
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
