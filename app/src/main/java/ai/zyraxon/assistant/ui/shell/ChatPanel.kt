package ai.zyraxon.assistant.ui.shell

import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import androidx.compose.ui.graphics.vector.ImageVector
import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.ai.ToolRegistry
import ai.zyraxon.assistant.model.SubAgentInfo
import ai.zyraxon.assistant.model.WorkspaceFile
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The glass chat box in the middle: Chat, Todo, Tool calls and Files, as four sections. */
@Composable
fun ChatPanel(vm: ZyraxonViewModel, modifier: Modifier = Modifier) {
    val section by vm.chatSection.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val execs by vm.toolExecutions.collectAsState()
    val files by vm.workspaceFiles.collectAsState()
    val active by vm.activeToolCalls.collectAsState()
    LaunchedEffect(Unit) { vm.refreshWorkspaceFiles() }
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(Glass.fill(0.72f))
            .border(1.dp, Glass.borderSoft, RoundedCornerShape(22.dp))
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTab(AppIcons.chat, t("চ্যাট"), section == "chat", Modifier.weight(1f)) { vm.setChatSection("chat") }
            SectionTab(AppIcons.checkCircle, t("টুডু {0}", tasks.count { !it.isCompleted }.takeIf { it > 0 } ?: ""), section == "todo", Modifier.weight(1f)) { vm.setChatSection("todo") }
            SectionTab(AppIcons.build, t("টুল {0}", (execs.size + active.size).takeIf { it > 0 } ?: ""), section == "tools", Modifier.weight(1f)) { vm.setChatSection("tools") }
            SectionTab(AppIcons.folder, t("ফাইল {0}", files.size.takeIf { it > 0 } ?: ""), section == "files", Modifier.weight(1f)) { vm.setChatSection("files") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                "todo" -> TodoSection(vm)
                "tools" -> ToolsSection(vm)
                "files" -> FilesSection(vm)
                else -> MessageList(vm)
            }
        }
    }
}

@Composable
private fun SectionTab(icon: ImageVector?, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Glass.pinkFill.copy(alpha = if (Glass.isLight) 0.16f else 0.3f) else Glass.rowFill(0.2f))
            .border(1.dp, if (selected) Glass.pink else Glass.borderSoft, RoundedCornerShape(12.dp))
            .clickable { onClick() }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
    ) {
        AppIcon(icon, null, size = 15.dp, tint = if (selected) Glass.textPrimary else Glass.textMuted)
        Text(label.trim(), color = if (selected) Glass.textPrimary else Glass.textMuted, fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun MessageList(vm: ZyraxonViewModel) {
    val messages by vm.messages.collectAsState()
    val liveCode by vm.liveCode.collectAsState()
    val jobs by vm.mediaJobs.collectAsState()
    val subs by vm.subAgents.collectAsState()
    val generating by vm.isGenerating.collectAsState()
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val state = rememberLazyListState()
    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.text?.length, last?.toolCalls?.size, liveCode?.text?.length) {
        if (messages.isNotEmpty()) state.scrollToItem(messages.size - 1 + if (subs.isNotEmpty()) 1 else 0, Int.MAX_VALUE / 4)
    }
    LazyColumn(
        state = state, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (subs.isNotEmpty()) item("subs") { SubAgentStrip(subs, onStop = { vm.stopSubAgent(it) }) }
        items(messages, key = { it.id }) { m ->
            val isLast = m.id == last?.id
            MessageItem(
                message = m, isLastStreaming = isLast && m.isStreaming && generating,
                liveCode = if (isLast) liveCode else null, jobs = if (isLast) jobs else emptyList(),
                turnStartedAt = m.createdAt,
                onCopy = {
                    clip.setText(AnnotatedString(m.text))
                    android.widget.Toast.makeText(ctx, t("কপি হয়েছে"), android.widget.Toast.LENGTH_SHORT).show()
                },
                onSpeak = { vm.speak(m.text) }
            )
        }
    }
}

/** The helper agents the main agent started: model, percentage and what each is doing. */
@Composable
private fun SubAgentStrip(subs: List<SubAgentInfo>, onStop: (String) -> Unit) {
    var open by remember { mutableStateOf(true) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass.rowFill(0.3f))
            .border(1.dp, Glass.violet.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(10.dp)
    ) {
        Row(Modifier.clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text(t("সাব-এজেন্ট ({0}/{1} চলছে)", subs.count { it.state == "running" }, subs.size), color = Glass.textPrimary,
                fontWeight = FontWeight.Bold, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
            AppIcon(if (open) AppIcons.chevronDown else AppIcons.chevronRight, null, size = 16.dp, tint = Glass.textMuted)
        }
        if (open) subs.forEach { s ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(
                    when (s.state) { "running" -> AppIcons.hourglass; "done" -> AppIcons.checkCircle; "stopped" -> AppIcons.stop; else -> AppIcons.warning },
                    s.state, size = 15.dp,
                    tint = when (s.state) { "done" -> Glass.green; "stopped" -> Glass.textMuted; else -> Glass.textPrimary }
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, color = Glass.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${s.model} · ${s.lastAction}", color = Glass.textMuted, fontSize = 10.5.sp, maxLines = 1)
                }
                Text("${s.percent}%", color = Glass.violet, fontWeight = FontWeight.Black, fontSize = 14.sp)
                if (s.state == "running") AppIcon(AppIcons.stop, t("থামাও"), size = 15.dp, tint = Glass.red, modifier = Modifier.clickable { onStop(s.id) }.padding(4.dp))
            }
            LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = Glass.violet, trackColor = Glass.rowFill(0.5f))
        }
    }
}

