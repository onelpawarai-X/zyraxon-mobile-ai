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
    fun `an unknown abi has no folder rather than a wrong one`() {
        // Returning a folder that does not exist reads as "no packages found",
        // which hides the real cause. Null lets the caller say what is missing.
        assertEquals(null, LinuxRuntime.arch("mips"))
    }

    @Test
    fun `a device that lists a 32-bit abi first still gets the arm64 payload`() {
        // The APK declares four ABIs but only two payloads are vendored. A phone
        // whose first ABI is armeabi-v7a must fall through to a payload that is
        // really there instead of being sent to an absent `arm` folder.
        val vendored = setOf("aarch64", "x86_64")
        val chosen = LinuxRuntime.pickAssetDir(
            arrayOf("armeabi-v7a", "arm64-v8a", "armeabi")
        ) { it in vendored }

        assertEquals("linux/aarch64", chosen)
    }

    @Test
    fun `no vendored payload yields null so the caller can explain`() {
        val chosen = LinuxRuntime.pickAssetDir(arrayOf("armeabi-v7a", "x86")) { false }

        assertEquals(null, chosen)
    }

    @Test
    fun `the device's own abi wins when its payload is vendored`() {
        val chosen = LinuxRuntime.pickAssetDir(arrayOf("x86_64", "arm64-v8a")) { true }

        assertEquals("linux/x86_64", chosen)
    }

    // ---- the prefix the guest is shown --------------------------------------

    @Test
    fun `the guest prefix is clean and never mentions the upstream build path`() {
        // The user sees $PREFIX, $HOME and pwd. Those must be ordinary Unix paths,
        // never the payload's upstream Termux path, which is an implementation
        // detail and would leak a third-party name into our Terminal.
        assertEquals("/usr", LinuxRuntime.GUEST_PREFIX)
        assertEquals("/home", LinuxRuntime.GUEST_HOME)
        assertTrue(!LinuxRuntime.GUEST_PREFIX.contains("termux"))
        assertTrue(!LinuxRuntime.GUEST_HOME.contains("termux"))
    }

    @Test
    fun `a symlink target on the build path is rewritten to the guest prefix`() {
        // The payload's links point at its own build path. Leaving that in place
        // would make the links depend on the compat bind and would leak the
        // upstream name into anything that prints a link target.
        assertEquals(
            "/usr/share/keys/a.gpg",
            LinuxRuntime.guestTarget("/data/data/com.termux/files/usr/share/keys/a.gpg")
        )
    }

    @Test
    fun `a relative or already clean target is left alone`() {
        assertEquals("ncurses.h", LinuxRuntime.guestTarget("ncurses.h"))
        assertEquals("/usr/bin/bash", LinuxRuntime.guestTarget("/usr/bin/bash"))
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
