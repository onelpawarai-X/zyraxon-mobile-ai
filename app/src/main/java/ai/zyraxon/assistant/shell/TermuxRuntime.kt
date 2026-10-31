package ai.zyraxon.assistant.shell

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * The real Termux userland, installed and run at system level.
 *
 * Termux is not a shell in an app — it is a full Arch-style userland: an
 * official bootstrap of ~4,000 files (`bash`, `apt`, `dpkg`, `git`, `python`,
 * `node`, `openssl`, `clang`) plus `proot`, which fakes `chroot` so `apt install`
 * works without root. The one reason an ordinary app cannot host it is Android's
 * W^X rule: a file in the app's own data directory may not be executed. That is
 * why Termux pins `targetSdkVersion` to 28, and why ZYRAXON — a modern app —
 * cannot simply unzip Termux into its sandbox.
 *
 * The way out is the same way Termux itself gets its powers on a modern device:
 * run it from a directory the *system* owns. `/data/local/tmp` is owned by the
 * `shell` user, is executable, and is exactly where `adb push` puts things. With
 * Shizuku (uid 2000, no root needed) or root, ZYRAXON installs the untouched
 * Termux bootstrap and the untouched Termux `proot` there and runs them.
 *
 * Because it runs at `shell` or `root` rather than as an app uid, the guest can
 * reach more than Termux itself can: `/data/local/tmp` is writable, Android's own
 * `system` binaries are on the guest's PATH, and at root level there is no wall
 * at all. That is the "more powerful than Termux" part — it is Termux's own
 * system, started from a stronger place.
 *
 * Every step reports what really happened, so a failure on a particular phone
 * shows up as text in the Terminal instead of a silent no-op.
 */
object TermuxRuntime {

    /** Termux's official package index, the source Termux's own `pkg` uses. */
    private const val APT = "https://packages.termux.dev/apt/termux-main"

    /**
     * The bootstrap archive, the same one the Termux app ships. The `-apt` build
     * carries `apt`/`dpkg` ready to use, so `pkg install` works offline-first.
     */
    private const val BOOTSTRAP_BASE =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.10.25-r1%2Bapt.android-7"

    /** Where the guest lives, at system level. */
    fun root(context: Context): File = File("/data/local/tmp/zyraxon/termux")
    fun prefix(context: Context): File = File(root(context), "usr")
    fun home(context: Context): File = File(root(context), "home")

    /** True when the guest's `login` binary is in place. */
    fun isInstalled(context: Context): Boolean {
        if (!SystemShell.available()) return false
        val login = File(prefix(context), "bin/login")
        val r = SystemShell.runBest(context, "test -x ${q(login.absolutePath)} && echo yes", timeoutMs = 10_000)
        return r?.stdout?.trim() == "yes"
    }

    /**
     * Installs the Termux userland. Runs the whole sequence at system level:
     * download to the app's cache, unpack there with the app's own busybox, copy
     * the tree into `/data/local/tmp` (which preserves the symlinks Termux needs),
     * then finish Termux's own first-run steps.
     *
     * [log] receives one human-readable line per step, so the Terminal shows the
     * install as it happens.
     */
    fun install(context: Context, log: (String) -> Unit): Boolean {
        if (!SystemShell.available()) {
            log("সিস্টেম অ্যাক্সেস নেই। Shizuku চালু করে ZYRAXON-কে অনুমতি দিন, তারপর আবার চেষ্টা করুন।")
            return false
        }
        val level = if (SystemShell.probe(context)[SystemShell.Level.ROOT] == true)
            SystemShell.Level.ROOT else SystemShell.Level.SHELL
        log("সিস্টেম লেভেল: ${level.label}")

        val stage = File(context.cacheDir, "termux-stage").apply { mkdirs() }
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val arch = termuxArch(abi)
        log("আর্কিটেকচার: $arch")

        // 1. The official bootstrap.
        val zip = File(stage, "bootstrap.zip")
        if (!zip.exists() || zip.length() < 1_000_000) {
            log("অফিসিয়াল Termux bootstrap নামাচ্ছি…")
            if (!download("$BOOTSTRAP_BASE/bootstrap-$arch.zip", zip, log)) return false
        }
        val unpack = File(stage, "rootfs").apply { deleteRecursively(); mkdirs() }
        log("আনপ্যাক করছি…")
        if (!unzipInto(zip, unpack, log)) return false
        applySymlinks(File(unpack, "SYMLINKS.txt"), unpack, log)

        // 2. Termux's own proot, with the two libraries it links against.
        val prootDir = File(unpack, "usr")
        for (pkg in listOf("proot", "libtalloc", "libandroid-shmem")) {
            val deb = File(stage, "$pkg.deb")
            if (!deb.exists()) {
                val url = debUrl(pkg, arch) ?: run { log("$pkg এর ঠিকানা পাওয়া যায়নি"); return false }
                log("$pkg নামাচ্ছি…")
                if (!download(url, deb, log)) return false
            }
            if (!extractDeb(context, deb, unpack, log)) return false
        }
        prootDir.mkdirs()

        // 3. Push the whole tree to the system-owned directory. `cp -a` keeps the
        //    symlinks and the exec bits, which a plain copy would flatten.
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

        // 4. Termux's own first-run setup: HOME, TMPDIR, the login shell, and the
        //    fake kernel release dpkg expects. Without this apt refuses to work.
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

        log("Termux ইনস্টল সম্পূর্ণ: ${dest.absolutePath}")
        return true
    }

