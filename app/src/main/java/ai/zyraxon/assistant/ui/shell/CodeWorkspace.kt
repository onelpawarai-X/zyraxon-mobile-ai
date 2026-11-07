package ai.zyraxon.assistant.ui.shell

import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import androidx.compose.ui.graphics.vector.ImageVector
import ai.zyraxon.assistant.core.safe

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.ai.ToolRegistry
import ai.zyraxon.assistant.tools.GitTools
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private data class Node(val file: File, val depth: Int)

private fun flatten(root: File, expanded: Set<String>, depth: Int = 0, out: MutableList<Node> = mutableListOf()): List<Node> {
    val kids = root.listFiles()?.filter { !it.name.startsWith(".git") && it.name != "node_modules" }
        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return out
    for (k in kids) {
        out.add(Node(k, depth))
        if (k.isDirectory && k.absolutePath in expanded && depth < 6) flatten(k, expanded, depth + 1, out)
    }
    return out
}

/** The Code panel: the files on the left, an editor on the right, and Git/GitHub in the toolbar. */
@Composable
fun CodeWorkspace(vm: ZyraxonViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { ToolRegistry.workspace(ctx) }
    var expanded by remember { mutableStateOf(setOf(root.absolutePath)) }
    var tick by remember { mutableStateOf(0) }
    val nodes = remember(expanded, tick) { flatten(root, expanded + root.absolutePath) }
    var openPath by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var saved by remember { mutableStateOf("") }
    var treeOpen by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val pending by vm.pendingOpenPath.collectAsState()

    fun load(path: String) {
        scope.launch {
            val body = withContext(Dispatchers.IO) { runCatching { File(path).readText() }.getOrDefault(t("(এই ফাইল পড়া যায়নি)")) }
            openPath = path; text = TextFieldValue(body.take(400_000)); saved = text.text
        }
    }
    LaunchedEffect(pending) { pending?.let { load(it); vm.consumeOpenRequest() } }
    // The agent writes files while this panel is open: keep the tree and open file honest.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2500)
            tick++
            val p = openPath
            if (p != null && text.text == saved) {
                val disk = withContext(Dispatchers.IO) { runCatching { File(p).readText() }.getOrNull() }
                if (disk != null && disk != saved) { text = TextFieldValue(disk); saved = disk }
            }
        }
    }
    fun save() {
        val p = openPath ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { File(p).writeText(text.text) } }
            saved = text.text
            android.widget.Toast.makeText(ctx, t("সেভ হয়েছে"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    fun git(args: JSONObject) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { GitTools.run(ctx, args) { l -> log += l } }
            log += r.safe("summary") + "\n" + (r.opt("data")?.toString().orEmpty().take(3000)) + "\n"
            busy = false; tick++
        }
    }
    fun tool(name: String, args: JSONObject) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { ToolRegistry.execute(ctx, name, args) }
            log += (if (r.optBoolean("ok")) "" else "") + r.safe("summary") + "\n" + (r.opt("data")?.toString().orEmpty().take(3000)) + "\n"
            busy = false
        }
    }

    Column(modifier.clip(RoundedCornerShape(22.dp)).background(Glass.fill(0.78f)).border(1.dp, Glass.borderSoft, RoundedCornerShape(22.dp))) {
        // header: the few things used all the time, and every Git action in one menu
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("কোড"), color = Glass.textPrimary, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text(root.name, color = Glass.textMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Btn(if (treeOpen) t("লুকাও") else t("ফাইল"), if (treeOpen) AppIcons.visibilityOff else AppIcons.folderOpen) { treeOpen = !treeOpen }
            Btn(t("নতুন ফাইল"), AppIcons.add) { dialog = "newfile" }
            Btn(t("সেভ"), AppIcons.save, enabled = openPath != null && text.text != saved) { save() }
            Box {
                Btn(t("Git"), AppIcons.hub) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Glass.sheetFill(0.95f)) {
                    // Every entry carries its own icon: the menu used to be a wall
                    // of identical grey words, so the action the user wanted had to
                    // be found by reading all of them.
                    val items = listOf(
                        Triple(t("নতুন রিপো"), AppIcons.add) { git(JSONObject().put("action", "init")) },
                        Triple(t("ক্লোন"), AppIcons.download) { dialog = "clone" },
                        Triple(t("স্ক্যান"), AppIcons.search) { git(JSONObject().put("action", "scan")) },
                        Triple(t("স্ট্যাটাস"), AppIcons.inventory) { git(JSONObject().put("action", "status")) },
                        Triple(t("কমিট"), AppIcons.save) { dialog = "commit" },
                        Triple(t("পুশ"), AppIcons.upload) { git(JSONObject().put("action", "push")) },
                        Triple(t("পুল"), AppIcons.arrowDown) { git(JSONObject().put("action", "pull")) },
                        Triple(t("ব্রাঞ্চ"), AppIcons.hub) { dialog = "branch" },
                        Triple("Pull Request", AppIcons.handshake) { dialog = "pr" },
                        Triple("GitHub Actions", AppIcons.bolt) { dialog = "actions" },
                        Triple(t("টোকেন"), AppIcons.key) { dialog = "token" }
                    )
                    items.forEach { (label, icon, action) ->
                        DropdownMenuItem(
                            leadingIcon = { Icon(icon, contentDescription = null, tint = Glass.textMuted, modifier = Modifier.size(17.dp)) },
                            text = { Text(label, color = Glass.textPrimary, fontSize = 13.sp) },
                            onClick = { menu = false; action() }
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Glass.borderSoft))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (treeOpen) {
                LazyColumn(Modifier.width(172.dp).fillMaxHeight().background(Glass.rowFill(0.15f)).padding(vertical = 4.dp)) {
                    items(nodes, key = { it.file.absolutePath }) { n ->
                        val sel = n.file.absolutePath == openPath
                        Row(
                            Modifier.fillMaxWidth().background(if (sel) Glass.pink.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable {
                                    if (n.file.isDirectory) expanded = if (n.file.absolutePath in expanded) expanded - n.file.absolutePath else expanded + n.file.absolutePath
                                    else load(n.file.absolutePath)
                                }.padding(start = (6 + n.depth * 10).dp, top = 5.dp, bottom = 5.dp, end = 4.dp)
                        ) {
                            AppIcon(if (n.file.isDirectory) (if (n.file.absolutePath in expanded) AppIcons.folderOpen else AppIcons.folder) else AppIcons.description, null, size = 14.dp, tint = Glass.textSecondary)
                            Text(n.file.name, color = if (n.file.isDirectory) Glass.textSecondary else Glass.textPrimary, fontSize = 11.5.sp, maxLines = 1)
                        }
                    }
                    if (nodes.isEmpty()) item { Text(t("ফাইল নেই। ক্লোন করো বা এজেন্টকে বানাতে বলো।"), color = Glass.textMuted, fontSize = 11.sp, modifier = Modifier.padding(8.dp)) }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val p = openPath
                if (p == null) {
                    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("</>", color = Glass.pink, fontSize = 34.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(8.dp))
                        Text(t("কোনো ফাইল খোলা নেই"), color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(t("বাঁ দিক থেকে ফাইল বেছে নাও, নতুন ফাইল বানাও, বা রিপো ক্লোন করো।"), color = Glass.textMuted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Btn(t("নতুন রিপো"), AppIcons.add) { git(JSONObject().put("action", "init")) }; Btn(t("ক্লোন"), AppIcons.download) { dialog = "clone" }; Btn(t("নতুন ফাইল"), AppIcons.description) { dialog = "newfile" } }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().background(Glass.rowFill(0.25f)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(File(p).relativeTo(root).path + if (text.text != saved) " " else "", color = Glass.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(File(p).extension.uppercase().ifBlank { "TXT" }, color = Glass.pink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    val v = rememberScrollState(); val h = rememberScrollState()
                    val lineCount = remember(text.text) { text.text.count { it == '\n' } + 1 }
                    val gutter = remember(lineCount) { (1..lineCount.coerceAtMost(4000)).joinToString("\n") }
                    Row(Modifier.weight(1f).fillMaxWidth().background(CodeColors.bg).verticalScroll(v)) {
                        Text(gutter, color = CodeColors.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.widthIn(min = 34.dp).padding(start = 6.dp, end = 8.dp, top = 10.dp))
                        Box(Modifier.horizontalScroll(h)) {
                            BasicTextField(
                                text, { text = it }, Modifier.padding(top = 10.dp, bottom = 10.dp, end = 10.dp).widthIn(min = 400.dp),
                                textStyle = TextStyle(color = CodeColors.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp),
                                cursorBrush = SolidColor(Color.White)
                            )
                        }
                    }
                    Text(t("{0} লাইন · {1} অক্ষর", lineCount, text.text.length), color = Glass.textMuted, fontSize = 10.sp, modifier = Modifier.fillMaxWidth().background(Glass.rowFill(0.25f)).padding(horizontal = 10.dp, vertical = 4.dp))
                }
                if (log.isNotBlank() || busy) Column(Modifier.fillMaxWidth().heightIn(max = 140.dp).background(Color(0xFF0A0A0C)).padding(8.dp)) {
                    Row { Text(if (busy) t("চলছে…") else t("লগ"), color = CodeColors.dim, fontSize = 10.sp, modifier = Modifier.weight(1f)); AppIcon(AppIcons.close, t("মুছো"), size = 14.dp, tint = CodeColors.dim, modifier = Modifier.clickable { log = "" }) }
                    Text(log.takeLast(2500), color = Color(0xFFB8C0CC), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            }
        }
    }

    when (dialog) {
        "clone" -> InputDialog(t("GitHub থেকে ক্লোন"), listOf(t("রিপো URL")), t("ক্লোন"), { dialog = null }) { git(JSONObject().put("action", "clone").put("url", it[0])); dialog = null }
        "commit" -> InputDialog(t("কমিট"), listOf(t("বার্তা")), t("কমিট"), { dialog = null }) { git(JSONObject().put("action", "commit").put("message", it[0])); dialog = null }
        "branch" -> InputDialog(t("নতুন ব্রাঞ্চ (খালি = তালিকা)"), listOf(t("নাম")), t("ঠিক আছে"), { dialog = null }) { git(JSONObject().put("action", "branch").put("name", it[0])); dialog = null }
        "pr" -> InputDialog("Pull Request", listOf(t("শিরোনাম (তৈরির জন্য)"), t("PR নম্বর (মার্জ/বন্ধ)")), t("তৈরি"), { dialog = null },
            extra = t("মার্জ"), onExtra = { tool("github_pr", JSONObject().put("action", "merge").put("number", it[1].toIntOrNull() ?: 0)); dialog = null }) {
            tool("github_pr", JSONObject().put("action", "create").put("title", it[0])); dialog = null
        }
        "actions" -> InputDialog("GitHub Actions", listOf(t("রিপো (owner/name)"), t("ওয়ার্কফ্লো ফাইল (চালাতে)")), t("রান তালিকা"), { dialog = null },
            extra = t("চালাও"), onExtra = { tool("github_actions", JSONObject().put("action", "dispatch").put("repo", it[0]).put("workflow", it[1])); dialog = null }) {
            tool("github_actions", JSONObject().put("action", "runs").put("repo", it[0])); dialog = null
        }
        "newfile" -> InputDialog(t("নতুন ফাইল"), listOf(t("পথ (যেমন src/index.js)")), t("তৈরি"), { dialog = null }) {
            val f = File(root, it[0].trim().trimStart('/'))
            scope.launch { withContext(Dispatchers.IO) { runCatching { f.parentFile?.mkdirs(); if (!f.exists()) f.createNewFile() } }; tick++; if (f.isFile) load(f.absolutePath) }
            dialog = null
        }
        "token" -> InputDialog(t("GitHub টোকেন"), listOf("Personal access token"), t("সেভ"), { dialog = null }, secret = true) { GitTools.setToken(ctx, it[0]); log += t("টোকেন সেভ হয়েছে\n"); dialog = null }
    }
}

@Composable
private fun Btn(label: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Glass.rowFill(0.35f)).border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }.padding(horizontal = 11.dp, vertical = 7.dp)
    ) {
        if (icon != null) AppIcon(icon, null, Modifier.size(14.dp), if (enabled) Glass.textPrimary else Glass.textDisabled)
        Text(label, color = if (enabled) Glass.textPrimary else Glass.textDisabled, fontSize = 11.5.sp, maxLines = 1)
    }
}

@Composable
internal fun InputDialog(
    title: String, labels: List<String>, ok: String, onDismiss: () -> Unit, secret: Boolean = false,
    extra: String? = null, onExtra: (List<String>) -> Unit = {}, onOk: (List<String>) -> Unit
) {
    val values = remember { mutableStateListOf(*Array(labels.size) { "" }) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Glass.sheetFill(0.9f),
        title = { Text(title, color = Glass.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, l ->
                    OutlinedTextField(values[i], { values[i] = it }, label = { Text(l, fontSize = 11.sp) }, singleLine = true,
                        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
                }
            }
        },
        confirmButton = { Row { if (extra != null) TextButton({ onExtra(values.toList()) }) {
            AppIcon(AppIcons.add, null, size = 15.dp, tint = Glass.green)
            Spacer(Modifier.width(5.dp))
            Text(extra, color = Glass.green)
        }; TextButton({ onOk(values.toList()) }) {
            AppIcon(AppIcons.check, null, size = 15.dp, tint = Glass.pink)
            Spacer(Modifier.width(5.dp))
            Text(ok, color = Glass.pink, fontWeight = FontWeight.Bold)
        } } },
        dismissButton = { TextButton(onDismiss) {
            AppIcon(AppIcons.close, null, size = 15.dp, tint = Glass.textMuted)
            Spacer(Modifier.width(5.dp))
            Text(t("বাতিল"), color = Glass.textMuted)
        } }
    )
}
