package ai.zyraxon.assistant.ui

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.model.AppTab
import ai.zyraxon.assistant.model.ChatMessage
import ai.zyraxon.assistant.model.TaskItem
import ai.zyraxon.assistant.model.ToolExecutionItem
import ai.zyraxon.assistant.model.WorkspaceFile
import ai.zyraxon.assistant.ui.components.AnimatedBackground
import ai.zyraxon.assistant.ui.components.AuraWebView
import ai.zyraxon.assistant.ui.components.ChatPanels
import ai.zyraxon.assistant.ui.components.VideoBackground
import ai.zyraxon.assistant.ui.components.CodePanel
import ai.zyraxon.assistant.ui.components.LivingAuraSphere
import ai.zyraxon.assistant.ui.components.MessageBubble
import ai.zyraxon.assistant.ui.components.ModelSelectionSheet
import ai.zyraxon.assistant.ui.components.PersonaSelectionSheet
import ai.zyraxon.assistant.ui.components.QuickActionChips
import ai.zyraxon.assistant.ui.components.SettingsSheet
import ai.zyraxon.assistant.ui.components.TabSelector
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.update.UpdateState
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.zyraxon.assistant.ui.components.McpScreen
import ai.zyraxon.assistant.mcp.McpAuth

@Composable
fun ZyraxonApp(viewModel: ZyraxonViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val selectedPersona by viewModel.selectedPersona.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val bgMode by viewModel.bgMode.collectAsState()
    val auraStyle by viewModel.auraStyle.collectAsState()
    val auraShader by viewModel.auraShader.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val systemStatus by viewModel.systemStatus.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val toolExecutions by viewModel.toolExecutions.collectAsState()
    val codeBoxOpen by viewModel.codeBoxOpen.collectAsState()
    val codeBoxTitle by viewModel.codeBoxTitle.collectAsState()
    val codeBoxText by viewModel.codeBoxText.collectAsState()
    val filesPanelOpen by viewModel.filesPanelOpen.collectAsState()
    val bgVideoAsset by viewModel.bgVideoAsset.collectAsState()
    val liveVoiceEnabled by viewModel.liveVoiceEnabled.collectAsState()
    val liveVoiceState by viewModel.liveVoiceState.collectAsState()
    val alwaysListening by viewModel.alwaysListening.collectAsState()
    val workspaceFiles by viewModel.workspaceFiles.collectAsState()
    val auraHue by viewModel.auraHue.collectAsState()
    val auraSize by viewModel.auraSize.collectAsState()
    val auraGlow by viewModel.auraGlow.collectAsState()
    val auraSpeed by viewModel.auraSpeed.collectAsState()
    val panelAlpha by viewModel.panelAlpha.collectAsState()

    var showPersonaSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var newTaskText by remember { mutableStateOf("") }

    val updateState by viewModel.updateState.collectAsState()
    val updateDismissed by viewModel.updateBannerDismissed.collectAsState()

    // One check when the app opens, so a new release is offered without the user
    // having to go looking for it. It is silent: being offline must not raise an
    // error banner at launch.
    LaunchedEffect(Unit) { viewModel.checkForUpdates(silent = true) }

    val showUpdateBanner = !updateDismissed && (
        updateState is UpdateState.Available ||
            updateState is UpdateState.Downloading ||
            updateState is UpdateState.Downloaded ||
            updateState is UpdateState.NeedsPermission
        )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05060F))
    ) {
        // 1. Looping video behind everything. The aura HTML is transparent, so
        //    the clip shows through exactly as the user asked.
        VideoBackground(
            assetName = bgVideoAsset,
            modifier = Modifier.fillMaxSize()
        )

        // 1b. Shader fallback, kept for the modes that have no video.
        if (bgVideoAsset.isBlank()) {
            AnimatedBackground(
                mode = bgMode,
                audioLevel = audioLevel,
                isSpeaking = isSpeaking,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Main Scaffold Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Top Bar
            ZyraxonTopBar(
                panelAlpha = panelAlpha,
                personaName = selectedPersona.name,
                personaEmoji = selectedPersona.emoji,
                modelName = selectedModel.name,
                liveActive = liveVoiceEnabled,
                micOn = alwaysListening,
                onMicToggle = { viewModel.setMicEnabled(!alwaysListening) },
                onPersonaClick = { showPersonaSheet = true },
                onModelClick = { showModelSheet = true },
                onLiveClick = {
                    // Speech-to-speech follows the model, so this opens the model
                    // list instead of pretending a toggle starts a live session.
                    showModelSheet = true
                },
                onSettingsClick = { showSettingsSheet = true },
                onClearClick = { viewModel.clearChat() }
            )

            // Update banner. Shown right under the top bar when a newer release
            // exists, with a close button so the user can put it away.
            if (showUpdateBanner) {
                UpdateBanner(
                    state = updateState,
                    onInstall = { viewModel.installUpdate() },
                    onDownload = { viewModel.downloadUpdate() },
                    onOpenPermission = { viewModel.openInstallPermission() },
                    onDismiss = { viewModel.dismissUpdateBanner() }
                )
            }

            // Mode Tabs (চ্যাট, টাস্ক, টুল)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                TabSelector(
                    selectedTab = activeTab,
                    onTabSelected = { viewModel.setTab(it) }
                )
            }

            // Active Tab View
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (activeTab) {
                    AppTab.CHAT -> {
                        ChatTabContent(
                            messages = messages,
                            isListening = isListening,
                            isSpeaking = isSpeaking,
                            micOn = alwaysListening,
                            audioLevel = audioLevel,
                            auraHue = auraHue,
                            auraSize = auraSize,
                            auraGlow = auraGlow,
                            auraSpeed = auraSpeed,
                            auraStyle = auraStyle,
                            auraShader = auraShader,
                            panelAlpha = panelAlpha,
                            tasks = tasks,
                            toolExecutions = toolExecutions,
                            files = workspaceFiles,
                            onCopyText = { clipboardManager.setText(AnnotatedString(it)) },
                            onSpeakText = { viewModel.speak(it) },
                            onDeleteFile = { viewModel.deleteWorkspaceFile(it) },
                            onOpenFile = { viewModel.openWorkspaceFile(it) },
                            onPublishFile = { file ->
                                viewModel.sendMessage(
                                    t("এই ফাইলটি ওয়েবসাইট হিসেবে প্রকাশ করো: {0}", file.relative)
                                )
                            },
                            onRefreshFiles = { viewModel.refreshWorkspaceFiles() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    AppTab.TASKS -> {
                        TasksTabContent(
                            panelAlpha = panelAlpha,
                            tasks = tasks,
                            newTaskText = newTaskText,
                            onNewTaskChange = { newTaskText = it },
                            onAddTask = {
                                viewModel.addTask(newTaskText)
                                newTaskText = ""
                            },
                            onToggleTask = { viewModel.toggleTask(it) },
                            onDeleteTask = { viewModel.deleteTask(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    AppTab.TOOLS -> {
                        ToolsTabContent(
                            panelAlpha = panelAlpha,
                            toolExecutions = toolExecutions,
                            systemStatus = systemStatus,
                            onTriggerTool = { cmd -> viewModel.sendMessage(cmd) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    AppTab.MCP -> {
                        McpTabContent(viewModel = viewModel, panelAlpha = panelAlpha)
                    }
                }
            }

            // The live code panel — it sits above the composer so the user
            // watches a file being written, exactly where the model asked for it.
            if (codeBoxOpen) {
                CodePanel(
                    title = codeBoxTitle,
                    code = codeBoxText,
                    onClose = { viewModel.closeCodePanel() }
                )
            }

            // Bottom Composer (only visible in Chat tab)
            if (activeTab == AppTab.CHAT) {
                BottomComposer(
                    panelAlpha = panelAlpha,
                    text = inputText,
                    onTextChange = { viewModel.onInputChange(it) },
                    onSend = { viewModel.sendMessage() },
                    isListening = isListening,
                    onQuickAction = { viewModel.sendMessage(it) }
                )
            }

            // Bottom-left Version Badge — the real installed version, not a
            // hardcoded string that drifts from the build.
            Box(
                modifier = Modifier
                    .padding(start = 16.dp, bottom = 4.dp)
            ) {
                Text(
                    text = "v${viewModel.currentVersionName}",
                    color = Glass.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Sheets
        if (showPersonaSheet) {
            PersonaSelectionSheet(
                selectedPersona = selectedPersona,
                onSelectPersona = { viewModel.setPersona(it) },
                onDismiss = { showPersonaSheet = false }
            )
        }

        if (showModelSheet) {
            ModelSelectionSheet(
                viewModel = viewModel,
                onDismiss = { showModelSheet = false }
            )
        }

        if (showSettingsSheet) {
            SettingsSheet(
                viewModel = viewModel,
                onDismiss = { showSettingsSheet = false }
            )
        }
    }
}

/**
 * The launch banner. It names the release, walks through download then install,
 * and can be closed. Closing only hides it for this run; the next launch offers
 * it again, so a dismissed update is never lost.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onInstall: () -> Unit,
    onDownload: () -> Unit,
    onOpenPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Glass.greenFill.copy(alpha = 0.16f))
            .border(1.dp, Glass.greenFill, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
            .testTag("update_banner"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                when (state) {
                    is UpdateState.Available -> t("🎉 নতুন আপডেট পাওয়া গেছে — {0}", state.release.tag)
                    is UpdateState.Downloading -> t("⬇️ আপডেট ডাউনলোড হচ্ছে... {0}%", (state.progress * 100).toInt())
                    is UpdateState.Downloaded -> t("✅ ডাউনলোড শেষ — ইনস্টল করুন")
                    is UpdateState.NeedsPermission -> t("🔓 ইনস্টল করতে অনুমতি দরকার")
                    else -> t("🎉 নতুন আপডেট পাওয়া গেছে")
                },
                color = Glass.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                t("অ্যাপ আপডেট হবে — চ্যাট, মেমরি ও সেটিংস থাকবে"),
                color = Glass.textMuted,
                fontSize = 11.sp
            )
        }
        when (state) {
            is UpdateState.Available -> TextButton(
                onClick = onDownload,
                modifier = Modifier.testTag("update_banner_install")
            ) {
                Text(t("ডাউনলোড"), color = Glass.greenFill, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            is UpdateState.Downloaded -> TextButton(
                onClick = onInstall,
                modifier = Modifier.testTag("update_banner_install")
            ) {
                Text(t("ইনস্টল"), color = Glass.greenFill, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            is UpdateState.NeedsPermission -> TextButton(
                onClick = onOpenPermission,
                modifier = Modifier.testTag("update_banner_install")
            ) {
                Text(t("অনুমতি"), color = Glass.greenFill, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            else -> Unit
        }
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("update_banner_close")) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = t("বন্ধ করুন"),
                tint = Glass.textMuted
            )
        }
    }
}


@Composable
fun ZyraxonTopBar(
    personaName: String,
    personaEmoji: String,
    modelName: String,
    panelAlpha: Float = 0.22f,
    liveActive: Boolean = false,
    micOn: Boolean = true,
    onPersonaClick: () -> Unit,
    onModelClick: () -> Unit,
    onLiveClick: () -> Unit = {},
    onMicToggle: () -> Unit = {},
    onSettingsClick: () -> Unit,
    onClearClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Persona Chip (💖 প্রেমিকা • online)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Glass.fill(panelAlpha))
                .border(1.dp, Glass.border, RoundedCornerShape(20.dp))
                .clickable { onPersonaClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("persona_button")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = personaEmoji, fontSize = 15.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = personaName,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Glass.textPrimary
                    )
                    Text(
                        text = if (liveActive) "লাইভ ভয়েস চালু" else "প্রস্তুত",
                        fontSize = 9.sp,
                        color = Glass.green
                    )
                }
            }
        }

        // Right side: mic switch, Live toggle, model pill, settings, trash
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // The microphone switch the user asked for: tap to mute the mic and
            // leave it muted; tap again to open it for the rest of the session.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (micOn) Glass.greenFill else Glass.fill(panelAlpha))
                    .border(1.dp, if (micOn) Glass.greenFill else Glass.border, RoundedCornerShape(20.dp))
                    .clickable { onMicToggle() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("mic_toggle_button")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = if (micOn) "🎙️" else "🔇", fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (micOn) t("মাইক চালু") else t("মাইক বন্ধ"),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (micOn) Glass.textPrimary else Glass.textSecondary
                    )
                }
            }

            // Live speech-to-speech toggle. Tapping it opens the model list, since
            // the live session follows the chosen realtime model.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (liveActive) Glass.cyanFill else Glass.fill(panelAlpha)
                    )
                    .border(1.dp, Glass.border, RoundedCornerShape(20.dp))
                    .clickable { onLiveClick() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("live_voice_button")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = if (liveActive) "🔴" else "🎙", fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "লাইভ",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (liveActive) Glass.textPrimary else Glass.textSecondary
                    )
                }
            }

            // Dynamic Model Selector Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Glass.fill(panelAlpha))
                    .border(1.dp, Glass.border, RoundedCornerShape(20.dp))
                    .clickable { onModelClick() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("model_picker_button")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Glass.cyanFill)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (modelName.length > 15) modelName.take(13) + "…" else modelName,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.textPrimary
                    )
                }
            }

            // Settings Button
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Glass.rowFill(panelAlpha))
                    .testTag("settings_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    tint = Glass.textPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Clear Chat Button
            IconButton(
                onClick = onClearClick,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Glass.rowFill(panelAlpha))
                    .testTag("clear_chat_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Clear Chat",
                    tint = Glass.red,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun ChatTabContent(
    messages: List<ChatMessage>,
    isListening: Boolean,
    isSpeaking: Boolean,
    micOn: Boolean = true,
    audioLevel: Float,
    auraHue: Int,
    auraSize: Float,
    auraGlow: Float,
    auraSpeed: Float,
    auraStyle: String,
    auraShader: String,
    panelAlpha: Float,
    tasks: List<TaskItem>,
    toolExecutions: List<ToolExecutionItem>,
    files: List<WorkspaceFile>,
    onCopyText: (String) -> Unit,
    onSpeakText: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onPublishFile: (WorkspaceFile) -> Unit,
    onRefreshFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var transcriptOpen by remember { mutableStateOf(true) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Keep the Files panel honest: whenever a turn finishes, re-read the disk so
    // a file the assistant just wrote appears without a manual refresh.
    LaunchedEffect(messages.lastOrNull()?.id) {
        onRefreshFiles()
    }

    Column(modifier = modifier) {
        // The aura, centred and untouched, exactly as designed. It is also the
        // microphone: the mic is always open, so there is no button and no icon.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 80.dp),
            contentAlignment = Alignment.Center
        ) {
            AuraWebView(
                isListening = isListening,
                isSpeaking = isSpeaking,
                audioLevel = audioLevel,
                hue = auraHue,
                size = auraSize,
                glow = auraGlow,
                speed = auraSpeed,
                auraStyle = auraStyle,
                auraShader = auraShader,
                onTap = { transcriptOpen = !transcriptOpen }
            )

            // Live transcript floats over the aura, so the user sees their words
            // land the moment they speak them. It also shows whether the mic is
            // on or muted, so the user never has to guess.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        !micOn -> t("🔇 মাইক বন্ধ")
                        isListening -> t("শুনছি…")
                        isSpeaking -> t("ZYRAXON বলছে…")
                        else -> t("মাইক চালু")
                    },
                    color = when {
                        !micOn -> Color(0x99FFFFFF)
                        isListening -> Color(0xFF7DF9FF)
                        isSpeaking -> Color(0xFFFF8AD8)
                        else -> Color(0x99FFFFFF)
                    },
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // The three live panels sit inside the chat column, right above the
        // composer, and stay out of the way until something happens.
        ChatPanels(
            tasks = tasks,
            toolExecutions = toolExecutions,
            files = files,
            panelAlpha = panelAlpha,
            onDeleteFile = onDeleteFile,
            onOpenFile = onOpenFile,
            onPublishFile = onPublishFile
        )

        // Transcript, collapsible so the aura keeps the screen when the user
        // only wants to talk.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (transcriptOpen) t("▼ কথোপকথন") else t("▲ কথোপকথন"),
                color = Glass.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { transcriptOpen = !transcriptOpen }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        if (transcriptOpen) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        onCopy = { onCopyText(message.text) },
                        onSpeak = { onSpeakText(message.text) }
                    )
                }
            }
        }
    }
}

@Composable
fun TasksTabContent(
    tasks: List<TaskItem>,
    newTaskText: String,
    onNewTaskChange: (String) -> Unit,
    onAddTask: () -> Unit,
    onToggleTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    panelAlpha: Float = 0.22f,
    modifier: Modifier = Modifier
) {
    val completedCount = tasks.count { it.isCompleted }
    val totalCount = tasks.size
    val progress = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f

    Column(
        modifier = modifier
            .padding(16.dp)
    ) {
        // Summary & Progress Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Glass.fill(panelAlpha)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = t("টাস্ক ও করণীয় তালিকা"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Glass.textPrimary
                    )
                    Text(
                        text = t("{0}/{1} সম্পন্ন", completedCount, totalCount),
                        fontSize = 12.sp,
                        color = Glass.pink,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Glass.pink,
                    trackColor = Glass.fill(panelAlpha)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Add Task Input Box
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Glass.fill(panelAlpha))
                .border(1.dp, Glass.border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = newTaskText,
                onValueChange = onNewTaskChange,
                modifier = Modifier
                    .weight(1f)
                    .testTag("new_task_input"),
                textStyle = TextStyle(color = Glass.textPrimary, fontSize = 13.5.sp),
                cursorBrush = SolidColor(Glass.pink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAddTask() }),
                decorationBox = { inner ->
                    if (newTaskText.isEmpty()) {
                        Text(t("নতুন টাস্ক লিখুন..."), color = Glass.textMuted, fontSize = 13.sp)
                    }
                    inner()
                }
            )
            IconButton(onClick = onAddTask, modifier = Modifier.testTag("add_task_button")) {
                Icon(Icons.Rounded.Add, contentDescription = "Add Task", tint = Glass.pink)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Task Items List
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tasks, key = { it.id }) { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (task.isCompleted) Color(0x2210B981) else Glass.fill(panelAlpha))
                        .border(
                            1.dp,
                            if (task.isCompleted) Color(0x4410B981) else Glass.borderSoft,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = task.isCompleted,
                        onCheckedChange = { onToggleTask(task.id) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF10B981),
                            uncheckedColor = Color(0xFF64748B)
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.text,
                            color = if (task.isCompleted) Glass.textMuted else Glass.textPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = if (task.isCompleted) FontWeight.Normal else FontWeight.Medium
                        )
                        if (task.createdAt.isNotEmpty()) {
                            Text(
                                text = task.createdAt,
                                color = Glass.textMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                    IconButton(onClick = { onDeleteTask(task.id) }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Delete",
                            tint = Color(0x88EF4444),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ToolsTabContent(
    toolExecutions: List<ToolExecutionItem>,
    systemStatus: ai.zyraxon.assistant.model.SystemStatus,
    onTriggerTool: (String) -> Unit,
    panelAlpha: Float = 0.22f,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = t("সিস্টেম টুল ও অটোমেশন কন্ট্রোল"),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Glass.textPrimary
        )
        Text(
            text = t("এক ট্যাপে ডিভাইস হার্ডওয়্যার ও সিস্টেম অ্যাকশন চালু করুন"),
            fontSize = 12.sp,
            color = Glass.textSecondary
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Grid of Quick Interactive Tools
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolActionCard(
                    title = t("টর্চলাইট"),
                    desc = t("ফ্ল্যাশলাইট অন/অফ"),
                    icon = Icons.Rounded.FlashlightOn,
                    color = Color(0xFFFBBF24),
                    onClick = { onTriggerTool(t("টর্চ জ্বালানো বা বন্ধ করো")) },
                    modifier = Modifier.weight(1f)
                )
                ToolActionCard(
                    title = t("ক্যামেরা"),
                    desc = t("ক্যামেরা ও ফটো ক্যাপচার"),
                    icon = Icons.Rounded.CameraAlt,
                    color = Glass.cyan,
                    onClick = { onTriggerTool(t("ক্যামেরা ওপেন করো")) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolActionCard(
                    title = t("ফেসবুক"),
                    desc = t("ফেসবুক মেসেজিং অ্যাপ"),
                    icon = Icons.Rounded.OpenInBrowser,
                    color = Color(0xFF3B82F6),
                    onClick = { onTriggerTool(t("ফেসবুক খোলো")) },
                    modifier = Modifier.weight(1f)
                )
                ToolActionCard(
                    title = t("ওয়াইফাই সেটিংস"),
                    desc = t("ওয়াইফাই ও নেটওয়ার্ক কনফিগ"),
                    icon = Icons.Rounded.Wifi,
                    color = Glass.green,
                    onClick = { onTriggerTool(t("ওয়াইফাই সেটিংস ওপেন করো")) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolActionCard(
                    title = t("ওয়েবসাইট বিল্ডার"),
                    desc = t("HTML/CSS কোড তৈরি"),
                    icon = Icons.Rounded.Security,
                    color = Glass.pink,
                    onClick = { onTriggerTool(t("একটি সুন্দর পোর্টফোলিও ওয়েবসাইট বানাও")) },
                    modifier = Modifier.weight(1f)
                )
                ToolActionCard(
                    title = t("স্ক্রিন রিডার"),
                    desc = t("স্ক্রিনের লেখা পাঠ"),
                    icon = Icons.Rounded.Phone,
                    color = Color(0xFFA855F7),
                    onClick = { onTriggerTool(t("আমার স্ক্রিন পড়ে শোনাও")) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Tool Execution History
        Text(
            text = t("টুল এক্সিকিউশন লগ"),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Glass.textPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))

        toolExecutions.forEach { item ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Glass.fill(panelAlpha))
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = item.icon, fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = item.name, color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(text = item.description, color = Glass.textSecondary, fontSize = 11.5.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = item.status, color = Glass.green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(text = item.timestamp, color = Glass.textMuted, fontSize = 9.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ToolActionCard(
    panelAlpha: Float = 0.22f,
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Glass.fill(panelAlpha))
            .border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(text = title, color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(text = desc, color = Glass.textSecondary, fontSize = 10.5.sp)
            }
        }
    }
}

@Composable
fun BottomComposer(
    panelAlpha: Float = 0.22f,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isListening: Boolean,
    onQuickAction: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Floating Frosted Glass Input Container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Glass.fill(panelAlpha))
                .border(1.dp, Glass.border, RoundedCornerShape(28.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                        .testTag("chat_input_field"),
                    textStyle = TextStyle(
                        color = Glass.textPrimary,
                        fontSize = 14.5.sp
                    ),
                    cursorBrush = SolidColor(Glass.pink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    decorationBox = { innerTextField ->
                        if (text.isEmpty()) {
                            Text(
                                text = t("বলা বা লেখা..."),
                                color = Glass.textMuted,
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                )

                // Send Button (glowing pink/magenta gradient)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Glass.pink, Color(0xFFD946EF))
                            )
                        )
                        .clickable {
                            if (text.isNotBlank()) onSend()
                        }
                        .testTag("send_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Send",
                        tint = Glass.textOnAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Quick Action Chips Row
        QuickActionChips(onChipClick = onQuickAction)
    }
}

/**
 * The MCP tab: every server, what it can do, and a Connect button that opens the
 * browser for the OAuth ones. A connected server's tools are already inside the
 * assistant, so there is nothing else to switch on here.
 */
@Composable
private fun McpTabContent(viewModel: ZyraxonViewModel, panelAlpha: Float) {
    val connections by viewModel.mcpConnections.collectAsState()
    val notice by viewModel.mcpNotice.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (notice.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Glass.fill(0.28f))
                    .border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    notice,
                    color = Glass.textSecondary,
                    fontSize = 11.5.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "✕",
                    color = Glass.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.clearMcpNotice() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        McpScreen(
            connections = connections,
            onConnect = { server ->
                // GitHub needs a token; ask for it right here rather than sending
                // the user to Settings.
                if (server.auth == McpAuth.TOKEN && !server.custom) {
                    viewModel.pendingTokenServer.value = server
                } else {
                    viewModel.connectMcp(server)
                }
            },
            onDisconnect = { viewModel.disconnectMcp(it) },
            onAddCustom = { url, name, token -> viewModel.addCustomMcp(url, name, token) },
            onRemoveCustom = { viewModel.removeCustomMcp(it) },
            modifier = Modifier.fillMaxSize()
        )
    }

    val pending by viewModel.pendingTokenServer.collectAsState()
    pending?.let { server ->
        McpTokenDialog(
            serverName = server.name,
            tokenUrl = server.tokenUrl,
            hint = server.tokenHint,
            onOpenTokenPage = { viewModel.openTokenPage(server.tokenUrl) },
            onDismiss = { viewModel.pendingTokenServer.value = null },
            onConnect = { token ->
                viewModel.connectMcp(server, token)
                viewModel.pendingTokenServer.value = null
            }
        )
    }
}

/** Collects a personal access token for a server that needs one. */
@Composable
private fun McpTokenDialog(
    serverName: String,
    tokenUrl: String,
    hint: String,
    onOpenTokenPage: () -> Unit,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit
) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Glass.sheetFill(0.92f),
        title = { Text(t("{0} কানেক্ট করো", serverName), color = Glass.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    hint.ifBlank { t("অ্যাক্সেস টোকেন দাও") },
                    color = Glass.textMuted,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    t("টোকেন বানাও →"),
                    color = Glass.pinkFill,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenTokenPage)
                        .padding(vertical = 4.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Access token", fontSize = 11.5.sp) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(token.trim()) }, enabled = token.isNotBlank()) {
                Text(t("কানেক্ট"), color = Glass.pinkFill, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("বাতিল"), color = Glass.textMuted) }
        }
    )
}
