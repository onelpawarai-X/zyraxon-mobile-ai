package ai.zyraxon.assistant.shell

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The real command line behind the Terminal tab and `run_shell`.
 *
 * Android's own `/system/bin/sh` is a toy: a handful of applets and a fixed
 * PATH. This ships a **static busybox** per ABI under `jniLibs`, so the app has
 * one binary that is roughly 400 real Unix commands — `sh`, `awk`, `sed`, `grep`,
 * `tar`, `wget`, `find`, `xargs`, `sort`, `xxd`, `diff`, and the rest.
 *
 * Android only lets an app execute a file that lives in the native library
 * directory, so busybox is packaged as `libbusybox.so` and run from
 * `applicationInfo.nativeLibraryDir`. Everything else — a PATH of applet names,
 * a writable HOME, a TMPDIR — is arranged on top of that one binary.
 */
object ShellRuntime {

    private const val BUSYBOX = "libbusybox.so"

    /** busybox is installed once; the applet farm is built with it. */
    @Volatile
    private var ready = false

    /**
     * The binary itself, or null when the device's ABI is not packaged.
     *
     * It is named `libbusybox.so` because that is the only shape Android lets an
     * app execute out of the native library directory.
     */
    fun binary(context: Context): File? {
        val f = File(context.applicationInfo.nativeLibraryDir, BUSYBOX)
        return if (f.canExecute()) f else null
    }

