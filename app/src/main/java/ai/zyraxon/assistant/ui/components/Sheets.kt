// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import androidx.compose.ui.graphics.vector.ImageVector
import ai.zyraxon.assistant.i18n.Loc
import ai.zyraxon.assistant.i18n.setLanguage
import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.ai.ZyraxonAiEngine
import ai.zyraxon.assistant.model.AiModel
import ai.zyraxon.assistant.model.AppTab
import ai.zyraxon.assistant.model.BgAnimationMode
import ai.zyraxon.assistant.model.Persona
import ai.zyraxon.assistant.shell.ShizukuState
import ai.zyraxon.assistant.update.UpdateState
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import ai.zyraxon.assistant.ui.components.rememberLiveTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import ai.zyraxon.assistant.ui.components.PermissionPanel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaSelectionSheet(
    selectedPersona: Persona,
    onSelectPersona: (Persona) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Glass.sheetFill(0.30f),
        dragHandle = null
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
        HorrorSheetBackdrop(Modifier.matchParentSize(), seed = 4)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = t("পরিচয় নির্বাচন (Persona)"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = BoneWhite,
                    style = TextStyle(shadow = BloodGlow)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Glass.textMuted)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            PersonaList(selectedPersona = selectedPersona, onSelectPersona = onSelectPersona)
            Spacer(modifier = Modifier.height(20.dp))
        }
        }
    }
}

/**
 * The persona cards, shared by the standalone sheet and the Settings section, so
 * "প্রেমিকা" lives in exactly one place whichever way the user reaches it.
 */
@Composable
fun PersonaList(
    selectedPersona: Persona,
    onSelectPersona: (Persona) -> Unit
) {
    ZyraxonAiEngine.PERSONAS.forEach { persona ->
        val isSelected = persona.id == selectedPersona.id
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isSelected) Glass.pinkFill.copy(alpha = 0.20f) else Glass.rowFill(0.30f))
                .border(
                    width = 1.dp,
                    color = if (isSelected) Glass.pinkFill else Glass.borderSoft,
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable { onSelectPersona(persona) }
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(AppIcons.of(persona.emoji), null, size = 28.dp, tint = Glass.textPrimary)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = t(persona.name),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Glass.textPrimary
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = t(persona.title),
                            fontSize = 12.sp,
                            color = Glass.cyanFill
                        )
                    }
                    Text(
                        text = t(persona.description),
                        fontSize = 12.sp,
                        color = Glass.textSecondary
                    )
                }
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Selected",
                        tint = Glass.pinkFill
                    )
                }
            }
        }
    }
}

/**
 * Centered Dynamic Model Selector Modal (Zero Hardcoded Models).
 * Loads live models from OpenCode server endpoints and supports custom model entries.
 */
/** A short chip label for a provider id, falling back to the id itself. */
private fun providerChipLabel(providerId: String): String =
    ai.zyraxon.assistant.ai.ProviderCatalog.find(providerId)?.label ?: providerId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionSheet(
    viewModel: ZyraxonViewModel,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Glass.sheetFill(0.30f),
        dragHandle = null
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            HorrorSheetBackdrop(Modifier.matchParentSize(), seed = 6)
            ModelListPane(viewModel = viewModel, onPicked = onDismiss, showClose = true)
        }
    }
}

