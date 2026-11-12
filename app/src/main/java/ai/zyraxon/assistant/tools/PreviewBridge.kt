package ai.zyraxon.assistant.tools

import ai.zyraxon.assistant.core.safe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.os.SystemClock
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

    /** A part of the page the user tapped in pick mode, described for the agent. */
    data class Picked(
        val selector: String, val tag: String, val text: String,
        val html: String, val styles: String, val url: String
    )

    /** True while the next tap on the page selects an element instead of using it. */
    val picking = MutableStateFlow(false)
    val picked = MutableStateFlow<Picked?>(null)
    @Volatile private var pickerSource: String? = null
    @Volatile private var appContext: Context? = null

    private class PickBridge {
        @android.webkit.JavascriptInterface
        fun pick(json: String) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return
            picked.value = Picked(
                o.optString("selector"), o.optString("tag"), o.optString("text"),
                o.optString("html"), o.optString("styles"), o.optString("url")
            )
            picking.value = false
        }
    }

    private fun pickerJs(): String {
        pickerSource?.let { return it }
        val ctx = appContext ?: return ""
        val js = runCatching { ctx.assets.open("web/zx-picker.js").bufferedReader().use { it.readText() } }.getOrDefault("")
        pickerSource = js
        return js
    }

    /** Turns pick mode on or off on the page that is showing. */
    fun setPicking(context: Context, on: Boolean) {
        view(context)
        picking.value = on
        if (on) picked.value = null
        main.post {
            val js = pickerJs()
            if (js.isNotEmpty()) webView?.evaluateJavascript(
                js + "\n;window.__zxPicker." + (if (on) "on" else "off") + "();", null
            )
        }
    }

    fun clearPicked() { picked.value = null }

    val problemFlow: StateFlow<List<Problem>> get() = problems

    /**
     * A site the app has just published, waiting for the UI to show it.
     *
     * A published site belongs in the app's own browser: handing it to Chrome
     * opened a second window on top of the assistant, so the page the agent had
     * just built disappeared behind an external app. The UI observes this,
     * switches to the preview panel and loads the URL there; the value is
     * cleared as soon as it is taken so a rotation does not reopen it.
     */
    val publishedSite = MutableStateFlow<String?>(null)

    fun offerPublishedSite(url: String) {
        publishedSite.value = url
    }

    fun takePublishedSite(): String? = publishedSite.value?.also { publishedSite.value = null }

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
            appContext = context.applicationContext
            wv.addJavascriptInterface(PickBridge(), "ZxPick")
            wv.setBackgroundColor(0xFFFFFFFF.toInt())
            wv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                    url.value = u; progress.value = 5; canBack.value = v.canGoBack(); canForward.value = v.canGoForward()
                }
                override fun onPageFinished(v: WebView, u: String) {
                    url.value = u; title.value = v.title.orEmpty(); progress.value = 100
                    if (picking.value) {
                        val js = pickerJs()
                        if (js.isNotEmpty()) v.evaluateJavascript(js + "\n;window.__zxPicker.on();", null)
                    }
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

    /** Bumped when something should bring the Preview tab to the front (a document was opened there). */
    val showRequest = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * Shows any file inside the Preview: Word, Excel, PowerPoint, PDF, pictures, Markdown, CSV, code,
     * and web pages. Documents become a page the WebView can draw; web files open as they are.
     */
    fun openFile(context: Context, file: java.io.File): String {
        val dir = java.io.File(context.cacheDir, "preview").apply { mkdirs() }
        val target = when (ai.zyraxon.assistant.viewer.DocViewer.kindOf(file)) {
            ai.zyraxon.assistant.viewer.DocViewer.Kind.HTML -> file
            ai.zyraxon.assistant.viewer.DocViewer.Kind.IMAGE -> java.io.File(dir, "img_${file.nameWithoutExtension}.html").also {
                it.writeText(ai.zyraxon.assistant.viewer.DocViewer.page(file.name,
                    "<img src=\"${file.toURI()}\" style=\"display:block;max-width:100%;margin:10px auto;background:#fff\">", "img"))
            }
            ai.zyraxon.assistant.viewer.DocViewer.Kind.PDF -> java.io.File(dir, "pdf_${file.nameWithoutExtension}.html").also {
                it.writeText(ai.zyraxon.assistant.viewer.PdfPages.toHtml(file, java.io.File(dir, "pdf_${file.nameWithoutExtension}")))
            }
            else -> java.io.File(dir, "doc_${file.name}.html").also { it.writeText(ai.zyraxon.assistant.viewer.DocViewer.toHtml(file)) }
        }
        open(context, target.toURI().toString())
        showRequest.value = showRequest.value + 1
        return target.toURI().toString()
    }

    private val VIEWABLE = setOf("docx", "dotx", "xlsx", "xlsm", "pptx", "potx", "pdf", "csv", "tsv", "md", "markdown", "png", "jpg", "jpeg", "gif", "webp", "bmp",
        "txt", "log", "json", "xml", "yaml", "yml", "kt", "java", "js", "ts", "py", "sh", "toml", "ini", "sql")

    fun open(context: Context, raw: String) {
        // A path to a document opens in the viewer instead of being handed to the WebView as a download.
        val local = raw.trim().removePrefix("file://").takeIf { it.startsWith("/") }
        if (local != null) {
            val f = java.io.File(android.net.Uri.decode(local))
            if (f.isFile && f.extension.lowercase() in VIEWABLE) { openFile(context, f); return }
        }
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
                val q = JSONObject()
                    .put("sel", args.safe("selector")).put("text", args.safe("text"))
                if (args.has("x") && args.has("y") && args.safe("selector").isBlank() && args.safe("text").isBlank()) {
                    q.put("x", args.optDouble("x")).put("y", args.optDouble("y"))
                } else if (args.safe("selector").isBlank() && args.safe("text").isBlank()) {
                    return bad("selector, text বা x/y দরকার")
                }
                val found = runCatching { JSONObject(decodeJs(eval(context, "($FIND_JS)($q)"))) }.getOrNull()
                    ?: return bad("পেজ এখনো তৈরি নয় — একটু পরে আবার চেষ্টা করো")
                if (!found.optBoolean("ok")) {
                    val hints = found.optJSONArray("hints")?.let { arr -> (0 until arr.length()).joinToString(" | ") { arr.optString(it) } }.orEmpty()
                    return bad("ক্লিক করার উপাদান পাওয়া যায়নি। এখন পেজে যা ক্লিকযোগ্য: ${hints.ifBlank { "কিছু নেই — আগে read বা screenshot দাও" }}")
                }
                settle(250)
                // Re-read the position after the scroll settled, then press with a real touch.
                val again = runCatching { JSONObject(decodeJs(eval(context, "($FIND_JS)($q)"))) }.getOrNull() ?: found
                val dpr = again.optDouble("dpr", 1.0)
                val px = (again.optDouble("x") * dpr).toFloat()
                val py = (again.optDouble("y") * dpr).toFloat()
                val pressed = touch(px, py)
                if (!pressed) {
                    eval(context, "(function(){var e=document.elementFromPoint(${again.optDouble("x")},${again.optDouble("y")});if(e){['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t){e.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}))})}})()")
                }
                settle(900)
                ok("ক্লিক হয়েছে (${again.optString("tag")} ${again.optString("label")})")
            }
            "type" -> {
                val q = JSONObject().put("sel", args.safe("selector")).put("text", args.safe("text"))
                    .put("submit", args.optBoolean("submit", false))
                val r = decodeJs(eval(context, "($TYPE_JS)($q)"))
                if (r == "OK") { settle(500); ok("লেখা হয়েছে") } else bad("ইনপুট পাওয়া যায়নি — আগে click দিয়ে ঘরটি বেছে নাও, বা selector দাও")
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

    /** A real finger press at ([x], [y]) in the WebView's own pixels. */
    private fun touch(x: Float, y: Float): Boolean {
        val ok = onMain {
            val v = webView ?: return@onMain false
            if (v.width == 0 || v.height == 0) return@onMain false
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
            v.dispatchTouchEvent(down); down.recycle()
            true
        }
        if (!ok) return false
        Thread.sleep(70)
        return onMain {
            val v = webView ?: return@onMain false
            val t = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(t - 70, t, MotionEvent.ACTION_UP, x, y, 0)
            v.dispatchTouchEvent(up); up.recycle()
            true
        }
    }

    /** Finds an element by selector, visible text or label (shadow DOM included) and returns where it is. */
    private const val FIND_JS = """function(q){
 function deepAll(root,out){var n=root.querySelectorAll('*');for(var i=0;i<n.length;i++){out.push(n[i]);if(n[i].shadowRoot)deepAll(n[i].shadowRoot,out);}return out;}
 function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none';}
 function label(e){return ((e.innerText||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.getAttribute('alt')||'')+' '+(e.value||'')+' '+(e.getAttribute('placeholder')||'')).replace(/\s+/g,' ').trim().toLowerCase();}
 function clickable(e){var c=e;for(var i=0;i<6&&c;i++){var t=c.tagName;if(t==='A'||t==='BUTTON'||t==='INPUT'||t==='SELECT'||t==='SUMMARY'||c.getAttribute('role')==='button'||c.getAttribute('role')==='link'||c.hasAttribute('onclick')||c.getAttribute('tabindex')==='0'||getComputedStyle(c).cursor==='pointer')return c;c=c.parentElement||(c.getRootNode&&c.getRootNode().host);}return e;}
 var el=null;var all=null;
 if(q.sel){try{el=document.querySelector(q.sel)}catch(e){}
  if(!el){all=deepAll(document,[]);for(var i=0;i<all.length;i++){try{if(all[i].matches(q.sel)&&vis(all[i])){el=all[i];break}}catch(e){break}}}}
 else if(q.text){var t=q.text.toLowerCase().trim();all=deepAll(document,[]);var best=null,bs=0;
  for(var i=0;i<all.length;i++){var e=all[i];if(!vis(e))continue;var l=label(e);if(!l||l.indexOf(t)<0)continue;var r=e.getBoundingClientRect();var sc=(l===t?3:l.indexOf(t)===0?2:1)*1e6/(1+r.width*r.height/1000+l.length);if(sc>bs){bs=sc;best=e}}
  el=best?clickable(best):null;}
 else if(q.x!=null){el=document.elementFromPoint(q.x,q.y);}
 if(!el){var h=[];all=all||deepAll(document,[]);for(var i=0;i<all.length&&h.length<25;i++){var e=all[i];var t=e.tagName;if((t==='A'||t==='BUTTON'||t==='INPUT'||e.getAttribute('role')==='button')&&vis(e)){var l=label(e);if(l)h.push(l.slice(0,50));}}return JSON.stringify({ok:false,hints:h});}
 if(q.x==null)el.scrollIntoView({block:'center',inline:'center'});
 var r=el.getBoundingClientRect();
 return JSON.stringify({ok:true,x:q.x!=null?q.x:r.left+r.width/2,y:q.x!=null?q.y:r.top+r.height/2,dpr:window.devicePixelRatio||1,tag:el.tagName,label:label(el).slice(0,40)});
}"""

    /** Types into a field the way a user does: works for plain inputs, React inputs and editable divs. */
    private const val TYPE_JS = """function(q){
 function deepAll(root,out){var n=root.querySelectorAll('*');for(var i=0;i<n.length;i++){out.push(n[i]);if(n[i].shadowRoot)deepAll(n[i].shadowRoot,out);}return out;}
 function vis(e){var r=e.getBoundingClientRect();return r.width>1&&r.height>1;}
 function editable(e){return e&&(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable);}
 var e=null;
 if(q.sel){try{e=document.querySelector(q.sel)}catch(x){}}
 if(!e){var a=document.activeElement;while(a&&a.shadowRoot&&a.shadowRoot.activeElement)a=a.shadowRoot.activeElement;if(editable(a))e=a;}
 if(!e){var all=deepAll(document,[]);for(var i=0;i<all.length;i++){if(editable(all[i])&&vis(all[i])&&all[i].type!=='hidden'&&all[i].type!=='checkbox'&&all[i].type!=='radio'){e=all[i];break}}}
 if(!e)return 'NOT_FOUND';
 e.scrollIntoView({block:'center'});e.focus();
 if(e.isContentEditable){document.execCommand('selectAll',false,null);document.execCommand('insertText',false,q.text);}
 else{var proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var set=Object.getOwnPropertyDescriptor(proto,'value').set;set.call(e,q.text);}
 e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
 if(q.submit){['keydown','keypress','keyup'].forEach(function(t){e.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}))});var f=e.form;if(f){try{f.requestSubmit()}catch(x){f.submit()}}}
 return 'OK';
}"""

    private fun waitLoaded() {
        var waited = 0
        Thread.sleep(400)
        while (progress.value < 100 && waited < 20_000) { Thread.sleep(250); waited += 250 }
        Thread.sleep(400)
    }
}
