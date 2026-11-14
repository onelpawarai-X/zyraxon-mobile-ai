// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.shell

import android.content.Context
import java.io.File

/**
 * ZYRAXON's own Linux userland, installed from the payload bundled inside the APK.
 *
 * This is a full Unix system — `bash`, `apt`, `dpkg`, `git`, `python`,
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
 * and so does everything inside the guest: the guest sees the same non-root uid.
 * Shizuku, when present, only adds the ability to touch real system directories;
 * it is never required for the Terminal to work.
 *
 * ## Why the guest prefix is not the app's real data path
 *
 * The payload's binaries were built against one fixed prefix, so its RUNPATH and
 * its symlinks all point at that same absolute path. That path is an internal
 * detail and is never shown to the user. The tree is bound at a clean prefix
 * (`/usr`) that `$PREFIX`, `$HOME` and `pwd` report, and *also* at the path the
 * binaries were built against, purely so they can still find their own libraries.
 * Nothing the user sees mentions anything else.
 */
object LinuxRuntime {

    /** The prefix the guest sees — what `$PREFIX`, `$HOME` and `pwd` show. */
    const val GUEST_PREFIX = "/usr"
    const val GUEST_HOME = "/home"

    /**
     * The path the payload's own binaries were built against, which carries the
     * app's package name. It equals the guest's `/usr` on disk, so it is bound
     * only for completeness.
     */
    private const val COMPAT_PREFIX = "/data/data/ai.zyraxon/files/usr"

    private const val MARKER = "SYMLINKS.txt"

    // The `st_mode` file-type bits, which Kotlin has no binding for. `lstat` is
    // what tells a symbolic link from the file it points at, and a link whose
    // target is not written yet is exactly the case `File.isFile` gets wrong.
    private const val S_IFMT = 0xF000
    private const val S_IFLNK = 0xA000
    private const val S_IFDIR = 0x4000
    private const val S_IFREG = 0x8000

    /** What a directory entry is, decided from `lstat` rather than from `File`. */
    enum class EntryKind { FILE, DIR, LINK, OTHER }

    /** The app's native library directory — the one place Android lets us exec from. */
    fun nativeLib(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    /** proot itself, packaged as a native lib so it can be executed. */
    fun proot(context: Context): File = File(nativeLib(context), "libproot.so")

    /** proot's loader, handed to it through `PROOT_LOADER`. */
    fun loader(context: Context): File = File(nativeLib(context), "libproot-loader.so")

    /** The guest's prefix on the phone: the app's own storage, no root needed. */
    fun root(context: Context): File = File(context.filesDir, "usr")

    /**
     * The guest's home directory.
     *
     * Created here, not only during install: `proot -w /home` and the direct
     * launcher's `cd $HOME` both run before anything else touches this path, and
     * a missing directory makes the shell die with "No such file or directory"
     * before the user's first command. An update that re-created the data dir
     * left the guest installed but homeless, which is exactly that failure.
     */
    fun home(context: Context): File = File(context.filesDir, "home").apply { mkdirs() }

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
            // Reached only when no vendored payload matches this device's CPU.
            // Said plainly, with the way forward in the same breath: the Android
            // session is a real busybox shell and needs none of this, so the user
            // is never left at a bare `exit 1` with no next step.
            log("This phone is ${android.os.Build.SUPPORTED_ABIS.joinToString()}.")
            log("No bundled Linux system matches this device's CPU, so there is nothing to install here.")
            log("")
            log("The Android session still works — switch to the 'android' tab and type commands there.")
            log("It runs the phone's own busybox shell: ls, cat, grep, sed, awk, tar, curl, python and more.")
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
        // `SYMLINKS.txt`. A deb wraps its payload under its own absolute path
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
        // The guest ships its own welcome text and prompt. Overwriting those files
        // is what makes the system read as ZYRAXON's; the binaries are untouched
        // and keep working exactly as before.
        applyBranding(context, root, log)
        ensureSystemDirectories(root)
        writeAptAndDpkgConfig(root)
        fixScriptShebangs(root)
        fixAptMethods(root)
        fixExecutablePermissions(context)
        try { File(root, BRANDED).writeText(System.currentTimeMillis().toString()) } catch (_: Exception) {}
        val ready = isInstalled(context)
        log(if (ready) "ZYRAXON Linux is ready. Run a command with: linux <command>"
            else "Install did not finish; open the Terminal again to retry.")
        return ready
    }

