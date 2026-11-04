package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.voice.AvatarBridge
import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader

/** One real-human avatar that can stand in the middle of the home screen. */
data class AvatarSpec(
    val id: String,        // the aura-style id stored in settings
    val key: String,       // page key: ?c=<key> in assets/avatar/index.html and models/<key>.glb
    val name: String,      // Bengali first name
    val age: Int,
    val emoji: String,
    val female: Boolean
) {
    /** "মায়া · ৩২" */
    val label: String get() = "$name · ${age.toString().map { '০' + (it - '0') }.joinToString("")}"
}

/** Every avatar, youngest first. Names follow the person's age. */
val AvatarRoster: List<AvatarSpec> = listOf(
    AvatarSpec("avatar_ruhi", "ruhi", "রুহি", 20, "👩", true),
    AvatarSpec("avatar_pori", "pori", "পরী", 22, "👱\u200D♀️", true),
    AvatarSpec("avatar_arian", "arian", "আরিয়ান", 24, "🧑", false),
    AvatarSpec("avatar_tisha", "tisha", "তিশা", 26, "🧢", true),
    AvatarSpec("avatar_maya", "maya", "মায়া", 32, "👩\u200D💼", true),
    AvatarSpec("avatar_arnab", "arnab", "অর্ণব", 35, "👨\u200D💼", false),
    AvatarSpec("avatar_nazia", "nazia", "নাজিয়া", 42, "🧕", true)
)

fun avatarSpec(id: String): AvatarSpec? = AvatarRoster.firstOrNull { it.id == id }
fun isAvatarStyle(id: String): Boolean = avatarSpec(id) != null

private fun avatarKey(id: String): String = avatarSpec(id)?.key ?: "ruhi"

/**
 * A 3D person that talks. The page (assets/avatar) renders a rigged human model
 * with lip-sync and gestures; this composable only hosts it. A transparent layer
 * above the WebView takes taps, so tapping the avatar is the microphone, exactly
 * like the aura it replaces.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun AvatarView(
    style: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val key = avatarKey(style)
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                    .build()
                WebView(ctx).apply {
                    setBackgroundColor(AColor.TRANSPARENT)
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
                    }
                    setTag(key)
                    loadUrl("https://appassets.androidplatform.net/assets/avatar/index.html?c=$key")
                    AvatarBridge.attach(this)
                }
            },
            update = { web ->
                if (web.tag != key) {
                    web.tag = key
                    web.loadUrl("https://appassets.androidplatform.net/assets/avatar/index.html?c=$key")
                }
                AvatarBridge.attach(web)
            },
            onRelease = { web ->
                AvatarBridge.detach(web)
                try { web.destroy() } catch (_: Throwable) {}
            }
        )
        // Taps go to the microphone, not into the 3D scene.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
        )
    }
}
