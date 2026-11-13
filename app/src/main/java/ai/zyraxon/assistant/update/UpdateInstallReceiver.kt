// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the result of an in-place install from [Updater].
 *
 * When the install succeeds Android restarts the app, so the success branch is
 * mostly a no-op; the failure branch is the one that matters, because it is the
 * only place the real reason (a bad signature, a newer build already installed)
 * becomes visible instead of the install silently doing nothing.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Updater.ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android wants the user to confirm the install; forward its dialog.
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirm) }
            return
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Log.w("ZYRAXON", "install failed: $status $message")
            // The install happens in the system's package service, so this
            // broadcast is the only place its refusal is ever stated. Publishing it
            // is what turns a silent no-op into a message the user can act on.
            Updater.installFailure.value = message ?: "ইনস্টল করা গেল না (কোড $status)"
        } else {
            Updater.installFailure.value = null
        }
    }
}