/**
 * The model picker's body, shared by the standalone sheet and the Settings
 * "মডেল" section, so both show exactly the same live catalogue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelListPane(
    viewModel: ZyraxonViewModel,
    onPicked: () -> Unit,
    showClose: Boolean = false
) {
    val selectedModel by viewModel.selectedModel.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val isLoading by viewModel.isLoadingModels.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("all") }
    var customModelId by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }

    val filteredModels = availableModels.filter { model ->
        val matchesSearch = model.name.contains(searchQuery, ignoreCase = true) ||
                model.id.contains(searchQuery, ignoreCase = true)
        val matchesCategory = when (selectedCategory) {
            "all" -> true
            "custom" -> model.category == "Custom"
            // Any real provider id filters by that provider, so a newly added
            // provider appears without another edit here.
            else -> model.provider == selectedCategory
        }
        matchesSearch && matchesCategory
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = t("এআই মডেল নির্বাচন (AI Models)"),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Glass.textPrimary
                    )
                    Text(
                        text = t("দ্রুত মডেল আগে — সরাসরি লাইভ ক্যাটালগ"),
                        fontSize = 11.5.sp,
                        color = Glass.textSecondary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { viewModel.refreshModels() },
                        modifier = Modifier.testTag("refresh_models_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Glass.cyanFill,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Sync", tint = Glass.cyanFill)
                        }
                    }
                    IconButton(onClick = onPicked) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Glass.textMuted)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BloodCardBrush)
                    .border(1.dp, Glass.cyanFill.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = Glass.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search_models_input"),
                        textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.5.sp),
                        cursorBrush = SolidColor(Glass.cyanFill),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    t("মডেল খুঁজুন (যেমন: Big Pickle, Flash, Mimo)..."),
                                    color = Glass.textMuted,
                                    fontSize = 13.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Clear",
                            tint = Glass.textMuted,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { searchQuery = "" }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Category Filter Pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // One chip per provider that actually returned models, plus the
                // two fixed ones. Building it from the loaded list means a
                // provider with no models never shows an empty tab.
                val providerChips = availableModels.map { it.provider }.distinct()
                    .map { id -> id to providerChipLabel(id) }
                listOf("all" to t("সব মডেল")) + providerChips + listOf("custom" to t("কাস্টম"))
                .forEach { (catId, label) ->
                    val isCatActive = selectedCategory == catId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isCatActive) Glass.cyanFill else Glass.rowFill(0.30f))
                            .clickable { selectedCategory = catId }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.5.sp,
                            fontWeight = if (isCatActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCatActive) Glass.textOnAccent else Glass.textPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Models List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
            ) {
                if (filteredModels.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(30.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                t("কোনো মডেল পাওয়া যায়নি। সার্ভার সিঙ্ক বোতামে ট্যাপ করুন।"),
                                color = Glass.textMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                items(filteredModels) { model ->
                    val isSelected = model.id == selectedModel.id
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) Glass.cyanFill.copy(alpha = 0.20f) else Glass.rowFill(0.30f))
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Glass.cyanFill else Glass.borderSoft,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                viewModel.setModel(model)
                                onPicked()
                            }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = model.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Glass.textPrimary
                                    )
                                    // Realtime models speak for themselves, so the
                                    // badge tells the user the phone's TTS will be
                                    // silent before they pick one.
                                    if (model.isRealtime) {
                                        Spacer(modifier = Modifier.size(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Glass.pinkFill.copy(alpha = 0.30f))
                                                .border(1.dp, Glass.pinkFill, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = t("রিয়েলটাইম"),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Glass.textPrimary
                                            )
                                        }
                                    }
                                    if (model.isFree) {
                                        Spacer(modifier = Modifier.size(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Glass.greenFill)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "FREE",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Glass.greenFill
                                            )
                                        }
                                    }
                                }
                                // Category-wise label, so realtime models are easy
                                // to tell apart from plain text models at a glance.
                                Text(
                                    text = if (model.isRealtime) {
                                        t(model.category) + " " + t("সরাসরি কথা বলে")
                                    } else {
                                        t(model.category) + " " + t("লেখা পড়ে শোনায়")
                                    },
                                    fontSize = 10.sp,
                                    color = if (model.isRealtime) Glass.pinkFill else Glass.cyanFill
                                )
                                Text(
                                    text = t(model.description),
                                    fontSize = 11.5.sp,
                                    color = Glass.textSecondary
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selected",
                                    tint = Glass.cyanFill
                                )
                            }
                        }
                    }
                }

                // Add Custom Model Section
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    if (!showCustomInput) {
                        OutlinedButton(
                            onClick = { showCustomInput = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Glass.cyanFill)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Add")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(t("কাস্টম মডেল যুক্ত করুন (Custom Model ID)"), fontSize = 12.sp)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(BloodCardBrush)
                                .padding(12.dp)
                        ) {
                            Text(t("মডেল ID লিখুন:"), color = Glass.textPrimary, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            BasicTextField(
                                value = customModelId,
                                onValueChange = { customModelId = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(BloodRowBrush, RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.sp),
                                cursorBrush = SolidColor(Glass.cyanFill)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = {
                                        if (customModelId.isNotBlank()) {
                                            viewModel.addCustomModel(customModelId, customModelId)
                                            onPicked()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Glass.cyanFill)
                                ) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Glass.textOnAccent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(t("যুক্ত ও নির্বাচন করুন"), color = Glass.textOnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Complete Full-Featured Settings Sheet
 * Restoring ALL buttons, tabs, API keys, GitHub token, memory, TTS controls, and system permissions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    viewModel: ZyraxonViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val bgMode by viewModel.bgMode.collectAsState()
    val bgVideoAsset by viewModel.bgVideoAsset.collectAsState()
    val auraHue by viewModel.auraHue.collectAsState()
    val auraSize by viewModel.auraSize.collectAsState()
    val auraGlow by viewModel.auraGlow.collectAsState()
    val auraSpeed by viewModel.auraSpeed.collectAsState()
    val auraStyle by viewModel.auraStyle.collectAsState()
    val auraShader by viewModel.auraShader.collectAsState()
    val panelAlpha by viewModel.panelAlpha.collectAsState()
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val ttsRate by viewModel.ttsRate.collectAsState()
    val ttsPitch by viewModel.ttsPitch.collectAsState()
    val alwaysListening by viewModel.alwaysListening.collectAsState()
    val autoSend by viewModel.autoSend.collectAsState()
    val customBgUrl by viewModel.customBgUrl.collectAsState()
    val animSpeed by viewModel.animSpeed.collectAsState()
    val glassBlur by viewModel.glassBlur.collectAsState()
    val githubToken by viewModel.githubToken.collectAsState()
    val githubStatus by viewModel.githubStatus.collectAsState()
    val systemStatus by viewModel.systemStatus.collectAsState()
    val memories by viewModel.memories.collectAsState()
    val messages by viewModel.messages.collectAsState()

    // The pane the user was last on is remembered across app restarts, so
    // reopening Settings returns to the same section instead of snapping back to
    // the first tab — the "settings reset themselves" complaint.
    var activePane by rememberSaveable { mutableStateOf(viewModel.settingsPane) }

    // Checking for a new release as soon as Settings opens is what makes the
    // আপডেট pane useful the first time it is visited; the launch check has
    // usually already answered, and checkForUpdates ignores a duplicate call.
    LaunchedEffect(Unit) { viewModel.checkForUpdates(silent = true) }

    // Provider Keys local edit states
    val providerStatus by viewModel.providerStatus.collectAsState()
    val providerBusy by viewModel.providerBusy.collectAsState()
    var editGeminiKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("gemini")) }
    var editOpenCodeKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("opencode")) }
    var editZyraxonKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("zyraxon")) }
    var editApexKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("zyraxon_apex")) }
    var editClaudeKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("anthropic")) }
    var editGroqKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("groq")) }
    var editOpenRouterKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("openrouter")) }
    var editDeepSeekKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("deepseek")) }
    var editOpenAiKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("openai")) }
    var editGithubToken by remember { mutableStateOf(githubToken) }

    // Memory form
    var newMemKey by remember { mutableStateOf("") }
    var newMemVal by remember { mutableStateOf("") }

    // Every sub-section carries its own icon, so no two rows share a glyph. The
    // security group is its own master section: the phone's permissions and the
    // app lock used to sit inside "system" alongside navigation and memory, five
    // rows deep, where the lock was easy to miss and the permission list had to
    // be scrolled past everything else to reach.
    val masterSections = listOf(
        Triple("lang", AppIcons.translate, t("ভাষা ও অনুবাদ")) to listOf(
            Triple("language", AppIcons.translate, t("অ্যাপ ও টুলের ভাষা"))
        ),
        Triple("audio", AppIcons.mic, t("ভয়েস ও মাইক")) to listOf(
            Triple("voice", AppIcons.recordVoice, t("ভয়েস প্রোফাইল")),
            Triple("mic", AppIcons.mic, t("মাইক ও ভাষা")),
            Triple("live", AppIcons.graphicEq, t("লাইভ ভয়েস"))
        ),
        Triple("keys", AppIcons.key, t("প্রোভাইডার ও এপিআই")) to listOf(
            Triple("providers", AppIcons.vpnKey, t("এআই প্রোভাইডার্স")),
            Triple("github", AppIcons.hub, "GitHub")
        ),
        Triple("updates", AppIcons.systemUpdate, t("আপডেট")) to listOf(
            Triple("updates", AppIcons.systemUpdate, t("অ্যাপ আপডেট"))
        ),
        Triple("ai", AppIcons.theater, t("মডেল ও পরিচয়")) to listOf(
            Triple("persona", AppIcons.theater, t("পরিচয়")),
            Triple("model", AppIcons.psychology, t("মডেল")),
            Triple("ondevice", AppIcons.memory, t("অন-ডিভাইস"))
        ),
        Triple("aura", AppIcons.diamond, t("ওরা ও থিম")) to listOf(
            Triple("theme", AppIcons.palette, t("থিম")),
            Triple("aura", AppIcons.autoAwesome, t("ওরা ও ব্যাকগ্রাউন্ড"))
        ),
        Triple("security", AppIcons.gppGood, t("নিরাপত্তা ও গোপনীয়তা")) to listOf(
            Triple("lock", AppIcons.fingerprint, t("অ্যাপ লক")),
            Triple("perms", AppIcons.adminPanel, t("পারমিশন")),
            Triple("privacy", AppIcons.privacyTip, t("গোপনীয়তা ও ডেটা"))
        ),
        Triple("system", AppIcons.shield, t("সিস্টেম")) to listOf(
            Triple("automation", AppIcons.bolt, t("অটোমেশন")),
            Triple("session", AppIcons.touch, t("নেভিগেশন")),
            Triple("memory", AppIcons.storage, t("মেমরি")),
            Triple("skills", AppIcons.extension, t("স্কিল গাইড"))
        )
    )

    val currentMaster = masterSections.find { (_, subs) -> subs.any { it.first == activePane } }
        ?: masterSections.first()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Glass.screen.copy(alpha = 0.98f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(64.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(BloodRedDark, BloodRedHot, BloodRedDark)))
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
        HorrorSheetBackdrop(Modifier.matchParentSize(), seed = 9)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(
                            AppIcons.dangerous, null, size = 18.dp, tint = BloodRedHot,
                            modifier = Modifier.horrorFlicker()
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = t("ZYRAXON সেটিংস ও কন্ট্রোল"),
                            fontSize = 17.5.sp,
                            fontWeight = FontWeight.Black,
                            color = BoneWhite,
                            style = TextStyle(shadow = BloodGlow)
                        )
                    }
                    Text(
                        text = t("৮টি সুবিন্যস্ত সেকশন — সম্পূর্ণ সিস্টেম নিয়ন্ত্রণ"),
                        fontSize = 11.sp,
                        color = Glass.pink,
                        style = TextStyle(shadow = BloodGlow)
                    )
                }
                IconButton(onClick = {
                    Glass.vibrate(context, 14)
                    onDismiss()
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Glass.textMuted)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 7 Master Categorized Sections Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                masterSections.forEach { (masterMeta, subs) ->
                    val (masterId, masterIcon, masterTitle) = masterMeta
                    val isMasterActive = currentMaster.first.first == masterId

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isMasterActive) Glass.pinkFill
                                else Glass.rowFill(0.35f)
                            )
                            .let {
                                if (isMasterActive) it.bloodPulseBorder(RoundedCornerShape(12.dp), 1.5.dp)
                                else it.border(1.dp, Glass.borderSoft, RoundedCornerShape(12.dp))
                            }
                            .clickable {
                                Glass.vibrate(context, 16)
                                val targetPane = subs.first().first
                                activePane = targetPane
                                viewModel.settingsPane = targetPane
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(masterIcon, null, size = 14.dp, tint = if (isMasterActive) Color.White else Glass.textPrimary)
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = masterTitle,
                            fontSize = 12.sp,
                            fontWeight = if (isMasterActive) FontWeight.Bold else FontWeight.Medium,
                            color = if (isMasterActive) Color.White else Glass.textPrimary
                        )
                    }
                }
            }

            // Sub-category Segmented Pills (only if more than 1 sub-section exists)
            if (currentMaster.second.size > 1) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Glass.rowFill(0.40f))
                        .border(1.dp, Glass.borderSoft, RoundedCornerShape(12.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    currentMaster.second.forEach { (subId, subIcon, subLabel) ->
                        val isSubActive = activePane == subId
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isSubActive) Glass.pinkFill else Color.Transparent)
                                .clickable {
                                    Glass.vibrate(context, 12)
                                    activePane = subId
                                    viewModel.settingsPane = subId
                                }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(
                                subIcon, null, size = 13.dp,
                                tint = if (isSubActive) Color.White else Glass.textSecondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = subLabel,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSubActive) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSubActive) Color.White else Glass.textSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, BloodRedHot, Color.Transparent)))
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Content Area Scrollable
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 380.dp, max = 540.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(Color(0x99240309), Color(0xB30A0104))))
                    .bloodPulseBorder(RoundedCornerShape(16.dp), 1.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                when (activePane) {
                    "language" -> {
                        HorrorSectionTitle(t("ভাষা ও স্বয়ংক্রিয় অনুবাদ (Language)"), AppIcons.translate)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            t("অ্যাপ্লিকেশন ইউআই এবং এজেন্টের টুল মেসেজের ভাষা নির্বাচন করুন। যেকোনো একটি ভাষা বাছলে অ্যাপের সকল ইন্টারফেস এবং টুলের ফলাফল ওই ভাষায় অনুবাদ হবে।"),
                            color = Glass.textSecondary,
                            fontSize = 11.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val activeLang = Loc.languages.firstOrNull { it.code == Loc.current }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Glass.pinkFill.copy(alpha = 0.12f))
                                .border(1.dp, Glass.pinkFill, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(activeLang?.flag ?: "🌐", fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    t("বর্তমান ভাষা: {0} ({1})", activeLang?.name ?: Loc.current, Loc.current),
                                    color = Glass.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    t("ইউআই অনুবাদ সক্রিয় • টুল মেসেজ অনুবাদ সক্রিয়"),
                                    color = Glass.pinkFill,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(t("ভাষা অনুসন্ধান ও নির্বাচন:"), color = Glass.cyanFill, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        val languages = remember { Loc.languages }
                        var languageFilter by remember { mutableStateOf("") }
                        BasicTextField(
                            value = languageFilter,
                            onValueChange = { languageFilter = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(BloodRowBrush)
                                .border(1.dp, Glass.borderSoft, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val shown = languages.filter { language ->
                                languageFilter.isBlank() ||
                                    language.name.contains(languageFilter, ignoreCase = true) ||
                                    language.code.contains(languageFilter, ignoreCase = true)
                            }
                            shown.forEach { language ->
                                val active = Loc.current == language.code
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (active) Glass.pinkFill.copy(alpha = 0.16f) else Glass.rowFill(0.22f))
                                        .border(1.dp, if (active) Glass.pinkFill else Glass.borderSoft, RoundedCornerShape(10.dp))
                                         .clickable {
                                            setLanguage(context, language.code)
                                            val voiceTag = when (language.code) {
                                                "bn" -> "bn-BD"
                                                "en" -> "en-US"
                                                "hi" -> "hi-IN"
                                                "ur" -> "ur-PK"
                                                "ar" -> "ar-SA"
                                                "es" -> "es-ES"
                                                "fr" -> "fr-FR"
                                                "de" -> "de-DE"
                                                "pt" -> "pt-BR"
                                                "ru" -> "ru-RU"
                                                "tr" -> "tr-TR"
                                                "id" -> "id-ID"
                                                "ms" -> "ms-MY"
                                                "zh-CN", "zh" -> "zh-CN"
                                                "zh-TW" -> "zh-TW"
                                                "ja" -> "ja-JP"
                                                "ko" -> "ko-KR"
                                                "it" -> "it-IT"
                                                "nl" -> "nl-NL"
                                                "pl" -> "pl-PL"
                                                "uk" -> "uk-UA"
                                                "vi" -> "vi-VN"
                                                "th" -> "th-TH"
                                                "fa" -> "fa-IR"
                                                "ta" -> "ta-IN"
                                                "te" -> "te-IN"
                                                "mr" -> "mr-IN"
                                                "gu" -> "gu-IN"
                                                "pa" -> "pa-IN"
                                                "ne" -> "ne-NP"
                                                "sw" -> "sw-KE"
                                                "fil" -> "fil-PH"
                                                "he" -> "he-IL"
                                                else -> "${language.code}-${language.code.uppercase()}"
                                            }
                                            viewModel.setVoiceLanguage(voiceTag)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(language.flag, fontSize = 16.sp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                language.name,
                                                color = Glass.textPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                language.code,
                                                color = Glass.textMuted,
                                                fontSize = 10.5.sp
                                            )
                                        }
                                    }
                                    if (active) {
                                        Icon(Icons.Rounded.Check, contentDescription = "Active", tint = Glass.pinkFill, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    "session" -> SessionNavPane(viewModel = viewModel, onDismiss = onDismiss)

                    "persona" -> {
                        val selectedPersona by viewModel.selectedPersona.collectAsState()
                        HorrorSectionTitle(t("পরিচয় নির্বাচন (Persona)"), AppIcons.theater)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            t("যে পরিচয় বাছবেন, তার কণ্ঠ ও কথা বলার ভঙ্গি সেই অনুযায়ী হবে।"),
                            color = Glass.textSecondary,
                            fontSize = 11.5.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        PersonaList(
                            selectedPersona = selectedPersona,
                            onSelectPersona = { viewModel.setPersona(it) }
                        )
                    }

                    "model" -> ModelListPane(viewModel = viewModel, onPicked = {})

                    "updates" -> UpdatePane(viewModel = viewModel)

                    // App lock lives on its own now: it is a privacy control, not a
                    // permission, and buried after the permission list it was the
                    // thing users could not find.
                    "lock" -> {
                        val appLock by viewModel.appLockEnabled.collectAsState()
                        val lockMode by viewModel.appLockMode.collectAsState()
                        var setup by remember { mutableStateOf<String?>(null) }

                        HorrorSectionTitle(t("অ্যাপ লক"), AppIcons.fingerprint)
                        Spacer(modifier = Modifier.height(10.dp))

                        // The secret lives in this app, so the whole flow is drawn
                        // here. Nothing opens a system screen, which is what kept
                        // the app from being dragged to the front while the agent
                        // was working elsewhere.
                        when (val kind = setup) {
                            null -> {
                                val hasSecret = lockMode != "none"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(BloodRowBrush)
                                        .border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AppIcon(
                                            if (hasSecret) AppIcons.verified else AppIcons.fingerprint,
                                            null, size = 18.dp,
                                            tint = if (hasSecret) Glass.greenFill else Glass.textMuted
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(t("অ্যাপ লক"), color = Glass.textPrimary, fontSize = 13.sp)
                                            Text(
                                                when (lockMode) {
                                                    "pattern" -> t("প্যাটার্ন সেট করা আছে — চালু আছে।")
                                                    "password" -> t("পাসওয়ার্ড সেট করা আছে — চালু আছে।")
                                                    else -> t("এখনো কোনো পাসওয়ার্ড বা প্যাটার্ন সেট করা হয়নি।")
                                                },
                                                color = Glass.textMuted, fontSize = 11.sp
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = appLock && hasSecret,
                                        enabled = hasSecret,
                                        onCheckedChange = { viewModel.setAppLockEnabled(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Glass.textPrimary,
                                            checkedTrackColor = Glass.cyanFill
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    t("অ্যাপ খুললে বা ব্যাকগ্রাউন্ড থেকে ফিরলে ZYRAXON নিজের পাসওয়ার্ড বা প্যাটার্ন চাইবে। এজেন্ট অন্য অ্যাপ খুললে বা কল করলে আপনার অ্যাপ সামনে আসবে না — লক শুধু আপনি অ্যাপে ঢুকলে দেখাবে।"),
                                    color = Glass.textSecondary, fontSize = 11.5.sp
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                HorrorSectionTitle(t("পাসওয়ার্ড"), AppIcons.password)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    LockKindCard(
                                        icon = AppIcons.password,
                                        title = t("পাসওয়ার্ড"),
                                        desc = t("৪–৮ সংখ্যার পিন"),
                                        selected = lockMode == "password",
                                        modifier = Modifier.weight(1f)
                                    ) { setup = "password" }
                                    LockKindCard(
                                        icon = AppIcons.gesture,
                                        title = t("প্যাটার্ন"),
                                        desc = t("৩×৩ বিন্দু"),
                                        selected = lockMode == "pattern",
                                        modifier = Modifier.weight(1f)
                                    ) { setup = "pattern" }
                                }
                                if (hasSecret) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    PrivacyAction(
                                        icon = AppIcons.lockOpen,
                                        title = t("লক সরান"),
                                        desc = t("সংরক্ষিত পাসওয়ার্ড বা প্যাটার্ন মুছে ফেলুন"),
                                        onAction = { viewModel.removeAppLockSecret() }
                                    )
                                }
                            }
                            else -> {
                                AppLockSetup(
                                    kind = kind,
                                    onCancel = { setup = null },
                                    onDone = { secret ->
                                        if (kind == "pattern") viewModel.setAppLockPattern(secret)
                                        else viewModel.setAppLockPassword(secret)
                                        setup = null
                                    }
                                )
                            }
                        }
                    }

                    "perms" -> {
                        val activity = context as? ai.zyraxon.assistant.MainActivity
                        HorrorSectionTitle(t("সিস্টেম পারমিশন"), AppIcons.adminPanel)
                        Spacer(modifier = Modifier.height(10.dp))
                        PermissionPanel(
                            context = context,
                            accessibilityOn = systemStatus.accessibilityConnected,
                            onRequestPermission = { row ->
                                val perms = row.androidPermissions.ifEmpty {
                                    listOfNotNull(row.androidPermission)
                                }
                                if (perms.isNotEmpty()) activity?.requestPermissions(perms)
                            },
                            onOpenSpecial = { page -> activity?.openSpecialSetting(page) }
                        )
                    }

                    "automation" -> {
                        // How much the agent may do on its own. The level is read on
                        // every tool call (AutomationPolicy), so changing it here
                        // changes what the agent does immediately — the setting is
                        // not advice, it is the gate.
                        val context2 = context
                        var level by remember {
                            mutableStateOf(ai.zyraxon.assistant.permission.AutomationPolicy.level(context2))
                        }
                        val levels = listOf(
                            ai.zyraxon.assistant.permission.AutomationPolicy.LOCKED to (
                                t("সম্পূর্ণ জিজ্ঞাসা") to
                                    t("যেকোনো পরিবর্তন আনার আগে জিজ্ঞাসা করবে। সবচেয়ে নিরাপদ।")),
                            ai.zyraxon.assistant.permission.AutomationPolicy.SUPERVISED to (
                                t("তত্ত্বাবধানে") to
                                    t("সিস্টেম ও ব্যক্তিগত কাজের আগে জিজ্ঞাসা করবে; সাধারণ কাজ নিজে করবে।")),
                            ai.zyraxon.assistant.permission.AutomationPolicy.BALANCED to (
                                t("সুষম") to
                                    t("শুধু সিস্টেম-স্তরের কাজের আগে জিজ্ঞাসা করবে; বাকিটা নিজে করবে।")),
                            ai.zyraxon.assistant.permission.AutomationPolicy.AUTOMATIC to (
                                t("সম্পূর্ণ অটোমেশন") to
                                    t("কিছুই জিজ্ঞাসা করবে না — সম্পূর্ণ অটোমেশন কাজ করবে।"))
                        )

                        Text(
                            t("এজেন্ট কতটা নিজে থেকে কাজ করবে, তা এখানে ঠিক হয়। লেভেল কোড-লেভেল থেকে প্রয়োগ হয়, তাই বদলালে সাথে সাথেই কার্যকর হবে।"),
                            color = Glass.textSecondary, fontSize = 12.sp, lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        levels.forEach { (value, pair) ->
                            val (title, desc) = pair
                            val active = level == value
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (active) androidx.compose.ui.graphics.SolidColor(Glass.pinkFill.copy(alpha = 0.16f))
                                        else BloodRowBrush
                                    )
                                    .border(1.dp, if (active) Glass.pinkFill else Glass.borderSoft, RoundedCornerShape(14.dp))
                                    .clickable {
                                        level = value
                                        ai.zyraxon.assistant.permission.AutomationPolicy.setLevel(context2, value)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = active,
                                    onClick = {
                                        level = value
                                        ai.zyraxon.assistant.permission.AutomationPolicy.setLevel(context2, value)
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Glass.pinkFill, unselectedColor = Glass.textMuted
                                    )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(title, color = Glass.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text(desc, color = Glass.textMuted, fontSize = 11.5.sp, lineHeight = 16.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        // The fully-offline switch: the agent may then use only the
                        // on-device model and the tools that keep the work local.
                        var onDevice by remember {
                            mutableStateOf(ai.zyraxon.assistant.permission.AutomationPolicy.onDeviceOnly(context2))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(BloodRowBrush)
                                .border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t("শুধু অন-ডিভাইস"), color = Glass.textPrimary, fontSize = 13.sp)
                                Text(
                                    t("চালু থাকলে ক্লাউড ব্যবহার হবে না — অন-ডিভাইস মডেল ও লোকাল টুল দিয়ে কাজ হবে, কিছুই ফোন থেকে বাইরে যাবে না।"),
                                    color = Glass.textMuted, fontSize = 11.sp, lineHeight = 16.sp
                                )
                            }
                            Switch(
                                checked = onDevice,
                                onCheckedChange = { want ->
                                    onDevice = want
                                    ai.zyraxon.assistant.permission.AutomationPolicy.setOnDeviceOnly(context2, want)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Glass.textPrimary, checkedTrackColor = Glass.cyanFill)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            t("স্তরের সীমা:"),
                            color = Glass.cyanFill, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        listOf(
                            t("নিরাপদ (ফাইল পড়া, মেমরি, ডকুমেন্ট) — কোনো লেভেলেই জিজ্ঞাসা হবে না।"),
                            t("ব্যক্তিগত (SMS, কল, ক্যামেরা, স্ক্রিন, ট্যাপ) — প্রথম দুই লেভেলে জিজ্ঞাসা করবে।"),
                            t("সিস্টেম (শেল, বিল্ড, পাবলিশ, ডিলিট, লক) — শেষ লেভেল ছাড়া সবখানে জিজ্ঞাসা করবে।")
                        ).forEach { line ->
                            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.Top) {
                                Text("•", color = Glass.pink, fontSize = 13.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(line, color = Glass.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                            }
                        }
                    }

                    "live" -> {
                        // LIVE SPEECH-TO-SPEECH
                        val liveOn by viewModel.liveVoiceEnabled.collectAsState()
                        val liveState by viewModel.liveVoiceState.collectAsState()
                        val liveModel by viewModel.liveVoiceModel.collectAsState()
                        val liveProvider by viewModel.liveVoiceProvider.collectAsState()
                        val models by viewModel.availableModels.collectAsState()
                        val selected by viewModel.selectedModel.collectAsState()
                        // The realtime models the loaded catalogue actually holds.
                        // Nothing is listed here that the providers did not send.
                        val realtimeModels = models.filter { it.isRealtime }

                        HorrorSectionTitle(t("লাইভ ভয়েস — স্পিচ-টু-স্পিচ"), AppIcons.graphicEq)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            t("চালু থাকলে রিয়েলটাইম মডেল নিজের কণ্ঠে সরাসরি কথা বলে, আর ফোনের TTS বন্ধ থাকে। বন্ধ থাকলে মডেলের লেখা ফোনের TTS পড়ে শোনায় — তাই লাইভ সংযোগ না হলেও কথা শোনা যায়।"),
                            color = Glass.textSecondary,
                            fontSize = 11.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // The live voice switch. Off hands the voice back to the
                        // phone's own TTS, so a realtime model that cannot open a
                        // socket still speaks normally instead of going silent.
                        val preferred by viewModel.liveVoicePreferred.collectAsState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(BloodRowBrush)
                                .border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t("মডেলের নিজের কণ্ঠ ব্যবহার করো"), color = Glass.textPrimary, fontSize = 13.sp)
                                Text(
                                    t("বন্ধ থাকলে ফোনের TTS পড়ে শোনায় (সবসময় কাজ করে)"),
                                    color = Glass.textMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = preferred,
                                onCheckedChange = { viewModel.setLiveVoicePreferred(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Glass.textPrimary, checkedTrackColor = Glass.cyanFill)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Live status. The switch follows the model, so this is a
                        // read-out, not a control.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (liveOn) Glass.cyanFill.copy(alpha = 0.22f) else Glass.rowFill(0.30f))
                                .border(1.dp, if (liveOn) Glass.cyanFill else Glass.borderSoft, RoundedCornerShape(14.dp))
                                .padding(14.dp)
                                .testTag("live_voice_toggle")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        if (liveOn) t("লাইভ ভয়েস চালু") else t("লাইভ ভয়েস বন্ধ"),
                                        color = Glass.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        when (liveState) {
                                            ai.zyraxon.assistant.voice.LiveVoiceSession.State.CONNECTING -> t("সংযোগ হচ্ছে…")
                                            ai.zyraxon.assistant.voice.LiveVoiceSession.State.LISTENING -> t("শুনছে…")
                                            ai.zyraxon.assistant.voice.LiveVoiceSession.State.SPEAKING -> t("বলছে…")
                                            else -> if (liveOn) t("অপেক্ষায়") else t("রিয়েলটাইম মডেল বাছলে নিজেই চালু হবে")
                                        },
                                        color = Glass.textSecondary,
                                        fontSize = 11.sp
                                    )
                                    if (liveOn && liveModel.isNotBlank()) {
                                        Text(
                                            "$liveProvider · $liveModel",
                                            color = Glass.cyanFill,
                                            fontSize = 10.5.sp
                                        )
                                    }
                                }
                                AppIcon(if (liveOn) AppIcons.mic else AppIcons.micOff, null, size = 20.dp, tint = Glass.textPrimary)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            t("রিয়েলটাইম মডেল (নিজের কণ্ঠে কথা বলে):"),
                            color = Glass.cyanFill,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        if (realtimeModels.isEmpty()) {
                            Text(
                                t("এখন কোনো রিয়েলটাইম মডেল লোড হয়নি। এআই প্রোভাইডার্স ট্যাবে Gemini, OpenAI বা Grok-এর কী দিলে তাদের লাইভ মডেল এখানে আসবে।"),
                                color = Glass.amber,
                                fontSize = 11.sp
                            )
                        } else {
                            realtimeModels.forEach { m ->
                                val active = selected.id == m.id
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (active) Glass.pinkFill.copy(alpha = 0.20f) else Glass.rowFill(0.25f))
                                        .border(1.dp, if (active) Glass.pinkFill else Glass.borderSoft, RoundedCornerShape(10.dp))
                                        .clickable { viewModel.setModel(m) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(m.name, color = Glass.textPrimary, fontSize = 11.5.sp)
                                            Text(m.provider, color = Glass.textSecondary, fontSize = 10.sp)
                                        }
                                        if (active) Icon(Icons.Rounded.Check, contentDescription = "Active", tint = Glass.pinkFill, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            t("নিচের স্লাইডারে কণ্ঠ বাছলে রিয়েলটাইম মডেলেও সেই কণ্ঠই ব্যবহার হয়।"),
                            color = Glass.textMuted,
                            fontSize = 10.5.sp
                        )
                    }
                    "ondevice" -> {
                        // ON-DEVICE (OFFLINE) MODEL
                        var installed by remember { mutableStateOf(viewModel.onDeviceModel.installedModels()) }
                        var busy by remember { mutableStateOf("") }
                        var progress by remember { mutableStateOf(0f) }
                        var note by remember { mutableStateOf("") }
                        var hfToken by remember { mutableStateOf("") }
                        val scope = rememberCoroutineScope()

                        HorrorSectionTitle(t("অন-ডিভাইস মডেল — ইন্টারনেট ছাড়াই উত্তর"), AppIcons.memory)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            t("মডেল ফাইলটা ফোনেই চলে, কোনো ডেটা বাইরে যায় না। ফাইল বড় (৫০০MB+), তাই একবার ডাউনলোড করে রাখা হয়।"),
                            color = Glass.textSecondary,
                            fontSize = 11.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            t("ডাউনলোড করা মডেল:"),
                            color = Glass.cyanFill,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        if (installed.isEmpty()) {
                            Text(t("এখনো কিছু ডাউনলোড করা হয়নি।"), color = Glass.textSecondary, fontSize = 11.5.sp)
                        } else {
                            installed.forEach { f ->
                                val loaded = viewModel.onDeviceModel.loadedPath == f.absolutePath
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (loaded) Glass.cyanFill.copy(alpha = 0.20f) else Glass.rowFill(0.25f))
                                        .border(1.dp, if (loaded) Glass.cyanFill else Glass.borderSoft, RoundedCornerShape(10.dp))
                                        .clickable {
                                            note = t("লোড হচ্ছে: ") + f.name
                                            scope.launch {
                                                val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    viewModel.onDeviceModel.load(f.absolutePath)
                                                }
                                                // Remember the loaded bundle so the next
                                                // launch reloads it instead of silently
                                                // falling back to the cloud.
                                                viewModel.rememberOnDeviceModel(if (ok) f.absolutePath else null)
                                                note = if (ok) t("লোড হয়েছে: ") + f.name
                                                else t("লোড ব্যর্থ: ") + f.name
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Column {
                                        Text(f.name, color = Glass.textPrimary, fontSize = 11.5.sp)
                                        Text(
                                            (f.length() / (1024 * 1024)).toString() + " MB" + if (loaded) " " + t("লোড করা আছে") else "",
                                            color = Glass.textSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            t("ডাউনলোডযোগ্য মডেল — সবগুলো টোকেন ছাড়াই নামে:"),
                            color = Glass.cyanFill,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            t("ছোট থেকে বড় সাজানো। কম RAM-এর ফোনে উপরের হালকা মডেলটা বেছে নাও। কোনো টোকেন লাগে না।"),
                            color = Glass.textSecondary,
                            fontSize = 10.5.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = hfToken,
                            onValueChange = { hfToken = it },
                            label = { Text(t("Hugging Face টোকেন (ঐচ্ছিক, শুধু গেটেড ফাইলের জন্য)"), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        ai.zyraxon.assistant.ai.OnDeviceModel.SUGGESTED.forEach { info ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Glass.rowFill(0.25f))
                                    .border(1.dp, Glass.borderSoft, RoundedCornerShape(10.dp))
                                    .clickable(enabled = busy.isBlank()) {
                                        busy = info.name
                                        progress = 0f
                                        scope.launch {
                                            val file = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                viewModel.onDeviceModel.download(info.url, info.fileName, hfToken) { done, total ->
                                                    if (total > 0) progress = done.toFloat() / total.toFloat()
                                                }
                                            }
                                            installed = viewModel.onDeviceModel.installedModels()
                                            note = if (file != null) t("ডাউনলোড হয়েছে: ") + file.name
                                            else t("ডাউনলোড ব্যর্থ — ইন্টারনেট দেখুন")
                                            busy = ""
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Column {
                                    Text(info.name, color = Glass.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${info.approxMb} MB " + if (info.requiresToken) t("টোকেন লাগবে") else t("টোকেন ছাড়াই"),
                                        color = if (info.requiresToken) Glass.amber else Glass.textSecondary,
                                        fontSize = 10.sp
                                    )
                                    if (busy == info.name) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier.fillMaxWidth(),
                                            color = Glass.cyanFill
                                        )
                                        Text(
                                            (progress * 100).toInt().toString() + "%",
                                            color = Glass.textSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }

                        if (note.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(note, color = Glass.pinkFill, fontSize = 11.sp)
                        }
                    }

                    "theme" -> ai.zyraxon.assistant.ui.shell.ThemePane(viewModel)

                    "voice" -> {
                        ai.zyraxon.assistant.ui.shell.VoiceLab(viewModel)
                        Spacer(modifier = Modifier.height(16.dp))
                        HorrorSectionTitle(t("ভয়েস সেটিংস ও কণ্ঠের প্রকার (Text-to-Speech)"), AppIcons.recordVoice)
                        Spacer(modifier = Modifier.height(8.dp))

                        val currentGender by viewModel.voiceGender.collectAsState()

                        Text(t("কণ্ঠ নির্বাচন করুন (Voice Profile):"), color = Glass.cyanFill, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val isFemale = currentGender == "female"
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isFemale) Glass.pinkFill.copy(alpha = 0.20f) else Glass.rowFill(0.30f))
                                    .border(
                                        1.dp,
                                        if (isFemale) Glass.pinkFill else Glass.borderSoft,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { viewModel.setVoiceGender("female") }
                                    .padding(12.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    AppIcon(AppIcons.female, null, size = 26.dp, tint = Glass.textPrimary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(t("মেয়ের কণ্ঠ"), color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(t("মিষ্টি ও রোমান্টিক"), color = Glass.pinkFill, fontSize = 10.5.sp)
                                    if (isFemale) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Icon(Icons.Rounded.Check, contentDescription = "Active", tint = Glass.pinkFill, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            val isMale = currentGender == "male"
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isMale) Glass.cyanFill.copy(alpha = 0.20f) else Glass.rowFill(0.30f))
                                    .border(
                                        1.dp,
                                        if (isMale) Glass.cyanFill else Glass.borderSoft,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { viewModel.setVoiceGender("male") }
                                    .padding(12.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    AppIcon(AppIcons.boy, null, size = 26.dp, tint = Glass.textPrimary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(t("ছেলের কণ্ঠ"), color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(t("গম্ভীর ও আত্মবিশ্বাসী"), color = Glass.cyanFill, fontSize = 10.5.sp)
                                    if (isMale) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Icon(Icons.Rounded.Check, contentDescription = "Active", tint = Glass.cyanFill, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        val voiceErr by viewModel.voiceError.collectAsState()
                        val useRemote by viewModel.useRemoteVoice.collectAsState()
                        val remoteName by viewModel.remoteVoiceName.collectAsState()
                        Text(
                            when {
                                voiceErr.isNotBlank() -> t("{0} — ফোনের কণ্ঠে পড়া হচ্ছে।", voiceErr)
                                useRemote && remoteName.isNotBlank() -> t("এখন বলছে: {0}", remoteName)
                                useRemote -> t("ওয়েবসাইটের কণ্ঠ প্রস্তুত (প্রথম বাক্য এলেই কণ্ঠের নাম দেখা যাবে)।")
                                else -> t("ফোনের নিজের ইঞ্জিন কথা বলছে।")
                            },
                            color = if (voiceErr.isNotBlank()) Glass.amber else Glass.textMuted,
                            fontSize = 10.5.sp
                        )

                        val ttsOk by viewModel.ttsAvailable.collectAsState()
                        if (!ttsOk) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                t("এই ফোনে কোনো স্পিচ ইঞ্জিন নেই — Google Speech Services ইনস্টল করলে কথা বলতে পারবে।"),
                                color = Glass.amber,
                                fontSize = 11.sp
                            )
                        }

                        val voiceMissing by viewModel.voiceDataMissing.collectAsState()
                        if (ttsOk && voiceMissing) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Glass.amber.copy(alpha = 0.16f))
                                    .border(1.dp, Glass.amber, RoundedCornerShape(12.dp))
                                    .clickable { viewModel.openVoiceDataInstall() }
                                    .padding(12.dp)
                                    .testTag("download_voice_data")
                            ) {
                                Column {
                                    Text(
                                        t("বাংলা কণ্ঠ ডাউনলোড করুন"),
                                        color = Glass.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp
                                    )
                                    Text(
                                        t("ফোনে বাংলা ভয়েস ডেটা নেই, তাই বাংলা লেখা ঠিকভাবে পড়া যায় না। ট্যাপ করলে Google ইঞ্জিনের ডাউনলোড স্ক্রিন খুলবে।"),
                                        color = Glass.textSecondary,
                                        fontSize = 10.5.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(t("উত্তরের অডিও শোনানো হবে"), color = Glass.textPrimary, fontSize = 13.sp)
                            Switch(
                                checked = ttsEnabled,
                                onCheckedChange = { viewModel.setTtsEnabled(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Glass.textPrimary, checkedTrackColor = Glass.pinkFill)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(t("কথা বলার গতি (Speed): {0}x", String.format("%.2f", ttsRate)), color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = ttsRate,
                            onValueChange = { viewModel.setTtsRate(it) },
                            valueRange = 0.6f..1.6f,
                            colors = SliderDefaults.colors(thumbColor = Glass.pinkFill, activeTrackColor = Glass.pinkFill)
                        )

                        Text(t("কণ্ঠের পিচ (Pitch): {0}", String.format("%.2f", ttsPitch)), color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = ttsPitch,
                            onValueChange = { viewModel.setTtsPitch(it) },
                            valueRange = 0.6f..1.6f,
                            colors = SliderDefaults.colors(thumbColor = Glass.cyanFill, activeTrackColor = Glass.cyanFill)
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.testVoice() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Glass.pinkFill)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Test")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(t("ভয়েস টেস্ট করুন (Test Voice)"), color = Glass.textOnAccent, fontWeight = FontWeight.Bold)
                        }
                    }

                    "mic" -> {
                        // MICROPHONE CONTROLS
                        HorrorSectionTitle(t("মাইক্রোফোন ও স্পিচ রিকগনিশন (Microphone)"), AppIcons.mic)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            t("মাইকে কথা বলার ইনপুট ভাষা নির্বাচন করুন এবং লিসেনিং আচরণ পরিচালনা করুন।"),
                            color = Glass.textSecondary,
                            fontSize = 11.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val currentLang by viewModel.voiceLanguage.collectAsState()
                        Text(t("মাইকে কথা বলার ভাষা (Speech Recognition Language):"), color = Glass.cyanFill, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        val micLanguages = listOf(
                            "bn-BD" to t("বাংলা"), "en-US" to "English", "hi-IN" to "हिन्दी", "ur-PK" to "اردو",
                            "ar-SA" to "العربية", "es-ES" to "Español", "fr-FR" to "Français", "de-DE" to "Deutsch",
                            "pt-BR" to "Português", "ru-RU" to "Русский", "tr-TR" to "Türkçe", "id-ID" to "Indonesia",
                            "ms-MY" to "Melayu", "zh-CN" to "中文", "ja-JP" to "日本語", "ko-KR" to "한국어",
                            "it-IT" to "Italiano", "nl-NL" to "Nederlands", "pl-PL" to "Polski", "uk-UA" to "Українська",
                            "vi-VN" to "Tiếng Việt", "th-TH" to "ไทย", "fa-IR" to "فارسی", "ta-IN" to "தமிழ்",
                            "te-IN" to "తెలుగు", "mr-IN" to "मराठी", "gu-IN" to "ગુજરાતી", "pa-IN" to "ਪੰਜਾਬੀ",
                            "ne-NP" to "नेपाली", "sw-KE" to "Kiswahili", "fil-PH" to "Filipino", "he-IL" to "עברית"
                        )
                        micLanguages.chunked(3).forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { (tag, label) ->
                                    val active = currentLang == tag
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (active) Glass.cyanFill.copy(alpha = 0.16f) else Glass.rowFill(0.30f))
                                            .border(1.dp, if (active) Glass.cyanFill else Glass.borderSoft, RoundedCornerShape(12.dp))
                                            .clickable { viewModel.setVoiceLanguage(tag) }
                                            .padding(vertical = 10.dp, horizontal = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(label, color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                                repeat(3 - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t("মাইক চালু রাখো (Always listening)"), color = Glass.textPrimary, fontSize = 13.sp)
                                Text(t("বন্ধ করলে আপনি নিজেই মাইক বন্ধ রাখতে পারবেন; চালু করলে সবসময় শুনবে"), color = Glass.textMuted, fontSize = 11.sp)
                            }
                            Switch(
                                checked = alwaysListening,
                                onCheckedChange = { viewModel.setAlwaysListening(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Glass.textPrimary, checkedTrackColor = Glass.cyanFill)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(t("কথা থামালে অটো-সেন্ড"), color = Glass.textPrimary, fontSize = 13.sp)
                                Text(t("বাক্য শেষ হলে সরাসরি উত্তর তৈরি হবে"), color = Glass.textMuted, fontSize = 11.sp)
                            }
                            Switch(
                                checked = autoSend,
                                onCheckedChange = { viewModel.setAutoSend(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Glass.textPrimary, checkedTrackColor = Glass.cyanFill)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (alwaysListening) Glass.greenFill.copy(alpha = 0.18f) else Glass.rowFill(0.30f))
                                .border(1.dp, if (alwaysListening) Glass.greenFill else Glass.borderSoft, RoundedCornerShape(14.dp))
                                .clickable { viewModel.setMicEnabled(!alwaysListening) }
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (alwaysListening) t("মাইক চালু — বন্ধ করতে চাপুন") else t("মাইক বন্ধ — চালু করতে চাপুন"),
                                color = Glass.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    "aura" -> {
                        // AURA & BACKGROUND
                        HorrorSectionTitle(t("ওরা ও ব্যাকগ্রাউন্ড অ্যানিমেশন"), AppIcons.autoAwesome)
                        Spacer(modifier = Modifier.height(8.dp))

                        // The aura gallery: twelve designed auras, each shown live.
                        // The picked one is what appears in the middle of the home
                        // screen, recoloured by the hue below.
                        Text(t("ওরার স্টাইল — ছুঁয়ে বেছে নাও"), color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        val galleryClock by rememberLiveTransition(label = "gallery").animateFloat(
                            initialValue = 0f,
                            targetValue = 3600f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(3_600_000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "galleryClock"
                        )
                        AuraStyleIds.chunked(3).forEach { rowIds ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowIds.forEach { styleId ->
                                    val picked = auraStyle == styleId
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(BloodCardBrush)
                                            .let {
                                                if (picked) it.bloodPulseBorder(RoundedCornerShape(14.dp), 1.6.dp)
                                                else it.border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp))
                                            }
                                            .clickable {
                                                Glass.vibrate(context, 16)
                                                viewModel.setAuraStyle(styleId)
                                            }
                                            .padding(vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        AuraPreview(
                                            style = styleId,
                                            hue = auraHue,
                                            clock = galleryClock,
                                            modifier = Modifier.size(84.dp)
                                        )
                                        Text(
                                            text = t(auraStyleLabel(styleId)),
                                            color = if (picked) BoneWhite else Glass.textSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = if (picked) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1,
                                            style = if (picked) TextStyle(shadow = BloodGlowSoft) else TextStyle.Default
                                        )
                                    }
                                }
                                repeat(3 - rowIds.size) { Spacer(modifier = Modifier.weight(1f)) }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))

                        // The aura itself. These four sliders drive the CSS
                        // variables inside aura.html, so the change is instant.
                        Text(t("ওরার রঙ"), color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                352 to t("রক্ত"),
                                330 to t("গোলাপি"),
                                268 to t("বেগুনি"),
                                190 to t("আকাশি"),
                                150 to t("সবুজ"),
                                30 to t("আগুন")
                            ).forEach { (hueValue, label) ->
                                val isSelected = kotlin.math.abs(auraHue - hueValue) <= 8
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isSelected) Glass.pinkFill.copy(alpha = 0.22f)
                                            else Glass.rowFill(0.30f)
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) Glass.pinkFill else Glass.borderSoft,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable { viewModel.setAuraHue(hueValue) }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) Glass.pinkFill else Glass.textSecondary,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(t("রঙের সূক্ষ্ম নিয়ন্ত্রণ: {0}°", auraHue), color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = auraHue.toFloat(),
                            onValueChange = { viewModel.setAuraHue(it.toInt()) },
                            valueRange = 0f..360f,
                            colors = SliderDefaults.colors(thumbColor = Glass.pinkFill, activeTrackColor = Glass.pinkFill)
                        )
                        Text(t("ওরার আকার: {0}x", String.format("%.2f", auraSize)), color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = auraSize,
                            onValueChange = { viewModel.setAuraSize(it) },
                            valueRange = 0.6f..1.5f,
                            colors = SliderDefaults.colors(thumbColor = Glass.pinkFill, activeTrackColor = Glass.pinkFill)
                        )
                        Text(t("আভার তীব্রতা: {0}x", String.format("%.2f", auraGlow)), color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = auraGlow,
                            onValueChange = { viewModel.setAuraGlow(it) },
                            valueRange = 0.2f..2.0f,
                            colors = SliderDefaults.colors(thumbColor = Glass.pinkFill, activeTrackColor = Glass.pinkFill)
                        )
                        Text(t("ঘোরার গতি: {0}x", String.format("%.2f", auraSpeed)), color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = auraSpeed,
                            onValueChange = { viewModel.setAuraSpeed(it) },
                            valueRange = 0.3f..3.0f,
                            colors = SliderDefaults.colors(thumbColor = Glass.pinkFill, activeTrackColor = Glass.pinkFill)
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Text(t("প্যানেলের স্বচ্ছতা: {0}%", (panelAlpha * 100).toInt()), color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = panelAlpha,
                            onValueChange = { viewModel.setPanelAlpha(it) },
                            valueRange = 0.06f..0.6f,
                            colors = SliderDefaults.colors(thumbColor = Glass.cyanFill, activeTrackColor = Glass.cyanFill)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // The floating eye: its own section, with an on/off switch and a
                        // gallery of looks. Turned on, it stays up over every app.
                        val overlayOn by viewModel.overlayEnabled.collectAsState()
                        val overlayStyle by viewModel.overlayStyle.collectAsState()
                        Text(t("ভাসমান চোখ"), color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            t("চালু করলে অন্য যেকোনো অ্যাপের উপরে সবসময় ভেসে থাকবে। ") +
                                t("ট্যাপে মাইক চালু/বন্ধ, ধরে রাখলে লেখার বক্স খোলে ও বন্ধ হয়।"),
                            color = Glass.textMuted,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (overlayOn) Glass.greenFill.copy(alpha = 0.20f) else Glass.rowFill(0.30f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (overlayOn) t("চালু — সবসময় ভাসছে") else t("বন্ধ"),
                                color = if (overlayOn) Glass.greenFill else Glass.textPrimary,
                                fontSize = 13.sp
                            )
                            Switch(
                                checked = overlayOn,
                                onCheckedChange = { want ->
                                    if (want) {
                                        // canDrawOverlays is API 23; before that the
                                        // platform always allows overlays.
                                        val ok = android.os.Build.VERSION.SDK_INT < 23 ||
                                            android.provider.Settings.canDrawOverlays(context)
                                        if (!ok) (context as? ai.zyraxon.assistant.MainActivity)
                                            ?.openSpecialSetting("overlay")
                                        else {
                                            viewModel.setOverlayEnabled(true)
                                            ai.zyraxon.assistant.OverlayChatService.start(context)
                                        }
                                    } else {
                                        viewModel.setOverlayEnabled(false)
                                        if (ai.zyraxon.assistant.voice.WakeWord.enabled(context))
                                            ai.zyraxon.assistant.OverlayChatService.hideEyeNow()
                                        else ai.zyraxon.assistant.OverlayChatService.stop(context)
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = Glass.greenFill)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(t("চোখের ডিজাইন"), color = Glass.textSecondary, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        // id, label, the two colours of its swatch
                        val eyeLooks = listOf(
                            Triple("auto", t("স্বয়ংক্রিয়"), listOf(Color(0xFF8B7BFF), Color(0xFFC81E28))),
                            Triple("eye", t("চোখ"), listOf(Color(0xFFC81E28), Color(0xFF2A0004))),
                            Triple("orb", t("গ্লো অর্ব"), listOf(Color(0xFFFFFFFF), Color(0xFF8B7BFF))),
                            Triple("neon", t("নিয়ন রিং"), listOf(Color(0xFF37D6C5), Color(0xFFB35BFF))),
                            Triple("galaxy", t("গ্যালাক্সি"), listOf(Color(0xFFFFB02E), Color(0xFF5B3DF5))),
                            Triple("orbit", t("অরবিট"), listOf(Color(0xFF5B8CFF), Color(0xFF37D6C5))),
                            Triple("plasma", t("প্লাজমা"), listOf(Color(0xFFFF3B6B), Color(0xFFFFB02E))),
                            Triple("pulsar", t("পালসার"), listOf(Color(0xFFFFFFFF), Color(0xFF37D6C5))),
                            Triple("prism", t("প্রিজম"), listOf(Color(0xFFB35BFF), Color(0xFFFF3B6B))),
                            Triple("edge", t("পর্দার কিনারা"), listOf(Color(0xFFFF3B6B), Color(0xFF5B8CFF)))
                        )
                        eyeLooks.chunked(3).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { (id, label, colors) ->
                                    val picked = overlayStyle == id
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(if (picked) Glass.pinkFill.copy(alpha = 0.20f) else Glass.rowFill(0.30f))
                                            .border(1.dp, if (picked) Glass.pinkFill else Glass.borderSoft, RoundedCornerShape(14.dp))
                                            .clickable { viewModel.setOverlayStyle(id) }
                                            .padding(vertical = 10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(Brush.linearGradient(colors))
                                        )
                                        Text(label, color = Glass.textPrimary, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                                // Keeps the last row's cells the same width.
                                repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        // Voice command: the assistant listens in the background and wakes
                        // when it hears one of the user's own phrases, in any language.
                        var wakeOn by remember { mutableStateOf(ai.zyraxon.assistant.voice.WakeWord.enabled(context)) }
                        val wakePhrases = remember {
                            mutableStateListOf<String>().apply { addAll(ai.zyraxon.assistant.voice.WakeWord.phrases(context)) }
                        }
                        var newPhrase by remember { mutableStateOf("") }
                        val savePhrases = {
                            ai.zyraxon.assistant.voice.WakeWord.setPhrases(context, wakePhrases.toList())
                            ai.zyraxon.assistant.OverlayChatService.refreshWake()
                        }
                        Text(t("ভয়েস কমান্ড"), color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            t("অ্যাপ ব্যাকগ্রাউন্ডে থাকলেও নাম ধরে ডাকলে ভাসমান চোখ চালু হবে ও তোমার কথা শুনবে। ") +
                                t("নিজের পছন্দের কমান্ড বাংলা, ইংরেজি বা যেকোনো ভাষায় যোগ করো।"),
                            color = Glass.textMuted,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (wakeOn) Glass.greenFill.copy(alpha = 0.20f) else Glass.rowFill(0.30f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (wakeOn) t("চালু — ডাকলেই সাড়া দেবে") else t("বন্ধ"),
                                color = if (wakeOn) Glass.greenFill else Glass.textPrimary,
                                fontSize = 13.sp
                            )
                            Switch(
                                checked = wakeOn,
                                onCheckedChange = { want ->
                                    if (want && androidx.core.content.ContextCompat.checkSelfPermission(
                                            context, android.Manifest.permission.RECORD_AUDIO
                                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                    ) {
                                        (context as? android.app.Activity)?.requestPermissions(
                                            arrayOf(android.Manifest.permission.RECORD_AUDIO), 4401
                                        )
                                    } else {
                                        wakeOn = want
                                        ai.zyraxon.assistant.voice.WakeWord.setEnabled(context, want)
                                        if (want) ai.zyraxon.assistant.OverlayChatService.start(context)
                                        else if (!viewModel.overlayEnabled.value) ai.zyraxon.assistant.OverlayChatService.stop(context)
                                        else ai.zyraxon.assistant.OverlayChatService.refreshWake()
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = Glass.greenFill)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        wakePhrases.toList().forEachIndexed { i, phrase ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Glass.rowFill(0.30f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(phrase, color = Glass.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(
                                    "✕", color = Glass.textMuted, fontSize = 15.sp,
                                    modifier = Modifier
                                        .clickable { wakePhrases.removeAt(i); savePhrases() }
                                        .padding(horizontal = 8.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newPhrase,
                                onValueChange = { newPhrase = it },
                                singleLine = true,
                                placeholder = { Text(t("নতুন কমান্ড লেখো"), fontSize = 12.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                t("যোগ"), color = Glass.pink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        val v = newPhrase.trim()
                                        if (v.isNotEmpty() && v !in wakePhrases) {
                                            wakePhrases.add(v); savePhrases()
                                        }
                                        newPhrase = ""
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        // The background is the bundled blood-red picture with living smoke and
                        // embers; the old looping-video list did nothing and has been removed.
                        Text(t("শেডার মোড"), color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))

                        BgAnimationMode.values().forEach { mode ->
                            val isSelected = mode == bgMode
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Glass.pinkFill.copy(alpha = 0.20f) else Glass.rowFill(0.30f))
                                    .border(
                                        1.dp,
                                        if (isSelected) Glass.pinkFill else Glass.borderSoft,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { viewModel.setBgMode(mode) }
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = t(mode.displayName), color = Glass.textPrimary, fontSize = 13.5.sp)
                                    if (isSelected) {
                                        Icon(Icons.Rounded.Check, contentDescription = "Active", tint = Glass.pinkFill)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(t("কাস্টম ব্যাকগ্রাউন্ড URL (mp4 / gif / image):"), color = Glass.textSecondary, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        BasicTextField(
                            value = customBgUrl,
                            onValueChange = { viewModel.setCustomBgUrl(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BloodCardBrush, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            textStyle = TextStyle(color = Glass.textPrimary, fontSize = 12.sp),
                            cursorBrush = SolidColor(Glass.pinkFill),
                            decorationBox = { inner ->
                                if (customBgUrl.isEmpty()) {
                                    Text("https://... (mp4/gif URL)", color = Glass.textMuted, fontSize = 12.sp)
                                }
                                inner()
                            }
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(t("অ্যানিমেশন স্পিড: {0}x", String.format("%.1f", animSpeed)), color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = animSpeed,
                            onValueChange = { viewModel.setAnimSpeed(it) },
                            valueRange = 0.4f..2.0f,
                            colors = SliderDefaults.colors(thumbColor = Glass.pinkFill, activeTrackColor = Glass.pinkFill)
                        )
                    }

                    "providers" -> {
                        // AI PROVIDERS KEYS
                        HorrorSectionTitle(t("এআই প্রোভাইডার ও নিজস্ব API Keys"), AppIcons.vpnKey)
                        Text(t("কী সেভ করলে অ্যাপ নিজেই প্রোভাইডারের ক্যাটালগ পড়ে যাচাই করে — সত্যি কানেক্টেড হলে সবুজ, নাহলে আসল কারণ লাল রঙে দেখাবে।"), color = Glass.textMuted, fontSize = 11.5.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        // The phone's own cloud agent. Unlike every other provider it
                        // has no catalogue to verify against, so the status is proved
                        // by reading the account instead: a key that answers is a key
                        // that works, and from then on it is always connected.
                        ProviderKeyRow(
                            t("ZYRAXON Apex — ক্লাউড এজেন্ট (সবসময় কানেক্টেড)"),
                            editApexKey, { editApexKey = it },
                            providerStatus["zyraxon_apex"], "zyraxon_apex" in providerBusy
                        ) {
                            viewModel.saveProviderKey("zyraxon_apex", editApexKey)
                        }
                        // The same route to the key as the first-run prompt: the site
                        // opens in one tap, and the steps say where the key lives.
                        // Without it a returning user who lost the key has nowhere
                        // to look inside the app.
                        Text(
                            t("নিচের বোতামে চাপ দিয়ে zyraxonai.lovable.app সাইটে যান। সেখানে একাউন্ট খুলে সেটিংসের API Key / Access Token অংশ থেকে নতুন কী তৈরি করে এখানে বসান।"),
                            color = Glass.textMuted, fontSize = 11.5.sp, lineHeight = 17.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://zyraxonai.lovable.app")
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (_: Exception) {
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Glass.cyanFill)
                        ) {
                            Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("ওয়েবসাইট খুলুন — zyraxonai.lovable.app"), fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // The ZYRAXON line is the user's own provider. Its catalogue
                        // is public, so its ten models load before any key is set.
                        ProviderKeyRow(
                            t("ZYRAXON API Key (নিজস্ব লাইন)"),
                            editZyraxonKey, { editZyraxonKey = it },
                            providerStatus["zyraxon"], "zyraxon" in providerBusy
                        ) {
                            viewModel.saveProviderKey("zyraxon", editZyraxonKey)
                        }

                        ProviderKeyRow(
                            t("OpenCode API Key (ফ্রি কোটা)"),
                            editOpenCodeKey, { editOpenCodeKey = it },
                            providerStatus["opencode"], "opencode" in providerBusy
                        ) {
                            viewModel.saveProviderKey("opencode", editOpenCodeKey)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        ProviderKeyRow("Google Gemini API Key", editGeminiKey, { editGeminiKey = it }, providerStatus["gemini"], "gemini" in providerBusy) {
                            viewModel.saveProviderKey("gemini", editGeminiKey)
                        }

                        ProviderKeyRow("Anthropic Claude API Key", editClaudeKey, { editClaudeKey = it }, providerStatus["anthropic"], "anthropic" in providerBusy) {
                            viewModel.saveProviderKey("anthropic", editClaudeKey)
                        }

                        ProviderKeyRow(t("Groq (দ্রুততম Llama) API Key"), editGroqKey, { editGroqKey = it }, providerStatus["groq"], "groq" in providerBusy) {
                            viewModel.saveProviderKey("groq", editGroqKey)
                        }

                        ProviderKeyRow("OpenRouter API Key", editOpenRouterKey, { editOpenRouterKey = it }, providerStatus["openrouter"], "openrouter" in providerBusy) {
                            viewModel.saveProviderKey("openrouter", editOpenRouterKey)
                        }

                        ProviderKeyRow("DeepSeek API Key", editDeepSeekKey, { editDeepSeekKey = it }, providerStatus["deepseek"], "deepseek" in providerBusy) {
                            viewModel.saveProviderKey("deepseek", editDeepSeekKey)
                        }

                        ProviderKeyRow("OpenAI (GPT-4o) API Key", editOpenAiKey, { editOpenAiKey = it }, providerStatus["openai"], "openai" in providerBusy) {
                            viewModel.saveProviderKey("openai", editOpenAiKey)
                        }
                    }

                    "github" -> {
                        // GITHUB INTEGRATION
                        HorrorSectionTitle(t("গিটহাব ইন্টিগ্রেশন (GitHub Access Token)"), AppIcons.hub)
                        Text(t("ওয়েবসাইট ডিপ্লয় ও কোড সেভ করার জন্য আপনার নিজস্ব গিটহাব টোকেন ব্যবহার করুন।"), color = Glass.textMuted, fontSize = 11.5.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        BasicTextField(
                            value = editGithubToken,
                            onValueChange = { editGithubToken = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BloodCardBrush, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.sp),
                            cursorBrush = SolidColor(Glass.cyanFill),
                            decorationBox = { inner ->
                                if (editGithubToken.isEmpty()) {
                                    Text("ghp_... (repo scope access token)", color = Glass.textMuted, fontSize = 12.sp)
                                }
                                inner()
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(t("স্ট্যাটাস: {0}", t(githubStatus)), color = Glass.cyanFill, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.saveGithubToken(editGithubToken) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Glass.cyanFill)
                            ) {
                                Icon(Icons.Rounded.Save, contentDescription = null, tint = Glass.textOnAccent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(t("টোকেন সেভ করুন"), color = Glass.textOnAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearGithubToken(); editGithubToken = "" },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(t("টোকেন মুছুন"), color = Color.Red, fontSize = 12.sp)
                            }
                        }
                    }

                    // The data controls, gathered where a user looking for them would
                    // look. Each one calls a function that already existed; nothing
                    // is invented, and nothing is destructive without a confirmation.
                    "privacy" -> {
                        HorrorSectionTitle(t("গোপনীয়তা ও ডেটা"), AppIcons.privacyTip)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            t("ZYRAXON-এর সব ডেটা আপনার ফোনেই থাকে — কোনো সার্ভারে যায় না। নিচে যেকোনো সময় মুছে ফেলতে পারেন।"),
                            color = Glass.textSecondary, fontSize = 11.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        PrivacyAction(
                            icon = AppIcons.storage, title = t("সব স্মৃতি মুছুন"),
                            desc = t("এজেন্ট আপনার সম্পর্কে যা মনে রেখেছে সব মুছে যাবে"),
                            onAction = { viewModel.clearMemories() }
                        )
                        PrivacyAction(
                            icon = AppIcons.chat, title = t("চ্যাট ইতিহাস মুছুন"),
                            desc = t("বর্তমান কথোপকথনের সব বার্তা মুছে যাবে"),
                            onAction = { viewModel.clearChat() }
                        )
                        PrivacyAction(
                            icon = AppIcons.key, title = t("GitHub টোকেন মুছুন"),
                            desc = t("সংরক্ষিত GitHub অ্যাক্সেস টোকেন সরিয়ে ফেলুন"),
                            onAction = { viewModel.clearGithubToken() }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            t("মাইক, ক্যামেরা ও ফাইল পারমিশন শুধু আপনি অনুমতি দিলে চলে — পারমিশন ট্যাব থেকে যেকোনো সময় বন্ধ করা যায়।"),
                            color = Glass.textMuted, fontSize = 11.sp
                        )
                    }

                    "memory" -> {
                        // MEMORY MANAGEMENT (LOCAL SQLITE ROOM DB)
                        HorrorSectionTitle(t("লোকাল এসকিউএল মেমরি (Persistent SQL Memory)"), AppIcons.storage)
                        Text(t("ZYRAXON আপনার যেসব তথ্য লোকাল SQLite ডাটাবেজে স্থায়ীভাবে মনে রেখেছে:"), color = Glass.textMuted, fontSize = 11.5.sp)
                        Spacer(modifier = Modifier.height(6.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Glass.greenFill.copy(alpha = 0.14f))
                                .border(1.dp, Glass.greenFill.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(t("লোকাল এসকিউএল ডাটাবেজে সংরক্ষিত — অ্যাপ বন্ধ বা রিস্টার্ট করলেও চিরতরে স্মৃতি বজায় থাকে।"), color = Glass.greenFill, fontSize = 11.sp)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (memories.isEmpty()) {
                            Text(t("এখনো কোনো স্মৃতি সংরক্ষণ করা হয়নি। নিচে নতুন তথ্য সেভ করুন।"), color = Glass.textMuted, fontSize = 12.sp)
                        } else {
                            memories.forEach { mem ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BloodCardBrush)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(mem.key, color = Glass.cyanFill, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                        Text(mem.value, color = Glass.textPrimary, fontSize = 12.sp)
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteMemory(mem.key) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = Glass.red.copy(alpha = 0.75f), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(t("নতুন মেমরি সংরক্ষণ করুন:"), color = Glass.textPrimary, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BasicTextField(
                                value = newMemKey,
                                onValueChange = { newMemKey = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .background(BloodCardBrush, RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                textStyle = TextStyle(color = Glass.textPrimary, fontSize = 12.sp),
                                cursorBrush = SolidColor(Glass.pinkFill),
                                decorationBox = { inner ->
                                    if (newMemKey.isEmpty()) Text(t("কী (Key)"), color = Glass.textMuted, fontSize = 11.sp)
                                    inner()
                                }
                            )
                            BasicTextField(
                                value = newMemVal,
                                onValueChange = { newMemVal = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .background(BloodCardBrush, RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                textStyle = TextStyle(color = Glass.textPrimary, fontSize = 12.sp),
                                cursorBrush = SolidColor(Glass.pinkFill),
                                decorationBox = { inner ->
                                    if (newMemVal.isEmpty()) Text(t("মান (Value)"), color = Glass.textMuted, fontSize = 11.sp)
                                    inner()
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (newMemKey.isNotBlank() && newMemVal.isNotBlank()) {
                                        viewModel.addMemory(newMemKey, newMemVal)
                                        newMemKey = ""
                                        newMemVal = ""
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Glass.pinkFill)
                            ) {
                                Icon(Icons.Rounded.Save, contentDescription = null, tint = Glass.textOnAccent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(t("মেমরিতে সেভ করুন"), color = Glass.textOnAccent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearMemories() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(t("সব মেমরি মুছুন"), color = Color.Red, fontSize = 11.5.sp)
                            }
                        }
                    }

                    "skills" -> {
                        // SKILLS & TOOLS GUIDE
                        HorrorSectionTitle(t("ZYRAXON সিস্টেম ক্যাপাবিলিটি ও স্কিল গাইড"), AppIcons.extension)
                        Spacer(modifier = Modifier.height(8.dp))

                        listOf(
                            t("ফেসবুক মেসেজিং") to t("বলুন 'ফেসবুক খোলো' বা যেকোনো মেসেজ সেন্ড কমান্ড।"),
                            t("ওয়েবসাইট নির্মাণ") to t("বলুন 'ওয়েবসাইট বানাও' — ZYRAXON সম্পূর্ণ কোড তৈরি ও গিটহাবে পাবলিশ করবে।"),
                            t("স্ক্রিন রিডার") to t("অ্যাক্সেসিবিলিটি সার্ভিসের মাধ্যমে স্ক্রিনের সকল লেখা বিশ্লেষণ করে।"),
                            t("টর্চলাইট কন্ট্রোল") to t("বলুন 'টর্চ জ্বালাও' অথবা 'টর্চ বন্ধ করো'।"),
                            t("ক্যামেরা ও ছবি বিশ্লেষণ") to t("বলুন 'ক্যামেরা চালু করো' কিংবা যেকোনো ফটো ক্যাপচার করুন।"),
                            t("ওয়াইফাই ও সেটিংস") to t("ভয়েস কমান্ডের মাধ্যমে সরাসরি সিস্টেম সেটিংস দ্রুত ওপেন হয়।")
                        ).forEach { (title, desc) ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BloodCardBrush)
                                    .padding(10.dp)
                            ) {
                                Text(title, color = Glass.cyanFill, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(desc, color = Glass.textSecondary, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = Glass.borderSoft)
            Spacer(modifier = Modifier.height(8.dp))

            // Footer Version Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ZYRAXON Native Engine v3.1.0 (Zero Lag) ",
                    color = Glass.textSecondary,
                    fontSize = 11.sp
                )
                Text(
                    text = t("মেসেজ সংখ্যা: {0}", messages.size),
                    color = Glass.cyanFill,
                    fontSize = 11.sp
                )
            }
        }
        // Decorative gore laid over the whole sheet; it never takes touches.
        BloodVeins(Modifier.matchParentSize(), seed = 14)
        BloodSplatter(Modifier.matchParentSize(), seed = 19)
        WatchingEyes(Modifier.matchParentSize(), seed = 41, pairs = 3)
        HorrorVignette(Modifier.matchParentSize(), intensity = 0.8f)
        BloodDrips(Modifier.align(Alignment.TopStart), seed = 3, count = 6, maxLength = 42.dp)
        }
    }
}

/**
 * The নেভিগেশন pane. Every control that used to sit in a row at the top of the
 * screen lives here now: the five sections, a fresh-session button and the
 * microphone switch. The top of the app is left to a single Settings gear, so
 * the screen belongs to the aura and the conversation.
 */
