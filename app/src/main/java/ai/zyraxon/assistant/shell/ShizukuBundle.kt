package ai.zyraxon.assistant.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * What the Shizuku card in Settings shows right now. Every branch the user can
 * reach has a name, so the card renders from one value instead of a scatter of
 * booleans that can disagree.
 */
sealed interface ShizukuState {
    /** Nothing read yet. */
    data object Unknown : ShizukuState

    /** Not installed — the APK ships inside ZYRAXON and can be installed here. */
    data object NotInstalled : ShizukuState

    /** The bundled APK is being handed to the system installer. */
    data object Installing : ShizukuState

    /** Installed, but the service is not running, so nothing can use it yet. */
    data object InstalledNotRunning : ShizukuState

    /** Running, but ZYRAXON has not been granted access. */
    data object NeedsPermission : ShizukuState

    /** Running and granted — the system-level tools work now. */
    data object Active : ShizukuState

    /** The last action failed; [message] is shown as-is. */
    data class Failed(val message: String) : ShizukuState
}

/**
 * Shizuku is the way an ordinary app reaches system level: it runs the ADB shell
 * (uid 2000) or root (uid 0) and lends that power to apps the user trusts. It is
 * a separate app, so it installs separately — but the user never has to find or
 * download it: ZYRAXON carries the official release inside its own APK and
 * installs it with one tap, so a Shizuku that has stopped keeps a copy on hand.
 *
 * Shizuku is Apache-2.0, so shipping it is allowed. The bundled file and its
 * licence are under `assets/shizuku/`.
 */
object ShizukuBundle {

    const val PACKAGE = "moe.shizuku.privileged.api"
    const val VERSION = "13.6.0"

    private const val ASSET = "shizuku/shizuku-13.6.0.apk"
    private const val FILE_NAME = "shizuku-13.6.0.apk"

    /** Where a phone without the bundled copy can still get it. */
    const val PLAY_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"

    /** Is the Shizuku app on the phone at all? */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * Copies the bundled APK out of the assets into the cache, once, and returns
     * it. The file is written under a temporary name and renamed whole, so an
     * interrupted copy can never be handed to the installer as a valid APK.
     */
    fun apkFile(context: Context): File? = runCatching {
        val dir = File(context.cacheDir, "shizuku").apply { mkdirs() }
        val out = File(dir, FILE_NAME)
        if (out.exists() && out.length() > 0) return@runCatching out
        val partial = File(dir, "$FILE_NAME.part")
        if (partial.exists()) partial.delete()
        context.assets.open(ASSET).use { input ->
            partial.outputStream().use { input.copyTo(it) }
        }
        if (!partial.renameTo(out)) {
            partial.copyTo(out, overwrite = true)
            partial.delete()
        }
        out
    }.getOrNull()

    /** Opens Shizuku itself, so the user can start it and grant access. */
    fun launch(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** The real state, read from the running service rather than remembered. */
    fun state(context: Context): ShizukuState = when {
        !isInstalled(context) -> ShizukuState.NotInstalled
        SystemShell.available() -> ShizukuState.Active
        SystemShell.needsPermission() -> ShizukuState.NeedsPermission
        else -> ShizukuState.InstalledNotRunning
    }
}
