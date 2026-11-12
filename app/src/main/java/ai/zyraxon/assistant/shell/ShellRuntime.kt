package ai.zyraxon.assistant.shell

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The real command line behind the Terminal tab and `run_shell`.
 *
 * Unix execution environment powered by a static busybox binary per ABI under `jniLibs`.
 * This gives the app roughly 400 real Unix commands — `sh`, `awk`, `sed`, `grep`,
 * `tar`, `wget`, `find`, `xargs`, `sort`, `xxd`, `diff`, `ls`, `cat`, etc.
 *
 * On install/startup, `libbusybox.so` is linked/copied into `toolbox/bin/busybox`, made executable,
 * and `busybox --install -s` installs the full applet farm.
 * With `targetSdk = 28`, Android allows executing binaries and symlinks
 * inside the app's private files directory.
 */
object ShellRuntime {

    private const val BUSYBOX = "libbusybox.so"

    /** busybox is installed once; the applet farm is built with it. */
    @Volatile
    private var ready = false

    /**
     * The `bin` directory the farm was built in. The farm is only valid for that
     * exact directory: a process whose data directory moved (a fresh install, or
     * a test that is handed a new one) must build it again rather than run against
     * a directory that no longer holds the applet links.
     */
    @Volatile
    private var readyDir: String? = null

    /**
     * The binary itself, or null when the device's ABI is not packaged.
     */
    fun binary(context: Context): File? {
        val f = File(context.applicationInfo.nativeLibraryDir, BUSYBOX)
        return if (f.canExecute()) f else null
    }

    /**
     * True when the bundled toolbox is usable on this device.
     */
    fun isAvailable(context: Context): Boolean = binary(context) != null

    /** Where the toolbox lives: internal storage. */
    fun toolRoot(context: Context): File = File(context.filesDir, "toolbox").apply { mkdirs() }

    fun binDir(context: Context): File = File(toolRoot(context), "bin").apply { mkdirs() }

    fun installedBusybox(context: Context): File = File(binDir(context), "busybox")

    fun installedSh(context: Context): File = File(binDir(context), "sh")

    /**
     * Returns command arguments to invoke busybox applets with argv[0] = "busybox".
     */
    fun busyboxArgs(context: Context, vararg args: String): List<String> {
        ensureReady(context)
        if (mode == "none") return listOf(args.firstOrNull() ?: "/system/bin/sh") + args.drop(1)
        val installed = installedBusybox(context)
        if (installed.canExecute()) {
            return listOf(installed.absolutePath) + args
        }
        val bb = binary(context)
        return if (bb != null) {
            listOf(
                "/system/bin/sh",
                "-c",
                "bb=\"\$1\"; shift; exec -a busybox \"\$bb\" \"\$@\"",
                "sh",
                bb.absolutePath
            ) + args
        } else {
            listOf(args.firstOrNull() ?: "/system/bin/sh") + args.drop(1)
        }
    }

    /**
     * Returns command arguments to run a shell command through busybox sh (or /system/bin/sh fallback).
     */
    fun shellArgs(context: Context, command: String): List<String> {
        ensureReady(context)
        if (mode == "none") return listOf("/system/bin/sh", "-c", command)
        val sh = installedSh(context)
        if (sh.canExecute() && shWorks(sh)) {
            return listOf(sh.absolutePath, "-c", command)
        }
        val installedBb = installedBusybox(context)
        if (installedBb.canExecute()) {
            return listOf(installedBb.absolutePath, "sh", "-c", command)
        }
        val bb = binary(context)
        return if (bb != null) {
            listOf(
                "/system/bin/sh",
                "-c",
                "bb=\"\$1\"; shift; exec -a busybox \"\$bb\" \"\$@\"",
                "sh",
                bb.absolutePath,
                "sh",
                "-c",
                command
            )
        } else {
            listOf("/system/bin/sh", "-c", command)
        }
    }

