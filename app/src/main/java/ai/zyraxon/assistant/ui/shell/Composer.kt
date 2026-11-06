package ai.zyraxon.assistant.ui.shell

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.model.AgentMode
import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The bottom composer: text, attach, the Chat/Build/Agent switch, the model, and send or stop. The mic is the aura. */
@Composable
fun Composer(vm: ZyraxonViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val text by vm.inputText.collectAsState()
    val generating by vm.isGenerating.collectAsState()
    val mode by vm.activeMode.collectAsState()
    val model by vm.selectedModel.collectAsState()
    val images by vm.attachedImages.collectAsState()
    val notice by vm.imageNotice.collectAsState()
    val seeScreen by vm.alwaysSeeScreen.collectAsState()
    val micOn by vm.alwaysListening.collectAsState()
    var attach by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.attachMedia(uri, ctx.contentResolver.getType(uri) ?: "image/*")
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Glass.sheetFill(0.5f))
            .border(1.dp, Glass.border.copy(alpha = 0.6f), RoundedCornerShape(24.dp)).padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (images.isNotEmpty()) {
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                images.take(6).forEachIndexed { i, url ->
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Glass.pink, RoundedCornerShape(10.dp))) {
                        coil.compose.AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        AppIcon(AppIcons.close, t("সরাও"), size = 10.dp, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).background(Color(0xAA000000)).clickable { vm.removeAttachedImage(i) }.padding(2.dp))
                    }
                }
                Text(t("সব মুছো"), color = Glass.textMuted, fontSize = 11.sp, modifier = Modifier.clickable { vm.clearAttachedImage() }.padding(6.dp))
            }
        }
        if (notice.isNotBlank()) Text(notice, color = Glass.textMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value = text, onValueChange = { vm.onInputChange(it) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp, max = 120.dp).padding(horizontal = 6.dp).testTag("chat_input_field"),
            textStyle = TextStyle(color = Glass.textPrimary, fontSize = 15.sp), cursorBrush = SolidColor(Glass.pink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) vm.sendMessage() }),
            decorationBox = { inner ->
                if (text.isEmpty()) Text(
                    when (mode) { AgentMode.CHAT -> t("কথা বলো বা প্রশ্ন করো…"); AgentMode.BUILD -> t("কী বানাবে? ছোট করে বলো…"); AgentMode.AGENT -> t("কী করতে হবে বলো…") },
                    color = Glass.textMuted, fontSize = 15.sp
                )
                inner()
            }
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconPill(AppIcons.attach, t("সংযুক্তি"), onClick = { attach = !attach }, tag = "attach_button")
            Box {
                IconPill(AppIcons.of(mode.icon), mode.title, chevron = true, onClick = { modeMenu = true }, tag = "mode_button")
                DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }, containerColor = Glass.sheetFill(0.9f)) {
                    AgentMode.values().forEach { m ->
                        DropdownMenuItem(
                            text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AppIcon(AppIcons.of(m.icon), null, size = 20.dp, tint = Glass.textPrimary)
                                Column { Text(m.title, color = Glass.textPrimary, fontWeight = FontWeight.Bold); Text(m.subtitle, color = Glass.textMuted, fontSize = 11.sp) }
                            } },
                            onClick = { vm.setMode(m); modeMenu = false }
                        )
                    }
                }
            }
            ComposerMicButton(isListening = micOn, onMicToggle = { vm.toggleListening() })
            Spacer(Modifier.width(6.dp))
            IconPill(AppIcons.psychology, model.name.take(16), chevron = true, onClick = { modelSheet = true }, modifier = Modifier.weight(1f, fill = false), tag = "model_button")
            Spacer(Modifier.weight(1f))
            if (generating && text.isBlank()) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Glass.red).clickable { vm.stopTurn() }.testTag("stop_button"), contentAlignment = Alignment.Center) {
                    AppIcon(AppIcons.stop, t("থামাও"), size = 20.dp, tint = Color.White)
                }
            } else {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (text.isBlank() && images.isEmpty()) Glass.rowFill(0.5f) else Glass.pinkFill)
                        .clickable { if (text.isNotBlank() || images.isNotEmpty()) vm.sendMessage() }.testTag("send_button"),
                    contentAlignment = Alignment.Center
                ) { AppIcon(AppIcons.send, t("পাঠাও"), size = 19.dp, tint = Color.White) }
            }
        }
        if (attach) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconPill(AppIcons.image, t("গ্যালারি"), { attach = false; picker.launch("*/*") })
            IconPill(AppIcons.camera, t("ক্যামেরা"), { attach = false; vm.captureImageFromTool("look_camera") })
            IconPill(AppIcons.smartphone, t("স্ক্রিন"), { attach = false; vm.captureImageFromTool("screen_shot") })
            IconPill(if (seeScreen) AppIcons.visibility else AppIcons.visibilityOff, if (seeScreen) t("স্ক্রিন দেখছে") else t("স্ক্রিন বন্ধ"), { vm.setAlwaysSeeScreen(!seeScreen) })
        }
    }
    if (modelSheet) ModelPicker(vm) { modelSheet = false }
}

