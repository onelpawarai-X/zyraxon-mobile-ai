package ai.zyraxon.assistant.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
    const val MAX_CHARS = 1200

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Reads the text in [frame], a `data:image/jpeg;base64,…` URL.
     *
     * Best effort by design: OCR is an addition to the digest, never the thing
     * the turn depends on, so any failure — a bad frame, a model not downloaded,
     * a slow device — returns "" and the turn carries on with the tree alone.
     */
    suspend fun read(frame: String): String {
        val b64 = frame.substringAfter("base64,", "")
        if (b64.isBlank()) return ""
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return ""
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return ""
        return try {
            recognize(bitmap).take(MAX_CHARS)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    // Line by line, and the blank ones dropped: OCR of a busy
                    // screen returns a lot of single characters that only pad the
                    // message without telling the agent anything.
                    val lines = text.text.split('\n')
                        .map { it.trim() }
                        .filter { it.length >= 2 }
                        .distinct()
                    if (cont.isActive) cont.resume(lines.joinToString("\n"))
                }
                .addOnFailureListener { if (cont.isActive) cont.resume("") }
        }
}
