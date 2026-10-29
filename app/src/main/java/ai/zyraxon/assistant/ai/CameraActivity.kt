package ai.zyraxon.assistant.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A transparent activity whose only job is to run the camera and hand one frame
 * back to the tool layer.
 *
 * The system camera app is used rather than CameraX on purpose: it needs no extra
 * dependency, it works from API 24 upwards, and it already handles every device's
 * quirks. The user sees the normal camera screen and takes the shot; if they back
 * out, the tool is told nothing arrived rather than being handed a blank image.
 */
class CameraActivity : Activity() {

    private var pendingFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingFile = File(
            cacheDir,
            "cam_" + System.currentTimeMillis() + ".jpg"
        )

        val uri: Uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            pendingFile!!
        )

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val front = intentFront
        if (front) intent.putExtra("android.intent.extras.CAMERA_FACING", 1)

        if (intent.resolveActivity(packageManager) == null) {
            finishWith(null)
            return
        }
        startActivityForResult(intent, REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return

        if (resultCode != RESULT_OK) {
            finishWith(null)
            return
        }

        val file = pendingFile
        val bitmap = if (file != null && file.exists() && file.length() > 0) {
            BitmapFactory.decodeFile(file.absolutePath)
        } else {
            // Some camera apps return the picture inline instead of to the URI.
            @Suppress("DEPRECATION")
            data?.extras?.get("data") as? Bitmap
        }
        runCatching { file?.delete() }
        finishWith(bitmap)
    }

    private fun finishWith(bitmap: Bitmap?) {
        result = bitmap
        latch.countDown()
        finish()
    }

    companion object {
        private const val REQ_CAPTURE = 8801

        private var latch = CountDownLatch(1)
        private var result: Bitmap? = null
        private var intentFront = false

        /**
         * Runs the camera on the main thread and suspends the calling tool until
         * the user has taken the picture (or backed out).
         */
        suspend fun capture(context: Context, front: Boolean): Bitmap? {
            latch = CountDownLatch(1)
            result = null
            intentFront = front

            // Launching an activity is a main-thread operation; the tool calls in
            // from an IO thread, so the hop is required, not cosmetic.
            withContext(Dispatchers.Main) {
                val intent = Intent(context, CameraActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }

            // The camera is a human action, so the wait is generous but still
            // bounded: a stuck camera must not hang the whole turn forever.
            latch.await(90, TimeUnit.SECONDS)
            return result
        }
    }
}
