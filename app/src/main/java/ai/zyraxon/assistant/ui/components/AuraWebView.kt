package ai.zyraxon.assistant.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The pink aura, rendered from `assets/aura/aura.html` exactly as designed.
 *
 * The HTML is kept byte-for-byte in assets and never rewritten here. A WebView is
 * used because the design depends on `conic-gradient`, `mask`, `blur()` and
 * `mix-blend-mode`, which Compose cannot draw; the browser engine draws it
 * perfectly. Only two things are injected at runtime, and neither touches the
 * aura itself:
 *
 *  - the page background is made transparent so the video behind it shows through
 *  - a tap on the aura is reported back, because the aura *is* the microphone button
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AuraWebView(
    isListening: Boolean,
    isSpeaking: Boolean,
    audioLevel: Float,
    hue: Int,
    size: Float,
    glow: Float,
    speed: Float,
    auraStyle: String,
    auraShader: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // The WebView is remembered at the *application* scope, not here. Compose
    // disposes this composable every time the user leaves the chat tab, and a
    // WebView destroyed on the way out cannot be brought back — that is exactly
    // why the aura used to disappear and never return. Keeping the one instance
    // alive and re-attaching it means the tab switch costs nothing.
    val webView = remember { AuraWebViewRegistry.webView(context) }

    // One JS call that restores the whole look: hue/size/glow/speed, the CSS
    // style and the shader mode. Used on load, on every settings change and on
    // re-attach, so "what the aura should look like" lives in one place.
    val pushLook = {
        "window.ZyraxonAuraLook && window.ZyraxonAuraLook($hue, $size, $glow, $speed);" +
            "window.ZyraxonAuraStyle && window.ZyraxonAuraStyle('$auraStyle');" +
            "window.ZyraxonAuraShader && window.ZyraxonAuraShader('$auraShader')"
    }

    // The bridge has to be re-armed whenever the page is (re)loaded, and the
    // current look re-pushed, because a fresh page starts at the defaults.
    DisposableEffect(webView) {
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onTap() = onTap()
        }, "ZyraxonAura")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(AURA_BRIDGE_JS, null)
                view.evaluateJavascript(pushLook(), null)
            }
        }
        if (webView.url == null) {
            // First time only: the page has never been loaded.
            webView.loadUrl("file:///android_asset/aura/aura.html")
        } else {
            // Returning to the tab: the page is already live, so only re-push
            // state. Reloading would restart the animation and stutter.
            webView.evaluateJavascript(pushLook(), null)
        }
        onDispose {
            // Detach, never destroy: the next composition re-attaches this view.
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        }
    }

    // Push the user's look-and-feel into the page. The four CSS variables are
    // what aura.html reads, so the design file itself stays untouched.
    DisposableEffect(hue, size, glow, speed, auraStyle, auraShader) {
        webView.evaluateJavascript(pushLook(), null)
        onDispose { }
    }

    // Push live state into the running page. `ZyraxonAuraState` is defined by
    // the injected script, so a page that has not loaded yet just ignores it.
    DisposableEffect(isListening, isSpeaking, audioLevel) {
        val level = audioLevel.coerceIn(0f, 1f)
        webView.evaluateJavascript(
            "window.ZyraxonAuraState && window.ZyraxonAuraState($isListening, $isSpeaking, $level)",
            null
        )
        onDispose { }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { webView },
            update = { view ->
                // Re-attached after a tab switch: the WebView came back, but the
                // page inside it was never torn down, so re-push and it is live.
                view.post { view.evaluateJavascript(pushLook(), null) }
            },
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        )
    }
}

/**
 * Holds the single aura WebView for the whole process.
 *
 * A WebView is expensive and cannot survive being destroyed, so one instance is
 * created once and handed back on every recomposition. The engine inside the
 * page keeps animating while the view is detached, so returning to the chat tab
 * shows the aura mid-motion instead of a restart.
 */
internal object AuraWebViewRegistry {
    private var instance: WebView? = null

    fun webView(context: android.content.Context): WebView {
        instance?.let { return it }
        val created = WebView(context.applicationContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
        }
        instance = created
        return created
    }

    /** Only for tests: drops the cached view so a fresh one is built. */
    fun reset() {
        instance = null
    }
}

/**
 * Injected at page load. Kept separate from [AuraWebView] so the file that holds
 * the design stays untouched.
 */
internal const val AURA_BRIDGE_JS = """
(function () {
  if (window.ZyraxonAuraState) return;
  var stage = document.querySelector('.aura-stage');
  var orb = document.querySelector('.orb');

  // The video sits behind the WebView, so the page's own backdrop must go.
  var s = document.createElement('style');
  s.textContent = 'html,body{background:transparent !important;}';
  document.head.appendChild(s);

  document.body.addEventListener('click', function () {
    if (window.ZyraxonAura && window.ZyraxonAura.onTap) window.ZyraxonAura.onTap();
  });

  // hue/size/glow/speed are written straight onto :root; aura.html does the rest.
  window.ZyraxonAuraLook = function (hue, size, glow, speed) {
    var r = document.documentElement;
    if (hue !== undefined && hue !== null) r.style.setProperty('--aura-hue', String(hue));
    if (size) r.style.setProperty('--aura-size', String(size));
    if (glow) r.style.setProperty('--aura-glow', String(glow));
    if (speed) r.style.setProperty('--aura-speed', String(speed));
    if (window.ZyraxonShaders) window.ZyraxonShaders.look(hue, glow, speed);
  };

  // Which of the designed looks is showing. The attribute is what the CSS above
  // keys off, so switching is a single attribute write with no reload.
  window.ZyraxonAuraStyle = function (style) {
    if (!style) return;
    document.documentElement.setAttribute('data-aura', String(style));
  };

  // The shader mode. "none" (or empty) restores the CSS aura; the WebGL engine
  // in aura.html removes the attribute itself if a shader cannot start, so the
  // aura is never left blank.
  window.ZyraxonAuraShader = function (shader) {
    if (!window.ZyraxonShaders) return;
    window.ZyraxonShaders.resize();
    window.ZyraxonShaders.set(shader ? String(shader) : 'none');
  };

  window.ZyraxonAuraState = function (listening, speaking, level) {
    if (window.ZyraxonShaders) window.ZyraxonShaders.state(listening, speaking, level);
    if (!stage || !orb) return;
    // The stage transform is owned by CSS (it carries the centring translate),
    // so the live pulse is applied to the orb's own scale instead of fighting it.
    var lv = level || 0;
    var boost = listening ? 1 + lv * 0.16 : (speaking ? 1 + lv * 0.11 : 1);
    orb.style.transform = 'scale(' + boost.toFixed(3) + ')';
    orb.style.filter = (listening || speaking)
      ? 'brightness(' + (1 + lv * 0.45).toFixed(3) + ')'
      : 'brightness(1)';
  };
})();
"""
