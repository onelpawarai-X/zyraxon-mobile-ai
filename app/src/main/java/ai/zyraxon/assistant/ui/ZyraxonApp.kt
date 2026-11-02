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
import ai.zyraxon.assistant.ui.components.BottomFloatingPanel
import ai.zyraxon.assistant.ui.components.BloodCyberDockBar
import ai.zyraxon.assistant.ui.components.VideoBackground
import ai.zyraxon.assistant.ui.components.BloodDrips
import ai.zyraxon.assistant.ui.components.BloodGlow
import ai.zyraxon.assistant.ui.components.BloodSplatter
import ai.zyraxon.assistant.ui.components.BloodVeins
import ai.zyraxon.assistant.ui.components.WatchingEyes
import ai.zyraxon.assistant.ui.components.glitchShake
import ai.zyraxon.assistant.ui.components.Scanlines
import ai.zyraxon.assistant.ui.components.BoneWhite
import ai.zyraxon.assistant.ui.components.HorrorVignette
import ai.zyraxon.assistant.ui.components.RitualCircle
import ai.zyraxon.assistant.ui.components.bloodPulseBorder
import ai.zyraxon.assistant.ui.components.horrorFlicker
import ai.zyraxon.assistant.ui.components.CodePanel
import ai.zyraxon.assistant.ui.components.LivingAuraSphere
import ai.zyraxon.assistant.ui.components.ZyraxonAura
import ai.zyraxon.assistant.ui.components.MessageBubble
import ai.zyraxon.assistant.ui.components.QuickActionChips
import ai.zyraxon.assistant.ui.components.SettingsSheet
import ai.zyraxon.assistant.ui.components.TerminalScreen
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.update.UpdateState
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import ai.zyraxon.assistant.ui.components.rememberLiveTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val attachedImages by viewModel.attachedImages.collectAsState()
    val imageNotice by viewModel.imageNotice.collectAsState()
    val alwaysSeeScreen by viewModel.alwaysSeeScreen.collectAsState()
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
    val terminalLines by viewModel.terminalLines.collectAsState()
    val terminalBusy by viewModel.terminalBusy.collectAsState()
    val terminalCwd by viewModel.terminalCwd.collectAsState()
    val terminalSessions by viewModel.terminalSessions.collectAsState()
    val activeTerminalId by viewModel.activeTerminalId.collectAsState()
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

    var showSettingsSheet by remember { mutableStateOf(false) }
    var newTaskText by remember { mutableStateOf("") }
    var activeDockPanel by remember { mutableStateOf<String?>(null) }

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
        // The Terminal is a full-screen takeover: it covers the aura, the chat and
        // the tab bar, so nothing of the app shows through while a real shell is
        // open. The ✕ in its header is the only way back to the main menu, and it
        // leaves the session — scrollback and working directory — untouched.
        if (activeTab == AppTab.TERMINAL) {
            TerminalScreen(
                sessions = terminalSessions,
                activeId = activeTerminalId,
                lines = terminalLines,
                cwd = terminalCwd,
                busy = terminalBusy,
                onRun = { viewModel.runTerminal(it) },
                onInput = { viewModel.sendTerminalInput(it) },
                onInterrupt = { viewModel.interruptTerminal() },
                onClear = { viewModel.clearTerminal() },
                onNewSession = { viewModel.newTerminalSession() },
                onSelectSession = { viewModel.selectTerminalSession(it) },
                onCloseSession = { viewModel.closeTerminalSession(it) },
                onClose = { viewModel.setTab(AppTab.CHAT) },
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            )
            return@Box
        }

        // 1. The animated shader sits behind everything, always. It used to be
        //    skipped whenever a video was chosen, so a clip that could not decode
        //    left the screen black with no animation at all — the "no animation"
        //    the user reported. Keeping it underneath means there is always a
        //    living background, and the video simply covers it when it plays.
        AnimatedBackground(
            mode = bgMode,
            audioLevel = audioLevel,
            isSpeaking = isSpeaking,
            modifier = Modifier.fillMaxSize()
        )

        // 1b. Background is the dedicated blood-red cyber picture with living circulating smoke and embers

        // 2. Main Scaffold Content (blurs smoothly when Settings is open)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showSettingsSheet) Modifier.blur(22.dp) else Modifier)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Top Bar with SVG Vector Icons & Real Terminal launcher
            ZyraxonTopBar(
                activeTab = activeTab,
                selectedPersonaName = selectedPersona.name,
                onTabSelect = { tab -> viewModel.setTab(tab) },
                onClearChat = { viewModel.clearChat() },
                onSettingsClick = { showSettingsSheet = true }
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
                            onCopyText = {
                                clipboardManager.setText(AnnotatedString(it))
                                android.widget.Toast.makeText(
                                    context, t("কপি হয়েছে"), android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            onSpeakText = { viewModel.speak(it) },
                            onDeleteFile = { viewModel.deleteWorkspaceFile(it) },
                            onOpenFile = { viewModel.openWorkspaceFile(it) },
                            onPublishFile = { file ->
                                viewModel.sendMessage(
                                    t("এই ফাইলটি ওয়েবসাইট হিসেবে প্রকাশ করো: {0}", file.relative)
                                )
                            },
                            onRefreshFiles = { viewModel.refreshWorkspaceFiles() },
                            onMicToggle = { viewModel.toggleListening() },
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
                        McpTabContent(
                            viewModel = viewModel,
                            panelAlpha = panelAlpha,
                            onClose = { viewModel.setTab(AppTab.CHAT) }
                        )
                    }

                    // The terminal returns above, before this scaffold is built.
                    AppTab.TERMINAL -> Unit
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

            // Expandable floating bottom panel (Todo, Tools, Files)
            if (activeDockPanel != null && activeTab == AppTab.CHAT) {
                BottomFloatingPanel(
                    panelType = activeDockPanel!!,
                    tasks = tasks,
                    toolExecutions = toolExecutions,
                    files = workspaceFiles,
                    onDeleteFile = { viewModel.deleteWorkspaceFile(it) },
                    onOpenFile = { viewModel.openWorkspaceFile(it) },
                    onPublishFile = { file ->
                        viewModel.sendMessage(
                            t("এই ফাইলটি ওয়েবসাইট হিসেবে প্রকাশ করো: {0}", file.relative)
                        )
                    },
                    onClose = { activeDockPanel = null }
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
                    micOn = alwaysListening,
                    onMicToggle = { viewModel.toggleListening() },
                    onQuickAction = { viewModel.sendMessage(it) },
                    attachedImages = attachedImages,
                    imageNotice = imageNotice,
                    onAttachMedia = { uri, mime -> viewModel.attachMedia(uri, mime) },
                    onCameraShot = { viewModel.captureImageFromTool("look_camera") },
                    onScreenShot = { viewModel.captureImageFromTool("screen_shot") },
                    onRemoveAttachment = { index ->
                        if (index < 0) viewModel.clearAttachedImage() else viewModel.removeAttachedImage(index)
                    },
                    alwaysSeeScreen = alwaysSeeScreen,
                    onToggleAlwaysSee = { viewModel.setAlwaysSeeScreen(it) }
                )

                // Blood Cyber Luxury Bottom Dock Panel
                BloodCyberDockBar(
                    activePanel = activeDockPanel,
                    todoCount = tasks.count { !it.isCompleted },
                    toolCount = toolExecutions.size,
                    fileCount = workspaceFiles.size,
                    onPanelSelect = { activeDockPanel = it },
                    onOpenTerminal = { viewModel.setTab(AppTab.TERMINAL) }
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

        // Home-screen gore: veins crawling in from the corners, a beating
        // vignette and blood running down from the top edge. Decorative only.
        // This layer sits over every tab (chat, tasks, tools, MCP), so the whole
        // app is consumed, not just the home screen.
        BloodVeins(Modifier.fillMaxSize(), seed = 5)
        BloodSplatter(Modifier.fillMaxSize(), seed = 8)
        WatchingEyes(Modifier.fillMaxSize(), seed = 66, pairs = 4)
        Scanlines(Modifier.fillMaxSize(), lineAlpha = 0.07f)
        HorrorVignette(Modifier.fillMaxSize(), intensity = 1.2f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .bloodPulseBorder(RoundedCornerShape(0.dp), 2.dp)
        )
        BloodDrips(Modifier.align(Alignment.TopStart), seed = 11, count = 7, maxLength = 54.dp)

        // Luxurious frosted dark blood blur scrim behind SettingsSheet
        if (showSettingsSheet) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xD9080103))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showSettingsSheet = false }
            )
        }

        // Settings Sheet
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


