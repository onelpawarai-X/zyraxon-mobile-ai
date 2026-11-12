// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.permission

import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * Lets Accessibility be switched on for an app that arrived as a file.
 *
 * Android 13+ greys out Accessibility for any app whose install did not come
 * through a store ("restricted setting"). The mark is put on at install time and
 * is cleared by installing through the system installer's session interface as a
 * store. So the app hands its own APK back to the system installer that way; one
 * Update tap later the mark is gone and Accessibility turns on like any other app.
 */
object SelfInstall {

    private const val ACTION = "ai.zyraxon.assistant.SELF_INSTALL_RESULT"

    /** True while Android is still blocking Accessibility for this install. */
    fun restricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val viaOps = runCatching {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.unsafeCheckOpNoThrow(
                "android:access_restricted_settings", android.os.Process.myUid(), context.packageName
            )
            mode != AppOpsManager.MODE_ALLOWED
        }.getOrNull()
        if (viaOps != null) return viaOps
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
        }.getOrNull()
        return installer != context.packageName && installer != "com.android.vending"
    }

    /** Sends this app's own APK(s) to the system installer. Returns false if it could not start. */
    fun reinstall(context: Context): Boolean {
        val app = context.applicationContext
        val info = app.applicationInfo
        val files = buildList {
            add(File(info.sourceDir))
            info.splitSourceDirs?.forEach { add(File(it)) }
        }.filter { it.exists() }
        if (files.isEmpty()) return false
        return try {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            val session = installer.openSession(id)
            files.forEachIndexed { i, f ->
                f.inputStream().use { input ->
                    session.openWrite(if (i == 0) "base.apk" else "split$i.apk", 0, f.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { c.startActivity(confirm) }
                    } else {
                        runCatching { c.unregisterReceiver(this) }
                    }
                }
            }
            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
            val pi = PendingIntent.getBroadcast(
                app, id, Intent(ACTION).setPackage(app.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            )
            session.commit(pi.intentSender)
            session.close()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
