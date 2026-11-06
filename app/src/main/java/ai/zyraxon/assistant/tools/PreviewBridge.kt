package ai.zyraxon.assistant.tools

import ai.zyraxon.assistant.core.safe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * The app's one real browser. The Preview panel shows it; the agent drives it
 * through the `preview` tool. It is a singleton so it keeps running (and keeps
 * its page, console and history) while the panel is not on screen, which is
 * what lets the agent work on a page in the background.
 */
object PreviewBridge {

    data class Problem(val kind: String, val text: String, val url: String = "")

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var webView: WebView? = null

    val url = MutableStateFlow("")
    val title = MutableStateFlow("")
    val progress = MutableStateFlow(100)
    val canBack = MutableStateFlow(false)
    val canForward = MutableStateFlow(false)
    val problems = MutableStateFlow<List<Problem>>(emptyList())
    private val consoleLines = ArrayDeque<String>()

    val problemFlow: StateFlow<List<Problem>> get() = problems

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = java.util.concurrent.CountDownLatch(1)
        var out: Result<T>? = null
        main.post { out = runCatching(block); done.countDown() }
        done.await(15, java.util.concurrent.TimeUnit.SECONDS)
        return out!!.getOrThrow()
    }

    /** The WebView, created on first use with the application context. */
    @SuppressLint("SetJavaScriptEnabled")
    fun view(context: Context): WebView = onMain {
        webView ?: WebView(context.applicationContext).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = userAgentString.replace("; wv", "")
            }
            wv.setBackgroundColor(0xFFFFFFFF.toInt())
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                    url.value = u; progress.value = 5; canBack.value = v.canGoBack(); canForward.value = v.canGoForward()
                }
                override fun onPageFinished(v: WebView, u: String) {
                    url.value = u; title.value = v.title.orEmpty(); progress.value = 100
                    canBack.value = v.canGoBack(); canForward.value = v.canGoForward()
                }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    addProblem(Problem(if (r.isForMainFrame) "load" else "resource",
                        "${e.description} (${r.url})", r.url.toString()))
                }
                override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: android.webkit.WebResourceResponse) {
                    if (e.statusCode >= 400) addProblem(Problem("http", "HTTP ${e.statusCode} ${r.url}", r.url.toString()))
                }
            }
            wv.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView, p: Int) { progress.value = p }
                override fun onReceivedTitle(v: WebView, t: String?) { title.value = t.orEmpty() }
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    val line = "[${m.messageLevel()}] ${m.message()} (${m.sourceId().orEmpty().substringAfterLast('/')}:${m.lineNumber()})"
                    synchronized(consoleLines) { consoleLines.addLast(line); while (consoleLines.size > 200) consoleLines.removeFirst() }
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) addProblem(Problem("js", line))
                    return true
                }
            }
            webView = wv
        }
    }

    private fun addProblem(p: Problem) {
        val cur = problems.value
        if (cur.any { it.text == p.text }) return
        problems.value = (cur + p).takeLast(40)
    }

    fun clearProblems() { problems.value = emptyList(); synchronized(consoleLines) { consoleLines.clear() } }

    /** `localhost:3000`, `example.com`, a path, or a search phrase → a loadable URL. */
    fun normalize(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "about:blank"
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(t) && !Regex("^[a-zA-Z0-9.-]+:\\d+").containsMatchIn(t)) return t
        if (t.startsWith("/")) return "file://$t"
        if (Regex("^(localhost|127\\.0\\.0\\.1|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$").matches(t)) return "http://$t"
        if (!t.contains(' ') && t.contains('.')) return "https://$t"
        return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
    }

    fun open(context: Context, raw: String) {
        val u = normalize(raw)
        view(context)
        onMain { webView?.loadUrl(u) }
        url.value = u
    }

    fun reload(context: Context) { view(context); onMain { webView?.reload() } }
    fun back(context: Context) { view(context); onMain { if (webView?.canGoBack() == true) webView?.goBack() } }
    fun forward(context: Context) { view(context); onMain { if (webView?.canGoForward() == true) webView?.goForward() } }
    fun stop() { onMain { webView?.stopLoading() } }

    suspend fun eval(context: Context, script: String): String {
        view(context)
        val d = CompletableDeferred<String>()
        main.post { webView?.evaluateJavascript(script) { d.complete(it ?: "null") } ?: d.complete("null") }
        return withTimeoutOrNull(10_000) { d.await() } ?: "timeout"
    }

    private fun decodeJs(s: String): String = try { JSONObject("{\"v\":$s}").safe("v", s) } catch (_: Exception) { s }

    /** A JPEG of what the browser shows right now, as a data URL, scaled to a sane size. */
    fun screenshot(context: Context, maxSide: Int = 1100): String? = onMain {
        val wv = view(context)
        if (wv.width <= 0 || wv.height <= 0) {
            // Not attached to a screen: give it a phone-sized surface so it can still render.
            val dm = context.resources.displayMetrics
            wv.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((dm.heightPixels * 0.8).toInt(), View.MeasureSpec.EXACTLY))
            wv.layout(0, 0, wv.measuredWidth, wv.measuredHeight)
        }
        val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
        wv.draw(Canvas(bmp))
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun consoleText(): String = synchronized(consoleLines) { consoleLines.joinToString("\n") }

    /** The `preview` tool. */
    suspend fun tool(context: Context, args: JSONObject): JSONObject {
        fun ok(s: String, data: Any? = null) = JSONObject().put("ok", true).put("summary", s).apply { if (data != null) put("data", data) }
        fun bad(s: String) = JSONObject().put("ok", false).put("error", s).put("summary", s)
        fun settle(ms: Long = 700) = Thread.sleep(ms)
        return when (val action = args.safe("action").lowercase()) {
            "open" -> {
                val u = args.safe("url").ifBlank { return bad("url দরকার") }
                clearProblems(); open(context, u)
                waitLoaded(); ok("খোলা হয়েছে: ${url.value}", JSONObject().put("url", url.value).put("title", title.value))
            }
            "search" -> { clearProblems(); open(context, args.safe("query").ifBlank { return bad("query দরকার") }); waitLoaded(); ok("সার্চ: ${url.value}") }
            "reload" -> { reload(context); waitLoaded(); ok("রিলোড হয়েছে") }
            "back" -> { back(context); waitLoaded(); ok("পেছনে: ${url.value}") }
            "forward" -> { forward(context); waitLoaded(); ok("সামনে: ${url.value}") }
            "close" -> { open(context, "about:blank"); ok("বন্ধ") }
            "screenshot" -> {
                val shot = screenshot(context) ?: return bad("স্ক্রিনশট নেওয়া যায়নি")
                ok("প্রিভিউ স্ক্রিনশট (${title.value.ifBlank { url.value }})").put("image_data_url", shot)
            }
            "scroll" -> {
                eval(context, "window.scrollBy(0,${args.optInt("dy", 600)});[window.scrollY,document.documentElement.scrollHeight].join('/')")
                    .let { ok("স্ক্রল: ${decodeJs(it)}") }
            }
            "click" -> {
                val sel = args.safe("selector"); val text = args.safe("text")
                val js = when {
                    sel.isNotBlank() -> "(function(){var e=document.querySelector(${JSONObject.quote(sel)});if(!e)return 'NOT_FOUND';e.scrollIntoView({block:'center'});e.click();return 'OK'})()"
                    text.isNotBlank() -> "(function(){var t=${JSONObject.quote(text)}.toLowerCase();var all=document.querySelectorAll('a,button,input,[role=button],[onclick],label,li,span,div');for(var i=0;i<all.length;i++){var e=all[i];var s=((e.innerText||e.value||'')+'').trim().toLowerCase();if(s&&s.indexOf(t)>=0&&e.children.length<4){e.scrollIntoView({block:'center'});e.click();return 'OK'}}return 'NOT_FOUND'})()"
                    args.has("x") && args.has("y") -> "(function(){var e=document.elementFromPoint(${args.optDouble("x")},${args.optDouble("y")});if(!e)return 'NOT_FOUND';e.click();return 'OK'})()"
                    else -> return bad("selector, text বা x/y দরকার")
                }
                val r = decodeJs(eval(context, js)); settle()
                if (r == "OK") ok("ক্লিক হয়েছে") else bad("ক্লিক করার উপাদান পাওয়া যায়নি")
            }
            "type" -> {
                val sel = args.safe("selector").ifBlank { "input,textarea" }
                val js = "(function(){var e=document.querySelector(${JSONObject.quote(sel)});if(!e)return 'NOT_FOUND';e.focus();e.value=${JSONObject.quote(args.safe("text"))};e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'OK'})()"
                if (decodeJs(eval(context, js)) == "OK") ok("লেখা হয়েছে") else bad("ইনপুট পাওয়া যায়নি")
            }
            "read" -> ok("পেজের লেখা", decodeJs(eval(context, "(document.body?document.body.innerText:'').slice(0,12000)")))
            "eval" -> ok("eval", decodeJs(eval(context, args.safe("script").ifBlank { return bad("script দরকার") })).take(8000))
            "console" -> ok("কনসোল", consoleText().ifBlank { "কোনো লগ নেই" })
            "problems" -> {
                val txt = problems.value.joinToString("\n") { "[${it.kind}] ${it.text}" }
                ok(if (txt.isBlank()) "কোনো সমস্যা নেই" else "${problems.value.size}টি সমস্যা", txt.ifBlank { "clean" })
            }
            else -> bad("অজানা preview action: $action")
        }
    }

    private fun waitLoaded() {
        var waited = 0
        Thread.sleep(400)
        while (progress.value < 100 && waited < 20_000) { Thread.sleep(250); waited += 250 }
        Thread.sleep(400)
    }
}
