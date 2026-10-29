package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.ai.ZyraxonAiEngine
import ai.zyraxon.assistant.model.AiModel
import ai.zyraxon.assistant.model.BgAnimationMode
import ai.zyraxon.assistant.model.Persona
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                    text = "পরিচয় নির্বাচন (Persona)",
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
                        text = "এআই মডেল নির্বাচন (AI Models)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Glass.textPrimary
                    )
                    Text(
                        text = "দ্রুত মডেল আগে — সরাসরি লাইভ ক্যাটালগ",
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
                                    "মডেল খুঁজুন (যেমন: Big Pickle, Flash, Mimo)...",
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
                listOf("all" to "সব মডেল") + providerChips + listOf("custom" to "✨ কাস্টম")
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
                                "কোনো মডেল পাওয়া যায়নি। সার্ভার সিঙ্ক বোতামে ট্যাপ করুন।",
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
                            Text("কাস্টম মডেল যুক্ত করুন (Custom Model ID)", fontSize = 12.sp)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Glass.fill(0.30f))
                                .padding(12.dp)
                        ) {
                            Text("মডেল ID লিখুন:", color = Glass.textPrimary, fontSize = 12.sp)
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
                                    Text("যুক্ত ও নির্বাচন করুন", color = Glass.textOnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        text = "ZYRAXON সেটিংস ও সিস্টেম কন্ট্রোল",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Glass.textPrimary
                    )
                    Text(
                        text = "ভার্সন ২.০.০ — অল ইন ওয়ান পাওয়ার প্যানেল",
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
                    "voice" to "🔊 ভয়েস (TTS)",
                    "mic" to "🎙️ মাইক",
                    "aura" to "🔮 ওরা ও ব্যাকগ্রাউন্ড",
                    "providers" to "🔑 এআই প্রোভাইডার্স",
                    "github" to "🐙 GitHub",
                    "perms" to "🛡️ পারমিশন",
                    "memory" to "🧠 মেমরি",
                    "skills" to "📖 স্কিল গাইড"
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

                    "voice" -> {
                        // VOICE (TTS) CONTROLS
                        Text("ভয়েস সেটিংস ও কণ্ঠের প্রকার (Text-to-Speech)", fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        val currentGender by viewModel.voiceGender.collectAsState()

                        Text("কণ্ঠ নির্বাচন করুন (Voice Profile):", color = Glass.cyanFill, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
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
                                    Text("মেয়ের কণ্ঠ", color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("মিষ্টি ও রোমান্টিক", color = Glass.pinkFill, fontSize = 10.5.sp)
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
                                    Text("ছেলের কণ্ঠ", color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("গম্ভীর ও স্পষ্ট", color = Glass.cyanFill, fontSize = 10.5.sp)
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
                        Text("ভাষা (Recognition + Speech):", color = Glass.cyanFill, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf("bn-BD" to "🇧🇩 বাংলা", "en-US" to "🇺🇸 English").forEach { (tag, label) ->
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
                                "এই ফোনে কোনো স্পিচ ইঞ্জিন নেই — Google Speech Services ইনস্টল করলে কথা বলতে পারবে।",
                                color = Glass.amber,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("উত্তরের অডিও শোনানো হবে", color = Glass.textPrimary, fontSize = 13.sp)
                            Switch(
                                checked = ttsEnabled,
                                onCheckedChange = { viewModel.setTtsEnabled(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Glass.textPrimary, checkedTrackColor = Glass.pinkFill)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("কথা বলার গতি (Speed): ${String.format("%.2f", ttsRate)}x", color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = ttsRate,
                            onValueChange = { viewModel.setTtsRate(it) },
                            valueRange = 0.6f..1.6f,
                            colors = SliderDefaults.colors(thumbColor = Glass.pinkFill, activeTrackColor = Glass.pinkFill)
                        )

                        Text("কণ্ঠের পিচ (Pitch): ${String.format("%.2f", ttsPitch)}", color = Glass.textSecondary, fontSize = 12.sp)
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
                            Text("ভয়েস টেস্ট করুন (Test Voice)", color = Glass.textOnAccent, fontWeight = FontWeight.Bold)
                        }
                    }

                    "mic" -> {
                        // MICROPHONE CONTROLS
                        Text("মাইক্রোফোন ও লিসেনিং সেটিংস", fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("সর্বদা শুনবে (Always listening)", color = Glass.textPrimary, fontSize = 13.sp)
                                Text("অ্যাপ চলাকালীন স্বয়ংক্রিয় লিসেনিং চালু রাখবে", color = Glass.textMuted, fontSize = 11.sp)
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
                                Text("কথা থামালে অটো-সেন্ড", color = Glass.textPrimary, fontSize = 13.sp)
                                Text("বাক্য শেষ হলে সরাসরি উত্তর তৈরি হবে", color = Glass.textMuted, fontSize = 11.sp)
                            }
                            Switch(
                                checked = autoSend,
                                onCheckedChange = { viewModel.setAutoSend(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Glass.textPrimary, checkedTrackColor = Glass.cyanFill)
                            )
                        }
                    }

                    "aura" -> {
                        // AURA & BACKGROUND
                        Text("ওরা ও ব্যাকগ্রাউন্ড অ্যানিমেশন", fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        // The aura itself. These four sliders drive the CSS
                        // variables inside aura.html, so the change is instant.
                        Text("ওরার রঙ", color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                325 to "গোলাপি",
                                268 to "বেগুনি",
                                190 to "আকাশি",
                                150 to "সবুজ",
                                20 to "কমলা",
                                0 to "লাল"
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

                        Text("ওরার আকার: ${String.format("%.2f", auraSize)}x", color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = auraSize,
                            onValueChange = { viewModel.setAuraSize(it) },
                            valueRange = 0.6f..1.5f,
                            colors = SliderDefaults.colors(thumbColor = Glass.pinkFill, activeTrackColor = Glass.pinkFill)
                        )
                        Text("আভার তীব্রতা: ${String.format("%.2f", auraGlow)}x", color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = auraGlow,
                            onValueChange = { viewModel.setAuraGlow(it) },
                            valueRange = 0.2f..2.0f,
                            colors = SliderDefaults.colors(thumbColor = Glass.pinkFill, activeTrackColor = Glass.pinkFill)
                        )
                        Text("ঘোরার গতি: ${String.format("%.2f", auraSpeed)}x", color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = auraSpeed,
                            onValueChange = { viewModel.setAuraSpeed(it) },
                            valueRange = 0.3f..3.0f,
                            colors = SliderDefaults.colors(thumbColor = Glass.pinkFill, activeTrackColor = Glass.pinkFill)
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Text("প্যানেলের স্বচ্ছতা: ${(panelAlpha * 100).toInt()}%", color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = panelAlpha,
                            onValueChange = { viewModel.setPanelAlpha(it) },
                            valueRange = 0.06f..0.6f,
                            colors = SliderDefaults.colors(thumbColor = Glass.cyanFill, activeTrackColor = Glass.cyanFill)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // The looping clip behind the aura. These are the clips
                        // bundled with the app, so they work with no network.
                        Text("ব্যাকগ্রাউন্ড ভিডিও", color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))
                        listOf(
                            "girl.mp4" to "মেয়ে (ওরা)",
                            "aurora.mp4" to "অরোরা",
                            "space.mp4" to "স্পেস টানেল",
                            "hero.mp4" to "টানেল",
                            "dream.mp4" to "স্বপ্নের মেঘ",
                            "ink.mp4" to "কালি",
                            "city.mp4" to "শহরের আলো",
                            "galaxy-eye.mp4" to "গ্যালাক্সি আই",
                            "" to "কোনো ভিডিও নয় (শুধু শেডার)"
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
                        Text("শেডার মোড", color = Glass.pink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
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
                        Text("কাস্টম ব্যাকগ্রাউন্ড URL (mp4 / gif / image):", color = Glass.textSecondary, fontSize = 12.sp)
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
                        Text("অ্যানিমেশন স্পিড: ${String.format("%.1f", animSpeed)}x", color = Glass.textSecondary, fontSize = 12.sp)
                        Slider(
                            value = animSpeed,
                            onValueChange = { viewModel.setAnimSpeed(it) },
                            valueRange = 0.4f..2.0f,
                            colors = SliderDefaults.colors(thumbColor = Glass.pinkFill, activeTrackColor = Glass.pinkFill)
                        )
                    }

                    "providers" -> {
                        // AI PROVIDERS KEYS
                        Text("এআই প্রোভাইডার ও নিজস্ব API Keys", fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Text("কী সেভ করলে অ্যাপ নিজেই প্রোভাইডারের ক্যাটালগ পড়ে যাচাই করে — সত্যি কানেক্টেড হলে সবুজ, নাহলে আসল কারণ লাল রঙে দেখাবে।", color = Glass.textMuted, fontSize = 11.5.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        // The ZYRAXON line is the user's own provider. Its catalogue
                        // is public, so its ten models load before any key is set.
                        ProviderKeyRow(
                            "ZYRAXON API Key (নিজস্ব লাইন)",
                            editZyraxonKey, { editZyraxonKey = it },
                            providerStatus["zyraxon"], "zyraxon" in providerBusy
                        ) {
                            viewModel.saveProviderKey("zyraxon", editZyraxonKey)
                        }

                        ProviderKeyRow(
                            "OpenCode API Key (ফ্রি কোটা)",
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

                        ProviderKeyRow("Groq (দ্রুততম Llama) API Key", editGroqKey, { editGroqKey = it }, providerStatus["groq"], "groq" in providerBusy) {
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
                        Text("গিটহাব ইন্টিগ্রেশন (GitHub Access Token)", fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Text("ওয়েবসাইট ডিপ্লয় ও কোড সেভ করার জন্য আপনার নিজস্ব গিটহাব টোকেন ব্যবহার করুন।", color = Glass.textMuted, fontSize = 11.5.sp)
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
                        Text("স্ট্যাটাস: $githubStatus", color = Glass.cyanFill, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.saveGithubToken(editGithubToken) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Glass.cyanFill)
                            ) {
                                Text("টোকেন সেভ করুন", color = Glass.textOnAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearGithubToken(); editGithubToken = "" },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("টোকেন মুছুন", color = Color.Red, fontSize = 12.sp)
                            }
                        }
                    }

                    "perms" -> {
                        // SYSTEM PERMISSIONS
                        Text("সিস্টেম পারমিশন ও ব্যাকগ্রাউন্ড সার্ভিস", fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(10.dp))

                        // Accessibility Bridge
                        PermissionRow(
                            title = "অ্যাক্সেসিবিলিটি সার্ভিস (Touch Bridge)",
                            desc = "স্ক্রিন রিড ও অটোমেশন কন্ট্রোল",
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
                            title = "মাইক্রোফোন পারমিশন",
                            desc = "ভয়েস ইন্টারঅ্যাকশন ও লাইভ লিসেনিং",
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
                            title = "ক্যামেরা পারমিশন",
                            desc = "ভিজ্যুয়াল রিকগনিশন ও ছবি তোলা",
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
                            title = "ব্যাকগ্রাউন্ড কিপ-অ্যালাইভ সার্ভিস",
                            desc = "ব্যাকগ্রাউন্ডে সর্বদা সক্রিয় থাকে",
                            granted = systemStatus.keepAliveRunning,
                            onAction = {}
                        )
                    }

                    "memory" -> {
                        // MEMORY MANAGEMENT (LOCAL SQLITE ROOM DB)
                        Text("লোকাল এসকিউএল মেমরি (Persistent SQL Memory)", fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Text("ZYRAXON আপনার যেসব তথ্য লোকাল SQLite ডাটাবেজে স্থায়ীভাবে মনে রেখেছে:", color = Glass.textMuted, fontSize = 11.5.sp)
                        Spacer(modifier = Modifier.height(6.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Glass.greenFill.copy(alpha = 0.14f))
                                .border(1.dp, Glass.greenFill.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("💾 লোকাল এসকিউএল ডাটাবেজে সংরক্ষিত — অ্যাপ বন্ধ বা রিস্টার্ট করলেও চিরতরে স্মৃতি বজায় থাকে।", color = Glass.greenFill, fontSize = 11.sp)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (memories.isEmpty()) {
                            Text("এখনো কোনো স্মৃতি সংরক্ষণ করা হয়নি। নিচে নতুন তথ্য সেভ করুন।", color = Glass.textMuted, fontSize = 12.sp)
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
                        Text("নতুন মেমরি সংরক্ষণ করুন:", color = Glass.textPrimary, fontSize = 12.sp)
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
                                    if (newMemKey.isEmpty()) Text("কী (Key)", color = Glass.textMuted, fontSize = 11.sp)
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
                                    if (newMemVal.isEmpty()) Text("মান (Value)", color = Glass.textMuted, fontSize = 11.sp)
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
                                Text("মেমরিতে সেভ করুন", color = Glass.textOnAccent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearMemories() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("সব মেমরি মুছুন", color = Color.Red, fontSize = 11.5.sp)
                            }
                        }
                    }

                    "skills" -> {
                        // SKILLS & TOOLS GUIDE
                        Text("ZYRAXON সিস্টেম ক্যাপাবিলিটি ও স্কিল গাইড", fontWeight = FontWeight.Bold, color = Glass.textPrimary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))

                        listOf(
                            "📱 ফেসবুক মেসেজিং" to "বলুন 'ফেসবুক খোলো' বা যেকোনো মেসেজ সেন্ড কমান্ড।",
                            "🌐 ওয়েবসাইট নির্মাণ" to "বলুন 'ওয়েবসাইট বানাও' — ZYRAXON সম্পূর্ণ কোড তৈরি ও গিটহাবে পাবলিশ করবে।",
                            "👁️ স্ক্রিন রিডার" to "অ্যাক্সেসিবিলিটি সার্ভিসের মাধ্যমে স্ক্রিনের সকল লেখা বিশ্লেষণ করে।",
                            "🔦 টর্চলাইট কন্ট্রোল" to "বলুন 'টর্চ জ্বালাও' অথবা 'টর্চ বন্ধ করো'।",
                            "📸 ক্যামেরা ও ছবি বিশ্লেষণ" to "বলুন 'ক্যামেরা চালু করো' কিংবা যেকোনো ফটো ক্যাপচার করুন।",
                            "📶 ওয়াইফাই ও সেটিংস" to "ভয়েস কমান্ডের মাধ্যমে সরাসরি সিস্টেম সেটিংস দ্রুত ওপেন হয়।"
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
                    text = "মেসেজ সংখ্যা: ${messages.size}",
                    color = Glass.cyanFill,
                    fontSize = 11.sp
                )
            }
        }
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
                        Text("কী বসান (API Key)", color = Glass.textMuted, fontSize = 11.sp)
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
                    Text("সেভ", color = Glass.textOnAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (!status.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            // Green only when the provider itself answered; a failure shows its
            // own words in red, so "connected" is never a guess.
            val ok = status.contains("পাওয়া গেছে") || status.contains("সংযুক্ত")
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
                if (granted) "অনুমোদিত ✓" else "অনুমতি দিন",
                color = if (granted) Glass.greenFill else Glass.textOnAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
