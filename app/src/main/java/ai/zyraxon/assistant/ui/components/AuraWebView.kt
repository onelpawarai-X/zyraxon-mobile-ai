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
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
        }
    }

    // Load once. Reloading on every state change would restart the animation and
    // make the aura visibly stutter.
    DisposableEffect(Unit) {
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onTap() = onTap()
        }, "ZyraxonAura")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(AURA_BRIDGE_JS, null)
            }
        }
        webView.loadUrl("file:///android_asset/aura/aura.html")
        onDispose { webView.destroy() }
    }

    // Push the user's look-and-feel into the page. The four CSS variables are
    // what aura.html reads, so the design file itself stays untouched.
    DisposableEffect(hue, size, glow, speed) {
        webView.evaluateJavascript(
            "window.ZyraxonAuraLook && window.ZyraxonAuraLook($hue, $size, $glow, $speed)",
            null
        )
        onDispose { }
    }

    // Push live state into the running page. `applyState` is defined by the
    // injected script below, so a missing page just ignores the call.
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
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        )
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
  };

  window.ZyraxonAuraState = function (listening, speaking, level) {
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