/**
 * Luxurious Top Bar with SVG Vector Icons & Terminal Launcher.
 * Clean, modern cyberpunk blood-red aesthetics with haptic feedback.
 */
@Composable
fun ZyraxonTopBar(
    activeTab: AppTab,
    selectedPersonaName: String,
    onTabSelect: (AppTab) -> Unit,
    onClearChat: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Branding & Persona Pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable {
                Glass.vibrate(context, 14)
                onTabSelect(AppTab.CHAT)
            }
        ) {
            Text(
                text = "☠",
                color = Color(0xFFFF1E44),
                fontSize = 15.sp,
                style = TextStyle(shadow = BloodGlow),
                modifier = Modifier.horrorFlicker()
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "ZYRAXON",
                color = BoneWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                style = TextStyle(shadow = BloodGlow),
                modifier = Modifier.horrorFlicker().glitchShake()
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Glass.pinkFill.copy(alpha = 0.22f))
                    .bloodPulseBorder(RoundedCornerShape(8.dp), 1.dp)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = selectedPersonaName,
                    color = Glass.pink,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // SVG / Vector Action Icons Cluster
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Terminal SVG Icon Button (Real Interactive Terminal full screen takeover)
            TopBarSvgIconButton(
                icon = Icons.Rounded.Terminal,
                contentDescription = t("রিয়েল টার্মিনাল"),
                isActive = activeTab == AppTab.TERMINAL,
                activeColor = Glass.pinkFill,
                testTag = "terminal_button",
                onClick = {
                    Glass.vibrate(context, 18)
                    onTabSelect(if (activeTab == AppTab.TERMINAL) AppTab.CHAT else AppTab.TERMINAL)
                }
            )

            // 2. Tasks / Todo SVG Button
            TopBarSvgIconButton(
                icon = Icons.Rounded.Checklist,
                contentDescription = t("টাস্ক ও টুডু"),
                isActive = activeTab == AppTab.TASKS,
                activeColor = Glass.pinkFill,
                testTag = "tasks_button",
                onClick = {
                    Glass.vibrate(context, 14)
                    onTabSelect(if (activeTab == AppTab.TASKS) AppTab.CHAT else AppTab.TASKS)
                }
            )

            // 3. Tools SVG Button
            TopBarSvgIconButton(
                icon = Icons.Rounded.Build,
                contentDescription = t("সিস্টেম টুলস"),
                isActive = activeTab == AppTab.TOOLS,
                activeColor = Glass.pinkFill,
                testTag = "tools_button",
                onClick = {
                    Glass.vibrate(context, 14)
                    onTabSelect(if (activeTab == AppTab.TOOLS) AppTab.CHAT else AppTab.TOOLS)
                }
            )

            // 4. MCP SVG Button
            TopBarSvgIconButton(
                icon = Icons.Rounded.Hub,
                contentDescription = t("MCP প্রোটোকল"),
                isActive = activeTab == AppTab.MCP,
                activeColor = Glass.pinkFill,
                testTag = "mcp_button",
                onClick = {
                    Glass.vibrate(context, 14)
                    onTabSelect(if (activeTab == AppTab.MCP) AppTab.CHAT else AppTab.MCP)
                }
            )

            // 5. New / Clear Chat SVG Button
            TopBarSvgIconButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = t("নতুন চ্যাট"),
                isActive = false,
                testTag = "clear_chat_button",
                onClick = {
                    Glass.vibrate(context, 16)
                    onClearChat()
                }
            )

            // 6. Settings SVG Button
            TopBarSvgIconButton(
                icon = Icons.Rounded.Settings,
                contentDescription = t("সেটিংস"),
                isActive = false,
                testTag = "settings_button",
                onClick = {
                    Glass.vibrate(context, 18)
                    onSettingsClick()
                }
            )
        }
    }
}

