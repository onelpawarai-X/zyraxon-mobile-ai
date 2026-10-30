package ai.zyraxon.assistant.ui.components

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * A looping video behind the aura.
 *
 * The clip is copied out of assets once into the cache, because VideoView cannot
 * open an asset directly. Audio is muted on purpose: the assistant's own voice is
 * the only sound the user should hear.
 *
 * [onFailed] fires when the clip cannot be copied, prepared, or played, so the
 * caller can show a live shader instead of leaving the screen black — a missing
 * codec used to mean no background at all.
 */
@Composable
fun VideoBackground(
    assetName: String,
    modifier: Modifier = Modifier,
    onFailed: () -> Unit = {}
) {
    if (assetName.isBlank()) return

    val context = androidx.compose.ui.platform.LocalContext.current
    val videoFile = remember(assetName) { copyAssetToCache(context, assetName) }
    LaunchedEffect(assetName, videoFile) {
        if (videoFile == null) onFailed()
    }
    if (videoFile == null) return

    val videoView = remember(assetName) {
        VideoView(context).apply {
            setMediaController(null as MediaController?)
            setVideoURI(Uri.fromFile(videoFile))
        }
    }

    DisposableEffect(assetName) {
        videoView.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            videoView.start()
        }
        videoView.setOnErrorListener { _, _, _ ->
            // The clip cannot play on this device; fall back to the shader.
            onFailed()
            true
        }
        onDispose { runCatching { videoView.stopPlayback() } }
    }

    AndroidView(
        factory = { videoView },
        modifier = modifier.fillMaxSize()
    )
}

private fun copyAssetToCache(
    context: android.content.Context,
    assetName: String
): File? = try {
    val out = File(context.cacheDir, "bg_" + assetName.replace('/', '_'))
    if (!out.exists() || out.length() == 0L) {
        context.assets.open("assets/bg/$assetName").use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
    }
    out
} catch (_: Exception) {
    null
}
