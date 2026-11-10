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
            ACTION_KILL -> {
                hardKill()
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
                    ShellRuntime.shellArgs(this@BackgroundCommandService, command)
                )
                pb.directory(cwd)
                pb.environment().putAll(ShellRuntime.environment(this@BackgroundCommandService))
                pb.redirectErrorStream(true)
                val process = pb.start()
                runningProcess = process
                running = true
                persistRunning(this@BackgroundCommandService, true)
                // The output grows as the command runs, so `status` and `logs`
                // can show how far it has got instead of only how it ended.
                val output = StringBuilder()
                val reader = Thread {
                    runCatching {
                        process.inputStream.bufferedReader().use { reader ->
                            reader.forEachLine {
                                synchronized(output) { output.appendLine(it) }
                                lastOutput = output.toString()
                            }
                        }
                    }
                }
                reader.start()
                process.waitFor()
                reader.join()
                lastOutput = output.toString()
                lastExit = process.exitValue()
            } catch (e: Exception) {
                lastOutput = e.message ?: e.toString()
                lastExit = -1
            } finally {
                running = false
                runningProcess = null
                persistRunning(this@BackgroundCommandService, false)
                persistResult(this@BackgroundCommandService, lastCommand, lastExit, lastOutput)
                notifyFinished()
                stopForegroundCompat()
                // Tell the app itself, so the assistant can pick the result up in
                // a fresh turn instead of the user having to ask what happened.
                runCatching { onFinished?.invoke(lastCommand, lastExit, lastOutput) }
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
        // `destroy` alone leaves children (a shell script's own processes)
        // running, so the whole tree is taken down where the platform allows it.
        runCatching {
            val pid = p?.let { processPid(it) }
            if (pid != null && pid > 0) killTree(pid) else p?.destroy()
        }
        notifyFinished()
    }

    /** The hard stop: SIGKILL on the whole process tree, no grace. */
    fun hardKill() {
        running = false
        val p = runningProcess
        runningProcess = null
        job?.cancel()
        job = null
        runCatching {
            val pid = p?.let { processPid(it) }
            if (pid != null && pid > 0) killTree(pid, hard = true) else p?.destroyForcibly()
        }
        notifyFinished()
    }

    /**
     * The OS pid of a `Process`, read through reflection so it works on every
     * API level (the field moved between `java.lang.Process` and
     * `ProcessImpl`, and no public getter exists before API 33). Null when it
     * cannot be read, in which case the caller falls back to `destroy()`.
     */
    private fun processPid(p: Process): Long? {
        for (name in listOf("pid", "hash")) {
            try {
                val f = p.javaClass.getDeclaredField(name)
                f.isAccessible = true
                val v = f.get(p)
                if (v is Int) return v.toLong()
            } catch (_: Throwable) {
                // try the next name
            }
        }
        return null
    }

    /**
     * Kills a process and everything it started. `kill -TERM` on the group is
     * what a terminal's Ctrl-C does; `kill -KILL` on the group is the hard stop
     * used by `action:"kill"`. Both are plain signals to our own process tree —
     * nothing outside this app is touched.
     */
    private fun killTree(pid: Long, hard: Boolean = false) {
        val sig = if (hard) "KILL" else "TERM"
        runCatching {
            val pb = ProcessBuilder(ShellRuntime.shellArgs(this, "kill -$sig -$pid 2>/dev/null || kill -$sig $pid"))
            pb.environment().putAll(ShellRuntime.environment(this))
            pb.redirectErrorStream(true)
            pb.start().waitFor()
        }
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
        private const val PREFS = "zyraxon_bg_command"
        const val ACTION_STOP = "ai.zyraxon.assistant.STOP_COMMAND"
        const val ACTION_KILL = "ai.zyraxon.assistant.KILL_COMMAND"
        const val EXTRA_COMMAND = "command"

        /**
         * A background job as it survives the process. Written while the job
         * runs and again when it ends, so a command that was in flight when the
         * app was killed can be reported instead of vanishing silently.
         */
        data class SavedJob(
            val command: String,
            val startedAt: Long,
            val running: Boolean,
            val exit: Int,
            val output: String,
            val interrupted: Boolean
        )

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

        /** When the running (or last) command was started, for `status`. */
        @Volatile
        var startedAt: Long = 0L
            private set

        /**
         * Called on the command's own thread the moment it ends — with the
         * command, its exit code and its whole output. The app registers this so
         * the assistant can wake up and read the result in a fresh turn. It is
         * cleared when the process dies with the app.
         */
        @Volatile
        var onFinished: ((command: String, exit: Int, output: String) -> Unit)? = null

        @Volatile
        private var runningProcess: Process? = null

        /** Starts a command that outlives the app. */
        fun start(context: Context, command: String) {
            lastCommand = command
            lastOutput = ""
            startedAt = System.currentTimeMillis()
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

        /** Hard-kills the running command and everything it started. */
        fun kill(context: Context) {
            running = false
            val p = runningProcess
            runningProcess = null
            runCatching { p?.destroyForcibly() }
            runCatching {
                context.startService(
                    Intent(context, BackgroundCommandService::class.java).setAction(ACTION_KILL)
                )
            }
        }

        /** How long the running command has been going, in milliseconds. */
        fun elapsedMs(): Long =
            if (running && startedAt > 0) System.currentTimeMillis() - startedAt else 0L

        // --- Disk persistence -------------------------------------------------
        // A foreground service is still a process, and Android may kill it. What
        // was running is written to disk so the next launch can say what
        // happened instead of forgetting the job the user was waiting on.

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun persistRunning(context: Context, isRunning: Boolean) {
            runCatching {
                prefs(context).edit()
                    .putString("command", lastCommand)
                    .putLong("started_at", startedAt)
                    .putBoolean("running", isRunning)
                    .putBoolean("interrupted", false)
                    .apply()
            }
        }

        private fun persistResult(context: Context, command: String, exit: Int, output: String) {
            runCatching {
                prefs(context).edit()
                    .putString("command", command)
                    .putInt("exit", exit)
                    .putString("output", output.takeLast(20_000))
                    .putBoolean("running", false)
                    .putBoolean("interrupted", false)
                    .apply()
            }
        }

        /**
         * What the last background job was, whether it is still running, and how
         * it ended. When the process died mid-command the saved job is marked
         * `interrupted`, which is the honest report — the command did not finish.
         */
        fun saved(context: Context): SavedJob? {
            val p = prefs(context)
            val command = p.getString("command", "") ?: ""
            if (command.isBlank()) return null
            val wasRunning = p.getBoolean("running", false)
            // A job saved as running while this process has no live process is a
            // job that was killed with the app, not one still going.
            val interrupted = wasRunning && runningProcess == null
            return SavedJob(
                command = command,
                startedAt = p.getLong("started_at", 0L),
                running = wasRunning && runningProcess != null,
                exit = p.getInt("exit", 0),
                output = p.getString("output", "") ?: "",
                interrupted = interrupted
            )
        }

        /** Forgets the saved job once its result has been delivered. */
        fun clearSaved(context: Context) {
            runCatching { prefs(context).edit().clear().apply() }
        }
    }
}
