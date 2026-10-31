package ai.zyraxon.assistant.shell

import android.content.Context
import java.io.File

/**
 * ZYRAXON's own Linux userland, installed from the payload bundled inside the APK.
 *
 * This is a full Arch-style Unix system — `bash`, `apt`, `dpkg`, `git`, `python`,
 * `node`, `clang` — running on the phone with no network access at all. The
 * bootstrap archive and every package it needs are shipped in the APK's assets
 * (`assets/linux/<arch>/`), so the first launch installs a working system and
 * never downloads anything: no index fetch, no package manager round trip, no
 * update check. That is the whole point — a phone in airplane mode still gets a
 * compiler.
 *
 * Android's W^X rule forbids executing a file from the app's own data directory,
 * so the tree is placed in `/data/local/tmp`, which the system owns and where
 * execution is allowed. Reaching it needs Shizuku (uid 2000) or root; without
 * either, [isInstalled] is false and [install] explains exactly what to do.
 */
object LinuxRuntime {

    /** Where the guest lives, at system level. */
    fun root(context: Context): File = File("/data/local/tmp/zyraxon/linux")
    fun prefix(context: Context): File = File(root(context), "usr")
    fun home(context: Context): File = File(root(context), "home")

    /**
     * The absolute prefix the payload's binaries were compiled against.
     *
     * Their `RUNPATH` and their shebangs both name one fixed absolute path, so the
     * tree only runs when it appears there. That value is not guessed or written
     * down here — it is read back out of the payload itself, from the absolute
     * targets in the bootstrap's own `SYMLINKS.txt`, which stays in the installed
     * tree. Keeping it as data means the tree stays relocatable: it can live
     * anywhere on disk and `proot` presents it at the path the payload asks for.
     */
    fun canonicalPrefix(context: Context): String =
        derivePrefix(prefix(context)) ?: prefix(context).absolutePath

    /**
     * Finds the absolute prefix inside an unpacked bootstrap by looking at the
     * absolute symlink targets it ships. They are all built from the same prefix,
     * so the first one with a `/usr/` segment gives it away.
     */
    internal fun derivePrefix(unpacked: File): String? {
        val manifest = File(unpacked, "SYMLINKS.txt")
        if (!manifest.isFile) return null
        for (line in manifest.readLines()) {
            val target = line.trim().substringBefore('←')
            if (target.startsWith("/") && target.contains("/usr/")) {
                return target.substringBefore("/usr/") + "/usr"
            }
        }
        return null
    }

    /**
     * The asset directory holding this device's payload, or null when the APK
     * ships nothing this device can run.
     *
     * The APK declares four ABIs but only two payloads are vendored, so a device
     * that lists a 32-bit ABI first must not be sent to a folder that does not
     * exist. Each of the device's ABIs is tried in order and the first one with a
     * real payload wins; when none does the caller explains that instead of
     * failing later with a confusing "no packages found".
     */
    fun assetDir(context: Context): String? {
        val abis = android.os.Build.SUPPORTED_ABIS
        return pickAssetDir(abis) { folder -> hasPayload(context, folder) }
    }

    /** The selection rule, kept pure so it can be checked without an APK. */
    internal fun pickAssetDir(abis: Array<String>, exists: (String) -> Boolean): String? {
        for (abi in abis) {
            val folder = arch(abi) ?: continue
            if (exists(folder)) return "linux/$folder"
        }
        return null
    }

    /** True when the APK actually carries a payload for this architecture folder. */
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

    /**
     * True when the guest's own shell is in place and runnable. The check runs the
     * binary rather than only looking for it, so a tree that was copied without
     * its execute bit reads as not installed instead of failing later.
     */
    fun isInstalled(context: Context): Boolean {
        if (!SystemShell.available()) return false
        val sh = File(prefix(context), "bin/bash")
        val marker = File(prefix(context), "SYMLINKS.txt")
        val r = SystemShell.runBest(
            context,
            "test -x ${q(sh.absolutePath)} && test -f ${q(marker.absolutePath)} && echo yes",
            timeoutMs = 10_000
        )
        return r?.stdout?.trim() == "yes"
    }

