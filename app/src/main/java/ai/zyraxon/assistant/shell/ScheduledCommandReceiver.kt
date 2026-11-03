package ai.zyraxon.assistant.shell

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Runs a command at a time the user chose, even after the app has been closed.
 *
 * An in-app timer dies with the app, so a job the user scheduled for later needs
 * the OS to hold the timer. `AlarmManager` is exactly that: the alarm is handed
 * to Android, this receiver is woken when it fires, and the command then goes to
 * [BackgroundCommandService] so it also survives the app being closed afterwards.
 */
class ScheduledCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        if (command.isBlank()) return
        BackgroundCommandService.start(context, command)
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        private const val REQUEST_CODE = 4242

        private fun pending(context: Context, command: String): PendingIntent {
            val intent = Intent(context, ScheduledCommandReceiver::class.java)
                .putExtra(EXTRA_COMMAND, command)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
        }

        /**
         * Schedules [command] to start in [afterMs] milliseconds from now.
         *
         * `setAndAllowWhileIdle` is used when available so the alarm still fires
         * when the phone is dozing; `set` is the fallback on API 21–22. Both are
         * inexact by Android's design — a scheduled command is not a stopwatch,
         * and pretending otherwise would be a lie about what the OS guarantees.
         */
        fun schedule(context: Context, command: String, afterMs: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(context, command)
            val at = SystemClock.elapsedRealtime() + afterMs
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }

        /** Cancels a scheduled command, if one is waiting. */
        fun cancel(context: Context, command: String) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(context, command))
        }
    }
}
