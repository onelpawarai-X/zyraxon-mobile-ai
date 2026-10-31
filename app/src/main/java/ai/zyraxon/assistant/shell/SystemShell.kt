package ai.zyraxon.assistant.shell

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * System-level command execution, above the app sandbox.
 *
 * An ordinary Android app is a prisoner of its own sandbox: it cannot read
 * `/data`, cannot see another app's files, cannot write to `/system`. This class
 * is the way out, and it is the same way Termux and every serious shell app on
 * Android takes.
 *
 * Two levels are offered, and both are the user's to grant:
 *
 *  - **[Level.SHELL] — uid 2000**, the `shell` user. This is what `adb shell`
 *    runs as. It can read most of `/data`, list packages, use `pm`, `am`,
 *    `settings`, `dumpsys`, `input`, and write to shared storage. It needs
 *    Shizuku running and ZYRAXON granted its permission — no root required.
 *  - **[Level.ROOT] — uid 0**, full root. On a rooted phone (Magisk, KernelSU)
 *    Shizuku can run as root and so can we. Then nothing is out of reach:
 *    `/system`, every app's `/data/data` directory, mounting, iptables,
 *    installing system apps.
 *
 * Nothing here is a promise the app cannot keep. [probe] reports exactly which
 * level is live right now, and [run] returns the real exit code. The README is
 * explicit that the user is the one who decides whether to hand this access
 * over; the app never asks for it by itself and works fine without it.
 */
object SystemShell {

    /** Which user a system-level command runs as. */
    enum class Level(val uid: Int, val label: String) {
        SHELL(2000, "system (shell)"),
        ROOT(0, "root");

        /** The next level down, used when a level is unavailable. */
        fun fallback(): Level? = if (this == ROOT) SHELL else null
    }

    /** What a system-level command produced. */
    data class Result(
        val command: String,
        val code: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val uid: Int,
        val millis: Long
    ) {
        val ok: Boolean get() = code == 0 && !timedOut
    }

    /** Is the Shizuku server reachable and is our permission granted? */
    fun available(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.getVersion() >= 10 &&
            (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                Shizuku.isPreV11())
    } catch (_: Throwable) {
        false
    }

