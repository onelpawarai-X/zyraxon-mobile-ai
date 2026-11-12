// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.mcp.McpAuth
import ai.zyraxon.assistant.model.AppTab
import ai.zyraxon.assistant.ui.components.AnimatedBackground
import ai.zyraxon.assistant.ui.components.BloodVeins
import ai.zyraxon.assistant.ui.components.HorrorVignette
import ai.zyraxon.assistant.ui.components.McpScreen
import ai.zyraxon.assistant.ui.components.Scanlines
import ai.zyraxon.assistant.ui.components.SettingsSheet
import ai.zyraxon.assistant.ui.components.TerminalScreen
import ai.zyraxon.assistant.ui.components.ZyraxonAura
import ai.zyraxon.assistant.ui.shell.ChatPanel
import ai.zyraxon.assistant.ui.shell.CodeWorkspace
import ai.zyraxon.assistant.ui.shell.Composer
import ai.zyraxon.assistant.ui.shell.PreviewPanel
import ai.zyraxon.assistant.ui.shell.Sidebar
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.update.UpdateState
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ZyraxonApp(viewModel: ZyraxonViewModel) {
    val context = LocalContext.current
    val activeTab by viewModel.activeTab.collectAsState()
    // A site the agent just published is shown in the app's own preview. Without
    // this the preview would only load if the user happened to be on that tab, so
    // a freshly built page would appear to do nothing.
    val publishedSite by ai.zyraxon.assistant.tools.PreviewBridge.publishedSite.collectAsState()
    LaunchedEffect(publishedSite) {
        if (publishedSite != null) {
            // Keep user on Chat tab so published sites do not hijack the screen
            ai.zyraxon.assistant.tools.PreviewBridge.takePublishedSite()
        }
    }
    // The chat list's scroll state lives here, above the tab switch, so leaving
    // the Chat tab and coming back does not rebuild it from the top. Held per
    // conversation so each chat keeps its own place.
    val chatScrolls = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val activeChatId by viewModel.activeConversationId.collectAsState()
    val chatListState = chatScrolls.getOrPut(activeChatId) { androidx.compose.foundation.lazy.LazyListState() }

    val themeMode by viewModel.themeMode.collectAsState()
    // The theme is Compose state read by every colour in the app.
    SideEffect { Glass.mode = themeMode }

    val bgMode by viewModel.bgMode.collectAsState()
    val auraStyle by viewModel.auraStyle.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val alwaysListening by viewModel.alwaysListening.collectAsState()
    val auraHue by viewModel.auraHue.collectAsState()
    val auraSize by viewModel.auraSize.collectAsState()
    val auraGlow by viewModel.auraGlow.collectAsState()
    val auraSpeed by viewModel.auraSpeed.collectAsState()
    val cloudStage by viewModel.cloudStage.collectAsState()
    val cloudNotice by viewModel.cloudNotice.collectAsState()
    val terminalLines by viewModel.terminalLines.collectAsState()
    val terminalBusy by viewModel.terminalBusy.collectAsState()
    val terminalCwd by viewModel.terminalCwd.collectAsState()
    val terminalSessions by viewModel.terminalSessions.collectAsState()
    val activeTerminalId by viewModel.activeTerminalId.collectAsState()
    val sidebarOpen by viewModel.sidebarOpen.collectAsState()
    val generating by viewModel.isGenerating.collectAsState()
    val mode by viewModel.activeMode.collectAsState()
    var showSettingsSheet by remember { mutableStateOf(false) }

    val updateState by viewModel.updateState.collectAsState()
    val updateDismissed by viewModel.updateBannerDismissed.collectAsState()
    LaunchedEffect(Unit) { viewModel.checkForUpdates(silent = true) }
    val showUpdateBanner = !updateDismissed && (
        updateState is UpdateState.Available || updateState is UpdateState.Downloading ||
            updateState is UpdateState.Downloaded || updateState is UpdateState.NeedsPermission)

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Glass.screen, Glass.screen2)))) {
        // Background: the living ember scene only in the horror look; the other two are clean.
        if (Glass.isHorror) {
            AnimatedBackground(mode = bgMode, audioLevel = audioLevel, isSpeaking = isSpeaking, modifier = Modifier.fillMaxSize())
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            TopBar(
                title = "ZYRAXON", modeLabel = mode.title, busy = generating,
                onMenu = { viewModel.setSidebarOpen(!sidebarOpen) },
                onSettings = { showSettingsSheet = true }
            )
            if (showUpdateBanner) UpdateBanner(
                state = updateState, onInstall = { viewModel.installUpdate() }, onDownload = { viewModel.downloadUpdate() },
                onOpenPermission = { viewModel.openInstallPermission() }, onDismiss = { viewModel.dismissUpdateBanner() }
            )

            // The middle: the person (or aura) always fills it; a panel opens over it as glass.
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Light and dark never show the eye-style auras or blood red; the
                    // horror look keeps whatever the user picked. Under other panels the
                    // aura stays alive (voice and mouth keep moving) but invisible, so
                    // the chat is a clean surface.
                    val calm = !Glass.isHorror
                    val shownStyle = if (calm && (auraStyle == "orb" || auraStyle == "swarm" || auraStyle == "crown" ||
                            auraStyle !in ai.zyraxon.assistant.ui.components.AuraStyleIds)) "twin" else auraStyle
                    val shownHue = if (calm && auraHue == 325) 255 else auraHue
                    Box(
                        Modifier.fillMaxSize().graphicsLayer { alpha = if (activeTab == AppTab.HOME) 1f else 0f },
                        // Without this the aura's 240.dp box is placed at the
                        // default top-left of its fill, so the orb and the eye
                        // styles sat in the corner instead of the middle.
                        contentAlignment = Alignment.Center
                    ) {
                        ZyraxonAura(
                            style = shownStyle, hue = shownHue, scaleFactor = auraSize, glow = auraGlow, speed = auraSpeed,
                            isListening = isListening, isSpeaking = isSpeaking, audioLevel = audioLevel,
                            onClick = { if (activeTab == AppTab.HOME) viewModel.toggleListening() }
                        )
                    }
                    if (activeTab == AppTab.HOME) {
                        Text(
                            when {
                                // A cloud turn spends its first seconds waking a
                                // sandbox; saying so is the difference between a
                                // wait and a screen that looks broken.
                                cloudNotice.isNotBlank() -> cloudNotice
                                cloudStage.isNotBlank() -> cloudStage
                                isSpeaking -> t("বলছে… থামাতে ট্যাপ করো")
                                !alwaysListening -> t("মাইক বন্ধ — ট্যাপ করো")
                                isListening -> t("শুনছি…")
                                else -> t("মাইক চালু — বন্ধ করতে ট্যাপ করো")
                            },
                            color = Glass.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).clip(RoundedCornerShape(14.dp))
                                .background(Glass.fill(0.6f)).border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                        Column(
                            Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SideBtn(if (alwaysListening) AppIcons.mic else AppIcons.micOff, t("মাইক"), Modifier.testTag("aura_mic")) { viewModel.toggleListening() }
                            SideBtn(AppIcons.chat, t("চ্যাট"), Modifier.testTag("aura_chat")) { viewModel.setTab(AppTab.CHAT) }
                            SideBtn(AppIcons.refresh, t("রিফ্রেশ"), Modifier.testTag("cloud_refresh")) { viewModel.refreshCloud() }
                        }
                    }
                }
                when (activeTab) {
                    AppTab.CHAT -> ChatPanel(viewModel, chatListState, Modifier.fillMaxSize().padding(vertical = 4.dp))
                    AppTab.CODE -> CodeWorkspace(viewModel, Modifier.fillMaxSize().padding(vertical = 4.dp))
                    AppTab.PREVIEW -> PreviewPanel(viewModel, Modifier.fillMaxSize().padding(vertical = 4.dp))
                    AppTab.MCP -> Box(Modifier.fillMaxSize().padding(vertical = 4.dp).clip(RoundedCornerShape(22.dp))
                        .background(Glass.fill(0.82f)).border(1.dp, Glass.borderSoft, RoundedCornerShape(22.dp))) {
                        McpTabContent(viewModel, 0.3f) { viewModel.setTab(AppTab.HOME) }
                    }
                    AppTab.TERMINAL -> Box(Modifier.fillMaxSize().padding(vertical = 4.dp).clip(RoundedCornerShape(22.dp))
                        .background(Glass.screen).border(1.dp, Glass.borderSoft, RoundedCornerShape(22.dp))) {
                        TerminalScreen(
                            sessions = terminalSessions, activeId = activeTerminalId, lines = terminalLines, cwd = terminalCwd, busy = terminalBusy,
                            onRun = { viewModel.runTerminal(it) },
                            onInterrupt = { viewModel.interruptTerminal() }, onClear = { viewModel.clearTerminal() },
                            onNewSession = { viewModel.newTerminalSession() }, onSelectSession = { viewModel.selectTerminalSession(it) },
                            onCloseSession = { viewModel.closeTerminalSession(it) }, onClose = { viewModel.setTab(AppTab.HOME) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> Unit
                }
            }

            if (activeTab == AppTab.CHAT) {
                Composer(viewModel, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            BottomNav(activeTab) { tab -> viewModel.setTab(tab) }
        }

        // Horror only: a few cheap overlays (the heavy ones were removed for speed).
        if (Glass.isHorror) {
            BloodVeins(Modifier.fillMaxSize(), seed = 5)
            Scanlines(Modifier.fillMaxSize(), lineAlpha = 0.05f)
            HorrorVignette(Modifier.fillMaxSize(), intensity = 1.0f)
        }

        ai.zyraxon.assistant.present.PresentationOverlay(viewModel)
        if (sidebarOpen) Sidebar(viewModel)

        if (showSettingsSheet) {
            Box(Modifier.fillMaxSize().background(Glass.screen.copy(alpha = 0.85f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { showSettingsSheet = false })
            SettingsSheet(viewModel = viewModel, onDismiss = { showSettingsSheet = false })
        }
    }
}

@Composable
private fun TopBar(title: String, modeLabel: String, busy: Boolean, onMenu: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundBtn(AppIcons.menu, t("মেনু"), "menu_button") { Glass.vibrate(ctx, 12); onMenu() }
        Spacer(Modifier.width(10.dp))
        Text(title, color = Glass.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            modeLabel, color = Glass.pink, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Glass.pinkFill.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 3.dp)
        )
        Spacer(Modifier.weight(1f))
        RoundBtn(AppIcons.settings, t("সেটিংস"), "settings_button") { Glass.vibrate(ctx, 14); onSettings() }
    }
}

@Composable
private fun RoundBtn(icon: ImageVector?, desc: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Glass.rowFill(0.4f)).border(1.dp, Glass.borderSoft, CircleShape)
            .clickable { onClick() }.testTag(tag),
        contentAlignment = Alignment.Center
    ) { AppIcon(icon, desc, size = 19.dp, tint = Glass.textPrimary) }
}

