// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant

import ai.zyraxon.assistant.i18n.Loc
import ai.zyraxon.assistant.i18n.languageCode
import ai.zyraxon.assistant.i18n.savedLanguage
import ai.zyraxon.assistant.ui.ZyraxonApp
import ai.zyraxon.assistant.ui.theme.ZyraxonTheme
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // One assistant for the whole process (see ZyraxonViewModel.shared), so the
    // floating eye keeps working after this screen is closed.
    private val viewModel: ZyraxonViewModel by lazy { ZyraxonViewModel.shared(application) }
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.updateMicPermission(true)
            // The mic is meant to stay open, so listening starts the moment the
            // user allows it rather than waiting for another tap.
            viewModel.startAlwaysOnListening()
        }
    }

    /**
     * Lets the `voice_change` tool reach the voice manager. The ViewModel is
     * private to this activity, so tools come through here.
     */
    fun applyVoice(gender: String?, rate: Float?, pitch: Float?) {
        viewModel.applyVoice(gender, rate, pitch)
    }

    /**
     * Lets the `live_voice` tool open a real live session. It switches to a
     * realtime model, which is what actually starts speech-to-speech, and
     * reports whether a session opened so the tool can tell the truth.
     */
    fun enableLiveVoice(): Boolean = viewModel.enableLiveVoice()

    /** Lets the `live_voice` tool close the session again. */
    fun stopLiveVoice() = viewModel.stopLiveVoice()

    /** Lets the `on_device_model` tool reach the local inference engine. */
    fun onDeviceEngine(): ai.zyraxon.assistant.ai.OnDeviceModel = viewModel.onDeviceModel

    /** Raises the system Allow dialog for any permission the panel asks for. */
    fun requestPermission(permission: String) {
        if (permission.isBlank()) return
        requestPermissionLauncher.launch(arrayOf(permission))
    }

    /**
     * Raises one Allow dialog for a whole group (the three Android 13+ media
     * types, for example), so the user presses Allow once per row, not per type.
     */
    fun requestPermissions(permissions: List<String>) {
        if (permissions.isEmpty()) return
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    /** Opens a settings page the app cannot toggle itself (overlay, battery, Play Protect). */
    fun openSpecialSetting(page: String) {
        // Android 13+ greys out Accessibility for an app installed from a file. The
        // app re-installs itself through the system installer as a store would; after
        // that one Update tap the setting is open like for any other app.
        if (page == "accessibility" && ai.zyraxon.assistant.permission.SelfInstall.restricted(this)) {
            if (ai.zyraxon.assistant.shell.SystemShell.available()) {
                val pkg = packageName
                val svc = "$pkg/ai.zyraxon.assistant.AccessibilityBridge"
                val cmd = "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow && " +
                    "settings put secure enabled_accessibility_services $svc && " +
                    "settings put secure accessibility_enabled 1"
                ai.zyraxon.assistant.shell.SystemShell.run(this, cmd)
                android.widget.Toast.makeText(this, Loc.t("অ্যাক্সেসিবিলিটি সার্ভিস চালু করা হয়েছে"), android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val info = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(info) }
            android.widget.Toast.makeText(
                this,
                Loc.t("উপরে ডানদিকের তিনটি ডটে (⋮) ট্যাপ করে 'Allow restricted settings' অনুমোদন করুন, তারপর অ্যাক্সেসিবিলিটি চালু করুন"),
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        if (page == "accessibility" && android.os.Build.VERSION.SDK_INT >= 34) {
            val cn = android.content.ComponentName(this, ai.zyraxon.assistant.AccessibilityBridge::class.java)
            val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra("android.provider.extra.COMPONENT_NAME", cn.flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(direct) }.isSuccess) return
        }
        val intent = when (page) {
            "overlay" -> Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            "accessibility" -> Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "security" -> Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
            "battery" -> Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "play_protect" -> Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:com.android.vending")
            )
            else -> Intent(android.provider.Settings.ACTION_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    companion object {
        @JvmField
        var instance: MainActivity? = null

        /**
         * Emits native events from AccessibilityBridge or CallWatcher directly into the app state.
         */
        @JvmStatic
        fun emit(kind: String, state: String, payload: String?) {
            instance?.let { act ->
                act.runOnUiThread {
                    when (kind) {
                        "screen" -> {
                            val parts = (payload ?: "").split("\u0001")
                            val pkg = parts.getOrNull(0) ?: ""
                            val label = parts.getOrNull(1) ?: pkg
                            act.viewModel.updateForegroundApp(pkg, label)
                        }
                        "native" -> {
                            if (state == "accessibility") {
                                act.viewModel.updateAccessibilityStatus(true)
                            }
                        }
                        "call" -> act.viewModel.onCallEvent(state ?: "", payload.orEmpty())
                        "shot" -> {
                            // Screen frames arrive here from the accessibility
                            // service. Until now nothing consumed them, which is
                            // why the assistant could not actually "see".
                            if (state == "bg" || state == "ok") {
                                act.viewModel.onScreenFrame(payload.orEmpty())
                            } else if (state == "text") {
                                act.viewModel.onScreenText(payload.orEmpty())
                            }
                        }
                    }
                }
            }
        }

        @JvmStatic
        fun getNativeGeminiKey(): String = System.getenv("GEMINI_API_KEY").orEmpty().trim()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        enableEdgeToEdge()

        // Request audio & notifications permissions if needed
        checkAndRequestPermissions()

        // The string tables ship as assets, so they are read once here and the
        // last language the user picked is restored before the first frame.
        val available = Loc.loadLanguages(this)
        val initial = savedLanguage(this)?.takeIf { code -> available.any { it.code == code } }
            ?: Loc.systemDefault(available)
        Loc.load(this, initial)
        languageCode.value = initial

        // Start background keepalive
        KeepAliveService.start(this)

        // The eye: always on screen, even after the app is swiped away. The
        // overlay permission is asked for once; after that it just starts.
        val eyePrefs = getSharedPreferences("zyraxon_eye", MODE_PRIVATE)
        if (ai.zyraxon.assistant.voice.WakeWord.enabled(this) && !viewModel.overlayEnabled.value) {
            OverlayChatService.start(this)
        }
        if (viewModel.overlayEnabled.value) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                OverlayChatService.start(this)
            } else if (!eyePrefs.getBoolean("asked", false)) {
                eyePrefs.edit().putBoolean("asked", true).apply()
                openSpecialSetting("overlay")
            }
        }

        // Initialize CallWatcher
        if (CallWatcher.permissionsOk(this)) {
            CallWatcher.refresh(this)
        }

        // Set high-performance native Jetpack Compose UI
        setContent {
            // Reading the language state here and keying the tree on it means a
            // language change rebuilds every screen with the new table, which a
            // plain function call inside a `remember { }` could not do.
            val language by languageCode
            key(language) {
                ZyraxonTheme {
                    var booting by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
                    // The Activity's context, taken from the composition: inside a
                    // composable lambda `this` is the composition scope, not the
                    // Activity, so the preferences must be read from LocalContext.
                    val appContext = androidx.compose.ui.platform.LocalContext.current
                    // The one-time warning, shown on the first launch after install
                    // and never again. It comes before everything, including the key
                    // prompt, because it is the thing a new user must read.
                    val firstRunPrefs = remember {
                        appContext.getSharedPreferences(
                            ai.zyraxon.assistant.ui.components.FirstRunNotice.PREFS,
                            android.content.Context.MODE_PRIVATE
                        )
                    }
                    var showNotice by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            !firstRunPrefs.getBoolean(
                                ai.zyraxon.assistant.ui.components.FirstRunNotice.KEY_SHOWN, false
                            )
                        )
                    }
                    // Asked once, on the first launch that has no key at all, and
                    // closable: a user who closes it without a key keeps the app on
                    // the on-device model and never sees the prompt again.
                    var needKey by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            viewModel.aiEngine.getApiKey("zyraxon_apex").isBlank() &&
                                !firstRunPrefs.getBoolean("key_prompt_dismissed", false)
                        )
                    }
                    androidx.compose.foundation.layout.Box {
                        ZyraxonApp(viewModel = viewModel)
                        if (booting) ai.zyraxon.assistant.ui.components.BootScreen { booting = false }
                        if (!booting && showNotice) {
                            ai.zyraxon.assistant.ui.components.FirstRunNoticeScreen {
                                showNotice = false
                            }
                        }
                        if (!booting && !showNotice && needKey) {
                            ai.zyraxon.assistant.ui.components.ApexKeyPrompt(viewModel) {
                                needKey = false
                                firstRunPrefs.edit().putBoolean("key_prompt_dismissed", true).apply()
                            }
                        }
                        // The lock sits above everything, including the key prompt,
                        // so nothing behind it is readable or tappable while it is
                        // up.
                        val locked by viewModel.appLocked.collectAsState()
                        if (!booting && locked) {
                            ai.zyraxon.assistant.ui.components.AppLockScreen(viewModel)
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        } else {
            viewModel.updateMicPermission(true)
            viewModel.startAlwaysOnListening()
        }
    }

    /**
     * The user may have left for a browser (an MCP consent page) and come back
     * without finishing. Clearing that pending flow here is what frees the
     * "কানেক্ট হচ্ছে…" card, so the Connect button comes back instead of the
     * screen waiting forever.
     */
    override fun onResume() {
        super.onResume()
        // Back from the overlay-permission screen: start the eye now.
        if (viewModel.overlayEnabled.value && android.provider.Settings.canDrawOverlays(this)) {
            OverlayChatService.start(this)
        }
        // The lock covers the app again each time it comes back to the front, so
        // handing the phone over mid-session does not leave the conversation and
        // the tools behind it open. unlockApp is what clears it, and only the
        // system credential screen can lead there.
        viewModel.onAppReturnedToForeground()
        // Prepare the cloud conversation in the background while the app is open,
        // so the user's first message streams immediately instead of waiting for
        // a cold start. Idempotent, and silent when there is no key or network.
        viewModel.prewarmCloud()
        // Fold in every conversation the account already has — including the ones
        // written in the ZYRAXON web app — so the sidebar shows the full history
        // from the moment the app opens, and any of them can be resumed.
        viewModel.syncCloudConversations()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    /**
     * The cover goes up when the app comes to the front, and only then.
     *
     * It used to go up in [onStop], on the way out. That is the wrong moment: an
     * agent that opens a browser, a dialler or any other app stops this activity
     * too, so the cover was raised behind whatever the agent was working in — and
     * since the old cover asked for the device credential, Android had to bring a
     * system screen forward to ask for it. That is what dragged ZYRAXON back on
     * top of the app the agent had just opened, every time. Locking here, on the
     * way in, means a trip out never raises the cover, and a return raises it
     * exactly once, over the app's own screen.
     *
     * A configuration change is not leaving the app, so it does not lock.
     */
    override fun onStart() {
        super.onStart()
        if (!isChangingConfigurations) viewModel.lockApp()
    }
}
