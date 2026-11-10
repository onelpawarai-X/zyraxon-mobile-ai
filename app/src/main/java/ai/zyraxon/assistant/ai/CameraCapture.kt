package ai.zyraxon.assistant.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Real camera and media access for the tool layer.
 *
 * Everything here returns a bitmap or a data URL, so a tool can hand a picture to
 * the model directly. Nothing is faked: if the camera cannot open, the caller gets
 * null and the tool reports the failure instead of inventing a result.
 */
internal object CameraCapture {

    /** Grabs one frame from the camera. Returns null when capture is impossible. */
    suspend fun capture(context: Context, facing: String): Bitmap? =
        CameraActivity.capture(context, facing == "front")

    /** Writes the frame into the assistant workspace and returns the file. */
    fun saveToWorkspace(context: Context, bitmap: Bitmap): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "zyraxon/media")
        dir.mkdirs()
        val file = File(dir, "photo_" + System.currentTimeMillis() + ".jpg")
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out) }
        return file
    }

    /**
     * Pulls a single frame out of a video file. `atSecond` is where to look; the
     * first frame is used when the requested time is past the end.
     */
    fun videoFrame(file: File, atSecond: Long): Bitmap? = try {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            val micros = atSecond * 1_000_000L
            r.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Encodes a bitmap as a JPEG data URL. Images are scaled down first: a full
     * camera frame as base64 is several megabytes and would stall the request.
     */
    fun toDataUrl(bitmap: Bitmap, maxSide: Int = 1024, quality: Int = 80): String {
        val scaled = downscale(bitmap, maxSide)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bitmap) scaled.recycle()
        return "data:image/jpeg;base64," +
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun decodeFile(file: File, maxSide: Int = 1024): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun downscale(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        return scaled
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / sample > maxSide * 2) sample *= 2
        return sample
    }
}