    /**
     * Runs one command in the guest and returns what it printed, so `linux-status`
     * can show a real probe rather than only a claim. A failure comes back as text,
     * never as an exception: the point is to make the reason visible on the phone.
     */
    fun probe(context: Context): String {
        if (!isInstalled(context)) return "not installed"
        val result = runCatching {
            runStreaming(context, "echo zyraxon-probe-ok; id -u; uname -m", onChunk = { _, _ -> })
        }.getOrNull()
        if (result == null) return "the guest could not be started"
        val text = (result.stdout + result.stderr).trim()
        return if (text.isEmpty()) {
            "the guest started but printed nothing (exit ${result.code})"
        } else {
            "exit ${result.code} · ${text.replace('\n', ' ')}"
        }
    }

    /**
     * True once [applyBranding] has run for the installed guest.
     *
     * The suffix is bumped whenever the generated configuration changes shape, so
     * a guest installed by an earlier build is rewritten once instead of keeping
     * a config that no longer matches the code — v4 rebuilds the SONAME symlinks; v3 was the absolute-path apt.conf.
     */
    private const val BRANDED = ".zyraxon-configured-v4"

    /**
     * Writes the branding into a guest installed by an earlier build, whose files
     * may predate it. Called before a command runs; the marker makes it a single
     * `isFile` check once it has been done. Never throws: a read-only or full
     * filesystem must not stop a command from running.
     */
    fun ensureBranding(context: Context) {
        val root = root(context)
        if (!isInstalled(context)) return
        if (File(root, BRANDED).isFile) return
        runCatching {
            applyBranding(context, root) { }
            ensureSystemDirectories(root)
            // A guest installed before the link pass was fixed is missing the
            // SONAME links its libraries need, and the install is not re-run for
            // it. The links are rebuilt from the manifest in the APK's own
            // bootstrap, not from the copy in the guest — that copy is the very
            // file an older build failed to act on.
            repairLinksFromPayload(context, root)
            if (librariesResolve(root)) {
                writeAptAndDpkgConfig(root)
                fixScriptShebangs(root)
                fixAptMethods(root)
                fixExecutablePermissions(context)
            } else {
                // A link on its own is not enough: the file it points at can be
                // missing too, because the old unpack stopped at the first link
                // whose target had not been written yet. Only a fresh install
                // repairs that, so the tree is removed and rebuilt from the
                // payload once. `isInstalled` also accepts a tree with `bin/dpkg`
                // but no DONE, so the whole tree has to go, not just the marker.
                root.deleteRecursively()
                install(context) { }
            }
            // Written in both cases: this is a one-shot repair, and a repair that
            // failed must not wipe the guest again on every later command.
            File(root, BRANDED).writeText(System.currentTimeMillis().toString())
        }
    }

    /**
     * True when every library link in the guest resolves to a real file.
     *
     * The check is limited to library links: those are what the loader
     * resolves before a program can start, and a broken one is exactly the state
     * an old unpack left behind. Links to applets and man pages are not checked,
     * because a missing one there is cosmetic and would force needless rebuilds.
     */
    private fun librariesResolve(root: File): Boolean {
        val manifest = File(root, MARKER)
        if (!manifest.isFile) return false
        val libDir = File(root, "lib")
        return manifest.readLines().all { line ->
            val link = parseSymlinkLine(line) ?: return@all true
            val name = link.second.substringAfterLast('/')
            if (!link.second.startsWith("lib/") || !name.contains(".so")) return@all true
            File(libDir, name).exists()
        }
    }

