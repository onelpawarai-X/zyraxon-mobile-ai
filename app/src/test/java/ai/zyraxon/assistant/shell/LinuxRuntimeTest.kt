package ai.zyraxon.assistant.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZYRAXON's Linux system is installed from the payload bundled in the APK, so the
 * parts that can be checked off-device are the ones that decide whether that
 * payload is read correctly. A wrong architecture name would look in the wrong
 * asset folder, and a wrong `ar` walk would hand `tar` a file that is not a tar.
 */
class LinuxRuntimeTest {

    // ---- architecture -> asset folder ---------------------------------------

    @Test
    fun `android abis map to the vendored asset folders`() {
        assertEquals("aarch64", LinuxRuntime.arch("arm64-v8a"))
        assertEquals("aarch64", LinuxRuntime.arch("arm64"))
        assertEquals("x86_64", LinuxRuntime.arch("x86_64"))
        assertEquals("i686", LinuxRuntime.arch("x86"))
        assertEquals("arm", LinuxRuntime.arch("armeabi-v7a"))
    }

    @Test
    fun `an unknown abi falls back to arm64 rather than an empty path`() {
        // Most phones are arm64; guessing wrong must still land on a real folder.
        assertEquals("aarch64", LinuxRuntime.arch("mips"))
    }

    // ---- deriving the prefix the payload was built for ----------------------

    @Test
    fun `the build prefix is read back out of the payload's own symlinks`() {
        // The value must come from the payload, never from a constant here: the
        // tree has to stay relocatable and the source must not name the upstream
        // build path at all.
        val dir = java.io.File.createTempFile("zyraxon", "").apply { delete(); mkdirs(); deleteOnExit() }
        java.io.File(dir, "SYMLINKS.txt").writeText(
            """
            curses.h←./include/ncurses.h
            /opt/build/payload/files/usr/share/keys/a.gpg←./etc/a.gpg
            """.trimIndent()
        )

        assertEquals("/opt/build/payload/files/usr", LinuxRuntime.derivePrefix(dir))
    }

    @Test
    fun `a manifest with only relative targets yields no prefix`() {
        val dir = java.io.File.createTempFile("zyraxon", "").apply { delete(); mkdirs(); deleteOnExit() }
        java.io.File(dir, "SYMLINKS.txt").writeText("curses.h←./include/ncurses.h")

        assertEquals(null, LinuxRuntime.derivePrefix(dir))
    }

    @Test
    fun `a missing manifest yields no prefix rather than throwing`() {
        val dir = java.io.File.createTempFile("zyraxon", "").apply { delete(); mkdirs(); deleteOnExit() }

        assertEquals(null, LinuxRuntime.derivePrefix(dir))
    }

    // ---- the ar reader ------------------------------------------------------

    /** Builds an `ar` archive the same way `ar` does: 60-byte headers, even padding. */
    private fun ar(vararg members: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("!<arch>\n".toByteArray(Charsets.US_ASCII))
        for ((name, body) in members) {
            val header = StringBuilder()
            header.append(name.padEnd(16))
            header.append("0".padEnd(12))
            header.append("0".padEnd(6))
            header.append("0".padEnd(6))
            header.append("100644".padEnd(8))
            header.append(body.size.toString().padEnd(10))
            header.append("`\n")
            out.write(header.toString().toByteArray(Charsets.US_ASCII))
            out.write(body)
            if (body.size % 2 == 1) out.write('\n'.code)
        }
        return out.toByteArray()
    }

    private fun tempDeb(bytes: ByteArray) = java.io.File.createTempFile("zyraxon", ".deb").apply {
        deleteOnExit()
        writeBytes(bytes)
    }

    @Test
    fun `reads the data member out of an ar archive`() {
        val file = tempDeb(
            ar(
                "debian-binary/" to "2.0\n".toByteArray(),
                "control.tar.xz/" to byteArrayOf(1, 2, 3),
                "data.tar.xz/" to "PAYLOAD".toByteArray()
            )
        )

        val member = LinuxRuntime.readArMember(file, "data.tar")

        assertEquals("data.tar.xz", member?.name)
        assertEquals("PAYLOAD", member?.bytes?.let { String(it) })
    }

    @Test
    fun `an odd-length member does not shift the next one`() {
        // A three-byte control member is padded with one byte. Missing that pad is
        // exactly how an ar reader silently starts reading the wrong file.
        val file = tempDeb(
            ar(
                "control.tar.xz/" to byteArrayOf(7, 7, 7),
                "data.tar.xz/" to "REAL".toByteArray()
            )
        )

        assertEquals("REAL", LinuxRuntime.readArMember(file, "data.tar")?.bytes?.let { String(it) })
    }

    @Test
    fun `the gnu symbol table is skipped`() {
        val file = tempDeb(
            ar(
                "/" to byteArrayOf(0, 0, 0),
                "data.tar.xz/" to "AFTER".toByteArray()
            )
        )

        assertEquals("AFTER", LinuxRuntime.readArMember(file, "data.tar")?.bytes?.let { String(it) })
    }

    @Test
    fun `a file that is not an ar archive yields nothing`() {
        assertEquals(null, LinuxRuntime.readArMember(tempDeb("not an archive".toByteArray()), "data.tar"))
    }

    @Test
    fun `a truncated member is refused rather than read past the end`() {
        val whole = ar("data.tar.xz/" to "TOOLONGPAYLOAD".toByteArray())
        val cut = whole.copyOf(whole.size - 6)

        assertEquals(null, LinuxRuntime.readArMember(tempDeb(cut), "data.tar"))
    }

    @Test
    fun `a member the archive does not contain yields nothing`() {
        val file = tempDeb(ar("control.tar.xz/" to "x".toByteArray()))

        assertTrue(LinuxRuntime.readArMember(file, "data.tar") == null)
    }
}
