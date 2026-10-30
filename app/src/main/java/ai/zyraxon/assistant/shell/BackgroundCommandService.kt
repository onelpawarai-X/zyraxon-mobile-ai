package ai.zyraxon.assistant.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import ai.zyraxon.assistant.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File


/**
 * Runs one command in a foreground service so it survives the app being closed.
 *
 * An ordinary `run_shell` dies with the activity: swipe the app away and the
 * process is reaped mid-command. A long job — a download, a clone, a build —
 * has to live in a foreground service, which is exactly what this is. The
 * notification carries a **Stop** action, and [stop] destroys the running
 * process, so the user always has one tap to end it.
 */
class BackgroundCommandService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val command = intent?.getStringExtra(EXTRA_COMMAND).orEmpty()
                if (command.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTE_ID, buildNotification(command, running = true))
                runCommand(command)
            }
        }
        return START_STICKY
    }

    private fun runCommand(command: String) {
        if (running) return
        job?.cancel()
        val cwd = File(filesDir, "linux").apply { mkdirs() }
        job = scope.launch {
            try {
                ShellRuntime.ensureReady(this@BackgroundCommandService)
                val pb = ProcessBuilder(
                    ShellRuntime.shell(this@BackgroundCommandService), "-c", command
                )
                pb.directory(cwd)
                pb.environment().putAll(ShellRuntime.environment(this@BackgroundCommandService))
                pb.redirectErrorStream(true)
                val process = pb.start()
                runningProcess = process
                running = true
                val output = StringBuilder()
                val reader = Thread {
                    runCatching {
                        process.inputStream.bufferedReader().use { reader ->
                            reader.forEachLine { output.appendLine(it) }
                        }
                    }
                }
                reader.start()
                process.waitFor()
                reader.join(2_000)
                lastOutput = output.toString().takeLast(8_000)
                lastExit = process.exitValue()
            } catch (e: Exception) {
                lastOutput = e.message ?: e.toString()
                lastExit = -1
            } finally {
                running = false
                runningProcess = null
                notifyFinished()
                stopForegroundCompat()
            }
        }
    }

    /** Destroys the running process, if any. This is the one-tap stop. */
    fun stop() {
        running = false
        val p = runningProcess
        runningProcess = null
        job?.cancel()
        job = null
        runCatching { p?.destroy() }
        notifyFinished()
    }

    private fun notifyFinished() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTE_ID, buildNotification(lastCommand, running = false))
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_DETACH)
        else @Suppress("DEPRECATION") stopForeground(false)
    }

    private fun buildNotification(command: String, running: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHAN, "ZYRAXON কমান্ড", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BackgroundCommandService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHAN) else Notification.Builder(this)
        return builder
            .setContentTitle(if (running) "কমান্ড চলছে" else "কমান্ড শেষ")
            .setContentText(command.take(80))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(running)
            .setContentIntent(open)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "বন্ধ করো",
                stopIntent
            )
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHAN = "zyraxon_commands"
        private const val NOTE_ID = 9101
        const val ACTION_STOP = "ai.zyraxon.assistant.STOP_COMMAND"
        const val EXTRA_COMMAND = "command"

        /** True while a background command is running. */
        @Volatile
        var running: Boolean = false
            private set

        /** The command last launched, for the notification and the UI. */
        @Volatile
        var lastCommand: String = ""
            private set

        @Volatile
        var lastOutput: String = ""
            private set

        @Volatile
        var lastExit: Int = 0
            private set

        @Volatile
        private var runningProcess: Process? = null

        /** Starts a command that outlives the app. */
        fun start(context: Context, command: String) {
            lastCommand = command
            lastOutput = ""
            val i = Intent(context, BackgroundCommandService::class.java)
                .putExtra(EXTRA_COMMAND, command)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        /** Stops the running command from anywhere, including a UI button. */
        fun stop(context: Context) {
            running = false
            val p = runningProcess
            runningProcess = null
            runCatching { p?.destroy() }
            runCatching {
                context.startService(
                    Intent(context, BackgroundCommandService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
