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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val viewModel: ZyraxonViewModel by viewModels()
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
                    ZyraxonApp(viewModel = viewModel)
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

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }
}