    /** The shell executable used for simple calls or fallbacks. */
    fun shell(context: Context): String {
        ensureReady(context)
        val sh = installedSh(context)
        return if (mode != "none" && sh.canExecute()) sh.absolutePath else "/system/bin/sh"
    }

    /** The binary file or null, kept for backward compatibility with callers. */
    fun command(context: Context): File? = binary(context)

    /**
     * The Terminal's own working directory — the shell the user types into.
     */
    fun home(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "linux").apply { mkdirs() }

    /**
     * Builds the applet farm — sets up bin/busybox and creates applet symlinks inside `bin/`.
     */
    /** How busybox runs on this phone: "native", "copy", or "none" (system shell only). */
    @Volatile
    private var mode = "unknown"

    /** True when [cmd] can start and print a marker — the only proof a launch route works here. */
    private fun shWorks(shFile: File): Boolean = runCatching {
        if (!shFile.canExecute()) return false
        val p = ProcessBuilder(shFile.absolutePath, "-c", "echo zx_ok").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        awaitProcess(p, 3_000)
        p.exitValue() == 0 && text.contains("zx_ok")
    }.getOrDefault(false)

    private fun works(cmd: List<String>): Boolean = runCatching {
        val p = ProcessBuilder(cmd + listOf("echo", "zx_ok")).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        awaitProcess(p, 5_000)
        text.contains("zx_ok") && runCatching { p.exitValue() }.getOrDefault(-1) == 0
    }.getOrDefault(false)