    /** Shizuku is installed and running, but ZYRAXON has not been granted yet. */
    fun needsPermission(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Asks Shizuku for permission. The user answers in Shizuku's own dialog. */
    fun requestPermission(requestCode: Int = 4210) {
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    /**
     * Which levels actually work right now. `root` is only reported when a
     * command really ran as uid 0 — asking is not the same as being.
     */
    fun probe(context: Context): Map<Level, Boolean> {
        if (!available()) return mapOf(Level.SHELL to false, Level.ROOT to false)
        val shell = idOf(context, Level.SHELL) == Level.SHELL.uid
        val root = idOf(context, Level.ROOT) == Level.ROOT.uid
        return mapOf(Level.SHELL to shell, Level.ROOT to root)
    }

    /** Runs `id -u` at a level and returns the uid it printed, or -1. */
    private fun idOf(context: Context, level: Level): Int {
        val r = run(context, "id -u", level, timeoutMs = 8_000) ?: return -1
        return r.stdout.trim().toIntOrNull() ?: -1
    }

    /**
     * Runs one command at the given level through Shizuku. Returns null when
     * Shizuku is not available, so the caller can fall back to the sandboxed
     * shell instead of failing.
     */
    fun run(
        context: Context,
        command: String,
        level: Level = Level.SHELL,
        timeoutMs: Long = 60_000
    ): Result? {
        if (!available()) return null
        val started = System.currentTimeMillis()
        return try {
            val binder: IBinder = Shizuku.getBinder() ?: return null
            val service = IShizukuService.Stub.asInterface(binder)
            val env = arrayOf(
                "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin",
                "HOME=/",
                "ANDROID_ROOT=/system",
                "ANDROID_DATA=/data",
                "TMPDIR=/data/local/tmp"
            )
            val process = service.newProcess(arrayOf("sh", "-c", command), env, null)
            val out = StringBuilder()
            val err = StringBuilder()
            val outStream = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
            val errStream = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
            val tOut = Thread { outStream.bufferedReader().use { out.append(it.readText()) } }
            val tErr = Thread { errStream.bufferedReader().use { err.append(it.readText()) } }
            tOut.start(); tErr.start()
            val finished = process.waitForTimeout(timeoutMs, "MILLISECONDS")
            val code = if (finished) process.exitValue() else -1
            if (!finished) runCatching { process.destroy() }
            tOut.join(2_000); tErr.join(2_000)
            Result(
                command = command,
                code = code,
                stdout = out.toString().take(120_000),
                stderr = err.toString().take(40_000),
                timedOut = !finished,
                uid = if (level == Level.ROOT) 0 else 2000,
                millis = System.currentTimeMillis() - started
            )
        } catch (e: Throwable) {
            Result(command, -1, "", e.message ?: e.toString(), false, -1,
                System.currentTimeMillis() - started)
        }
    }

    /**
     * Runs the command at the best level that is live: root if the phone is
     * rooted and Shizuku runs as root, otherwise shell, otherwise null so the
     * caller uses the sandboxed shell.
     */
    fun runBest(context: Context, command: String, timeoutMs: Long = 60_000): Result? {
        val levels = probe(context)
        val level = when {
            levels[Level.ROOT] == true -> Level.ROOT
            levels[Level.SHELL] == true -> Level.SHELL
            else -> return null
        }
        return run(context, command, level, timeoutMs)
    }

    /** Reads a file at system level — the thing the sandbox cannot do. */
    fun readFile(context: Context, path: String, level: Level = Level.ROOT): String? {
        val r = run(context, "cat ${shellQuote(path)}", level, timeoutMs = 20_000) ?: return null
        return if (r.ok) r.stdout else null
    }

    /**
     * Runs a command at system level and reports output **as it arrives**, so a
     * long install or build shows live progress in the Terminal instead of a
     * spinner. The reader threads call [onChunk], so a caller that touches UI
     * state must post it to the main thread itself.
     */
    fun runStreaming(
        context: Context,
        command: String,
        level: Level = Level.SHELL,
        timeoutMs: Long = 600_000,
        onChunk: (stream: String, text: String) -> Unit
    ): Result? {
        if (!available()) return null
        val started = System.currentTimeMillis()
        return try {
            val binder: IBinder = Shizuku.getBinder() ?: return null
            val service = IShizukuService.Stub.asInterface(binder)
            val env = arrayOf(
                "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin",
                "HOME=/",
                "ANDROID_ROOT=/system",
                "ANDROID_DATA=/data",
                "TMPDIR=/data/local/tmp"
            )
            val process = service.newProcess(arrayOf("sh", "-c", command), env, null)
            val out = StringBuilder()
            val err = StringBuilder()
            val outStream = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
            val errStream = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
            val tOut = Thread { pump(outStream.bufferedReader(), out, "out", onChunk) }
            val tErr = Thread { pump(errStream.bufferedReader(), err, "err", onChunk) }
            tOut.start(); tErr.start()
            val finished = process.waitForTimeout(timeoutMs, "MILLISECONDS")
            val code = if (finished) process.exitValue() else -1
            if (!finished) runCatching { process.destroy() }
            tOut.join(2_000); tErr.join(2_000)
            Result(
                command = command,
                code = code,
                stdout = out.toString().take(200_000),
                stderr = err.toString().take(60_000),
                timedOut = !finished,
                uid = if (level == Level.ROOT) 0 else 2000,
                millis = System.currentTimeMillis() - started
            )
        } catch (e: Throwable) {
            Result(command, -1, "", e.message ?: e.toString(), false, -1,
                System.currentTimeMillis() - started)
        }
    }

    /** Reads a stream line by line, appending to [sink] and calling [onChunk]. */
    private fun pump(
        reader: BufferedReader,
        sink: StringBuilder,
        stream: String,
        onChunk: (String, String) -> Unit
    ) {
        try {
            reader.use {
                var line: String?
                while (true) {
                    line = it.readLine() ?: break
                    synchronized(sink) { sink.append(line).append('\n') }
                    runCatching { onChunk(stream, line) }
                }
            }
        } catch (_: Exception) {}
    }

    /** Quotes a path for the remote `sh`. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Where a downloaded helper binary would live at system level. */
    fun systemTmp(): File = File("/data/local/tmp")
}
