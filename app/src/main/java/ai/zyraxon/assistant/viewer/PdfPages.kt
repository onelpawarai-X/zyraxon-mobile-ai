// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** Renders a PDF into page pictures and an HTML page that shows them, for the Preview panel. */
object PdfPages {
    fun toHtml(pdf: File, outDir: File, maxPages: Int = 60): String {
        outDir.mkdirs()
        val imgs = ArrayList<String>()
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                for (i in 0 until minOf(r.pageCount, maxPages)) {
                    r.openPage(i).use { p ->
                        val w = 1100; val h = (w.toFloat() * p.height / p.width).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val f = File(outDir, "${pdf.nameWithoutExtension}_p${i + 1}.png")
                        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle()
                        imgs += f.toURI().toString()
                    }
                }
            }
        }
        val body = imgs.joinToString("") { "<img src=\"$it\" style=\"display:block;width:100%;margin:0 0 8px;background:#fff\">" }
        return DocViewer.page(pdf.name, "<div style=\"max-width:900px;margin:8px auto\">$body</div>", "pdf")
    }
}
