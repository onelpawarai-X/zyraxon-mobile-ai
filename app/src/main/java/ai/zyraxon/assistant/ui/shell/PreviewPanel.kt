// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.shell

import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import androidx.compose.ui.graphics.vector.ImageVector
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
    val pickDoc = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "document"
            val dir = java.io.File(ctx.cacheDir, "preview_in").apply { mkdirs() }
            val f = java.io.File(dir, name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_"))
            ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
            PreviewBridge.openFile(ctx, f)
        }
    }
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
    val picking by PreviewBridge.picking.collectAsState()
    val picked by PreviewBridge.picked.collectAsState()
    // The moment a part of the page is picked, the little agent box opens with it.
    LaunchedEffect(picked) { if (picked != null) { agent = true; mini = false } }
    LaunchedEffect(url) { if (!editing) field = if (url == "about:blank") "" else url }
    LaunchedEffect(Unit) { PreviewBridge.view(ctx) }

    @Composable
    fun Bar() {
        Column {
            // Row 1: the address bar gets the whole width, so it never wraps.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Ic(AppIcons.arrowBack, t("পিছনে"), canBack) { PreviewBridge.back(ctx) }
                Ic(AppIcons.arrowForward, t("সামনে"), canFwd) { PreviewBridge.forward(ctx) }
                Ic(if (progress < 100) AppIcons.close else AppIcons.refresh, if (progress < 100) t("থামাও") else t("রিলোড"), true) { if (progress < 100) PreviewBridge.stop() else PreviewBridge.reload(ctx) }
                Box(
                    Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(19.dp)).background(Glass.rowFill(0.4f))
                        .border(1.dp, Glass.borderSoft, RoundedCornerShape(19.dp)).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (field.isEmpty()) Text(t("গুগলে খোঁজো বা URL লেখো"), color = Glass.textMuted, fontSize = 12.5.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    BasicTextField(
                        field, { field = it; editing = true }, Modifier.fillMaxWidth(),
                        singleLine = true, textStyle = TextStyle(color = Glass.textPrimary, fontSize = 12.5.sp), cursorBrush = SolidColor(Glass.pink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                        keyboardActions = KeyboardActions(onGo = { editing = false; PreviewBridge.clearProblems(); PreviewBridge.open(ctx, field) })
                    )
                }
            }
            if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Glass.pink, trackColor = Color.Transparent)
            // Row 2: page title on the left, tools on the right.
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title.ifBlank { t("প্রিভিউ") }, color = Glass.textMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Pill(t("ফাইল খোলো"), false, icon = AppIcons.folderOpen) { pickDoc.launch(arrayOf("*/*")) }
                Pill(t("সাইজ"), false, icon = if (full) AppIcons.compress else AppIcons.fullscreen) { full = !full }
                Pill(t("ছোট করো"), false, icon = AppIcons.remove) { mini = true }
                Pill(if (problems.isEmpty()) t("ফিক্স") else t("ফিক্স ({0})", problems.size), problems.isNotEmpty(), Glass.red, AppIcons.build) { vm.fixPreviewProblems() }
                Pill(if (picking) t("অংশে ট্যাপ করো…") else t("অংশ বাছো"), picking, Glass.pink, AppIcons.edit) { PreviewBridge.setPicking(ctx, !picking) }
                Pill(t("এজেন্ট"), agent, Glass.pink, AppIcons.smartToy) { agent = !agent }
            }
        }
    }

    if (mini) {
        Row(modifier.clip(RoundedCornerShape(18.dp)).background(Glass.fill(0.8f)).border(1.dp, Glass.borderSoft, RoundedCornerShape(18.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title.ifBlank { url.ifBlank { t("প্রিভিউ") } }, color = Glass.textPrimary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { mini = false }.padding(6.dp)) {
                AppIcon(AppIcons.fullscreen, null, size = 14.dp, tint = Glass.pink)
                Spacer(Modifier.width(4.dp))
                Text(t("বড় করো"), color = Glass.pink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Glass.fill(0.82f)).border(1.dp, Glass.borderSoft, RoundedCornerShape(22.dp))) {
        Bar()
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(14.dp))) {
            if (!full) WebHost(Modifier.fillMaxSize())
            if (url.isBlank() || url == "about:blank") Column(
                Modifier.fillMaxSize().background(Glass.screen2).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                AppIcon(AppIcons.publicGlobe, null, size = 40.dp, tint = Glass.textMuted)
                Text(t("এজেন্ট যা বানাবে বা তুমি যা খুলবে, এখানে দেখা যাবে"), color = Glass.textSecondary, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
                Text(t("দ্রুত খোলো"), color = Glass.textMuted, fontSize = 11.sp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("localhost:3000", "localhost:5173", "localhost:8080").forEach { u ->
                        Text(u, color = Glass.textPrimary, fontSize = 11.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Glass.rowFill(0.45f)).border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp))
                                .clickable { PreviewBridge.clearProblems(); PreviewBridge.open(ctx, u) }.padding(horizontal = 11.dp, vertical = 7.dp))
                    }
                }
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
private fun Pill(label: String, active: Boolean, tint: Color = Glass.pink, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(if (active) tint else Glass.rowFill(0.4f))
            .border(1.dp, if (active) tint else Glass.borderSoft, RoundedCornerShape(14.dp)).clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        AppIcon(icon, null, size = 14.dp, tint = if (active) Color.White else Glass.textSecondary)
        Text(label, color = if (active) Color.White else Glass.textSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Ic(icon: ImageVector?, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Glass.rowFill(0.4f)).clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
        AppIcon(icon, desc, size = 17.dp, tint = if (enabled) Glass.textPrimary else Glass.textDisabled)
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
            Text(t("এজেন্ট এই পেজ দেখছে"), color = Glass.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            AppIcon(AppIcons.close, t("বন্ধ করো"), size = 16.dp, tint = Glass.textMuted, modifier = Modifier.clickable { onClose() }.padding(4.dp))
        }
        val pk by PreviewBridge.picked.collectAsState()
        pk?.let { p ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(Glass.pink.copy(alpha = 0.16f))
                    .border(1.dp, Glass.pink, RoundedCornerShape(12.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("<" + p.tag + ">  " + p.selector, color = Glass.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (p.text.isNotBlank()) Text(p.text, color = Glass.textSecondary, fontSize = 11.5.sp, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                AppIcon(AppIcons.close, t("সরাও"), size = 16.dp, tint = Glass.textMuted, modifier = Modifier.clickable { PreviewBridge.clearPicked() }.padding(4.dp))
            }
        }
        if (last != null && last.text.isNotBlank()) {
            Box(Modifier.heightIn(max = 130.dp).padding(vertical = 6.dp).androidxScroll()) { Text(last.text.takeLast(900), color = Glass.textSecondary, fontSize = 12.sp) }
        }
        if (generating) Text(t("ভাবছে…"), color = Glass.pink, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(q, { q = it }, Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Glass.rowFill(0.4f)).padding(9.dp),
                textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.sp), cursorBrush = SolidColor(Glass.pink),
                decorationBox = { inner -> if (q.isEmpty()) Text(if (pk != null) t("এই অংশটা কী করবো? লেখো…") else t("এই পেজ নিয়ে বলো…"), color = Glass.textMuted, fontSize = 13.sp); inner() })
            AppIcon(AppIcons.send, t("পাঠাও"), size = 20.dp, tint = Glass.pink, modifier = Modifier.clickable { if (q.isNotBlank()) { vm.askAboutPreview(q); q = "" } }.padding(horizontal = 10.dp))
        }
    }
}

@Composable
private fun Modifier.androidxScroll(): Modifier = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