/**
 * The composer's microphone button: one tap holds the mic closed, the next opens
 * it again. It is a composable of its own so the shell can place it and a test
 * can render it without building a whole ViewModel.
 */
@Composable
fun ComposerMicButton(isListening: Boolean, onMicToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(CircleShape)
            .background(if (isListening) Glass.pinkFill else Glass.rowFill(0.5f))
            .clickable { onMicToggle() }.testTag("mic_button"),
        contentAlignment = Alignment.Center
    ) {
        AppIcon(if (isListening) AppIcons.mic else AppIcons.micOff, t("মাইক"), size = 19.dp, tint = Color.White)
    }
}

/** A pill with a vector icon and an optional chevron, for the composer's row. */
@Composable
private fun IconPill(
    icon: ImageVector?,
    label: String,
    chevron: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = ""
) {
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(Glass.rowFill(0.35f))
            .border(1.dp, Glass.borderSoft, RoundedCornerShape(18.dp)).clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AppIcon(icon, null, size = 16.dp, tint = Glass.textPrimary)
        Text(label, color = Glass.textPrimary, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Medium)
        if (chevron) AppIcon(AppIcons.chevronDown, null, size = 14.dp, tint = Glass.textMuted)
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String = "") {
    Text(
        label, color = Glass.textPrimary, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Medium,
        modifier = modifier.clip(RoundedCornerShape(18.dp)).background(Glass.rowFill(0.35f))
            .border(1.dp, Glass.borderSoft, RoundedCornerShape(18.dp)).clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 8.dp).testTag(tag)
    )
}

/** The model list, searchable and grouped by provider. It lives in the composer now. */
@Composable
fun ModelPicker(vm: ZyraxonViewModel, onDismiss: () -> Unit) {
    val models by vm.availableModels.collectAsState()
    val selected by vm.selectedModel.collectAsState()
    val loading by vm.isLoadingModels.collectAsState()
    var q by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    val providers = models.map { it.provider }.distinct()
    val list = models.filter { (provider.isBlank() || it.provider == provider) && (q.isBlank() || it.name.contains(q, true) || it.id.contains(q, true)) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(22.dp)).background(Glass.sheetFill(0.7f))
                .border(1.dp, Glass.border, RoundedCornerShape(22.dp)).padding(14.dp)
        ) {
            Text(t("মডেল বেছে নাও"), color = Glass.textPrimary, fontWeight = FontWeight.Black, fontSize = 16.sp)
            BasicTextField(q, { q = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(Glass.rowFill(0.3f)).padding(10.dp),
                textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.sp), cursorBrush = SolidColor(Glass.pink),
                decorationBox = { inner -> if (q.isEmpty()) Text(t("খোঁজো…"), color = Glass.textMuted, fontSize = 13.sp); inner() })
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Chip(t("সব"), provider.isBlank()) { provider = "" } }
                items(providers) { p -> Chip(p, provider == p) { provider = p } }
            }
            Spacer(Modifier.height(8.dp))
            if (loading && models.isEmpty()) Text(t("মডেল লোড হচ্ছে…"), color = Glass.textMuted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(list, key = { it.provider + it.id }) { m ->
                    val sel = m.id == selected.id && m.provider == selected.provider
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (sel) Glass.pinkFill.copy(alpha = 0.22f) else Glass.rowFill(0.25f))
                            .border(1.dp, if (sel) Glass.pink else Glass.borderSoft, RoundedCornerShape(12.dp))
                            .clickable { vm.setModel(m); onDismiss() }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, color = Glass.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(m.provider + if (m.isRealtime) t(" · লাইভ") else "", color = Glass.textMuted, fontSize = 10.5.sp)
                        }
                        if (sel) AppIcon(AppIcons.check, t("নির্বাচিত"), size = 16.dp, tint = Glass.pink)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, color = if (selected) Color.White else Glass.textSecondary, fontSize = 11.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) Glass.pinkFill else Glass.rowFill(0.3f))
            .clickable { onClick() }.padding(horizontal = 10.dp, vertical = 5.dp))
}