    /**
     * Runs a command inside the installed Termux guest, at system level, with the
     * output streamed as it is produced.
     *
     * The `proot` line is the one Termux's own `proot-distro` builds, minus the
     * distro rootfs: fake kernel release, `--link2symlink` (so `apt` can make the
     * links it wants), `--sysvipc`, `--kill-on-exit`, `-L`, and the baseline
     * `/dev`, `/proc`, `/sys` binds.
     */
    fun runStreaming(
        context: Context,
        command: String,
        onChunk: (stream: String, text: String) -> Unit
    ): SystemShell.Result? {
        if (!SystemShell.available()) return null
        val prefix = prefix(context).absolutePath
        val home = home(context).absolutePath
        val guest = root(context).absolutePath
        val proot = "$prefix/bin/proot"
        val shell = "$prefix/bin/bash"
        val line = buildString {
            append("env -i ")
            append("HOME=$home ")
            append("PREFIX=$prefix ")
            append("TMPDIR=$prefix/tmp ")
            append("PATH=$prefix/bin:$prefix/bin/applets:/system/bin:/system/xbin:/apex/com.android.runtime/bin ")
            append("LD_LIBRARY_PATH=$prefix/lib ")
            append("TERM=xterm-256color ")
            append("LANG=C.UTF-8 ")
            append("ANDROID_ROOT=/system ANDROID_DATA=/data ")
            append("$proot ")
            append("--kill-on-exit --link2symlink --sysvipc -L ")
            append("--kernel-release=\\\\Linux\\\\localhost\\\\4.14.0\\\\${unameMachine()} \\\\localdomain\\\\-1\\\\ ")
            append("--rootfs=$guest ")
            append("--cwd=$home ")
            append("--bind=/dev --bind=/proc --bind=/sys ")
            append("--bind=/data/local/tmp:/data/local/tmp ")
            append("--bind=$prefix/tmp:/tmp ")
            append("--bind=/storage:/storage ")
            append("--bind=/system:/system ")
            append("$shell -lc ${shq(command)}")
        }
        return SystemShell.runStreaming(context, line, timeoutMs = 600_000, onChunk = onChunk)
    }

    // ---- helpers ------------------------------------------------------------

    /** The Termux architecture name for an Android ABI. */
    fun termuxArch(abi: String): String = when {
        abi.startsWith("arm64") -> "aarch64"
        abi.startsWith("armeabi") -> "arm"
        abi.startsWith("x86_64") -> "x86_64"
        abi.startsWith("x86") -> "i686"
        else -> "aarch64"
    }

    /** The uname machine string Termux's proot reports to the guest. */
    private fun unameMachine(): String = when (termuxArch(
        android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    )) {
        "aarch64" -> "aarch64"; "arm" -> "armv8l"; "x86_64" -> "x86_64"; else -> "i686"
    }

    /** The `Filename:` of a package in Termux's own index, or null. */
    private fun debUrl(pkg: String, arch: String): String? = try {
        val index = URL("$APT/dists/stable/main/binary-$arch/Packages").openStream()
            .bufferedReader().use { it.readText() }
        val block = index.split("\n\n").firstOrNull {
            it.lineSequence().any { l -> l == "Package: $pkg" }
        }
        val file = block?.lineSequence()
            ?.firstOrNull { it.startsWith("Filename: ") }
            ?.removePrefix("Filename: ")
        file?.let { "$APT/$it" }
    } catch (_: Exception) {
        null
    }

    private fun download(url: String, dest: File, log: (String) -> Unit): Boolean = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }
        conn.inputStream.use { input ->
            dest.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        }
        log("  ${dest.name} — ${dest.length() / 1024 / 1024} MB")
        true
    } catch (e: Exception) {
        log("  ডাউনলোড ব্যর্থ (${dest.name}): ${e.message}")
        false
    }

    /** Unpacks the bootstrap zip, including the empty directories it lists. */
    private fun unzipInto(zip: File, dest: File, log: (String) -> Unit): Boolean = try {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            var count = 0
            while (entry != null) {
                val out = File(dest, entry.name)
                // Guard against a crafted archive escaping the destination.
                if (!out.canonicalPath.startsWith(dest.canonicalPath)) {
                    log("  অস্বাভাবিক পথ বাদ: ${entry.name}")
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it, 1 shl 16) }
                    count++
                }
                entry = zis.nextEntry
            }
            log("  $count ফাইল")
        }
        true
    } catch (e: Exception) {
        log("  আনপ্যাক ব্যর্থ: ${e.message}")
        false
    }

    /**
     * Termux ships its symlinks as a text file (`target←link`) because zip cannot
     * hold them. Without these, `sh`, `env`, `xz` and most applets do not exist.
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
                // A relative target is relative to the link's own directory.
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
     * Extracts a `.deb` with the app's own bundled busybox: `ar` pulls out the
     * member and `tar`/`unxz` unpack the payload. The paths inside a Termux deb
     * are absolute Termux paths, so they are re-rooted under [dest].
     */
    private fun extractDeb(context: Context, deb: File, dest: File, log: (String) -> Unit): Boolean = try {
        val work = File(deb.parentFile, deb.nameWithoutExtension).apply { deleteRecursively(); mkdirs() }
        val bb = ShellRuntime.command(context)?.absolutePath ?: run {
            log("  বাজবক্স পাওয়া যায়নি"); return false
        }
        fun sh(cmd: String): String {
            val p = ProcessBuilder(bb, "sh", "-c", cmd).directory(work)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            return out
        }
        sh("ar x ${q(deb.absolutePath)}")
        sh("tar xf data.tar.xz")
        // Re-root: data/data/com.termux/files/usr/... -> <dest>/usr/...
        val payload = File(work, "data/data/com.termux/files")
        if (payload.exists()) {
            payload.copyRecursively(dest, overwrite = true)
        }
        true
    } catch (e: Exception) {
        log("  deb খোলা যায়নি (${deb.name}): ${e.message}")
        false
    }

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