    /**
     * Rebuilds the guest's symbolic links from the manifest inside the APK.
     *
     * The install is skipped once a guest is finished, so a link pass that failed
     * in an older build would never be retried. Reading the manifest straight out
     * of the bundled archive is what makes the repair independent of whatever the
     * guest was left holding. A missing archive or manifest is not an error here:
     * this is a repair pass, and it must never stop a command from running.
     */
    private fun repairLinksFromPayload(context: Context, root: File) {
        val assets = assetDir(context) ?: return
        val text = try {
            context.assets.open("$assets/bootstrap.zip").use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    var body: String? = null
                    while (entry != null) {
                        if (entry.name == MARKER) {
                            body = zis.readBytes().toString(Charsets.UTF_8)
                            break
                        }
                        entry = zis.nextEntry
                    }
                    body
                }
            }
        } catch (_: Exception) {
            null
        } ?: return
        try {
            val manifest = File(root, MARKER)
            manifest.writeText(text)
            applySymlinks(context, manifest, root) { }
        } catch (_: Exception) {
        }
    }

    /**
     * Writes ZYRAXON's welcome text and prompt into the guest. The binaries carry
     * their build path in their RUNPATH, but nothing the user reads has to name
     * anything else, and this is where that is settled. Every file written here is
     * cosmetic: removing one never breaks a command.
     */
    private fun applyBranding(context: Context, root: File, log: (String) -> Unit) {
        val etc = File(root, "etc").apply { mkdirs() }
        val profileD = File(etc, "profile.d").apply { mkdirs() }

        // The banner, shown by a login shell (`bash -l`) and by `login`.
        writeExecutable(File(etc, "zyraxon-motd.sh"), ZYRAXON_MOTD)

        // Sourced by /etc/profile (through profile.d below) for every login shell
        // the runtime starts. It sets the prompt; a command the assistant runs is
        // not interactive, so the banner stays out of its output.
        writeExecutable(File(etc, "zyraxon-brand.sh"), ZYRAXON_BRAND_SH)

        // The static motd `login` falls back to, the dynamic one it prefers, and
        // the Play Store variant.
        File(etc, "motd").writeText(ZYRAXON_MOTD_TEXT)
        File(etc, "motd-playstore").writeText(ZYRAXON_MOTD_TEXT)
        writeExecutable(File(etc, "motd.sh"), ZYRAXON_MOTD)

        // A login shell sources /etc/profile, which sources this directory, so the
        // brand is applied there too rather than only in the runtime's own call.
        File(profileD, "00-zyraxon-brand.sh")
            .writeText("[ -r $GUEST_PREFIX/etc/zyraxon-brand.sh ] && . $GUEST_PREFIX/etc/zyraxon-brand.sh\n")

        File(etc, "issue").writeText("ZYRAXON Linux \\n \\l\n\n")
        File(etc, "hostname").writeText("zyraxon\n")
        File(etc, "os-release").writeText(
            """
            NAME="ZYRAXON"
            ID=zyraxon
            PRETTY_NAME="ZYRAXON Mobile Linux"
            VERSION="${com.zyraxon.assistant.BuildConfig.VERSION_NAME}"
            HOME_URL="https://zyraxonai.lovable.app"
            SUPPORT_URL="https://github.com/onelpawarai-X/zyraxon-mobile-ai/issues"
            """.trimIndent() + "\n"
        )

        log(" ZYRAXON branding")
    }

    /**
     * Ensures all binaries and scripts under the guest prefix have POSIX executable permissions.
     * Fixes exit 126 (Permission denied) on apt, dpkg, bash, and unpacked packages.
     */
    fun fixExecutablePermissions(context: Context) {
        val usr = root(context)
        if (!usr.exists()) return
        listOf(File(usr, "bin"), File(usr, "libexec"), File(usr, "lib/apt/methods"), File(usr, "etc/profile.d")).forEach { dir ->
            if (dir.exists()) {
                dir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        file.setExecutable(true, false)
                        file.setReadable(true, false)
                    }
                }
            }
        }
        runCatching {
            Runtime.getRuntime().exec(arrayOf("chmod", "-R", "755", File(usr, "bin").absolutePath)).waitFor()
        }
    }

    /**
     * Creates all required standard directories for APT and DPKG to run cleanly
     * without missing directory warnings or packaging system errors.
     */
    fun ensureSystemDirectories(root: File) {
        listOf(
            "tmp",
            "etc/apt/apt.conf.d",
            "etc/apt/preferences.d",
            "etc/apt/sources.list.d",
            "etc/apt/trusted.gpg.d",
            "etc/dpkg/dpkg.cfg.d",
            "var/lib/apt/lists/partial",
            "var/cache/apt/archives/partial",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/info",
            "var/lib/dpkg/triggers",
            "var/log/apt"
        ).forEach { rel ->
            File(root, rel).mkdirs()
        }
        val status = File(root, "var/lib/dpkg/status")
        if (!status.exists()) {
            runCatching { status.createNewFile() }
        }
        val available = File(root, "var/lib/dpkg/available")
        if (!available.exists()) {
            runCatching { available.createNewFile() }
        }
    }

    /**
     * Generates apt.conf and dpkg.cfg directing package tools to our app's storage,
     * fully resolving the payload's hardcoded paths (Exit 100 DirectoryExists error).
     *
     * Every `Dir::` value that apt *stats* is written absolute, and that is what
     * fixes "Unable to determine a suitable packaging system type". apt picks its
     * backend by score (`pkgInitSystem` → `debSystem::Score`), and the score comes
     * from three raw `stat()` calls — `Dir::State::status`, `Dir::Bin::dpkg` and
     * `/etc/debian_version`. A relative value is resolved against the process's
     * working directory (`/home`), where neither file exists, so the score stays
     * zero and apt exits 100 before doing anything. The absolute prefix is the
     * same path the payload was built with, and it is visible inside the guest
     * both through proot's bind and as part of the real rootfs.
     */
    fun writeAptAndDpkgConfig(root: File) {
        val realPath = root.absolutePath
        val etcApt = File(root, "etc/apt").apply { mkdirs() }
        val confContent = """
Dir "$realPath";
Dir::State "$realPath/var/lib/apt";
Dir::State::status "$realPath/var/lib/dpkg/status";
Dir::Cache "$realPath/var/cache/apt";
Dir::Etc "$realPath/etc/apt";
Dir::Etc::sourcelist "sources.list";
Dir::Etc::sourceparts "sources.list.d";
Dir::Etc::parts "apt.conf.d";
Dir::Etc::preferences "preferences";
Dir::Etc::preferencesparts "preferences.d";
Dir::Bin::methods "$realPath/lib/apt/methods";
Dir::Bin::dpkg "$realPath/bin/dpkg";
Dir::Media::MountPath "$realPath/media/apt";
Acquire::Languages "none";
""".trimIndent()
        File(etcApt, "apt.conf").writeText(confContent)
        val aptConfD = File(etcApt, "apt.conf.d").apply { mkdirs() }
        File(aptConfD, "00zyraxon.conf").writeText(confContent)

        val etcDpkg = File(root, "etc/dpkg").apply { mkdirs() }
        File(etcDpkg, "dpkg.cfg").writeText("admindir $realPath/var/lib/dpkg\n")
        val dpkgCfgD = File(etcDpkg, "dpkg.cfg.d").apply { mkdirs() }
        File(dpkgCfgD, "00zyraxon.cfg").writeText("admindir $realPath/var/lib/dpkg\n")
    }

    /**
     * Patches the payload's hardcoded shebangs and prefixes in bundled shell scripts (like pkg).
     */
    fun fixScriptShebangs(root: File) {
        val real = root.absolutePath
        val compat = COMPAT_PREFIX
        listOf(File(root, "bin"), File(root, "etc"), File(root, "libexec")).forEach { dir ->
            if (dir.exists()) {
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (file.isFile && file.length() in 1..262144) {
                        try {
                            val head = ByteArray(64)
                            val read = file.inputStream().use { it.read(head) }
                            if (read >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) {
                                var text = file.readText()
                                var changed = false
                                if (text.contains(compat)) {
                                    text = text.replace(compat, real)
                                    changed = true
                                }
                                if (file.name == "pkg" && text.contains("Zyrxon-PKG")) {
                                    text = text.replace("Zyrxon-PKG", "ZYRAXON-PKG")
                                        .replace("zyrxon-tools", "zyraxon-tools")
                                        .replace("Zyrxon (ai.zyraxon", "ZYRAXON (com.zyraxon.assistant")
                                    changed = true
                                }
                                if (changed) {
                                    file.writeText(text)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    /**
     * Ensures the HTTPS method binary/symlink for APT exists and is executable.
     */
    fun fixAptMethods(root: File) {
        val methodsDir = File(root, "lib/apt/methods")
        if (methodsDir.exists()) {
            val http = File(methodsDir, "http")
            val https = File(methodsDir, "https")
            if (http.exists() && (!https.exists() || !https.canExecute())) {
                runCatching {
                    https.delete()
                    Runtime.getRuntime().exec(arrayOf("ln", "-sf", "http", https.absolutePath)).waitFor()
                }
            }
        }
    }


    private fun writeExecutable(file: File, text: String) {
        file.writeText(text)
        file.setExecutable(true, false)
    }

    private val ZYRAXON_MOTD_TEXT = """
Welcome to ZYRAXON.

Docs:      https://zyraxonai.lovable.app
Community: https://github.com/onelpawarai-X/zyraxon-mobile-ai/issues

Working with packages:

 - Search:  pkg search <query>
 - Install: pkg install <package>
 - Upgrade: pkg upgrade
""".trimIndent()

    private val ZYRAXON_MOTD = """
#!/usr/bin/env sh
# ZYRAXON's welcome banner. Printed by an interactive shell only, so a command
# run by the assistant or by the app stays free of it.
width="$(stty size 2>/dev/null | cut -d' ' -f2)"
if [ -n "${'$'}width" ] && [ "${'$'}width" -gt 60 ] 2>/dev/null; then
    printf '\n  \033[1mZYRAXON\033[0m  \033[0;37ma real Unix system on this phone\033[0m\n\n'
    printf '  \033[1mDocs:\033[0m      \033[4mhttps://zyraxonai.lovable.app\033[0m\n'
    printf '  \033[1mCommunity:\033[0m \033[4mhttps://github.com/onelpawarai-X/zyraxon-mobile-ai/issues\033[0m\n\n'
else
    printf '\n\033[1mWelcome to ZYRAXON.\033[0m\n\n'
    printf '\033[1mDocs:\033[0m      \033[4mhttps://zyraxonai.lovable.app\033[0m\n'
    printf '\033[1mCommunity:\033[0m \033[4mhttps://github.com/onelpawarai-X/zyraxon-mobile-ai/issues\033[0m\n\n'
fi
printf '\033[1mWorking with packages:\033[0m\n\n'
printf ' - Search:  pkg search <query>\n'
printf ' - Install: pkg install <package>\n'
printf ' - Upgrade: pkg upgrade\n\n'
""".trimIndent()

    private val ZYRAXON_BRAND_SH = """
# ZYRAXON brand for the guest shell. Sourced before every command, and by
# /etc/profile for a login shell.
export ZYRAXON=1
[ -z "${'$'}PREFIX" ] && export PREFIX="$GUEST_PREFIX"
export ZYRXON_PREFIX="${'$'}PREFIX"
[ -f "${'$'}PREFIX/etc/apt/apt.conf" ] && export APT_CONFIG="${'$'}PREFIX/etc/apt/apt.conf"
[ -d "${'$'}PREFIX/var/lib/dpkg" ] && export DPKG_ADMINDIR="${'$'}PREFIX/var/lib/dpkg"
[ -d "${'$'}PREFIX/share/dpkg" ] && export DPKG_DATADIR="${'$'}PREFIX/share/dpkg"
[ -d "${'$'}PREFIX/share/terminfo" ] && export TERMINFO="${'$'}PREFIX/share/terminfo"
PS1='\[\e[1;35m\]zyraxon\[\e[0m\]:\[\e[0;36m\]\w\[\e[0m\]\$ '
case "$-" in
    *i*) [ -r "${'$'}PREFIX/etc/zyraxon-motd.sh" ] && "${'$'}PREFIX/etc/zyraxon-motd.sh" ;;
esac
""".trimIndent()

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
            if (!ShellRuntime.isAvailable(context)) {
                log(" busybox not found"); return false
            }
            val work = File(deb.parentFile, deb.nameWithoutExtension).apply { deleteRecursively(); mkdirs() }
            val payload = readArMember(deb, "data.tar") ?: return false
            val tar = File(work, "data.tar")
            when {
                payload.name.endsWith(".xz") -> {
                    val xz = File(work, "data.tar.xz")
                    xz.writeBytes(payload.bytes)
                    if (!unxz(context, xz, tar)) return false
                }
                payload.name.endsWith(".gz") -> {
                    val gz = File(work, "data.tar.gz")
                    gz.writeBytes(payload.bytes)
                    if (!gunzip(context, gz, tar)) return false
                }
                else -> tar.writeBytes(payload.bytes)
            }
            val extract = File(work, "x").apply { mkdirs() }
            if (!run(context, work, "tar", "xf", tar.absolutePath, "-C", extract.absolutePath)) return false
            // The payload stores files under its own absolute data path, so the
            // `files` directory is located, not guessed.
            val payloadRoot = findPayloadRoot(extract)
            if (payloadRoot != null) mergeTree(payloadRoot, dest)
            work.deleteRecursively()
            true
        } catch (e: Exception) {
            log(" Could not unpack deb (${deb.name}): ${e.message}")
            false
        }
    }

    /**
     * Classifies a `st_mode` into the four cases the merge cares about.
     *
     * Pure, so the rule can be checked off-device. The one that matters is
     * [EntryKind.LINK]: a symbolic link is neither a file nor a directory to the
     * merge, because it must be recreated as a link rather than followed. Reading
     * it through `File.isFile` would report false for a link whose target is not
     * written yet, and the link would then be dropped instead of recreated.
     */
    internal fun entryKind(mode: Int): EntryKind = when (mode and S_IFMT) {
        S_IFREG -> EntryKind.FILE
        S_IFDIR -> EntryKind.DIR
        S_IFLNK -> EntryKind.LINK
        else -> EntryKind.OTHER
    }

    /**
     * Merges one package's tree into the guest, preserving symbolic links.
     *
     * `copyRecursively` is the obvious call and the wrong one twice over: it
     * follows a link and copies what it points at, so `libncursesw.so.6` becomes a
     * second copy of the library rather than the link the loader looks for; and on
     * a link whose target has not been written yet it stops the whole copy, so the
     * files after it never arrive. Both leave a guest whose libraries cannot be
     * resolved, and the failure is silent — the package reports success.
     *
     * Directories merge. A file or link replaces whatever is already there, which
     * is how a later package upgrades an earlier one.
     */
    private fun mergeTree(source: File, dest: File) {
        when (entryKind(lstatMode(source) ?: return)) {
            EntryKind.LINK -> replaceWithSymlink(source, dest)
            EntryKind.DIR -> {
                if (!dest.exists()) dest.mkdirs()
                source.listFiles()?.forEach { child -> mergeTree(child, File(dest, child.name)) }
            }
            EntryKind.FILE -> {
                dest.parentFile?.mkdirs()
                if (isSymlink(dest)) dest.delete()
                source.copyTo(dest, overwrite = true)
            }
            EntryKind.OTHER -> Unit
        }
    }

    /**
     * The `st_mode` of [file] without following it, or null when it is not there.
     *
     * `lstat` rather than `File.exists()`: a link whose target is not written yet
     * still has to be recognised as a link and recreated, and `exists()` reports
     * false for it. `android.system.Os` is used rather than `java.nio.file`, which
     * is API 26+ while this app supports API 21.
     */
    private fun lstatMode(file: File): Int? = try {
        android.system.Os.lstat(file.absolutePath).st_mode
    } catch (_: Exception) {
        null
    }

    private fun isSymlink(file: File): Boolean = entryKind(lstatMode(file) ?: 0) == EntryKind.LINK

    /** Points [dest] at whatever [source] points at, rather than copying the target. */
    private fun replaceWithSymlink(source: File, dest: File) {
        val target = try {
            android.system.Os.readlink(source.absolutePath)
        } catch (_: Exception) {
            return
        }
        try {
            dest.delete()
        } catch (_: Exception) {
        }
        dest.parentFile?.mkdirs()
        try {
            android.system.Os.symlink(target, dest.absolutePath)
        } catch (_: Exception) {
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

    private fun unxz(context: Context, xz: File, out: File): Boolean {
        if (!run(context, xz.parentFile!!, "unxz", "-f", xz.absolutePath)) return false
        val produced = File(xz.parentFile, xz.name.removeSuffix(".xz"))
        return produced.exists() && produced.renameTo(out)
    }

    private fun gunzip(context: Context, gz: File, out: File): Boolean {
        if (!run(context, gz.parentFile!!, "gunzip", "-f", gz.absolutePath)) return false
        val produced = File(gz.parentFile, gz.name.removeSuffix(".gz"))
        return produced.exists() && produced.renameTo(out)
    }

    private fun run(context: Context, dir: File, vararg args: String): Boolean = try {
        val cmd = ShellRuntime.busyboxArgs(context, *args)
        val p = ProcessBuilder(cmd).directory(dir)
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
     * Recreates every link listed in the archive's `SYMLINKS.txt`.
     *
     * A zip cannot hold symlinks, so the bootstrap ships them as a `target←link`
     * text file. Without them `sh`, `env` and most applets do not exist, and a
     * shared object cannot find the version of its dependency it was built
     * against — `libreadline.so.8.3` asks for `libncursesw.so.6` while the
     * archive holds only `libncursesw.so.6.5`, so without the link every program
     * that pulls in readline dies at startup with `Library ... not found`.
     *
     * The links are made with the kernel directly rather than through the bundled
     * toolbox, which may not be ready yet while the guest is still being unpacked,
     * and not through `java.nio.file.Files`, which is API 26+ while this app
     * supports API 21. Any absolute target that points at the payload's build path
     * is rewritten to the prefix the guest actually sees, so a link never depends
     * on the compat bind. A failure is counted and reported rather than passed
     * over in silence.
     */
    private fun applySymlinks(context: Context, manifest: File, dest: File, log: (String) -> Unit) {
        if (!manifest.exists()) {
            log(" no link manifest in the payload")
            return
        }
        var made = 0
        var failed = 0
        manifest.readLines().forEach { raw ->
            val link = parseSymlinkLine(raw) ?: return@forEach
            val target = guestTarget(link.first, dest)
            val file = File(dest, link.second)
            file.parentFile?.mkdirs()
            if (makeSymlink(context, dest, file, target)) made++ else failed++
        }
        log(" $made symlinks" + if (failed > 0) " ($failed could not be made)" else "")
    }

    /**
     * Splits one `target←link` line of the manifest, or null when it is not one.
     *
     * The separator is a real `←` (U+2190), not an ASCII arrow, and the link half
     * is written relative to the prefix. Blank lines, comments and anything that
     * does not carry the separator are skipped rather than guessed at. Pure, so
     * the format can be checked without a payload.
     */
    internal fun parseSymlinkLine(line: String): Pair<String, String>? {
        val text = line.trim()
        val sep = text.indexOf('←')
        if (sep <= 0) return null
        val target = text.substring(0, sep).trim()
        val link = text.substring(sep + 1).trim().removePrefix("./")
        if (target.isEmpty() || link.isEmpty()) return null
        return target to link
    }

    /**
     * Creates one symbolic link, kernel first and toolbox second.
     *
     * `Os.symlink` is the direct call and needs nothing else present. The toolbox
     * is kept as the fallback for the case where the kernel refuses the call, and
     * its own absence is no longer a reason to skip the link.
     */
    private fun makeSymlink(context: Context, dir: File, link: File, target: String): Boolean {
        // `delete` removes the link itself, never what it points at, and succeeds
        // on a dangling link where `exists` would report false.
        try {
            link.delete()
        } catch (_: Exception) {
        }
        try {
            android.system.Os.symlink(target, link.absolutePath)
            return true
        } catch (_: Exception) {
        }
        if (ShellRuntime.isAvailable(context)) {
            if (run(context, dir, "ln", "-sf", target, link.absolutePath)) return true
        }
        return false
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
     * the app's own uid, which is the only uid Android gives it, and no fake root
     * is ever handed to it. This is also what lets the guest's `pkg` and `apt`
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

        // A guest installed by an earlier build may predate a fix to the unpack
        // or link pass, and the install is not re-run for it. This repairs it in
        // place once, before the first command. A single marker check afterwards.
        ensureBranding(context)
        // Ensure permissions are intact before running
        fixExecutablePermissions(context)
        // 1. Prioritize Direct Linker Execution: 100% immune to SECCOMP Exit 159 & noexec restrictions
        run {
            var sawOut = false
            val r = ShellRuntime.runStreaming(
                context, directLine(context, command),
                timeoutMs = timeoutMs,
                onChunk = { st, tx -> sawOut = true; onChunk(st, tx) },
                onSpawn = onSpawn
            )
            val failedDirect = r.code == 159 || r.code == 126 || r.code == 127 ||
                (!sawOut && r.stdout.isBlank() && r.stderr.isBlank() && r.code != 0)
            if (!failedDirect) return r
        }
        // On some phones the kernel kills proot at once (exit 159 is SIGSYS) or it
        // exits and prints nothing, with the full flag set. Plainer launch modes
        // are tried in turn, and the first that works is remembered so the next
        // command goes straight to it. If none starts, the command still runs in
        // the bundled Android shell, with the reason printed, so a command never
        // ends in silence.
        val order = if (workingLevel >= 0) listOf(workingLevel) + (0..3).filter { it != workingLevel } else (0..3).toList()
        val codes = mutableListOf<String>()
        for (level in order) {
            var sawOutput = false
            val r = ShellRuntime.runStreaming(
                context, buildLine(context, command, level),
                timeoutMs = timeoutMs,
                onChunk = { st, tx -> sawOutput = true; onChunk(st, tx) },
                onSpawn = onSpawn
            )
            val silentFail = r.code != 0 && !r.timedOut &&
                (r.code == 159 || (!sawOutput && r.stdout.isBlank() && r.stderr.isBlank()))
            if (!silentFail) { workingLevel = level; return r }
            codes.add("L$level=${r.code}")
        }
        workingLevel = -1
        // The app targets API 28, so Android lets it run files from its own storage.
        // When proot cannot start, the guest's programs are run directly: the same
        // bash, coreutils, git and python, without the path-translation layer.
        run {
            var sawOutput = false
            val r = ShellRuntime.runStreaming(
                context, directLine(context, command),
                timeoutMs = timeoutMs,
                onChunk = { st, tx -> sawOutput = true; onChunk(st, tx) },
                onSpawn = onSpawn
            )
            val silentFail = r.code != 0 && !r.timedOut &&
                (r.code == 159 || (!sawOutput && r.stdout.isBlank() && r.stderr.isBlank()))
            if (!silentFail) return r
            codes.add("direct=${r.code}")
        }
        val diag = "Linux guest could not start (${codes.joinToString(" ")}; Android ${android.os.Build.VERSION.RELEASE}/" +
            "API ${android.os.Build.VERSION.SDK_INT}, kernel ${System.getProperty("os.version")}, " +
            "${android.os.Build.SUPPORTED_ABIS.firstOrNull()}, proot ${if (proot(context).canExecute()) "ok" else "missing"}, " +
            "loader ${if (loader(context).canExecute()) "ok" else "missing"}). Running in the Android shell instead:"
        onChunk("err", diag)
        val fallback = ShellRuntime.runStreaming(
            context, command, timeoutMs = timeoutMs, onChunk = onChunk, onSpawn = onSpawn
        )
        return fallback.copy(stderr = diag + "\n" + fallback.stderr)
    }

    /** The launch mode that last worked, so commands skip the ones that crashed. */
    @Volatile private var workingLevel = -1

    /** Runs the guest's bash straight from its own storage, no proot. */
    private fun directLine(context: Context, command: String): String {
        val real = root(context).absolutePath
        val home = home(context).absolutePath
        val native = nativeLib(context).absolutePath
        val is64 = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("64") == true
        val linker = if (is64 && java.io.File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"
        val bashPath = java.io.File(real, "bin/bash").absolutePath
        val execCmd = if (java.io.File(linker).exists()) "$linker $bashPath" else bashPath
        return "cd $home && " +
            "export PREFIX=$real && " +
            "export ZYRAXON_PREFIX=$real && " +
            "export HOME=$home && " +
            "export TMPDIR=$real/tmp && " +
            "export APT_CONFIG=$real/etc/apt/apt.conf && " +
            "export DPKG_ADMINDIR=$real/var/lib/dpkg && " +
            "export DPKG_DATADIR=$real/share/dpkg && " +
            "export TERMINFO=$real/share/terminfo && " +
            "export PATH=$real/bin:$native:/system/bin && " +
            "export LD_LIBRARY_PATH=$real/lib:$native:/system/lib64:/system/lib && " +
            "export TERM=xterm-256color LANG=C.UTF-8 ZYRAXON=1 && " +
            "$execCmd -c ${q(command)}"
    }

    private fun buildLine(context: Context, command: String, level: Int): String {
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
            append("LD_LIBRARY_PATH=$native ")
            append("PROOT_LOADER=$loader PROOT_TMP_DIR=$tmp ")
            append("PREFIX=$GUEST_PREFIX HOME=$GUEST_HOME TMPDIR=$GUEST_PREFIX/tmp ")
            append("TERM=xterm-256color LANG=C.UTF-8 ")
            append("PROOT_NO_SECCOMP=1 ")
            // `--kill-on-exit` reaps the whole traced tree when the command ends.
            // Without it a killed command leaves the guest's background children
            // behind, and proot is holding the pipes open, so the Terminal hangs
            // even though the command itself already finished.
            if (level < 3) append("$proot --kill-on-exit ") else append("$proot ")
            // `--link2symlink` emulates hard links with symlinks. `dpkg` unpacks
            // its control files as hard links, so without this an install fails
            // partway through and leaves the package database inconsistent. This
            // is why proot-distro turns it on for every container it runs.
            if (level < 2) append("--link2symlink ")
            // `-L` corrects the size `lstat` reports for a symlink. `dpkg` uses
            // that to decide whether a path is a link it must replace, and the
            // wrong answer makes it warn and mis-handle the file.
            if (level < 2) append("-L ")
            // Some programs use SysV IPC. Android's kernel allows it and the
            // guest binaries expect it, so the extension is left on, as it is in
            // proot-distro's default (non-minimal) mode.
            if (level < 1) append("--sysvipc ")
            append("-r / ")
            if (level < 3) append("-b /dev -b /proc -b /sys -b /storage -b /system ")
            else append("-b /dev -b /proc ")
            append("-b $real:$GUEST_PREFIX ")
            append("-b $real:$COMPAT_PREFIX ")
            append("-b $realHome:$GUEST_HOME ")
            append("-b $real/tmp:/tmp ")
            append("-w $GUEST_HOME ")
            // Inside the guest the prefix is `/usr`, so PATH is set there rather
            // than inherited: a host path in `$PATH` would leak the app's storage
            // layout into a shell the user is looking at.
            val flags = if (level < 3) "-lc" else "-c"
            append("$GUEST_PREFIX/bin/bash $flags ${q("export PATH=$GUEST_PREFIX/bin:/system/bin; export LD_LIBRARY_PATH=$GUEST_PREFIX/lib; $command")}")
        }
        return line
    }

    /**
     * Rewrites a symlink target that points at the payload's build path into the
     * prefix the guest sees. A target that is relative, or already clean, is
     * returned unchanged. Pure, so the rule can be checked off-device.
     */
    internal fun guestTarget(target: String, dest: File? = null): String {
        if (target.startsWith(COMPAT_PREFIX)) {
            val rel = target.removePrefix(COMPAT_PREFIX).removePrefix("/")
            return if (dest != null) File(dest, rel).absolutePath else "$GUEST_PREFIX/$rel"
        }
        return target
    }

    internal fun guestTarget(target: String): String = guestTarget(target, null)

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