@Composable
private fun TodoSection(vm: ZyraxonViewModel) {
    val tasks by vm.tasks.collectAsState()
    var text by remember { mutableStateOf("") }
    val done = tasks.count { it.isCompleted }
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text(if (tasks.isEmpty()) t("এজেন্ট যে কাজের পরিকল্পনা করবে, এখানে দেখা যাবে") else t("{0}/{1} শেষ", done, tasks.size),
            color = Glass.textSecondary, fontSize = 12.sp)
        if (tasks.isNotEmpty()) LinearProgressIndicator(progress = { done / tasks.size.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(6.dp).clip(CircleShape),
            color = Glass.green, trackColor = Glass.rowFill(0.5f))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(tasks, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Glass.rowFill(0.3f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(t.isCompleted, { vm.toggleTask(t.id) })
                    Text(t.text, color = if (t.isCompleted) Glass.textMuted else Glass.textPrimary, fontSize = 13.sp,
                        textDecoration = if (t.isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f))
                    AppIcon(AppIcons.close, t("মুছো"), size = 15.dp, tint = Glass.textMuted, modifier = Modifier.clickable { vm.deleteTask(t.id) }.padding(6.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(text, { text = it }, Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Glass.rowFill(0.3f)).padding(10.dp),
                textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.sp), cursorBrush = SolidColor(Glass.pink),
                decorationBox = { inner -> if (text.isEmpty()) Text(t("নিজের কাজ যোগ করো…"), color = Glass.textMuted, fontSize = 13.sp); inner() })
            AppIcon(AppIcons.add, t("কাজ যোগ করো"), size = 22.dp, tint = Glass.pink, modifier = Modifier.clickable { vm.addTask(text); text = "" }.padding(horizontal = 12.dp))
        }
    }
}

@Composable
private fun ToolsSection(vm: ZyraxonViewModel) {
    val execs by vm.toolExecutions.collectAsState()
    val active by vm.activeToolCalls.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (execs.isEmpty() && active.isEmpty()) item { Text(t("এজেন্ট যে টুল কল করবে তার তালিকা এখানে আসবে"), color = Glass.textMuted, fontSize = 12.sp) }
        items(active, key = { "a" + it.id }) { e ->
            ToolRow(e.icon, e.name, e.description, t("চলছে…"), e.timestamp, e.output, true)
        }
        items(execs, key = { it.id }) { e ->
            ToolRow(e.icon, e.name, e.description, e.status, e.timestamp, e.output, false)
        }
    }
}

@Composable
private fun ToolRow(icon: String, name: String, desc: String, status: String, time: String, output: String, running: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Glass.rowFill(0.3f))
            .border(1.dp, if (running) Glass.pink else Glass.borderSoft, RoundedCornerShape(12.dp))
            .clickable { open = !open }.padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 15.sp); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Glass.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                Text(desc, color = Glass.textMuted, fontSize = 11.sp, maxLines = if (open) 5 else 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(status, color = if (running) Glass.pink else if (status == t("সম্পন্ন") || status == t("সফল")) Glass.green else Glass.red, fontSize = 11.sp)
                Text(time, color = Glass.textMuted, fontSize = 9.5.sp)
            }
        }
        if (open && output.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            TerminalBox(name, output, running)
        }
    }
}

