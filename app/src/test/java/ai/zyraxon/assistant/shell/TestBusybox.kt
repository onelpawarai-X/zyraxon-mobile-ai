package ai.zyraxon.assistant.shell

import android.content.Context
import java.io.File

/**
 * Puts the bundled busybox where a test's app looks for it.
 *
 * Robolectric leaves `nativeLibraryDir` null, so a real directory is made and
 * handed to the ApplicationInfo. Production code is untouched: it still resolves
 * busybox through `applicationInfo.nativeLibraryDir`, exactly as on a phone.
 */
object TestBusybox {

    fun install(context: Context) {
        val abi = when (System.getProperty("os.arch")) {
            "aarch64" -> "arm64-v8a"
            "arm", "armv7l" -> "armeabi-v7a"
            else -> if (System.getProperty("os.arch").contains("64")) "x86_64" else "x86"
        }
        val rel = "jniLibs/$abi/libbusybox.so"
        val source = listOf(File(rel), File("app/src/main/$rel"), File("src/main/$rel"))
            .firstOrNull { it.exists() }
            ?: error("test binary not found for $abi (looked in jniLibs/$abi)")

        val nativeDir = File(System.getProperty("java.io.tmpdir"), "zyraxon-native").apply {
            deleteRecursively(); mkdirs()
        }
        source.copyTo(File(nativeDir, "libbusybox.so"), overwrite = true)
        File(nativeDir, "libbusybox.so").setExecutable(true)
        context.applicationInfo.nativeLibraryDir = nativeDir.absolutePath

        // The farm is built once per process; a stale marker from another test
        // would hide a broken build.
        File(ShellRuntime.binDir(context), ".ready").delete()
    }
}
