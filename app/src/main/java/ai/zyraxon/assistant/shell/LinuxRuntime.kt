package ai.zyraxon.assistant.shell

import android.content.Context
import java.io.File

/**
 * ZYRAXON's own Linux userland, installed from the payload bundled inside the APK.
 *
 * This is a full Termux-style Unix system — `bash`, `apt`, `dpkg`, `git`, `python`,
 * `node`, `clang` — running on the phone with no network access at all. The
 * bootstrap archive and every package it needs ship in the APK's assets
 * (`assets/linux/<arch>/`), so the first launch installs a working system and
 * never downloads anything.
 *
 * ## Why it runs without root or Shizuku
 *
 * Android 10+ forbids `exec()` of a file inside the app's own data directory, so
 * the guest cannot simply be executed from where it is unpacked. Two facts make
 * that irrelevant:
 *
 * 1. Android *does* allow executing files in the app's native library directory.
 *    `proot` and its loader are shipped there under the `lib*.so` names the
 *    platform expects, exactly as the bundled `busybox` already is.
 * 2. `proot` never `exec()`s the guest program. It `exec()`s its own **loader** —
 *    which lives in the native library directory, so the kernel allows it — and
 *    that loader maps the guest program into memory. The blocked `execve` is
 *    never reached.
 *
 * So the whole system runs as the app's own user, with no Shizuku and no root,
 * and so does everything inside the guest: the guest sees the same non-root uid
 * that Termux itself runs as. Shizuku, when present, only adds the ability to
 * touch real system directories; it is never required for the Terminal to work.
 *
 * ## Why the guest prefix is not `/data/data/com.termux/...`
 *
 * The payload is a Termux bootstrap, so its binaries carry a hard-coded RUNPATH
 * and its symlinks point at `/data/data/com.termux/files/usr`. That path is an
 * upstream detail and is never shown to the user. The tree is bound at a clean
 * prefix (`/usr`) that `$PREFIX`, `$HOME` and `pwd` report, and *also* at the
 * canonical Termux path, purely so the binaries can still find their own
 * libraries. Nothing the user sees mentions Termux.
 */
object LinuxRuntime {

    /** The prefix the guest sees — what `$PREFIX`, `$HOME` and `pwd` show. */
    const val GUEST_PREFIX = "/usr"
    const val GUEST_HOME = "/home"

    /**
     * The path the payload's own binaries were built against. Kept only so the
     * dynamic linker can resolve their RUNPATH; never shown to the user.
     */
    private const val COMPAT_PREFIX = "/data/data/com.termux/files/usr"

    private const val MARKER = "SYMLINKS.txt"

