// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.shell

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The links the payload needs to run, checked against the payload itself.
 *
 * A shared object records the dependency it was built against in `DT_NEEDED`
 * (`libreadline.so.8.3` asks for `libncursesw.so.6`) while the archive ships only
 * the fully versioned file (`libncursesw.so.6.5`). The difference is supplied by a
 * link in `SYMLINKS.txt`. When one is missing the loader refuses the program with
 * `CANNOT LINK EXECUTABLE ... Library "libncursesw.so.6" not found`, which is a
 * guest that installs cleanly and then cannot run a single command — a failure no
 * unit test of the installer's logic would ever see.
 *
 * This reads the real archive and proves every dependency is resolvable, so a
 * payload swap that drops a link fails here rather than on a phone.
 */
class LinuxPayloadLinksTest {

    private val payloads = listOf(
        "payload64/assets/linux/aarch64/bootstrap.zip",
        "payload32/assets/linux/arm/bootstrap.zip"
    )

    private fun open(rel: String): File? {
        val candidates = listOf(
            File("app/src/$rel"),
            File("src/$rel"),
            File("../app/src/$rel"),
            File(rel)
        )
        return candidates.firstOrNull { it.isFile }
    }

    @Test
    fun `every library a payload binary asks for can be resolved`() {
        val checked = mutableListOf<String>()
        for (rel in payloads) {
            val file = open(rel) ?: continue
            checked += rel
            ZipFile(file).use { zip ->
                val names = mutableListOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) names += entries.nextElement().name

                // What the guest will hold: the real files, plus every name the
                // link manifest creates.
                val provided = names
                    .filter { it.startsWith("lib/") || it.startsWith("bin/") }
                    .map { it.substringAfterLast('/') }
                    .toMutableSet()
                val manifest = zip.getEntry("SYMLINKS.txt")
                assertNotNull("$rel has no SYMLINKS.txt", manifest)
                zip.getInputStream(manifest!!).bufferedReader().readLines().forEach { line ->
                    LinuxRuntime.parseSymlinkLine(line)?.let { provided += it.second.substringAfterLast('/') }
                }

                val broken = mutableListOf<String>()
                names.forEach { name ->
                    if (!name.startsWith("bin/") && !name.startsWith("lib/") && !name.startsWith("libexec/")) return@forEach
                    val entry = zip.getEntry(name) ?: return@forEach
                    if (entry.isDirectory) return@forEach
                    val data = zip.getInputStream(entry).use { it.readBytes() }
                    for (needed in elfNeeded(data)) {
                        if (needed in provided) continue
                        // Held under a fuller version (`libncursesw.so.6.5` for
                        // `libncursesw.so.6`) means a link is what supplies it.
                        val held = provided.any { it == needed || it.startsWith("$needed.") }
                        if (held) broken += "$name needs $needed (held under a fuller name, no link)"
                    }
                }
                assertTrue("$rel cannot resolve:\n  " + broken.joinToString("\n  "), broken.isEmpty())
            }
        }
        assertTrue("no payload archive found to check", checked.isNotEmpty())
    }

    /**
     * The SONAME-shaped names a binary can ask for, read out of its own string
     * table. Only `lib*.so*` names are taken: the table also holds paths and symbol
     * names, and none of those are dependencies.
     */
    private fun elfNeeded(data: ByteArray): List<String> {
        if (data.size < 4 || data[0] != 0x7f.toByte() || data[1] != 'E'.code.toByte() ||
            data[2] != 'L'.code.toByte() || data[3] != 'F'.code.toByte()
        ) return emptyList()
        val text = String(data, Charsets.ISO_8859_1)
        return Regex("lib[a-zA-Z0-9_+\\-]+\\.so[0-9.]*")
            .findAll(text)
            .map { it.value }
            .filter { it.length in 5..64 }
            .toSortedSet()
            .toList()
    }

    // ---- the merge's view of a directory entry -------------------------------

    @Test
    fun `a link is classified as a link, not as the file it points at`() {
        // 0xA1FF = a symbolic link with mode 0777. Reading it through File would
        // call it a directory or nothing at all depending on whether the target
        // exists yet, and the merge would drop the link instead of recreating it.
        assertEquals(LinuxRuntime.EntryKind.LINK, LinuxRuntime.entryKind(0xA1FF))
        assertEquals(LinuxRuntime.EntryKind.FILE, LinuxRuntime.entryKind(0x81A4))
        assertEquals(LinuxRuntime.EntryKind.DIR, LinuxRuntime.entryKind(0x41ED))
        assertEquals(LinuxRuntime.EntryKind.OTHER, LinuxRuntime.entryKind(0x0000))
        assertEquals(LinuxRuntime.EntryKind.OTHER, LinuxRuntime.entryKind(0x1000))
    }

    // ---- the manifest format -------------------------------------------------

    @Test
    fun `a manifest line splits on the real arrow`() {
        assertEquals(
            "libncursesw.so.6.5" to "lib/libncursesw.so.6",
            LinuxRuntime.parseSymlinkLine("libncursesw.so.6.5←./lib/libncursesw.so.6")
        )
    }

    @Test
    fun `a line without the arrow is skipped, not guessed`() {
        assertNull(LinuxRuntime.parseSymlinkLine(""))
        assertNull(LinuxRuntime.parseSymlinkLine("   "))
        assertNull(LinuxRuntime.parseSymlinkLine("just some text"))
        assertNull(LinuxRuntime.parseSymlinkLine("←./lib/nothing"))
        assertNull(LinuxRuntime.parseSymlinkLine("lib/x.so.1←"))
    }

    @Test
    fun `an absolute target keeps its leading slash`() {
        assertEquals(
            "/data/data/ai.zyraxon/files/usr/bin/sh" to "bin/sh",
            LinuxRuntime.parseSymlinkLine("/data/data/ai.zyraxon/files/usr/bin/sh←./bin/sh")
        )
    }
}
