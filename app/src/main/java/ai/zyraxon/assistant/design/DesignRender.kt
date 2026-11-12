package ai.zyraxon.assistant.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Draws a design (HTML or SVG) to a PNG and a PDF.
 *
 * A WebView of the exact design size is laid out off screen, the page is given a
 * viewport of that width so one CSS pixel is one image pixel, and the picture is
 * taken only after the page reports a painted frame. The work runs on the main
 * thread (a WebView requires it) while the caller waits on a latch.
 */
object DesignRender {

    private val main = Handler(Looper.getMainLooper())

    /** The page drawn into a bitmap, or null when it did not paint in time. */
    fun bitmap(context: Context, html: String, w: Int, h: Int, transparent: Boolean): Bitmap? {
        val done = CountDownLatch(1)
        var result: Bitmap? = null
        main.post {
            val wv = WebView(context.applicationContext)
            var finished = false
            fun finish(bmp: Bitmap?) {
                if (finished) return
                finished = true
                result = bmp
                runCatching { wv.stopLoading(); wv.destroy() }
                done.countDown()
            }
            try {
                wv.settings.javaScriptEnabled = true
                wv.settings.allowFileAccess = true
                wv.settings.useWideViewPort = true
                wv.settings.loadWithOverviewMode = true
                wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                wv.setBackgroundColor(if (transparent) Color.TRANSPARENT else Color.WHITE)
                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, w, h)
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String) {
                        // Give fonts and images a moment, then draw once a frame is painted.
                        main.postDelayed({
                            if (finished) return@postDelayed
                            val take = {
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                if (!transparent) bmp.eraseColor(Color.WHITE)
                                v.draw(Canvas(bmp))
                                finish(bmp)
                            }
                            if (android.os.Build.VERSION.SDK_INT >= 23) {
                                v.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) { take() }
                                })
                                main.postDelayed({ if (!finished) take() }, 1500)
                            } else take()
                        }, 700)
                    }
                }
                wv.loadDataWithBaseURL("https://zyraxon.local/", html, "text/html", "utf-8", null)
                main.postDelayed({ finish(null) }, 25_000)
            } catch (_: Throwable) {
                finish(null)
            }
        }
        done.await(30, TimeUnit.SECONDS)
        return result
    }

    fun savePng(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** One PDF page the size of the picture (1 px = 1 pt). */
    fun savePdf(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create())
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdf.finishPage(page)
            FileOutputStream(file).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }
}