    /**
     * The name commands are actually run through: a `busybox` symlink in the
     * applet farm.
     *
     * busybox chooses its applet from `argv[0]`, and `libbusybox.so` is not an
     * applet name, so the raw binary refuses to start. Going through a link
     * called `busybox` is what makes `busybox sh`, `busybox --install` and every
     * applet symlink work.
     */
    fun command(context: Context): File? {
        val bb = binary(context) ?: return null
        val link = File(binDir(context), "busybox")
        if (!link.exists()) {
            runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), bb.toPath()) }
        }
        return if (link.exists()) link else bb
    }

    /** True when the bundled toolbox is usable on this device. */
    fun isAvailable(context: Context): Boolean = binary(context) != null

    /** The shell a command should run through: bundled busybox `sh`, else the system `sh`. */
    fun shell(context: Context): String = command(context)?.absolutePath ?: "/system/bin/sh"

    /** The argv prefix that starts a shell. */
    private fun shellArgs(context: Context): List<String> =
        command(context)?.let { listOf(it.absolutePath, "sh") } ?: listOf("/system/bin/sh")

    /**
     * Where the toolbox lives: internal storage.
     *
     * This must not be external storage. Android mounts `/storage/emulated/0`
     * through FUSE, which refuses `symlink()` — and every applet in the farm is a
     * symlink. Internal storage is a real filesystem, so the links are allowed.
     */
    fun toolRoot(context: Context): File = File(context.filesDir, "toolbox").apply { mkdirs() }

    fun binDir(context: Context): File = File(toolRoot(context), "bin").apply { mkdirs() }

    /** The working directory the terminal opens in, and where its files live. */
    fun home(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "linux").apply { mkdirs() }

    /**
     * Builds the applet farm — one symlink per busybox applet inside `bin/`, so
     * `awk`, `sed`, `tar` and the rest resolve by name on PATH. Runs once; the
     * marker file means a restart does not redo it.
     */
    @Synchronized
    fun ensureReady(context: Context) {
        val bb = command(context) ?: run { ready = true; return }
        val bin = binDir(context)
        val marker = File(bin, ".ready")
        if (ready && marker.exists()) return
        if (!marker.exists()) {
            val applets = runApplets(bb)
            // busybox installs its own applets; letting it do that is more
            // reliable than making the links by hand, and it knows its own list.
            runCatching {
                ProcessBuilder(bb.absolutePath, "--install", "-s", bin.absolutePath)
                    .redirectErrorStream(true).start().waitFor(30, TimeUnit.SECONDS)
            }
            // `--install` skips anything already present, so a partly built farm
            // is completed here before the marker claims the job is done. A
            // symlink is preferred; a hard link is the fallback for a filesystem
            // that allows one but not the other.
            for (name in applets) {
                if (name.isEmpty() || name.contains('/')) continue
                val link = File(bin, name)
                if (link.exists()) continue
                runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), bb.toPath()) }
                    .onFailure { runCatching { java.nio.file.Files.createLink(link.toPath(), bb.toPath()) } }
            }
            runCatching { marker.writeText(applets.size.toString()) }
        }
        ready = true
    }

    /** The applet names this busybox provides, asked of busybox itself. */
    private fun runApplets(bb: File): List<String> = runCatching {
        val p = ProcessBuilder(bb.absolutePath, "--list").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor(10, TimeUnit.SECONDS)
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    /** The environment every command runs with — a real PATH, HOME and TMPDIR. */
    fun environment(context: Context): Map<String, String> {
        val home = home(context)
        val bin = binDir(context)
        File(home, "tmp").mkdirs()
        return mapOf(
            "PATH" to "${bin.absolutePath}:/system/bin:/system/xbin:/vendor/bin",
            "HOME" to home.absolutePath,
            "TMPDIR" to File(home, "tmp").absolutePath,
            "PWD" to home.absolutePath,
            "SHELL" to shell(context),
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "ANDROID_ROOT" to (System.getenv("ANDROID_ROOT") ?: "/system"),
            "ANDROID_DATA" to (System.getenv("ANDROID_DATA") ?: "/data"),
            "LD_LIBRARY_PATH" to context.applicationInfo.nativeLibraryDir
        )
    }

    /** What a finished command produced. */
    data class Result(
        val command: String,
        val code: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val millis: Long
    ) {
        val ok: Boolean get() = code == 0 && !timedOut
    }

    /**
     * Runs one line through the bundled shell and returns everything it did.
     * `cwd` defaults to the terminal home. Output is capped so one runaway
     * command cannot swallow the whole context window.
     */
    fun run(
        context: Context,
        command: String,
        cwd: File? = null,
        timeoutMs: Long = 60_000,
        extraEnv: Map<String, String> = emptyMap()
    ): Result {
        ensureReady(context)
        val dir = (cwd ?: home(context)).apply { mkdirs() }
        val started = System.currentTimeMillis()
        return try {
            val pb = ProcessBuilder(shellArgs(context) + listOf("-c", command))
            pb.directory(dir)
            pb.environment().putAll(environment(context))
            pb.environment().putAll(extraEnv)
            pb.redirectErrorStream(false)
            val p = pb.start()
            // Read both streams on their own threads: a command that fills one
            // pipe while we block on the other would otherwise deadlock.
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { p.inputStream.bufferedReader().use { out.append(it.readText()) } }
            val tErr = Thread { p.errorStream.bufferedReader().use { err.append(it.readText()) } }
            tOut.start(); tErr.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) p.destroy()
            tOut.join(2_000); tErr.join(2_000)
            Result(
                command = command,
                code = if (finished) p.exitValue() else -1,
                stdout = out.toString().take(120_000),
                stderr = err.toString().take(40_000),
                timedOut = !finished,
                millis = System.currentTimeMillis() - started
            )
        } catch (e: Exception) {
            Result(command, -1, "", e.message ?: e.toString(), false,
                System.currentTimeMillis() - started)
        }
    }

    /** A one-line description of the toolbox, for `platform_info`. */
    fun describe(context: Context): String {
        val bb = command(context)
        if (bb == null) return "Android ${Build.VERSION.RELEASE} · shell: /system/bin/sh (no bundled toolbox for ${Build.SUPPORTED_ABIS.firstOrNull()})"
        val applets = runApplets(bb).size
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()} · " +
            "shell: bundled busybox, $applets applets · PATH ${binDir(context).absolutePath}"
    }
}
