package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.i18n.Loc
import ai.zyraxon.assistant.i18n.setLanguage
import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.ai.ZyraxonAiEngine
import ai.zyraxon.assistant.model.AiModel
import ai.zyraxon.assistant.model.BgAnimationMode
import ai.zyraxon.assistant.model.Persona
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                    fontWeight = FontWeight.Bold,
                    color = Glass.textPrimary
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Glass.textMuted)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                        .clickable {
                            onSelectPersona(persona)
                            onDismiss()
                        }
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = persona.emoji, fontSize = 28.sp)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = persona.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Glass.textPrimary
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = "• ${persona.title}",
                                    fontSize = 12.sp,
                                    color = Glass.cyanFill
                                )
                            }
                            Text(
                                text = persona.description,
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
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/**
 * Centered Dynamic Model Selector Modal (Zero Hardcoded Models).
 * Loads live models from OpenCode server endpoints and supports custom model entries.
 */
/** A short chip label with an emoji for a provider id, falling back to the id. */
private fun providerChipLabel(providerId: String): String {
    val emoji = when (providerId) {
        "zyraxon" -> "🧠"
        "opencode" -> "⚡"
        "gemini" -> "💎"
        "groq" -> "🚀"
        "openai" -> "🤖"
        "anthropic" -> "🎭"
        "deepseek" -> "🐋"
        "openrouter" -> "🌐"
        else -> "🔹"
    }
    val label = ai.zyraxon.assistant.ai.ProviderCatalog.find(providerId)?.label ?: providerId
    return "$emoji $label"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionSheet(
    viewModel: ZyraxonViewModel,
    onDismiss: () -> Unit
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Glass.sheetFill(0.30f),
        dragHandle = null
    ) {
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
                    IconButton(onClick = onDismiss) {
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
                    .background(Glass.fill(0.30f))
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
                listOf("all" to t("সব মডেল")) + providerChips + listOf("custom" to t("✨ কাস্টম"))
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
                                onDismiss()
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
                                                text = "🎙️ " + t("রিয়েলটাইম"),
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
                                        "${model.category} • " + t("সরাসরি কথা বলে")
                                    } else {
                                        "${model.category} • " + t("লেখা পড়ে শোনায়")
                                    },
                                    fontSize = 10.sp,
                                    color = if (model.isRealtime) Glass.pinkFill else Glass.cyanFill
                                )
                                Text(
                                    text = model.description,
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
                                .background(Glass.fill(0.30f))
                                .padding(12.dp)
                        ) {
                            Text(t("মডেল ID লিখুন:"), color = Glass.textPrimary, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            BasicTextField(
                                value = customModelId,
                                onValueChange = { customModelId = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Glass.rowFill(0.30f), RoundedCornerShape(8.dp))
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
                                            onDismiss()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Glass.cyanFill)
                                ) {
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

    var activePane by remember { mutableStateOf("voice") }

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
    var editClaudeKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("anthropic")) }
    var editGroqKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("groq")) }
    var editOpenRouterKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("openrouter")) }
    var editDeepSeekKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("deepseek")) }
    var editOpenAiKey by remember { mutableStateOf(viewModel.aiEngine.getApiKey("openai")) }
    var editGithubToken by remember { mutableStateOf(githubToken) }

    // Memory form
    var newMemKey by remember { mutableStateOf("") }
    var newMemVal by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Glass.sheetFill(0.30f),
        dragHandle = null
    ) {
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
                        text = t("ZYRAXON সেটিংস ও সিস্টেম কন্ট্রোল"),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Glass.textPrimary
                    )
                    Text(
                        text = t("ভার্সন ২.০.০ — অল ইন ওয়ান পাওয়ার প্যানেল"),
                        fontSize = 11.sp,
                        color = Glass.pinkFill
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Glass.textMuted)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Navigation Tabs Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "updates" to t("⬆️ আপডেট"),
                    "voice" to t("🔊 ভয়েস (TTS)"),
                    "live" to t("📞 লাইভ ভয়েস"),
                    "ondevice" to t("📴 অন-ডিভাইস মডেল"),
                    "mic" to t("🎙️ মাইক"),
                    "aura" to t("🔮 ওরা ও ব্যাকগ্রাউন্ড"),
                    "providers" to t("🔑 এআই প্রোভাইডার্স"),
                    "github" to "🐙 GitHub",
                    "perms" to t("🛡️ পারমিশন"),
                    "memory" to t("🧠 মেমরি"),
                    "skills" to t("📖 স্কিল গাইড")
                ).forEach { (paneId, label) ->
                    val isActive = activePane == paneId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isActive) Glass.pinkFill else Glass.rowFill(0.30f))
                            .clickable { activePane = paneId }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isActive) Glass.textOnAccent else Glass.textPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = Glass.borderSoft)
            Spacer(modifier = Modifier.height(14.dp))

            // Content Area Scrollable
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (activePane) {
                    "updates" -> UpdatePane(viewModel = viewModel)

                    "perms" -> {
                        val activity = context as? ai.zyraxon.assistant.MainActivity
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

                        Text(
                            t("লাইভ ভয়েস — স্পিচ-টু-স্পিচ"),
                            fontWeight = FontWeight.Bold,
                            color = Glass.textPrimary,
                            fontSize = 14.sp
                        )
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
                                .background(Glass.rowFill(0.30f))
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
                                Text(if (liveOn) "🎙️" else "🔇", fontSize = 20.sp)
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
                                t("এখন কোনো রিয়েলটাইম মডেল লোড হয়নি। 🔑 এআই প্রোভাইডার্স ট্যাবে Gemini, OpenAI বা Grok-এর কী দিলে তাদের লাইভ মডেল এখানে আসবে।"),
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

                        Text(
                            t("অন-ডিভাইস মডেল — ইন্টারনেট ছাড়াই উত্তর"),
                            fontWeight = FontWeight.Bold,
                            color = Glass.textPrimary,
                            fontSize = 14.sp
                        )
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
                                            viewModel.onDeviceModel.load(f.absolutePath)
                                            note = t("লোড হয়েছে: ") + f.name
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Column {
                                        Text(f.name, color = Glass.textPrimary, fontSize = 11.5.sp)
                                        Text(
                                            (f.length() / (1024 * 1024)).toString() + " MB" + if (loaded) " • " + t("লোড করা আছে") else "",
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
                                        "${info.approxMb} MB • " + if (info.requiresToken) t("টোকেন লাগবে") else t("টোকেন ছাড়াই"),
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

                    "voice" -> {
                        // VOICE (TTS) CONTROLS
                        Text(t("ভয়েস সেটিংস ও কণ্ঠের প্রকার (Text-to-Speech)"), fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        val currentGender by viewModel.voiceGender.collectAsState()

                        Text(t("কণ্ঠ নির্বাচন করুন (Voice Profile):"), color = Glass.cyanFill, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Female Voice Option
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
                                    Text("👩", fontSize = 26.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(t("মেয়ের কণ্ঠ"), color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(t("মিষ্টি ও রোমান্টিক"), color = Glass.pinkFill, fontSize = 10.5.sp)
                                    if (isFemale) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Icon(Icons.Rounded.Check, contentDescription = "Active", tint = Glass.pinkFill, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            // Male Voice Option
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
                                    Text("👨", fontSize = 26.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(t("ছেলের কণ্ঠ"), color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(t("গম্ভীর ও স্পষ্ট"), color = Glass.cyanFill, fontSize = 10.5.sp)
                                    if (isMale) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Icon(Icons.Rounded.Check, contentDescription = "Active", tint = Glass.cyanFill, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // The phone's own recogniser and engine read these two
                        // languages; the choice is saved and restored on launch.
                        val currentLang by viewModel.voiceLanguage.collectAsState()
                        val ttsOk by viewModel.ttsAvailable.collectAsState()
                        Text(t("ভাষা (Recognition + Speech):"), color = Glass.cyanFill, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf("bn-BD" to t("🇧🇩 বাংলা"), "en-US" to "🇺🇸 English").forEach { (tag, label) ->
                                val active = currentLang == tag
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (active) Glass.cyanFill.copy(alpha = 0.16f) else Glass.rowFill(0.30f))
                                        .border(1.dp, if (active) Glass.cyanFill else Glass.borderSoft, RoundedCornerShape(12.dp))
                                        .clickable { viewModel.setVoiceLanguage(tag) }
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                        if (!ttsOk) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                t("এই ফোনে কোনো স্পিচ ইঞ্জিন নেই — Google Speech Services ইনস্টল করলে কথা বলতে পারবে।"),
                                color = Glass.amber,
                                fontSize = 11.sp
                            )
                        }

                        // A phone with an engine but no Bengali voice reads Bengali
                        // words as noise. Saying so, and offering the engine's own
                        // download screen, is the only honest fix.
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

                        // The UI language. The tables are assets, so switching
                        // only swaps the map and rebuilds the tree — no restart
                        // and no reinstall.
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(t("অ্যাপের ভাষা"), color = Glass.cyanFill, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
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
                                .background(Glass.rowFill(0.30f))
                                .border(1.dp, Glass.borderSoft, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // A plain scrolling Column, not a LazyColumn: this sits
                        // inside the sheet's own verticalScroll, and a lazy list
                        // would be measured with an infinite height and crash.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
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
                                        .clickable { setLanguage(context, language.code) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "${language.flag}  ${language.name}",
                                        color = Glass.textPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (active) {
                                        Icon(Icons.Rounded.Check, contentDescription = "Active", tint = Glass.pinkFill, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

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
                        Text(t("মাইক্রোফোন ও লিসেনিং সেটিংস"), fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

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
                        // A second, larger microphone switch, so the user can mute
                        // from inside Settings as well as from the top bar.
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
                                if (alwaysListening) t("🎙️ মাইক চালু — বন্ধ করতে চাপুন") else t("🔇 মাইক বন্ধ — চালু করতে চাপুন"),
                                color = Glass.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    "aura" -> {
                        // AURA & BACKGROUND
                        Text(t("ওরা ও ব্যাকগ্রাউন্ড অ্যানিমেশন"), fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        // Which designed aura is showing. Each one is a distinct
                        // shape, not just a recolour.
                        Text(t("ওরার স্টাইল"), color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "orb" to t("ক্লাসিক"),
                                "twin" to t("টুইন রিং"),
                                "vortex" to t("ভর্টেক্স")
                            ).forEach { (styleId, label) ->
                                AuraStyleChip(styleId, label, auraStyle) { viewModel.setAuraStyle(it) }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "bloom" to t("ব্লুম"),
                                "helix" to t("হেলিক্স"),
                                "core" to t("কোর")
                            ).forEach { (styleId, label) ->
                                AuraStyleChip(styleId, label, auraStyle) { viewModel.setAuraStyle(it) }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // The shader modes. Each is a real WebGL scene rendered
                        // inside the orb, and "ক্লাসিক" hands the aura back to
                        // the CSS shapes above.
                        Text(t("শেডার মোড"), color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "none" to t("ক্লাসিক"),
                                "cosmic" to t("কসমিক"),
                                "portal" to t("পোর্টাল")
                            ).forEach { (shaderId, label) ->
                                AuraStyleChip(shaderId, label, auraShader) { viewModel.setAuraShader(it) }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "cyber" to t("সাইবার গ্রিড"),
                                "nebula" to t("নেবুলা")
                            ).forEach { (shaderId, label) ->
                                AuraStyleChip(shaderId, label, auraShader) { viewModel.setAuraShader(it) }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // The aura itself. These four sliders drive the CSS
                        // variables inside aura.html, so the change is instant.
                        Text(t("ওরার রঙ"), color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                325 to t("গোলাপি"),
                                268 to t("বেগুনি"),
                                190 to t("আকাশি"),
                                150 to t("সবুজ"),
                                20 to t("কমলা"),
                                0 to t("লাল")
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

                        // Floating chat bubble over other apps. The row explains
                        // that the system Allow dialog is all that is needed.
                        val overlayOn by viewModel.overlayEnabled.collectAsState()
                        Text(t("ফ্লোটিং চ্যাট বাবল"), color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            t("চালু করলে অন্য যেকোনো অ্যাপের উপরে ছোট একটি বাবল থাকবে। ") +
                                t("ক্লিক করে লিখলে সোজা এই অ্যাসিস্ট্যান্টের কাছে যাবে — অ্যাপের ভিতরে ঢুকতে হবে না।"),
                            color = Glass.textMuted,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (overlayOn) Glass.greenFill.copy(alpha = 0.20f) else Glass.rowFill(0.30f))
                                .clickable {
                                    if (!overlayOn) {
                                        val ok = android.provider.Settings.canDrawOverlays(context)
                                        if (!ok) (context as? ai.zyraxon.assistant.MainActivity)
                                            ?.openSpecialSetting("overlay")
                                        else {
                                            viewModel.setOverlayEnabled(true)
                                            ai.zyraxon.assistant.OverlayChatService.start(context)
                                        }
                                    } else {
                                        viewModel.setOverlayEnabled(false)
                                        ai.zyraxon.assistant.OverlayChatService.stop(context)
                                    }
                                }
                                .padding(12.dp)
                        ) {
                            Text(
                                text = if (overlayOn) t("● চালু আছে — বন্ধ করতে চাপো") else t("○ চালু করো (উপর থেকে Allow দিতে হবে)"),
                                color = if (overlayOn) Glass.greenFill else Glass.textPrimary,
                                fontSize = 12.5.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        // The looping clip behind the aura. These are the clips
                        // bundled with the app, so they work with no network.
                        Text(t("ব্যাকগ্রাউন্ড ভিডিও"), color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        listOf(
                            "girl.mp4" to t("মেয়ে (ওরা)"),
                            "aurora.mp4" to t("অরোরা"),
                            "space.mp4" to t("স্পেস টানেল"),
                            "hero.mp4" to t("টানেল"),
                            "dream.mp4" to t("স্বপ্নের মেঘ"),
                            "ink.mp4" to t("কালি"),
                            "city.mp4" to t("শহরের আলো"),
                            "galaxy-eye.mp4" to t("গ্যালাক্সি আই"),
                            "" to t("কোনো ভিডিও নয় (শুধু শেডার)")
                        ).forEach { (asset, label) ->
                            val isSelected = bgVideoAsset == asset
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Glass.pinkFill.copy(alpha = 0.20f) else Glass.rowFill(0.30f))
                                    .clickable { viewModel.setBgVideoAsset(asset) }
                                    .padding(11.dp)
                            ) {
                                Text(
                                    text = if (isSelected) "● $label" else "○ $label",
                                    color = if (isSelected) Glass.pink else Glass.textPrimary,
                                    fontSize = 12.5.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
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
                                    Text(text = mode.displayName, color = Glass.textPrimary, fontSize = 13.5.sp)
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
                                .background(Glass.fill(0.30f), RoundedCornerShape(8.dp))
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
                        Text(t("এআই প্রোভাইডার ও নিজস্ব API Keys"), fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Text(t("কী সেভ করলে অ্যাপ নিজেই প্রোভাইডারের ক্যাটালগ পড়ে যাচাই করে — সত্যি কানেক্টেড হলে সবুজ, নাহলে আসল কারণ লাল রঙে দেখাবে।"), color = Glass.textMuted, fontSize = 11.5.sp)
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
                        Text(t("গিটহাব ইন্টিগ্রেশন (GitHub Access Token)"), fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Text(t("ওয়েবসাইট ডিপ্লয় ও কোড সেভ করার জন্য আপনার নিজস্ব গিটহাব টোকেন ব্যবহার করুন।"), color = Glass.textMuted, fontSize = 11.5.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        BasicTextField(
                            value = editGithubToken,
                            onValueChange = { editGithubToken = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Glass.fill(0.30f), RoundedCornerShape(8.dp))
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
                        Text(t("স্ট্যাটাস: {0}", githubStatus), color = Glass.cyanFill, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.saveGithubToken(editGithubToken) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Glass.cyanFill)
                            ) {
                                Text(t("টোকেন সেভ করুন"), color = Glass.textOnAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearGithubToken(); editGithubToken = "" },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(t("টোকেন মুছুন"), color = Color.Red, fontSize = 12.sp)
                            }
                        }
                    }

                    "perms" -> {
                        // SYSTEM PERMISSIONS
                        Text(t("সিস্টেম পারমিশন ও ব্যাকগ্রাউন্ড সার্ভিস"), fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        // Accessibility Bridge
                        PermissionRow(
                            title = t("অ্যাক্সেসিবিলিটি সার্ভিস (Touch Bridge)"),
                            desc = t("স্ক্রিন রিড ও অটোমেশন কন্ট্রোল"),
                            granted = systemStatus.accessibilityConnected,
                            onAction = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        )

                        // Microphone
                        PermissionRow(
                            title = t("মাইক্রোফোন পারমিশন"),
                            desc = t("ভয়েস ইন্টারঅ্যাকশন ও লাইভ লিসেনিং"),
                            granted = true,
                            onAction = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        )

                        // Camera
                        PermissionRow(
                            title = t("ক্যামেরা পারমিশন"),
                            desc = t("ভিজ্যুয়াল রিকগনিশন ও ছবি তোলা"),
                            granted = true,
                            onAction = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        )

                        // KeepAlive Service
                        PermissionRow(
                            title = t("ব্যাকগ্রাউন্ড কিপ-অ্যালাইভ সার্ভিস"),
                            desc = t("ব্যাকগ্রাউন্ডে সর্বদা সক্রিয় থাকে"),
                            granted = systemStatus.keepAliveRunning,
                            onAction = {}
                        )
                    }

                    "memory" -> {
                        // MEMORY MANAGEMENT (LOCAL SQLITE ROOM DB)
                        Text(t("লোকাল এসকিউএল মেমরি (Persistent SQL Memory)"), fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
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
                            Text(t("💾 লোকাল এসকিউএল ডাটাবেজে সংরক্ষিত — অ্যাপ বন্ধ বা রিস্টার্ট করলেও চিরতরে স্মৃতি বজায় থাকে।"), color = Glass.greenFill, fontSize = 11.sp)
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
                                        .background(Glass.fill(0.30f))
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
                                    .background(Glass.fill(0.30f), RoundedCornerShape(8.dp))
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
                                    .background(Glass.fill(0.30f), RoundedCornerShape(8.dp))
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
                                Text(t("মেমরিতে সেভ করুন"), color = Glass.textOnAccent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearMemories() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(t("সব মেমরি মুছুন"), color = Color.Red, fontSize = 11.5.sp)
                            }
                        }
                    }

                    "skills" -> {
                        // SKILLS & TOOLS GUIDE
                        Text(t("ZYRAXON সিস্টেম ক্যাপাবিলিটি ও স্কিল গাইড"), fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        listOf(
                            t("📱 ফেসবুক মেসেজিং") to t("বলুন 'ফেসবুক খোলো' বা যেকোনো মেসেজ সেন্ড কমান্ড।"),
                            t("🌐 ওয়েবসাইট নির্মাণ") to t("বলুন 'ওয়েবসাইট বানাও' — ZYRAXON সম্পূর্ণ কোড তৈরি ও গিটহাবে পাবলিশ করবে।"),
                            t("👁️ স্ক্রিন রিডার") to t("অ্যাক্সেসিবিলিটি সার্ভিসের মাধ্যমে স্ক্রিনের সকল লেখা বিশ্লেষণ করে।"),
                            t("🔦 টর্চলাইট কন্ট্রোল") to t("বলুন 'টর্চ জ্বালাও' অথবা 'টর্চ বন্ধ করো'।"),
                            t("📸 ক্যামেরা ও ছবি বিশ্লেষণ") to t("বলুন 'ক্যামেরা চালু করো' কিংবা যেকোনো ফটো ক্যাপচার করুন।"),
                            t("📶 ওয়াইফাই ও সেটিংস") to t("ভয়েস কমান্ডের মাধ্যমে সরাসরি সিস্টেম সেটিংস দ্রুত ওপেন হয়।")
                        ).forEach { (title, desc) ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Glass.fill(0.30f))
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
                    text = "ZYRAXON Native Engine v3.0.0 (Zero Lag)",
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

    Text(
        t("অ্যাপ আপডেট (In-app Update)"),
        fontWeight = FontWeight.Bold,
        color = Glass.textPrimary,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        t("নতুন রিলিজ আপলোড হলে এখানেই দেখা যাবে। ইনস্টল করলে পুরনো অ্যাপটিই আপডেট হবে — চ্যাট, মেমরি ও সেটিংস মুছবে না, আলাদা অ্যাপ বসবে না।"),
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
                t("✨ নতুন আপডেট পাওয়া গেছে — {0}", release.tag),
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
            UpdateButton(t("আপডেট চেক করুন"), enabled = true) {
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
            UpdateButton(t("আবার চেক করুন"), enabled = true) {
                viewModel.checkForUpdates()
            }
        }

        is UpdateState.Available -> {
            UpdateButton(t("⬇️ ডাউনলোড ও ইনস্টল"), enabled = true) {
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
            UpdateButton(t("আবার চেক করুন"), enabled = true) {
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
            UpdateButton(t("✅ ইনস্টল করুন"), enabled = true) {
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
            UpdateButton(t("অনুমতি সেটিংস খুলুন"), enabled = true) {
                viewModel.openInstallPermission()
            }
            Spacer(modifier = Modifier.height(8.dp))
            UpdateButton(t("আবার ইনস্টল চেষ্টা করুন"), enabled = true) {
                viewModel.installUpdate()
            }
        }

        is UpdateState.Failed -> {
            Text(s.message, color = Color(0xFFFF6B6B), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(12.dp))
            UpdateButton(t("আবার চেষ্টা করুন"), enabled = true) {
                viewModel.checkForUpdates()
            }
        }
    }
}

@Composable
private fun UpdateButton(label: String, enabled: Boolean, onClick: () -> Unit) {
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
            .background(Glass.fill(0.30f))
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
                    .background(Glass.rowFill(0.30f), RoundedCornerShape(8.dp))
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
            .background(Glass.fill(0.30f))
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
            Text(
                if (granted) t("অনুমোদিত ✓") else t("অনুমতি দিন"),
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