@Composable
fun SessionNavPane(viewModel: ZyraxonViewModel, onDismiss: () -> Unit) {
    val activeTab by viewModel.activeTab.collectAsState()
    val alwaysListening by viewModel.alwaysListening.collectAsState()
    val messages by viewModel.messages.collectAsState()

    HorrorSectionTitle(t("সেকশন"), AppIcons.apps)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        t("যে অংশে যেতে চান সেটি বেছে নিন। উপরে কোনো বাটন নেই — সব এখানে।"),
        color = Glass.textSecondary,
        fontSize = 11.5.sp
    )
    Spacer(modifier = Modifier.height(10.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTab.values().forEach { tab ->
            val selected = tab == activeTab
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Glass.pinkFill else Glass.rowFill(0.30f))
                    .border(
                        1.dp,
                        if (selected) Glass.pinkFill else Glass.borderSoft,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable {
                        viewModel.setTab(tab)
                        if (tab != AppTab.TERMINAL) onDismiss()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (tab == AppTab.TERMINAL) t("⌘ ZX — সিস্টেম শেল") else t(tab.title),
                    color = if (selected) Glass.textOnAccent else Glass.textPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
                if (selected) {
                    Text(t("চালু আছে"), color = Glass.textOnAccent, fontSize = 11.sp)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(18.dp))
    HorrorSectionTitle(t("নতুন সেশন"), AppIcons.add)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        t("চ্যাট ইতিহাস মুছে একেবারে নতুন করে শুরু করুন।"),
        color = Glass.textSecondary,
        fontSize = 11.5.sp
    )
    Spacer(modifier = Modifier.height(10.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BloodRowBrush)
            .border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp))
            .clickable {
                viewModel.clearChat()
                viewModel.setTab(AppTab.CHAT)
                onDismiss()
            }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            t("নতুন সেশন শুরু করো"),
            color = Glass.textPrimary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(t("{0} টি বার্তা", messages.size), color = Glass.textMuted, fontSize = 11.sp)
    }

    Spacer(modifier = Modifier.height(18.dp))
    HorrorSectionTitle(t("মাইক"), AppIcons.mic)
    Spacer(modifier = Modifier.height(10.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (alwaysListening) Glass.greenFill.copy(alpha = 0.18f) else Glass.rowFill(0.30f))
            .border(1.dp, if (alwaysListening) Glass.greenFill else Glass.borderSoft, RoundedCornerShape(14.dp))
            .clickable { viewModel.setMicEnabled(!alwaysListening) }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (alwaysListening) t("মাইক চালু — বন্ধ করতে চাপুন") else t("মাইক বন্ধ — চালু করতে চাপুন"),
            color = Glass.textPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

/**
 * The আপডেট pane. It reads the newest release from GitHub and walks the user
 * through download then install. The install replaces the running app in place,
 * so chats, memories and settings are all kept — nothing is uninstalled.
 */
@Composable
fun UpdatePane(viewModel: ZyraxonViewModel) {
    val state by viewModel.updateState.collectAsState()

    HorrorSectionTitle(t("অ্যাপ আপডেট (In-app Update)"), AppIcons.systemUpdate)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        t("নতুন রিলিজ আপলোড হলে এখানেই দেখা যাবে। অ্যাপ নিজে থেকেই নতুন ভার্সন আগে থেকে ডাউনলোড করে রাখে — আপনি শুধু ইনস্টল চাপবেন। ইনস্টল করলে পুরনো অ্যাপটিই আপডেট হবে — চ্যাট, মেমরি ও সেটিংস মুছবে না, আলাদা অ্যাপ বসবে না।"),
        color = Glass.textMuted,
        fontSize = 11.5.sp
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        t("বর্তমান ভার্সন: {0}", viewModel.currentVersionName),
        color = Glass.cyanFill,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(14.dp))

    ShizukuCard(viewModel = viewModel)
    Spacer(modifier = Modifier.height(14.dp))

    // A release card. Rendered for every state that has a release to show, so the
    // tag, notes and the one action button always look the same.
    val release = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.NoApk -> s.release
        is UpdateState.Downloading -> null
        is UpdateState.Downloaded -> s.release
        is UpdateState.NeedsPermission -> s.release
        else -> null
    }

    if (release != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Glass.greenFill.copy(alpha = 0.14f))
                .border(1.dp, Glass.greenFill, RoundedCornerShape(14.dp))
                .padding(14.dp)
        ) {
            Text(
                t("নতুন আপডেট পাওয়া গেছে — {0}", release.tag),
                color = Glass.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp
            )
            if (release.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    release.notes.take(600),
                    color = Glass.textSecondary,
                    fontSize = 11.5.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    when (val s = state) {
        UpdateState.Idle -> {
            Text(t("নতুন আপডেট আছে কি না জানতে চেক করুন।"), color = Glass.textSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(12.dp))
            UpdateButton(t("আপডেট চেক করুন"), enabled = true, icon = Icons.Rounded.Refresh) {
                viewModel.checkForUpdates()
            }
        }

        UpdateState.Checking -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Glass.cyanFill,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(t("চেক করা হচ্ছে..."), color = Glass.textSecondary, fontSize = 12.sp)
            }
        }

        is UpdateState.UpToDate -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Glass.greenFill, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(t("আপনি সর্বশেষ ভার্সনই ব্যবহার করছেন (v{0})।", s.installed), color = Glass.greenFill, fontSize = 12.5.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            UpdateButton(t("আবার চেক করুন"), enabled = true, icon = Icons.Rounded.Refresh) {
                viewModel.checkForUpdates()
            }
        }

        is UpdateState.Available -> {
            UpdateButton(t("ডাউনলোড ও ইনস্টল"), enabled = true, icon = Icons.Rounded.Download) {
                viewModel.downloadUpdate()
            }
        }

        is UpdateState.NoApk -> {
            Text(
                t("এই রিলিজে ইনস্টল করার মতো APK নেই।"),
                color = Glass.textMuted,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            UpdateButton(t("আবার চেক করুন"), enabled = true, icon = Icons.Rounded.Refresh) {
                viewModel.checkForUpdates()
            }
        }

        is UpdateState.Downloading -> {
            LinearProgressIndicator(
                progress = { s.progress },
                modifier = Modifier.fillMaxWidth(),
                color = Glass.cyanFill,
                trackColor = Glass.rowFill(0.3f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                t("ডাউনলোড হচ্ছে... {0}%", (s.progress * 100).toInt()),
                color = Glass.textSecondary,
                fontSize = 12.sp
            )
        }

        is UpdateState.Downloaded -> {
            Text(
                t("ডাউনলোড শেষ। এখন ইনস্টল করলে অ্যাপটি আপডেট হয়ে যাবে।"),
                color = Glass.greenFill,
                fontSize = 12.5.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            UpdateButton(t("ইনস্টল করুন"), enabled = true, icon = Icons.Rounded.Download) {
                viewModel.installUpdate()
            }
        }

        is UpdateState.NeedsPermission -> {
            Text(
                t("ইনস্টল করতে Android-কে অনুমতি দিতে হবে। সেটিংস খুলে ZYRAXON-কে \"এই সোর্স থেকে অ্যাপ ইনস্টল\" চালু করুন, তারপর আবার ইনস্টল চাপুন।"),
                color = Glass.textSecondary,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            UpdateButton(t("অনুমতি সেটিংস খুলুন"), enabled = true, icon = Icons.Rounded.Settings) {
                viewModel.openInstallPermission()
            }
            Spacer(modifier = Modifier.height(8.dp))
            UpdateButton(t("আবার ইনস্টল চেষ্টা করুন"), enabled = true, icon = Icons.Rounded.Refresh) {
                viewModel.installUpdate()
            }
        }

        is UpdateState.Failed -> {
            Text(t(s.message), color = Color(0xFFFF6B6B), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(12.dp))
            UpdateButton(t("আবার চেষ্টা করুন"), enabled = true, icon = Icons.Rounded.Refresh) {
                viewModel.checkForUpdates()
            }
        }
    }
}

/**
 * The system-access card, kept in the Updates section as the one place a user
 * goes for "the app needs something installed". ZYRAXON carries the official
 * Shizuku APK inside itself, so there is no download and no hunting for a file —
 * the button installs it, and the guide walks through starting it and granting
 * access. Every step is the user's own tap, because Android requires it.
 */
@Composable
private fun ShizukuCard(viewModel: ZyraxonViewModel) {
    val state by viewModel.shizukuState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshShizuku() }

    val active = state is ShizukuState.Active
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Glass.greenFill.copy(alpha = 0.14f) else Glass.rowFill(0.30f))
            .border(
                1.dp,
                if (active) Glass.greenFill else Glass.pinkFill.copy(alpha = 0.30f),
                RoundedCornerShape(14.dp)
            )
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (active) Icons.Rounded.Check else Icons.Rounded.Build,
                contentDescription = null,
                tint = if (active) Glass.greenFill else Glass.pinkFill,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                t("সিস্টেম অ্যাক্সেস (Shizuku)"),
                color = Glass.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            t("সিস্টেম লেভেলে কাজ করতে (ফাইল, টার্মিনাল, Linux) Shizuku লাগে। এটা ZYRAXON-এর ভেতরেই আছে — নিচের বাটনে চাপলেই ইনস্টল হবে, আলাদা করে কিছু খুঁজতে হবে না।"),
            color = Glass.textSecondary,
            fontSize = 11.5.sp
        )
        Spacer(Modifier.height(10.dp))

        when (val s = state) {
            ShizukuState.Unknown -> {
                UpdateButton(t("অবস্থা দেখুন"), enabled = true, icon = Icons.Rounded.Search) { viewModel.refreshShizuku() }
            }

            ShizukuState.NotInstalled -> {
                UpdateButton(t("Shizuku ইনস্টল করুন"), enabled = true, icon = Icons.Rounded.Download) {
                    viewModel.installShizuku()
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    t("ইনস্টল হওয়ার পর Shizuku খুলে তাকে চালু করুন, তারপর নিচের ধাপগুলো।"),
                    color = Glass.textMuted,
                    fontSize = 11.sp
                )
            }

            ShizukuState.Installing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Glass.cyanFill,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        t("ইনস্টলার খুলছে — Android-এর পপ-আপে Install চাপুন।"),
                        color = Glass.textSecondary,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                UpdateButton(t("আবার দেখুন"), enabled = true, icon = Icons.Rounded.Refresh) { viewModel.refreshShizuku() }
            }

            ShizukuState.InstalledNotRunning -> {
                Text(
                    t("Shizuku ইনস্টল হয়েছে, কিন্তু চালু নেই। খুলে Start চাপুন (রুটেড ফোনে সহজ; নাহলে Wireless debugging লাগবে), তারপর এখানে ফিরে আসুন।"),
                    color = Glass.textSecondary,
                    fontSize = 11.5.sp
                )
                Spacer(Modifier.height(10.dp))
                UpdateButton(t("Shizuku খুলুন"), enabled = true, icon = Icons.Rounded.OpenInNew) { viewModel.openShizuku() }
                Spacer(Modifier.height(8.dp))
                UpdateButton(t("চালু করেছি — আবার দেখুন"), enabled = true, icon = Icons.Rounded.Refresh) { viewModel.refreshShizuku() }
            }

            ShizukuState.NeedsPermission -> {
                Text(
                    t("Shizuku চালু আছে। এখন ZYRAXON-কে অনুমতি দিতে হবে — নিচের বাটনে চাপলে Shizuku-র নিজের ডায়ালগ আসবে, Allow চাপুন।"),
                    color = Glass.textSecondary,
                    fontSize = 11.5.sp
                )
                Spacer(Modifier.height(10.dp))
                UpdateButton(t("অনুমতি দিন"), enabled = true, icon = Icons.Rounded.Check) { viewModel.requestShizukuPermission() }
                Spacer(Modifier.height(8.dp))
                UpdateButton(t("আবার দেখুন"), enabled = true, icon = Icons.Rounded.Refresh) { viewModel.refreshShizuku() }
            }

            ShizukuState.Active -> {
                Text(
                    t("সিস্টেম অ্যাক্সেস চালু — এখন এজেন্ট সিস্টেম লেভেলে কাজ করতে পারবে।"),
                    color = Glass.greenFill,
                    fontSize = 12.5.sp
                )
                Spacer(Modifier.height(8.dp))
                UpdateButton(t("আবার দেখুন"), enabled = true, icon = Icons.Rounded.Refresh) { viewModel.refreshShizuku() }
            }

            is ShizukuState.Failed -> {
                Text(t(s.message), color = Color(0xFFFF6B6B), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                UpdateButton(t("আবার চেষ্টা করুন"), enabled = true, icon = Icons.Rounded.Refresh) { viewModel.refreshShizuku() }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            t("Shizuku (v13.6.0, Apache-2.0) ZYRAXON-এর ভেতরে বান্ডেল করা আছে। শুধু ইনস্টল ও অনুমতি—দুইটা ট্যাপ আপনার নিজের।"),
            color = Glass.textMuted,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun UpdateButton(label: String, enabled: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("update_action_button"),
        colors = ButtonDefaults.buttonColors(
            containerColor = Glass.cyanFill,
            contentColor = Glass.textOnAccent
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}


@Composable
fun ProviderKeyRow(
    title: String,
    currentValue: String,
    onValueChange: (String) -> Unit,
    status: String? = null,
    busy: Boolean = false,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BloodCardBrush)
            .padding(10.dp)
    ) {
        Text(title, color = Glass.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = currentValue,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .background(BloodRowBrush, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                textStyle = TextStyle(color = Glass.textPrimary, fontSize = 12.sp),
                cursorBrush = SolidColor(Glass.cyanFill),
                decorationBox = { inner ->
                    if (currentValue.isEmpty()) {
                        Text(t("কী বসান (API Key)"), color = Glass.textMuted, fontSize = 11.sp)
                    }
                    inner()
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onSave,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Glass.cyanFill)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Glass.textOnAccent,
                        strokeWidth = 2.dp
                    )
                } else {
                    AppIcon(AppIcons.save, null, size = 14.dp, tint = Glass.textOnAccent)
                    Spacer(Modifier.width(5.dp))
                    Text(t("সেভ"), color = Glass.textOnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (!status.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            // Green only when the provider itself answered; a failure shows its
            // own words in red, so "connected" is never a guess.
            val ok = status.contains(t("পাওয়া গেছে")) || status.contains(t("সংযুক্ত"))
            Text(
                text = status,
                color = if (ok) Glass.greenFill else Glass.pinkFill,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    desc: String,
    granted: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BloodCardBrush)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Glass.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = Glass.textMuted, fontSize = 11.sp)
        }
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (granted) Glass.greenFill else Glass.pinkFill
            )
        ) {
            AppIcon(
                if (granted) AppIcons.check else AppIcons.shield,
                null, size = 13.dp,
                tint = if (granted) Glass.greenFill else Glass.textOnAccent
            )
            Spacer(Modifier.width(5.dp))
            Text(
                if (granted) t("অনুমোদিত ") else t("অনুমতি দিন"),
                color = if (granted) Glass.greenFill else Glass.textOnAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * One aura style button. Kept here so the aura pane stays readable; the styles
 * themselves live in assets/aura/aura.html. It is a RowScope extension because
 * it fills an equal share of the row it is placed in.
 */
@Composable
private fun RowScope.AuraStyleChip(
    styleId: String,
    label: String,
    selected: String,
    onPick: (String) -> Unit
) {
    val isSelected = selected == styleId
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) Glass.pinkFill.copy(alpha = 0.22f) else Glass.rowFill(0.30f)
            )
            .border(
                1.dp,
                if (isSelected) Glass.pinkFill else Glass.borderSoft,
                RoundedCornerShape(10.dp)
            )
            .clickable { onPick(styleId) }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Glass.pinkFill else Glass.textSecondary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

