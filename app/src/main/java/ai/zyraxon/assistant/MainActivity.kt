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
                        "call" -> {
                            if (state == "ringing") {
                                act.viewModel.sendMessage("ইনকামিং কল এসেছে: $payload")
                            }
                        }
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
                    // Asked once, on the first launch that has no key at all. A user
                    // who already has one never sees it again, which is what "set it
 // once and it stays connected" means in practice.
                    var needKey by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(
                            viewModel.aiEngine.getApiKey("zyraxon_apex").isBlank()
                        )
                    }
                    androidx.compose.foundation.layout.Box {
                        ZyraxonApp(viewModel = viewModel)
                        if (booting) ai.zyraxon.assistant.ui.components.BootScreen { booting = false }
                        if (!booting && needKey) {
                            ai.zyraxon.assistant.ui.components.ApexKeyPrompt(viewModel) {
                                needKey = false
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
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    /**
     * Locking here rather than in [onResume] is deliberate. The device-credential
     * screen is a separate activity, so asking for it stops this one; locking on
     * the way out means the cover is already up when the credential is accepted
     * or cancelled, and the answer alone decides whether it lifts. Locking in
     * [onResume] instead would put the cover back over a user who had just
     * unlocked, and they could never get past it.
     *
     * A configuration change is not leaving the app, so it does not lock.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.lockApp()
    }
}
