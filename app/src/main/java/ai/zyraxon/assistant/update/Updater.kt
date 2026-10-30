package ai.zyraxon.assistant.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** One downloadable file attached to a release. */
data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** The newest release the user could install, as read from GitHub. */
data class UpdateRelease(
    val tag: String,
    val version: Version,
    val notes: String,
    val apk: ReleaseAsset?
)

/**
 * What the update UI is showing right now. Every branch the user can reach has a
 * name here, so the sheet and the launch banner render from one value instead of
 * a scatter of booleans that can disagree.
 */
sealed interface UpdateState {
    /** Nothing has been asked yet. */
    data object Idle : UpdateState

    /** A check is in flight. */
    data object Checking : UpdateState

    /** The installed build is the newest one. */
    data class UpToDate(val installed: String) : UpdateState

    /** A newer release exists and can be downloaded. */
    data class Available(val release: UpdateRelease) : UpdateState

    /** A newer release exists but carries no APK to install. */
    data class NoApk(val release: UpdateRelease) : UpdateState

    /** The APK is arriving; [progress] is 0..1. */
    data class Downloading(val progress: Float) : UpdateState

    /** The APK is on disk and ready to install. */
    data class Downloaded(val release: UpdateRelease, val apkPath: String) : UpdateState

    /** Android needs the user to allow installs from this app first. */
    data class NeedsPermission(val release: UpdateRelease) : UpdateState

    /** The check or the download failed; [message] is shown as-is. */
    data class Failed(val message: String) : UpdateState
}

/**
 * A release version, compared numerically rather than as text.
 *
 * Text comparison gets `6.10.0` < `6.9.0` wrong, and the app would then never offer
 * the 6.10.0 update. Anything that is not a dotted number parses to null and is
 * ignored, so a tag like `release-candidate` cannot be mistaken for an update.
 */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {

    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

        /** Pulls a version out of `v6.5.0`, `6.5`, or a title that contains one. */
        fun parse(text: String?): Version? {
            val m = PATTERN.find(text ?: "") ?: return null
            return Version(
                major = m.groupValues[1].toIntOrNull() ?: return null,
                minor = m.groupValues[2].toIntOrNull() ?: return null,
                patch = m.groupValues[3].toIntOrNull() ?: 0
            )
        }
    }
}

/**
 * Checks GitHub for a newer build of ZYRAXON and installs it in place.
 *
 * The update replaces the installed app rather than adding a second one, because
 * the new APK carries the same package name and is signed with the same key. The
 * install goes through [PackageInstaller] with a `SESSION_COMMIT` intent, which is
 * the in-place path Android provides; the app is never uninstalled first, so the
 * user's chats, memories and settings survive the update.
 */
class Updater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val currentVersion: Version =
        Version.parse(installedVersionName(context)) ?: Version(0, 0, 0)

    /** The release feed. Public repo, so no token is needed. */
    private val latestUrl = "https://api.github.com/repos/$REPO/releases/latest"

    /**
     * The newest release, or null when the installed build is already current.
     * Returns the release even when it carries no APK, so the caller can say so.
     */
    suspend fun check(): Result<UpdateRelease?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(latestUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ZYRAXON-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("GitHub replied ${response.code}")
                }
                val release = parseLatest(response.body?.string().orEmpty()) ?: return@use null
                if (release.version > currentVersion) release else null
            }
        }
    }

    /**
     * Streams the APK to the cache and reports 0..1 progress. The file is written
     * to a temporary name and only renamed once it is whole, so an interrupted
     * download can never be handed to the installer as a valid APK.
     */
    suspend fun download(
        release: UpdateRelease,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val asset = release.apk ?: error("এই রিলিজে কোনো APK নেই")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "ZYRAXON-${release.version}.apk")
            val partial = File(dir, "ZYRAXON-${release.version}.part")
            if (partial.exists()) partial.delete()

            val request = Request.Builder()
                .url(asset.url)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "ZYRAXON-Android")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("ডাউনলোড ব্যর্থ (${response.code})")
                val body = response.body ?: error("খালি উত্তর")
                val total = body.contentLength().takeIf { it > 0 } ?: asset.size
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            onProgress(1f)
            target
        }
    }

    /**
     * Hands the downloaded APK to Android's package installer. Returns false when
     * the user has not granted this app permission to install packages, so the UI
     * can open that exact settings row instead of failing silently.
     */
    fun install(apk: File): Boolean {
        if (!canInstall()) return false
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            context.contentResolver.openInputStream(uri)?.use { input ->
                session.openWrite("zyraxon", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            } ?: error("APK খোলা গেল না")
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(callback.intentSender)
        }
        return true
    }

    /** True when Android will let this app install a package update itself. */
    fun canInstall(): Boolean =
        // canRequestPackageInstalls is API 26. On Android 5–7 the unknown-sources
        // switch is a device-wide setting the user already controls, so a package
        // install is allowed whenever that switch is on.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Opens the "install unknown apps" row for this app, where the switch lives. */
    fun openInstallPermissionSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }

    companion object {
        const val REPO = "onelpawarai-X/zyraxon-mobile-ai"
        const val ACTION_INSTALL_STATUS = "ai.zyraxon.assistant.INSTALL_STATUS"

        /**
         * The version name Android has for the installed package. Reading it here
         * rather than from BuildConfig keeps the answer honest — it is the build
         * that is actually on the phone, which is what an update decision needs.
         */
        fun installedVersionName(context: Context): String = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
        }.getOrNull().orEmpty()

        /**
         * Reads GitHub's release JSON. The APK is found by name, so the release
         * can also carry notes, a checksum and a source zip without confusing the
         * updater. The version comes from the tag, which is what the release is
         * named after.
         */
        fun parseLatest(json: String): UpdateRelease? {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val tag = root.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            val version = Version.parse(tag) ?: return null

            val assetsJson = root.optJSONArray("assets")
            var apk: ReleaseAsset? = null
            for (i in 0 until (assetsJson?.length() ?: 0)) {
                val a = assetsJson?.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                apk = ReleaseAsset(
                    name = name,
                    url = a.optString("browser_download_url"),
                    size = a.optLong("size")
                )
                break
            }
            return UpdateRelease(
                tag = tag,
                version = version,
                notes = root.optString("body"),
                apk = apk
            )
        }

        /** True when [remote] is a newer build than the one installed. */
        fun isNewer(remote: Version, installed: Version): Boolean = remote > installed
    }
}