@Composable
fun TopBarSvgIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean = false,
    activeColor: Color = Glass.pinkFill,
    testTag: String = "",
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (isActive) activeColor else Glass.rowFill(0.35f))
            .border(
                1.dp,
                if (isActive) Color(0xFFFF1E44) else Glass.borderSoft,
                CircleShape
            )
            .clickable { onClick() }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) Color.White else Glass.textPrimary,
            modifier = Modifier.size(18.dp)
        )
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
    onMicToggle: () -> Unit = {},
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
        // The Living Blood-Red Aura, centered and alive!
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 16.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            // 0. Slowly turning blood sigil behind the aura
            RitualCircle(Modifier.size(320.dp))

            // 0b. Flickering title and tagline at the top of the home screen
            Column(
                modifier = Modifier.align(Alignment.TopCenter),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "☠ Z Y R A X O N ☠",
                    color = BoneWhite,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp,
                    style = TextStyle(shadow = BloodGlow),
                    modifier = Modifier.horrorFlicker().glitchShake()
                )
                Text(
                    text = t("রক্তে জাগা বুদ্ধিমত্তা — ডাকলেই সাড়া দেবে"),
                    color = Glass.pink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(shadow = BloodGlow)
                )
            }

            // 1. Living Blood-Red Aura Sphere
            ZyraxonAura(
                style = auraStyle,
                hue = auraHue,
                scaleFactor = auraSize,
                glow = auraGlow,
                speed = auraSpeed,
                isListening = isListening,
                isSpeaking = isSpeaking,
                audioLevel = audioLevel,
                onClick = {
                    onMicToggle()
                }
            )

            // 2. Live transcript & voice status floating below the aura
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Glass.fill(panelAlpha))
                    .bloodPulseBorder(RoundedCornerShape(14.dp), 1.dp)
                    .clickable { onMicToggle() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        isSpeaking -> t("ZYRAXON বলছে… থামাতে ওরায় ট্যাপ করো")
                        !micOn -> t("🔇 মাইক বন্ধ — চালু করতে ওরায় ট্যাপ করো")
                        isListening -> t("🎙️ শুনছি… — বন্ধ করতে ওরায় ট্যাপ করো")
                        else -> t("মাইক চালু — বন্ধ করতে ওরায় ট্যাপ করো")
                    },
                    color = when {
                        isSpeaking -> Color(0xFFFF5277)
                        !micOn -> Glass.textMuted
                        isListening -> Color(0xFFFF1E44)
                        else -> Glass.textPrimary
                    },
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Middle floating buttons (Todo, Tool, File) are removed from here and relocated cleanly to the bottom BloodCyberDockBar

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
            Spacer(modifier = Modifier.weight(1f))
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
                        Text(text = t(item.name), color = Glass.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(text = t(item.description), color = Glass.textSecondary, fontSize = 11.5.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(text = t(item.status), color = Glass.green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
fun BloodCyberMicButton(
    micOn: Boolean,
    isListening: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val infiniteTransition = rememberLiveTransition(label = "blood_mic_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic_pulse"
    )
    val rippleRadius by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.50f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mic_ripple"
    )

    Box(
        modifier = Modifier
            .size(46.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                Glass.vibrate(context, 20)
                onClick()
            }
            .testTag("mic_button"),
        contentAlignment = Alignment.Center
    ) {
        // Living pulsing audio-reactive energy ripples when mic is active
        if (micOn) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseR = (size.width / 2f) * 0.70f
                val rippleAlpha = ((1.50f - rippleRadius) / 0.65f).coerceIn(0f, 0.75f)
                drawCircle(
                    color = Color(0xFFFF1E44).copy(alpha = rippleAlpha),
                    radius = baseR * rippleRadius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Blood-Red Cyber Mic Core Orb
        Box(
            modifier = Modifier
                .size(if (micOn && isListening) 40.dp * pulseScale else 40.dp)
                .clip(CircleShape)
                .background(
                    if (micOn) {
                        Brush.radialGradient(
                            listOf(
                                Color(0xFFFF2A4D),
                                Color(0xFFC0001E),
                                Color(0xFF6B000B),
                                Color(0xFF280004)
                            )
                        )
                    } else {
                        Brush.radialGradient(
                            listOf(
                                Color(0xFF24060B),
                                Color(0xFF140205),
                                Color(0xFF0A0103)
                            )
                        )
                    }
                )
                .border(
                    width = if (micOn) 1.6.dp else 1.dp,
                    brush = if (micOn) {
                        Brush.sweepGradient(
                            listOf(
                                Color(0xFFFF8599),
                                Color(0xFFFF1E44),
                                Color(0xFF880016),
                                Color(0xFFFF1E44),
                                Color(0xFFFF8599)
                            )
                        )
                    } else {
                        SolidColor(Color(0x55FF1E44))
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (micOn) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                contentDescription = if (micOn) t("মাইক বন্ধ করুন") else t("মাইক চালু করুন"),
                tint = if (micOn) Color(0xFFFFFFFF) else Color(0xFF882233),
                modifier = Modifier.size(20.dp)
            )
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
    micOn: Boolean,
    onMicToggle: () -> Unit,
    onQuickAction: (String) -> Unit,
    attachedImages: List<String> = emptyList(),
    imageNotice: String = "",
    onAttachImage: (android.net.Uri) -> Unit = {},
    onAttachMedia: (android.net.Uri, String) -> Unit = { _, _ -> },
    onCameraShot: () -> Unit = {},
    onScreenShot: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    alwaysSeeScreen: Boolean = true,
    onToggleAlwaysSee: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    // The picker is asked for "any" type, so one button reaches photos, videos
    // and audio. The MIME type comes back with the URI and decides how it is read.
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            val type = context.contentResolver.getType(uri) ?: "image/*"
            onAttachMedia(uri, type)
        }
    }
    var showAttachMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        // Attachment strip: what is queued for the next send, each with a remove
        // button so the user can drop one picture without clearing the rest.
        if (attachedImages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                attachedImages.take(6).forEachIndexed { index, dataUrl ->
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(46.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x33120A0C))
                            .border(1.dp, Color(0xFFFF4A66), RoundedCornerShape(10.dp))
                    ) {
                        coil.compose.AsyncImage(
                            model = dataUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color(0xCC6B000B))
                                .clickable { onRemoveAttachment(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✕", color = Color.White, fontSize = 9.sp)
                        }
                    }
                }
                if (attachedImages.size > 6) {
                    Text("+${attachedImages.size - 6}", color = Color(0xFFFFA3B2), fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "সব মুছুন",
                    color = Color(0xFFFF6B85),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onRemoveAttachment(-1) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        if (imageNotice.isNotBlank()) {
            Text(
                imageNotice,
                color = Color(0xCCFFB3C0),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp, start = 6.dp)
            )
        }
        // Floating Frosted Obsidian-Blood Input Capsule
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xF2150206),
                            Color(0xF222040B),
                            Color(0xF2150206)
                        )
                    )
                )
                .bloodPulseBorder(RoundedCornerShape(28.dp), 1.6.dp)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The microphone is the aura in the middle of the screen, so the
                // composer has no mic button of its own. The attach button opens
                // the picture/video/audio picker, or a small menu for a fresh
                // camera shot and a screen capture.
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FF2A4D))
                        .border(1.dp, Color(0x66FF6680), CircleShape)
                        .clickable { showAttachMenu = !showAttachMenu }
                        .testTag("attach_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AttachFile,
                        contentDescription = "Attach",
                        tint = Color(0xFFFFC2CD),
                        modifier = Modifier.size(17.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // The microphone switch: tap once to keep the ear open, tap again
                // to keep it closed. Its state is the saved choice, so a restart
                // comes back exactly as the user left it.
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (micOn) Color(0x55FF2A4D) else Color(0x33120A0C))
                        .border(
                            1.dp,
                            if (micOn) Color(0xFFFF6680) else Color(0x55FFA3B2),
                            CircleShape
                        )
                        .clickable { onMicToggle() }
                        .testTag("mic_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (micOn) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                        contentDescription = if (micOn) "Mic on" else "Mic off",
                        tint = if (micOn) Color(0xFFFFE0E6) else Color(0x99FFA3B2),
                        modifier = Modifier.size(17.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                        .testTag("chat_input_field"),
                    textStyle = TextStyle(
                        color = Color(0xFFFFF0F2),
                        fontSize = 14.5.sp
                    ),
                    cursorBrush = SolidColor(Color(0xFFFF1E44)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (text.isNotBlank()) {
                            Glass.vibrate(context, 18)
                            onSend()
                        }
                    }),
                    decorationBox = { innerTextField ->
                        if (text.isEmpty()) {
                            Text(
                                text = t("বলা বা লেখা..."),
                                color = Color(0x99FFA3B2),
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                )

                // Send Button (Glowing blood-red ruby circular button)
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    Color(0xFFFF2A4D),
                                    Color(0xFFC0001E),
                                    Color(0xFF6B000B)
                                )
                            )
                        )
                        .border(1.2.dp, Color(0xFFFF6680), CircleShape)
                        .clickable {
                            if (text.isNotBlank()) {
                                Glass.vibrate(context, 18)
                                onSend()
                            }
                        }
                        .testTag("send_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Quick Action Chips Row
        QuickActionChips(onChipClick = onQuickAction)

        // The attach menu: one row for the picker (photo/video/audio), one for a
        // camera shot, one for a fresh screen capture, and a switch for the
        // always-see-screen timer. It floats above the chips and closes on tap.
        if (showAttachMenu) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xE6140207))
                    .border(1.dp, Color(0x66FF2A4D), RoundedCornerShape(16.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AttachChip("🖼️ গ্যালারি") {
                    showAttachMenu = false
                    mediaPicker.launch("*/*")
                }
                AttachChip("📷 ক্যামেরা") {
                    showAttachMenu = false
                    onCameraShot()
                }
                AttachChip("📱 স্ক্রিন") {
                    showAttachMenu = false
                    onScreenShot()
                }
                AttachChip(if (alwaysSeeScreen) "👁️ সবসময় দেখছে" else "🙈 স্ক্রিন দেখা বন্ধ") {
                    onToggleAlwaysSee(!alwaysSeeScreen)
                }
            }
        }
    }
}

/** One pill inside the attach menu. */
@Composable
private fun AttachChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFFFFD9E0),
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x33FF2A4D))
            .border(1.dp, Color(0x55FF6680), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 6.dp)
    )
}

/**
 * The MCP tab: every server, what it can do, and a Connect button that opens the
 * browser for the OAuth ones. A connected server's tools are already inside the
 * assistant, so there is nothing else to switch on here.
 */
@Composable
private fun McpTabContent(viewModel: ZyraxonViewModel, panelAlpha: Float, onClose: () -> Unit = {}) {
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
            onCancel = { server -> viewModel.cancelMcp(server) },
            onDisconnect = { viewModel.disconnectMcp(it) },
            onAddCustom = { url, name, token -> viewModel.addCustomMcp(url, name, token) },
            onRemoveCustom = { viewModel.removeCustomMcp(it) },
            onClose = onClose,
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