@Composable
private fun FilesSection(vm: ZyraxonViewModel) {
    val files by vm.workspaceFiles.collectAsState()
    val ctx = LocalContext.current
    val root = remember { ToolRegistry.workspace(ctx) }
    var query by remember { mutableStateOf("") }
    val shown = files.filter { query.isBlank() || it.relative.contains(query, true) }
    val groups = shown.groupBy { it.relative.substringBefore('/', "·") }
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        // The save location. Tapping it opens the phone's file manager right there.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Brush2.accent()).border(1.dp, Glass.pink.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .clickable { openFileManager(ctx, root) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(AppIcons.folderOpen, null, size = 22.dp, tint = Glass.textPrimary); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t("ফাইল সেভ হয় এখানে"), color = Glass.textMuted, fontSize = 10.5.sp)
                Text(root.absolutePath.substringAfter("/Android/", root.absolutePath), color = Glass.textPrimary, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t("খোলো"), color = Glass.pink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                AppIcon(AppIcons.arrowOut, null, size = 13.dp, tint = Glass.pink)
            }
        }
        BasicTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(Glass.rowFill(0.3f)).padding(10.dp),
            textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.sp), cursorBrush = SolidColor(Glass.pink),
            decorationBox = { inner -> if (query.isEmpty()) Text(t("ফাইল খোঁজো…"), color = Glass.textMuted, fontSize = 13.sp); inner() })
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (shown.isEmpty()) item { Text(t("এখনো কোনো ফাইল নেই — এজেন্ট যা বানাবে এখানে জমা হবে"), color = Glass.textMuted, fontSize = 12.sp) }
            groups.forEach { (folder, list) ->
                item("h$folder") { Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AppIcon(AppIcons.folder, null, size = 13.dp, tint = Glass.textSecondary)
                    Text("$folder · ${list.size}", color = Glass.textSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                } }
                items(list, key = { it.path }) { f -> FileRow(f, vm) }
            }
        }
    }
}

internal object Brush2 {
    @Composable fun accent() = androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Glass.pink.copy(alpha = 0.22f), Glass.violet.copy(alpha = 0.12f)))
}

internal fun openFileManager(ctx: android.content.Context, dir: java.io.File) {
    val rel = dir.absolutePath.substringAfter("/storage/emulated/0/", "")
    val tries = listOf(
        Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://com.android.externalstorage.documents/document/primary:" + Uri.encode(rel)),
            "vnd.android.document/directory"),
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.android.externalstorage.documents/root/primary"), "vnd.android.document/root"),
        Intent("android.intent.action.GET_CONTENT").setType("*/*")
    )
    for (i in tries) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
    }
    android.widget.Toast.makeText(ctx, dir.absolutePath, android.widget.Toast.LENGTH_LONG).show()
}

private fun iconFor(name: String): ImageVector = when (langOf(name)) {
    "html", "htm" -> AppIcons.publicGlobe; "css" -> AppIcons.palette
    "js", "ts", "jsx", "tsx" -> AppIcons.code; "py" -> AppIcons.code; "kt", "java" -> AppIcons.code
    "json", "yml", "yaml", "toml" -> AppIcons.settings; "md", "txt" -> AppIcons.description
    "png", "jpg", "jpeg", "gif", "webp", "svg" -> AppIcons.image
    "mp3", "wav", "m4a", "ogg" -> AppIcons.headphones; "mp4", "webm", "mkv" -> AppIcons.theater
    "apk" -> AppIcons.inventory; "zip" -> AppIcons.compress; "sh" -> AppIcons.terminal
    else -> AppIcons.description
}

@Composable
private fun FileRow(f: WorkspaceFile, vm: ZyraxonViewModel) {
    var del by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass.rowFill(0.3f))
            .border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp)).clickable { vm.requestOpenInEditor(f.path) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Glass.pink.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            AppIcon(iconFor(f.name), null, size = 18.dp, tint = Glass.textPrimary)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, color = Glass.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${f.relative.substringBeforeLast('/', "")} · ${f.sizeLabel}", color = Glass.textMuted, fontSize = 10.5.sp, maxLines = 1)
        }
        ShareButton(path = f.path, label = t("শেয়ার"))
        AppIcon(AppIcons.delete, t("মুছো"), size = 16.dp, tint = Glass.textMuted, modifier = Modifier.clickable { del = true }.padding(6.dp))
    }
    if (del) AlertDialog(
        onDismissRequest = { del = false }, containerColor = Glass.sheetFill(0.9f),
        title = { Text(t("মুছবে?"), color = Glass.textPrimary) }, text = { Text(f.relative, color = Glass.textMuted) },
        confirmButton = { TextButton({ vm.deleteWorkspaceFile(f.path); del = false }) { Text(t("মোছো"), color = Glass.red) } },
        dismissButton = { TextButton({ del = false }) { Text(t("না"), color = Glass.textMuted) } }
    )
}