    /** The app's native library directory — the one place Android lets us exec from. */
    fun nativeLib(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    /** proot itself, packaged as a native lib so it can be executed. */
    fun proot(context: Context): File = File(nativeLib(context), "libproot.so")

    /** proot's loader, handed to it through `PROOT_LOADER`. */
    fun loader(context: Context): File = File(nativeLib(context), "libproot-loader.so")

    /** The guest's prefix on the phone: the app's own storage, no root needed. */
    fun root(context: Context): File = File(context.filesDir, "usr")

    /** The guest's home directory. */
    fun home(context: Context): File = File(context.filesDir, "home")

    /** A writable scratch directory for proot's own bookkeeping. */
    private fun prootTmp(context: Context): File = File(context.filesDir, "proot-tmp").apply { mkdirs() }

    /** The directory the bootstrap and packages are unpacked through. */
    private fun stage(context: Context): File = File(context.cacheDir, "linux-stage").apply { mkdirs() }

    /**
     * The asset directory holding this device's payload, or null when the APK
     * ships nothing this device can run.
     */
    fun assetDir(context: Context): String? =
        pickAssetDir(android.os.Build.SUPPORTED_ABIS) { folder -> hasPayload(context, folder) }

    /** The selection rule, kept pure so it can be checked without an APK. */
    internal fun pickAssetDir(abis: Array<String>, exists: (String) -> Boolean): String? {
        for (abi in abis) {
            val folder = arch(abi) ?: continue
            if (exists(folder)) return "linux/$folder"
        }
        return null
    }

    private fun hasPayload(context: Context, folder: String): Boolean = try {
        context.assets.list("linux/$folder/debs").orEmpty().any { it.endsWith(".deb") }
    } catch (_: Exception) {
        false
    }

    /** The package name for an Android ABI, matching the vendored asset folders. */
    fun arch(abi: String): String? = when {
        abi.startsWith("arm64") -> "aarch64"
        abi.startsWith("x86_64") -> "x86_64"
        abi.startsWith("armeabi") -> "arm"
        abi.startsWith("x86") -> "i686"
        else -> null
    }

    /** True when this device has a payload and the proot toolchain to run it. */
    fun isSupported(context: Context): Boolean =
        assetDir(context) != null && proot(context).canExecute() && loader(context).canExecute()

    /**
     * True when the guest is in place and runnable. This asks the filesystem, not
     * Shizuku: the guest lives in the app's own storage, so its presence is all
     * that matters.
     */
    fun isInstalled(context: Context): Boolean {
        val bash = File(root(context), "bin/bash")
        val marker = File(root(context), MARKER)
        if (!(bash.isFile && marker.isFile && proot(context).canExecute())) return false
        // An install writes DONE as its very last step, so a run that was killed
        // half-way (the app swiped away, storage full) is never mistaken for a
        // finished one. A guest installed by an older build has no DONE file but
        // does carry the package manager, which only a complete install unpacks.
        return File(root(context), DONE).isFile || File(root(context), "bin/dpkg").isFile
    }

    private const val DONE = ".zyraxon-installed"

    /** One install at a time: startup, the Terminal and the tool all call [install]. */
    private val installLock = Any()

    /** Free bytes in the app's storage, or -1 when it cannot be read. */
    private fun freeBytes(context: Context): Long = try { context.filesDir.usableSpace } catch (_: Exception) { -1L }

    /**
     * Installs the bundled userland into the app's own storage. Nothing here
     * touches the network, and nothing here needs root or Shizuku: the bootstrap
     * and every `.deb` come from the APK and are written as the app.
     *
     * [log] receives one line per step so the Terminal shows the install live.
     * Every line is plain English — a real terminal does not speak Bengali.
     */
    fun install(context: Context, log: (String) -> Unit): Boolean = synchronized(installLock) {
        installLocked(context, log)
    }

    private fun installLocked(context: Context, log: (String) -> Unit): Boolean {
        if (isInstalled(context)) {
            log("Linux is already installed.")
            return true
        }
        val assets = assetDir(context)
        if (assets == null) {
            log("This device's architecture (${android.os.Build.SUPPORTED_ABIS.joinToString()}) has no Linux payload in the APK.")
            return false
        }
        if (!proot(context).canExecute() || !loader(context).canExecute()) {
            log("proot is missing from the native libraries; this build cannot run Linux.")
            return false
        }

        val free = freeBytes(context)
        if (free in 0..(700L * 1024 * 1024)) {
            log("Not enough free storage: ${free / (1024 * 1024)} MB free, about 700 MB needed. Free some space and open the Terminal again.")
            return false
        }
        // A half-finished earlier attempt is wiped, so unpacking starts clean
        // instead of layering new files over a broken tree.
        if (root(context).exists()) {
            log("Cleaning up an incomplete earlier install…")
            root(context).deleteRecursively()
        }
        val root = root(context).apply { mkdirs() }
        log("Installing ZYRAXON Linux (${android.os.Build.SUPPORTED_ABIS.firstOrNull()})…")
        log("Payload: $assets (bundled in the APK)")

        // The bootstrap archive *is* the prefix: its top level is `bin/`, `lib/`,
        // `SYMLINKS.txt`. A deb wraps its payload under an absolute Termux path
        // that is stripped, so both land directly under the prefix.
        log("Unpacking the bundled system…")
        if (!unzipAsset(context, "$assets/bootstrap.zip", root, log)) return false
        applySymlinks(context, File(root, MARKER), root, log)

        log("Installing bundled packages…")
        val installed = installBundledDebs(context, assets, root, log)
        log(" $installed packages ready")

        // First-run setup: HOME, TMPDIR and the guest's own tmp.
        File(root, "tmp").mkdirs()
        home(context).mkdirs()

        try { File(root, DONE).writeText(System.currentTimeMillis().toString()) } catch (_: Exception) {}
        val ready = isInstalled(context)
        log(if (ready) "ZYRAXON Linux is ready. Run a command with: linux <command>"
            else "Install did not finish; open the Terminal again to retry.")
        return ready
    }

    /**
     * Unpacks every bundled `.deb` into [dest]. The debs are listed in the APK
     * itself, so the set installed is exactly the set that shipped.
     */
    private fun installBundledDebs(
        context: Context,
        assets: String,
        dest: File,
        log: (String) -> Unit
    ): Int {
        val work = stage(context)
        val names: Array<String> = try {
            context.assets.list("$assets/debs").orEmpty().filter { it.endsWith(".deb") }.sorted().toTypedArray()
        } catch (_: Exception) {
            emptyArray()
        }
        if (names.isEmpty()) {
            log(" No packages found in the APK")
            return 0
        }
        var done = 0
        for (name in names) {
            val deb = File(work, name)
            if (!copyAsset(context, "$assets/debs/$name", deb, log)) continue
            if (extractDeb(context, deb, dest, log)) done++
            deb.delete()
        }
        return done
    }

    /** Copies one APK asset to [dest]. The payload never leaves the device. */
    private fun copyAsset(context: Context, path: String, dest: File, log: (String) -> Unit): Boolean = try {
        dest.parentFile?.mkdirs()
        context.assets.open(path).use { input ->
            dest.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        }
        true
    } catch (e: Exception) {
        log(" Could not open asset ($path): ${e.message}")
        false
    }

    /**
     * Extracts a `.deb` and merges its payload into [dest].
     *
     * A `.deb` is an `ar` archive holding `control.tar.xz` and `data.tar.xz`. The
     * bundled busybox has no `ar` applet, so the archive is walked here in Kotlin.
     * The members then go to the busybox applets that do exist: `unxz` and `tar`.
     */
    private fun extractDeb(context: Context, deb: File, dest: File, log: (String) -> Unit): Boolean {
        return try {
            val bb = ShellRuntime.command(context)?.absolutePath ?: run {
                log(" busybox not found"); return false
            }
            val work = File(deb.parentFile, deb.nameWithoutExtension).apply { deleteRecursively(); mkdirs() }
            val payload = readArMember(deb, "data.tar") ?: return false
            val tar = File(work, "data.tar")
            when {
                payload.name.endsWith(".xz") -> {
                    val xz = File(work, "data.tar.xz")
                    xz.writeBytes(payload.bytes)
                    if (!unxz(bb, xz, tar)) return false
                }
                payload.name.endsWith(".gz") -> {
                    val gz = File(work, "data.tar.gz")
                    gz.writeBytes(payload.bytes)
                    if (!gunzip(bb, gz, tar)) return false
                }
                else -> tar.writeBytes(payload.bytes)
            }
            val extract = File(work, "x").apply { mkdirs() }
            if (!run(bb, work, "tar", "xf", tar.absolutePath, "-C", extract.absolutePath)) return false
            // The payload stores files under an absolute Termux data path. That is
            // an upstream detail, so the `files` directory is located, not guessed.
            val payloadRoot = findPayloadRoot(extract)
            if (payloadRoot != null) payloadRoot.copyRecursively(dest, overwrite = true)
            work.deleteRecursively()
            true
        } catch (e: Exception) {
            log(" Could not unpack deb (${deb.name}): ${e.message}")
            false
        }
    }

    /** Finds `data/data/<pkg>/files` inside an extracted deb, whatever the name. */
    private fun findPayloadRoot(extract: File): File? {
        val data = File(extract, "data/data")
        val pkg = data.listFiles()?.firstOrNull() ?: return null
        val files = File(pkg, "files")
        return if (files.isDirectory) files else null
    }

    internal data class ArMember(val name: String, val bytes: ByteArray)

    /**
     * Reads the first `ar` member whose name starts with [prefix].
     *
     * Fixed-width text format: an 8-byte magic, then 60-byte headers each followed
     * by the member's bytes padded to an even length. Names end with `/`; the GNU
     * special members `/` and `//` are skipped. Pure, so it is testable.
     */
    internal fun readArMember(file: File, prefix: String): ArMember? {
        val all = file.readBytes()
        if (all.size < 8 || String(all, 0, 8, Charsets.US_ASCII) != "!<arch>\n") return null
        var i = 8
        while (i + 60 <= all.size) {
            val header = String(all, i, 60, Charsets.US_ASCII)
            i += 60
            val name = header.substring(0, 16).trim().removeSuffix("/")
            val size = header.substring(48, 58).trim().toIntOrNull() ?: return null
            if (size < 0 || i + size > all.size) return null
            if (name.isEmpty() || name == "//" || name == "/") {
                i += size + (size % 2)
                continue
            }
            if (name.startsWith(prefix)) return ArMember(name, all.copyOfRange(i, i + size))
            i += size + (size % 2)
        }
        return null
    }

    private fun unxz(bb: String, xz: File, out: File): Boolean {
        if (!run(bb, xz.parentFile!!, "unxz", "-f", xz.absolutePath)) return false
        val produced = File(xz.parentFile, xz.name.removeSuffix(".xz"))
        return produced.exists() && produced.renameTo(out)
    }

    private fun gunzip(bb: String, gz: File, out: File): Boolean {
        if (!run(bb, gz.parentFile!!, "gunzip", "-f", gz.absolutePath)) return false
        val produced = File(gz.parentFile, gz.name.removeSuffix(".gz"))
        return produced.exists() && produced.renameTo(out)
    }

    private fun run(bb: String, dir: File, vararg args: String): Boolean = try {
        val p = ProcessBuilder(listOf(bb) + args).directory(dir)
            .redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    /** Unzips the bootstrap straight out of the APK, so it is never staged on disk. */
    private fun unzipAsset(context: Context, asset: String, dest: File, log: (String) -> Unit): Boolean {
        return try {
            context.assets.open(asset).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    var count = 0
                    while (entry != null) {
                        val out = File(dest, entry.name)
                        // A crafted archive could contain `../`; anything that would
                        // escape the destination is dropped.
                        if (!out.canonicalPath.startsWith(dest.canonicalPath)) {
                            entry = zis.nextEntry
                            continue
                        }
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zis.copyTo(it, 1 shl 16) }
                            count++
                        }
                        entry = zis.nextEntry
                    }
                    log(" $count files")
                }
            }
            true
        } catch (e: Exception) {
            log(" Unpack failed: ${e.message}")
            false
        }
    }

    /**
     * A zip cannot hold symlinks, so the bootstrap ships them as a `target←link`
     * text file. Without them `sh`, `env` and most applets do not exist.
     *
     * The links are made with the bundled busybox rather than
     * `java.nio.file.Files`, which is API 26+ and this app supports API 21. Any
     * absolute target that points at the payload's build path is rewritten to the
     * prefix the guest actually sees, so a link never depends on the compat bind.
     */
    private fun applySymlinks(context: Context, manifest: File, dest: File, log: (String) -> Unit) {
        if (!manifest.exists()) return
        val bb = ShellRuntime.command(context)?.absolutePath ?: return
        var made = 0
        manifest.readLines().forEach { raw ->
            val line = raw.trim()
            val sep = line.indexOf('←')
            if (sep <= 0) return@forEach
            val target = guestTarget(line.substring(0, sep))
            val link = File(dest, line.substring(sep + 1).removePrefix("./"))
            link.parentFile?.mkdirs()
            // `-f` replaces an existing link, so there is nothing to clean up first.
            if (run(bb, dest, "ln", "-sf", target, link.absolutePath)) made++
        }
        log(" $made symlinks")
    }

    /**
     * Runs a command inside the installed guest, streaming its output live.
     *
     * `proot` is exec'd from the native library directory (allowed), and it in turn
     * execs its loader from the same place, so no blocked `execve` is ever made.
     * The guest tree is bound at a clean prefix (`/usr`) that the user sees, and
     * again at the payload's canonical path so the binaries find their own
     * libraries.
     *
     * The identity is left alone — no `-0`, no `--change-id`. The guest runs as
     * the app's own uid, which is the only uid Android gives it, and that is
     * exactly what Termux does: a `termux`-type container in proot-distro is
     * never given a fake root. This is also what lets the guest's `pkg` and `apt`
     * work at all, since they refuse to run when `id -u` is 0 and treat that as a
     * mistake rather than a privilege.
     */
    fun runStreaming(
        context: Context,
        command: String,
        onChunk: (stream: String, text: String) -> Unit,
        onSpawn: ((ShellRuntime.LiveProcess) -> Unit)? = null,
        timeoutMs: Long = 0
    ): ShellRuntime.Result? {
        if (!isInstalled(context)) return null
        val native = nativeLib(context).absolutePath
        val real = root(context).absolutePath
        val realHome = home(context).absolutePath
        val proot = proot(context).absolutePath
        val loader = loader(context).absolutePath
        val tmp = prootTmp(context).absolutePath

        val line = buildString {
            append("PATH=$native:$real/bin:/system/bin ")
            // The guest's own libraries only. Pointing this at the native library
            // directory would put Android's `libc.so` on the guest linker's search
            // path, and a guest binary that then loads it dies on the spot. proot
            // itself finds its own libraries through the `$ORIGIN` runpath, so it
            // does not need this to include them.
            append("LD_LIBRARY_PATH=$GUEST_PREFIX/lib ")
            append("PROOT_LOADER=$loader PROOT_TMP_DIR=$tmp ")
            append("PREFIX=$GUEST_PREFIX HOME=$GUEST_HOME TMPDIR=$GUEST_PREFIX/tmp ")
            append("TERM=xterm-256color LANG=C.UTF-8 ")
            // `--kill-on-exit` reaps the whole traced tree when the command ends.
            // Without it a killed command leaves the guest's background children
            // behind, and proot is holding the pipes open, so the Terminal hangs
            // even though the command itself already finished.
            append("$proot --kill-on-exit ")
            // `--link2symlink` emulates hard links with symlinks. `dpkg` unpacks
            // its control files as hard links, so without this an install fails
            // partway through and leaves the package database inconsistent. This
            // is why proot-distro turns it on for every container it runs.
            append("--link2symlink ")
            // `-L` corrects the size `lstat` reports for a symlink. `dpkg` uses
            // that to decide whether a path is a link it must replace, and the
            // wrong answer makes it warn and mis-handle the file.
            append("-L ")
            // Some programs use SysV IPC. Android's kernel allows it and the
            // guest binaries expect it, so the extension is left on, as it is in
            // proot-distro's default (non-minimal) mode.
            append("--sysvipc ")
            append("-r / ")
            append("-b /dev -b /proc -b /sys -b /storage -b /system ")
            append("-b $real:$GUEST_PREFIX ")
            append("-b $real:$COMPAT_PREFIX ")
            append("-b $realHome:$GUEST_HOME ")
            append("-b $real/tmp:/tmp ")
            append("-w $GUEST_HOME ")
            // Inside the guest the prefix is `/usr`, so PATH is set there rather
            // than inherited: a host path in `$PATH` would leak the app's storage
            // layout into a shell the user is looking at.
            append("$GUEST_PREFIX/bin/bash -lc ${q("export PATH=$GUEST_PREFIX/bin:/system/bin; $command")}")
        }
        // No deadline by default. A real terminal does not put a clock on the
        // command you typed, and neither does Termux's interactive session:
        // `apt upgrade`, `git clone` and a long build all run to completion. A
        // deadline here was the thing that cut work off halfway. A caller that
        // expects a hang may pass its own `timeoutMs`.
        return ShellRuntime.runStreaming(
            context, line,
            timeoutMs = timeoutMs,
            onChunk = onChunk,
            onSpawn = onSpawn
        )
    }

    /**
     * Rewrites a symlink target that points at the payload's build path into the
     * prefix the guest sees. A target that is relative, or already clean, is
     * returned unchanged. Pure, so the rule can be checked off-device.
     */
    internal fun guestTarget(target: String): String =
        if (target.startsWith(COMPAT_PREFIX)) GUEST_PREFIX + target.removePrefix(COMPAT_PREFIX) else target

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