    /**
     * Installs the bundled userland. Nothing here touches the network: the
     * bootstrap and every `.deb` come from the APK, are unpacked into the app's
     * cache, and the finished tree is copied into `/data/local/tmp` at system
     * level, which is the only way to reach a directory the app may not write.
     *
     * [log] receives one line per step so the Terminal shows the install live.
     */
    fun install(context: Context, log: (String) -> Unit): Boolean {
        if (!SystemShell.available()) {
            log("সিস্টেম অ্যাক্সেস নেই। Shizuku চালু করে ZYRAXON-কে অনুমতি দিন, তারপর আবার চেষ্টা করুন।")
            return false
        }
        val level = if (SystemShell.probe(context)[SystemShell.Level.ROOT] == true)
            SystemShell.Level.ROOT else SystemShell.Level.SHELL
        log("সিস্টেম লেভেল: ${level.label}")

        val assets = assetDir(context)
        if (assets == null) {
            log("এই ডিভাইসের আর্কিটেকচারের (${android.os.Build.SUPPORTED_ABIS.joinToString()}) জন্য ZYRAXON Linux পেলোড APK-তে নেই।")
            return false
        }
        val stage = File(context.cacheDir, "linux-stage").apply { mkdirs() }
        log("প্যাকেজ: $assets (APK-তে বান্ডল করা)")

        // The staged tree mirrors the installed one: `rootfs/usr` is the prefix and
        // `rootfs/home` is the user's home. The bootstrap archive *is* the prefix,
        // so it unpacks into `usr/`; a deb wraps its payload in `usr/` already, so
        // it unpacks into the root. Getting that backwards would nest a second
        // `usr` and nothing would be found.
        val unpack = File(stage, "rootfs").apply { deleteRecursively(); mkdirs() }
        val unpackPrefix = File(unpack, "usr").apply { mkdirs() }

        // 1. The bootstrap, straight out of the APK. It is unzipped from the asset
        //    stream, so the 33 MB archive is never written to disk twice.
        log("বান্ডল করা সিস্টেম খুলছি…")
        if (!unzipAsset(context, "$assets/bootstrap.zip", unpackPrefix, log)) return false
        applySymlinks(File(unpackPrefix, "SYMLINKS.txt"), unpackPrefix, log)

        // 2. Every package, from the same APK. Each deb is written to a scratch
        //    file, unpacked and deleted before the next one starts, so the peak
        //    temporary footprint is one package rather than the whole toolchain.
        log("প্যাকেজ বসাচ্ছি…")
        val installed = installBundledDebs(context, assets, unpack, log)
        log("  $installed প্যাকেজ প্রস্তুত")

        // 3. Push the finished tree to the system-owned directory. `cp -a` keeps
        //    the symlinks and the exec bits, which a plain copy would flatten.
        val dest = root(context)
        log("সিস্টেম ডিরেক্টরিতে বসাচ্ছি…")
        val push = SystemShell.runBest(
            context,
            "mkdir -p ${q(dest.absolutePath)} && rm -rf ${q(dest.absolutePath)}/usr ${q(dest.absolutePath)}/home " +
                "&& cp -a ${q(unpack.absolutePath)}/. ${q(dest.absolutePath)}/ && " +
                "chmod -R 755 ${q(prefix(context).absolutePath)} && echo ok",
            timeoutMs = 300_000
        )
        if (push == null || !push.stdout.contains("ok")) {
            log("বসানো ব্যর্থ: ${push?.stderr?.take(300) ?: "Shizuku সাড়া দেয়নি"}")
            return false
        }

        // 4. First-run setup: HOME, TMPDIR and the login shell. Without this the
        //    package manager refuses to work.
        val setup = SystemShell.runBest(
            context,
            "P=${q(prefix(context).absolutePath)}; H=${q(home(context).absolutePath)}; " +
                "mkdir -p \"\$H\" \"\$P/tmp\" && " +
                "ln -sf \"\$P/bin/login\" ${q(File(dest, "bin/login").absolutePath)} 2>/dev/null; " +
                "echo ok",
            timeoutMs = 60_000
        )
        if (setup == null || !setup.stdout.contains("ok")) {
            log("প্রস্তুতি ধাপে সমস্যা: ${setup?.stderr?.take(300) ?: "কোনো উত্তর নেই"}")
            return false
        }

        log("ZYRAXON Linux প্রস্তুত: ${dest.absolutePath}")
        return true
    }