    /**
     * Picks the way busybox is launched, by trying each and keeping the first that
     * works on this very phone. Newer Android versions and some vendors kill a
     * binary started from one location (SIGSYS, exit 159) and let it run from
     * another, so no single route can be assumed:
     *  1. a symlink to the library packaged with the app — runs on every version;
     *  2. a copy in the app's own storage;
     *  3. neither: the system shell, which always runs.
     * The farm of applet links is rebuilt whenever the chosen route changes, so no
     * link ever points at a binary that does not run.
     */
    @Synchronized
    fun ensureReady(context: Context) {
        val bin = binDir(context)
        if (ready && mode != "unknown" && readyDir == bin.absolutePath) return
        val bb = binary(context) ?: run {
            mode = "none"
            readyDir = bin.absolutePath
            ready = true
            return
        }
        val busyboxBin = installedBusybox(context)
        val marker = File(bin, ".ready")
        val modeFile = File(toolRoot(context), "mode")
        try {
            // Route 1: symlink to the packaged library, re-pointed every start because
            // an app update moves that directory.
            runCatching { busyboxBin.delete() }
            runCatching { java.nio.file.Files.createSymbolicLink(busyboxBin.toPath(), bb.toPath()) }
            var chosen = if (busyboxBin.exists() && works(listOf(busyboxBin.absolutePath))) "native" else "none"
            if (chosen == "none") {
                // Route 2: a private copy.
                runCatching { busyboxBin.delete() }
                runCatching { bb.copyTo(busyboxBin, overwrite = true) }
                busyboxBin.setReadable(true, false)
                busyboxBin.setExecutable(true, false)
                if (busyboxBin.exists() && works(listOf(busyboxBin.absolutePath))) chosen = "copy"
            }
            val previous = runCatching { modeFile.readText().trim() }.getOrNull()
            if (previous != chosen) {
                // Different route: every old link is suspect. Clear them and rebuild.
                bin.listFiles()?.forEach { f -> if (f.name != "busybox") runCatching { f.delete() } }
                runCatching { marker.delete() }
                runCatching { modeFile.writeText(chosen) }
            }
            mode = chosen
            if (chosen != "none" && (!marker.exists() || !File(bin, "sh").exists())) {
                runCatching {
                    val p = ProcessBuilder(busyboxBin.absolutePath, "--install", "-s", bin.absolutePath)
                        .directory(bin).redirectErrorStream(true).start()
                    awaitProcess(p, 15_000)
                }
                val applets = runApplets(context)
                for (name in applets) {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty() || trimmed.contains('/')) continue
                    val link = File(bin, trimmed)
                    if (!link.exists()) {
                        runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), busyboxBin.toPath()) }
                            .onFailure { runCatching { java.nio.file.Files.createLink(link.toPath(), busyboxBin.toPath()) } }
                    }
                }
                runCatching { marker.writeText(applets.size.toString()) }
            }
        } catch (e: Exception) {
            if (mode == "unknown") mode = "none"
        }
        readyDir = bin.absolutePath
        ready = true
    }

    private fun awaitProcess(p: Process, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0) {
            return try { p.waitFor(); true } catch (_: InterruptedException) { false }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try { p.exitValue(); return true } catch (_: IllegalThreadStateException) {}
            try { Thread.sleep(20) } catch (_: InterruptedException) { return false }
        }
        return try { p.exitValue(); true } catch (_: IllegalThreadStateException) { false }
    }

    /** The applet names this busybox provides. */
    private fun runApplets(context: Context): List<String> = runCatching {
        val busyboxBin = installedBusybox(context)
        val cmd = if (busyboxBin.canExecute()) {
            listOf(busyboxBin.absolutePath, "--list")
        } else {
            busyboxArgs(context, "--list")
        }
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        awaitProcess(p, 10_000)
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    /** The environment every command runs with — a real PATH, HOME and TMPDIR. */
    fun environment(context: Context): Map<String, String> {
        val home = home(context)
        val bin = binDir(context)
        val busyboxBin = installedBusybox(context)
        val sh = installedSh(context)
        File(home, "tmp").mkdirs()
        val usr = File(context.filesDir, "usr")
        val usrBin = File(usr, "bin")
        val usrLib = File(usr, "lib")
        val pathStr = if (usrBin.isDirectory) {
            "${bin.absolutePath}:${usrBin.absolutePath}:/system/bin:/system/xbin:/vendor/bin"
        } else {
            "${bin.absolutePath}:/system/bin:/system/xbin:/vendor/bin"
        }
        val ldStr = if (usrLib.isDirectory) {
            "${usrLib.absolutePath}:${context.applicationInfo.nativeLibraryDir}:/system/lib64:/system/lib"
        } else {
            context.applicationInfo.nativeLibraryDir
        }
        val extra = mutableMapOf<String, String>()
        if (usr.isDirectory) {
            extra["PREFIX"] = usr.absolutePath
            extra["ZYRXON_PREFIX"] = usr.absolutePath
            val aptConf = File(usr, "etc/apt/apt.conf")
            if (aptConf.isFile) extra["APT_CONFIG"] = aptConf.absolutePath
            val dpkgDir = File(usr, "var/lib/dpkg")
            if (dpkgDir.isDirectory) extra["DPKG_ADMINDIR"] = dpkgDir.absolutePath
            val terminfo = File(usr, "share/terminfo")
            if (terminfo.isDirectory) extra["TERMINFO"] = terminfo.absolutePath
        }
        return mapOf(
            "PATH" to pathStr,
            "HOME" to home.absolutePath,
            "TMPDIR" to File(home, "tmp").absolutePath,
            "PWD" to home.absolutePath,
            "SHELL" to (if (sh.canExecute()) sh.absolutePath else shell(context)),
            "BUSYBOX" to (if (busyboxBin.canExecute()) busyboxBin.absolutePath else (binary(context)?.absolutePath ?: "")),
            "TERM" to "xterm-256color",
            "PYTHONUNBUFFERED" to "1",
            "DEBIAN_FRONTEND" to "readline",
            "STDBUF_OUTPUT" to "L",
            "LANG" to "en_US.UTF-8",
            "ANDROID_ROOT" to (System.getenv("ANDROID_ROOT") ?: "/system"),
            "ANDROID_DATA" to (System.getenv("ANDROID_DATA") ?: "/data"),
            "LD_LIBRARY_PATH" to ldStr
        ) + extra + HttpProxy.env()
    }

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

    fun run(
        context: Context,
        command: String,
        cwd: File? = null,
        timeoutMs: Long = 0,
        extraEnv: Map<String, String> = emptyMap()
    ): Result {
        ensureReady(context)
        val dir = (cwd ?: home(context)).apply { mkdirs() }
        val started = System.currentTimeMillis()
        return try {
            val pb = ProcessBuilder(shellArgs(context, command))
            pb.directory(dir)
            pb.environment().putAll(environment(context))
            pb.environment().putAll(extraEnv)
            pb.redirectErrorStream(false)
            val p = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { p.inputStream.bufferedReader().use { out.append(it.readText()) } }
            val tErr = Thread { p.errorStream.bufferedReader().use { err.append(it.readText()) } }
            tOut.start(); tErr.start()
            val finished = awaitProcess(p, timeoutMs)
            if (!finished) p.destroy()
            tOut.join(1000); tErr.join(1000)
                        val resCode = if (finished) p.exitValue() else -1
            if (resCode == 159 && mode != "none") {
                mode = "none"
                return run(context, command, cwd, timeoutMs, extraEnv)
            }
            Result(
                command = command,
                code = resCode,
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

    class LiveProcess(
        val input: java.io.OutputStream,
        private val process: Process
    ) {
        @Volatile private var asked = false

        fun interrupt() {
            if (asked) runCatching { process.destroyForcibly() }
            else {
                asked = true
                runCatching { process.destroy() }
            }
        }
    }

    fun runStreaming(
        context: Context,
        command: String,
        cwd: File? = null,
        timeoutMs: Long = 0,
        extraEnv: Map<String, String> = emptyMap(),
        onChunk: (stream: String, text: String) -> Unit
    ): Result = runStreaming(context, command, cwd, timeoutMs, extraEnv, onChunk, null)

    fun runStreaming(
        context: Context,
        command: String,
        cwd: File? = null,
        timeoutMs: Long = 0,
        extraEnv: Map<String, String> = emptyMap(),
        onChunk: (stream: String, text: String) -> Unit,
        onSpawn: ((LiveProcess) -> Unit)?
    ): Result {
        ensureReady(context)
        val dir = (cwd ?: home(context)).apply { mkdirs() }
        val started = System.currentTimeMillis()
        return try {
            val pb = ProcessBuilder(shellArgs(context, command))
            pb.directory(dir)
            pb.environment().putAll(environment(context))
            pb.environment().putAll(extraEnv)
            pb.redirectErrorStream(false)
            val p = pb.start()
            onSpawn?.invoke(LiveProcess(p.outputStream, p))
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { pump(p.inputStream, out, "out", onChunk) }
            val tErr = Thread { pump(p.errorStream, err, "err", onChunk) }
            tOut.start(); tErr.start()
            val finished = awaitProcess(p, timeoutMs)
            if (!finished) p.destroy()
            tOut.join(1000); tErr.join(1000)
                        val resCode = if (finished) p.exitValue() else -1
            if (resCode == 159 && mode != "none") {
                mode = "none"
                return runStreaming(context, command, cwd, timeoutMs, extraEnv, onChunk, onSpawn)
            }
            Result(
                command = command,
                code = resCode,
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

    private fun pump(
        stream: java.io.InputStream,
        sink: StringBuilder,
        streamName: String,
        onChunk: (String, String) -> Unit
    ) {
        try {
            stream.use { input ->
                val buf = ByteArray(512)
                while (true) {
                    val n = input.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val chunk = String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8)
                    synchronized(sink) { sink.append(chunk) }
                    runCatching { onChunk(streamName, chunk) }
                }
            }
        } catch (_: Exception) {}
    }

    fun describe(context: Context): String {
        val bb = binary(context)
        if (bb == null) return "Android ${Build.VERSION.RELEASE} · shell: /system/bin/sh (no bundled toolbox for ${Build.SUPPORTED_ABIS.firstOrNull()})"
        val applets = runApplets(context).size
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()} · " +
            "shell: bundled busybox, $applets applets · PATH ${binDir(context).absolutePath}"
    }
}
