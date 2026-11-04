package ai.zyraxon.assistant.voice

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * The line between the assistant's voice and the 3D avatar on the home screen.
 *
 * The avatar lives in a WebView ([ai.zyraxon.assistant.ui.components.AvatarView]).
 * Whenever a piece of speech starts, the voice code tells this object the exact
 * text and, when it knows it, how long it lasts; the page turns the text into
 * mouth shapes (Bangla and English alike) and times them against the real audio.
 * A speech engine that reports word positions ([boundary]) or a loudness level
 * ([level]) keeps the lips locked to the sound. Nothing here can fail loudly:
 * with no avatar on screen every call is a no-op.
 */
object AvatarBridge {
    private var view: WeakReference<WebView>? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(webView: WebView) { view = WeakReference(webView) }
    fun detach(webView: WebView) { if (view?.get() === webView) view = null }

    private fun js(code: String) {
        val v = view?.get() ?: return
        main.post { try { v.evaluateJavascript("try{$code}catch(e){}", null) } catch (_: Throwable) {} }
    }

    /** A piece of speech is starting. [durationMs] <= 0 means "estimate from the text". */
    fun speak(text: String, durationMs: Long = 0L) {
        if (text.isBlank()) return
        val d = if (durationMs > 0) durationMs else estimateMs(text)
        js("ZC.speakExternal(${JSONObject.quote(text)},$d)")
    }

    /** The engine reached character [index] of the current piece. */
    fun boundary(index: Int) = js("ZC.boundary($index)")

    /** Current loudness of the voice, 0..1. Silence closes the mouth. */
    fun level(level: Float) = js("ZC.level(${level.coerceIn(0f, 1f)})")

    /** Voice with no text (live voice): the mouth follows the loudness alone. */
    fun talk(level: Float) = js("ZC.talkLevel(${level.coerceIn(0f, 1f)})")

    /** Speech is over; the arms settle and the mouth closes. */
    fun end() = js("ZC.end()")

    private fun estimateMs(text: String): Long = (text.length * 62L).coerceIn(500L, 30_000L)
}