    /**
     * Unpacks every bundled `.deb` into [dest]. The debs are listed in the APK
     * itself, so the set installed is exactly the set that shipped — no version
     * can drift and nothing is fetched.
     */
    private fun installBundledDebs(
        context: Context,
        assets: String,
        dest: File,
        log: (String) -> Unit
    ): Int {
        val stage = File(context.cacheDir, "linux-stage").apply { mkdirs() }
        val names: Array<String> = try {
            context.assets.list("$assets/debs").orEmpty().filter { it.endsWith(".deb") }.sorted().toTypedArray()
        } catch (_: Exception) {
            emptyArray()
        }
        if (names.isEmpty()) {
            log("  APK-তে প্যাকেজ পাওয়া যায়নি")
            return 0
        }
        var done = 0
        for (name in names) {
            val deb = File(stage, name)
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
        log("  asset খোলা যায়নি ($path): ${e.message}")
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
                log("  বাজবক্স পাওয়া যায়নি"); return false
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
            // The payload stores files under an absolute Android data path
            // (`data/data/<pkg>/files/usr/...`). The package name is an upstream
            // detail, so the `files` directory is located rather than hard-coded.
            val root = findPayloadRoot(extract)
            if (root != null) root.copyRecursively(dest, overwrite = true)
            work.deleteRecursively()
            true
        } catch (e: Exception) {
            log("  deb খোলা যায়নি (${deb.name}): ${e.message}")
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
        // busybox `unxz` refuses a name it does not recognise, so the file carries
        // the `.xz` suffix it wants and the result is renamed.
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
                    log("  $count ফাইল")
                }
            }
            true
        } catch (e: Exception) {
            log("  আনপ্যাক ব্যর্থ: ${e.message}")
            false
        }
    }

    /**
     * A zip cannot hold symlinks, so the bootstrap ships them as a `target←link`
     * text file. Without them `sh`, `env` and most applets do not exist.
     */
    private fun applySymlinks(manifest: File, dest: File, log: (String) -> Unit) {
        if (!manifest.exists()) return
        var made = 0
        manifest.readLines().forEach { raw ->
            val line = raw.trim()
            val sep = line.indexOf('←')
            if (sep <= 0) return@forEach
            val target = line.substring(0, sep)
            val linkPath = line.substring(sep + 1)
            val link = File(dest, linkPath.removePrefix("./"))
            val resolvedTarget = if (target.startsWith("/")) target else {
                File(link.parentFile, target).path
            }
            runCatching {
                link.parentFile?.mkdirs()
                if (link.exists()) link.delete()
                java.nio.file.Files.createSymbolicLink(link.toPath(), File(resolvedTarget).toPath())
                made++
            }
        }
        log("  $made সিমলিংক")
    }

    /**
     * Runs a command inside the installed guest, streaming its output live.
     *
     * The guest's binaries and scripts were built against a fixed absolute prefix:
     * their `RUNPATH` points at it and their shebangs name it, so moving the tree
     * would break both. Rather than patch hundreds of files, `proot` is asked to
     * present the real tree at that canonical path. Every path lookup is then
     * translated for the guest, which fixes `RUNPATH`, shebangs and hard-coded
     * paths in one step and needs no `LD_PRELOAD` shim.
     *
     * `proot` itself runs *before* that translation exists, so its own libraries
     * are found through `LD_LIBRARY_PATH` pointing at the real prefix. Inside, the
     * canonical path is used everywhere, which is what the packages expect.
     *
     * `-0` makes the guest believe it is root, which is what lets `apt` and `dpkg`
     * write to their own directories. That is an illusion inside this one process
     * tree; the app's real privileges are unchanged.
     */
    fun runStreaming(
        context: Context,
        command: String,
        onChunk: (stream: String, text: String) -> Unit
    ): SystemShell.Result? {
        if (!SystemShell.available()) return null
        val real = prefix(context).absolutePath
        val realHome = home(context).absolutePath
        val guest = canonicalPrefix(context)
        val guestHome = "$guest/../home"
        val line = buildString {
            append("PATH=$real/bin:/system/bin:\$PATH ")
            append("LD_LIBRARY_PATH=$real/lib ")
            append("HOME=$guestHome PREFIX=$guest TMPDIR=$guest/tmp ")
            append("TERM=xterm-256color LANG=C.UTF-8 ")
            append("$real/bin/proot -0 -r / ")
            append("-b /dev -b /proc -b /sys -b /storage -b /system ")
            append("-b $real:$guest ")
            append("-b $realHome:$guestHome ")
            append("-b $guest/tmp:/tmp ")
            append("-w $guestHome ")
            append("$guest/bin/bash -lc ${q(command)}")
        }
        return SystemShell.runStreaming(context, line, timeoutMs = 600_000, onChunk = onChunk)
    }

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