@Composable
private fun SideBtn(icon: ImageVector?, desc: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(Glass.fill(0.7f))
            .border(1.dp, Glass.borderSoft, RoundedCornerShape(23.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { AppIcon(icon, desc, size = 22.dp, tint = Glass.textPrimary) }
}

/** Aura view, then Chat, Terminal, Code, Preview, MCP. */
@Composable
private fun BottomNav(active: AppTab, onSelect: (AppTab) -> Unit) {
    val items = listOf(
        Triple(AppTab.HOME, AppIcons.diamond, t("ওরা")),
        Triple(AppTab.CHAT, AppIcons.chat, t("চ্যাট")), Triple(AppTab.TERMINAL, AppIcons.terminal, t("টার্মিনাল")), Triple(AppTab.CODE, AppIcons.code, t("কোড")),
        Triple(AppTab.PREVIEW, AppIcons.publicGlobe, t("প্রিভিউ")), Triple(AppTab.MCP, AppIcons.hub, "MCP")
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(22.dp))
            .background(Glass.sheetFill(0.4f)).border(1.dp, Glass.borderSoft, RoundedCornerShape(22.dp)).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (tab, icon, label) ->
            val sel = tab == active
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(17.dp))
                    .background(if (sel) Glass.pinkFill.copy(alpha = if (Glass.isLight) 0.16f else 0.32f) else Color.Transparent)
                    .border(1.dp, if (sel) Glass.pink else Color.Transparent, RoundedCornerShape(17.dp))
                    .clickable { onSelect(tab) }.padding(vertical = 7.dp).testTag("nav_" + tab.name.lowercase()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AppIcon(icon, label, size = 19.dp, tint = if (sel) Glass.textPrimary else Glass.textMuted)
                Text(label, fontSize = 10.sp, color = if (sel) Glass.textPrimary else Glass.textMuted, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
            }
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
                    is UpdateState.Available -> t("নতুন আপডেট পাওয়া গেছে — {0}", state.release.tag)
                    is UpdateState.Downloading -> t("আপডেট ডাউনলোড হচ্ছে... {0}%", (state.progress * 100).toInt())
                    is UpdateState.Downloaded -> t("ডাউনলোড শেষ — ইনস্টল করুন")
                    is UpdateState.NeedsPermission -> t("ইনস্টল করতে অনুমতি দরকার")
                    else -> t("নতুন আপডেট পাওয়া গেছে")
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
                Icon(Icons.Rounded.Download, contentDescription = null, tint = Glass.greenFill, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(t("ডাউনলোড"), color = Glass.greenFill, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            is UpdateState.Downloaded -> TextButton(
                onClick = onInstall,
                modifier = Modifier.testTag("update_banner_install")
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = Glass.greenFill, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(t("ইনস্টল"), color = Glass.greenFill, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            is UpdateState.NeedsPermission -> TextButton(
                onClick = onOpenPermission,
                modifier = Modifier.testTag("update_banner_install")
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = Glass.greenFill, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
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
 * The MCP tab: every server, what it can do, and a Connect button that opens the
 * browser for the OAuth ones. A connected server's tools are already inside the
 * assistant, so there is nothing else to switch on here.
 */
@Composable
fun McpTabContent(viewModel: ZyraxonViewModel, panelAlpha: Float, onClose: () -> Unit = {}) {
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
                AppIcon(
                    AppIcons.close, t("বন্ধ করো"), size = 16.dp, tint = Glass.textMuted,
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
                    t("টোকেন বানাও"),
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
                Icon(Icons.Rounded.Check, contentDescription = null, tint = Glass.pinkFill, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(t("কানেক্ট"), color = Glass.pinkFill, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = Glass.textMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(t("বাতিল"), color = Glass.textMuted)
            }
        }
    )
}
