package ai.zyraxon.assistant.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Words the accessibility tree cannot see: text drawn inside a picture, a video,
 * a canvas or a game surface.
 *
 * This is the supplement to [CloudScreen], not a replacement. The tree already
 * gives every button its own label and coordinate — read by Android, so Bangla
 * arrives as Bangla — and OCR cannot produce a coordinate that taps anything.
 * What OCR adds is the caption burned into an image, which the tree reports as
 * one opaque node or not at all.
 *
 * The recogniser is the Latin-script model: it is the small, bundled one, and it
 * costs nothing to ship. Bangla script is not lost because of that — the tree
 * carries it — and a Bengali OCR model would add tens of megabytes to the APK
 * for a case the tree already covers.
 */
object ScreenOcr {

    /** Longest OCR text kept. A dense screen of picture-text is cut, not dropped. */
    const val MAX_CHARS = 2500

    private val latin by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // Devanagari covers Hindi and Marathi, and reads the digits and Latin mixed
    // in with them; its result is merged with the Latin pass, never trusted alone.
    private val devanagari by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    /**
     * Reads the text in [frame], a `data:image/jpeg;base64,…` URL.
     *
     * The picture is prepared first — small frames are enlarged and the contrast
     * is lifted, because thin caption text on a busy picture is where recognisers
     * lose letters — and then read by two recognisers whose lines are merged.
     *
     * Best effort by design: OCR is an addition to the digest, never the thing
     * the turn depends on, so any failure returns "" and the turn carries on
     * with the accessibility tree alone.
     */
    suspend fun read(frame: String): String {
        val b64 = frame.substringAfter("base64,", "")
        if (b64.isBlank()) return ""
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return ""
        val source = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return ""
        val prepared = runCatching { prepare(source) }.getOrDefault(source)
        return try {
            val a = recognize(latin, prepared)
            val b = runCatching { recognize(devanagari, prepared) }.getOrDefault(emptyList())
            merge(a, b).joinToString("\n").take(MAX_CHARS)
        } finally {
            if (prepared !== source) prepared.recycle()
            source.recycle()
        }
    }

    /** Enlarges a small frame (up to 2x) and raises its contrast. */
    private fun prepare(src: Bitmap): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        val scale = when {
            longEdge >= 2400 -> 1f
            longEdge >= 1200 -> 1.5f
            else -> 2f
        }
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val contrast = 1.35f
        val shift = (-0.5f * contrast + 0.5f) * 255f
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, shift,
            0f, contrast, 0f, 0f, shift,
            0f, 0f, contrast, 0f, shift,
            0f, 0f, 0f, 1f, 0f
        )))
        Canvas(out).drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), paint)
        return out
    }

    /** One line from each pass, the longer reading winning when two overlap. */
    private fun merge(a: List<String>, b: List<String>): List<String> {
        val out = a.toMutableList()
        for (line in b) {
            val key = line.lowercase().filter { it.isLetterOrDigit() }
            if (key.length < 2) continue
            val clash = out.indexOfFirst {
                val k = it.lowercase().filter { c -> c.isLetterOrDigit() }
                k == key || k.contains(key) || key.contains(k)
            }
            when {
                clash < 0 -> out.add(line)
                line.length > out[clash].length -> out[clash] = line
            }
        }
        return out
    }

    private suspend fun recognize(
        client: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap
    ): List<String> =
        suspendCancellableCoroutine { cont ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    // Line by line, in reading order, the single characters and
                    // repeats dropped: they pad the message without telling the
                    // agent anything.
                    val lines = text.textBlocks
                        .flatMap { it.lines }
                        .map { it.text.trim() }
                        .filter { it.length >= 2 }
                        .distinct()
                    if (cont.isActive) cont.resume(lines)
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
        }
}
