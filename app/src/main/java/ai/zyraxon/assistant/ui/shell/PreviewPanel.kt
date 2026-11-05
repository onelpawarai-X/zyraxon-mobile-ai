package ai.zyraxon.assistant.ui.shell

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.tools.PreviewBridge
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
private fun WebHost(modifier: Modifier) {
    val ctx = LocalContext.current
    AndroidView(
        factory = { c ->
            FrameLayout(c).also { box ->
                val wv = PreviewBridge.view(c)
                (wv.parent as? ViewGroup)?.removeView(wv)
                box.addView(wv, FrameLayout.LayoutParams(-1, -1))
            }
        },
        onRelease = { box -> box.removeAllViews() },
        modifier = modifier
    )
}

/** The Preview panel: a real browser the user and the agent both drive. */
@Composable
fun PreviewPanel(vm: ZyraxonViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val url by PreviewBridge.url.collectAsState()
    val title by PreviewBridge.title.collectAsState()
    val progress by PreviewBridge.progress.collectAsState()
    val canBack by PreviewBridge.canBack.collectAsState()
    val canFwd by PreviewBridge.canForward.collectAsState()
    val problems by PreviewBridge.problems.collectAsState()
    var field by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    var mini by remember { mutableStateOf(false) }
    var agent by remember { mutableStateOf(false) }
    LaunchedEffect(url) { if (!editing) field = if (url == "about:blank") "" else url }
    LaunchedEffect(Unit) { PreviewBridge.view(ctx) }

    @Composable
    fun Bar() {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Ic("◀", canBack) { PreviewBridge.back(ctx) }
                Ic("▶", canFwd) { PreviewBridge.forward(ctx) }
                Ic(if (progress < 100) "✕" else "⟳", true) { if (progress < 100) PreviewBridge.stop() else PreviewBridge.reload(ctx) }
                BasicTextField(
                    field, { field = it; editing = true },
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Glass.rowFill(0.4f)).border(1.dp, Glass.borderSoft, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
                    singleLine = true, textStyle = TextStyle(color = Glass.textPrimary, fontSize = 12.5.sp), cursorBrush = SolidColor(Glass.pink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                    keyboardActions = KeyboardActions(onGo = { editing = false; PreviewBridge.clearProblems(); PreviewBridge.open(ctx, field) }),
                    decorationBox = { inner -> if (field.isEmpty()) Text(t("🔎 গুগলে খোঁজো বা URL / localhost:3000"), color = Glass.textMuted, fontSize = 12.5.sp); inner() }
                )
                Ic(if (full) "🗗" else "⛶", true) { full = !full }
                Ic("➖", true) { mini = true }
            }
            if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Glass.pink, trackColor = Color.Transparent)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title.ifBlank { t("প্রিভিউ") }, color = Glass.textMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    if (problems.isEmpty()) t("🔧 ফিক্স") else t("🔧 ফিক্স ({0})", problems.size),
                    color = if (problems.isEmpty()) Glass.textSecondary else Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (problems.isEmpty()) Glass.rowFill(0.4f) else Glass.red)
                        .clickable { vm.fixPreviewProblems() }.padding(horizontal = 10.dp, vertical = 5.dp)
                )
                Text(t("🤖 এজেন্ট"), color = Glass.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Glass.pinkFill.copy(alpha = 0.3f)).border(1.dp, Glass.pink, RoundedCornerShape(14.dp))
                        .clickable { agent = !agent }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }

    if (mini) {
        Row(modifier.clip(RoundedCornerShape(18.dp)).background(Glass.fill(0.8f)).border(1.dp, Glass.borderSoft, RoundedCornerShape(18.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(t("🌐 {0}", title.ifBlank { url.ifBlank { t("প্রিভিউ") } }), color = Glass.textPrimary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(t("▢ বড় করো"), color = Glass.pink, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { mini = false }.padding(6.dp))
        }
        return
    }

    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Glass.fill(0.82f)).border(1.dp, Glass.borderSoft, RoundedCornerShape(22.dp))) {
        Bar()
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(14.dp))) {
            if (!full) WebHost(Modifier.fillMaxSize())
            if (url.isBlank() || url == "about:blank") Box(Modifier.fillMaxSize().background(Glass.screen2), contentAlignment = Alignment.Center) {
                Text(t("এজেন্ট যা বানাবে বা তুমি যা খুলবে, এখানে দেখা যাবে\nউপরে URL বা খোঁজার শব্দ লেখো"), color = Glass.textMuted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            if (agent) MiniAgent(vm, Modifier.align(Alignment.BottomCenter)) { agent = false }
        }
        Spacer(Modifier.height(6.dp))
    }

    if (full) Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Glass.screen).statusBarsPadding().navigationBarsPadding()) {
            Bar()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                WebHost(Modifier.fillMaxSize())
                if (agent) MiniAgent(vm, Modifier.align(Alignment.BottomCenter)) { agent = false }
            }
        }
    }
}

@Composable
private fun Ic(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Glass.rowFill(0.4f)).clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Glass.textPrimary else Glass.textDisabled, fontSize = 13.sp)
    }
}

/** A small chat box over the page: the agent sees this screen and answers. */
@Composable
private fun MiniAgent(vm: ZyraxonViewModel, modifier: Modifier, onClose: () -> Unit) {
    val messages by vm.messages.collectAsState()
    val generating by vm.isGenerating.collectAsState()
    var q by remember { mutableStateOf("") }
    val last = messages.lastOrNull { it.role == ai.zyraxon.assistant.model.MessageRole.ASSISTANT }
    Column(
        modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(18.dp)).background(Glass.sheetFill(0.7f))
            .border(1.dp, Glass.pink.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("🤖 এজেন্ট এই পেজ দেখছে"), color = Glass.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("✕", color = Glass.textMuted, modifier = Modifier.clickable { onClose() }.padding(4.dp))
        }
        if (last != null && last.text.isNotBlank()) {
            Box(Modifier.heightIn(max = 130.dp).padding(vertical = 6.dp).androidxScroll()) { Text(last.text.takeLast(900), color = Glass.textSecondary, fontSize = 12.sp) }
        }
        if (generating) Text(t("⏳ ভাবছে…"), color = Glass.pink, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(q, { q = it }, Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Glass.rowFill(0.4f)).padding(9.dp),
                textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.sp), cursorBrush = SolidColor(Glass.pink),
                decorationBox = { inner -> if (q.isEmpty()) Text(t("এই পেজ নিয়ে বলো…"), color = Glass.textMuted, fontSize = 13.sp); inner() })
            Text("➤", color = Glass.pink, fontSize = 20.sp, modifier = Modifier.clickable { if (q.isNotBlank()) { vm.askAboutPreview(q); q = "" } }.padding(horizontal = 10.dp))
        }
    }
}

@Composable
private fun Modifier.androidxScroll(): Modifier = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
