package ai.zyraxon.assistant.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs the real shell, not a mock of it. The terminal is only worth having if
 * the commands actually execute on the device, so these tests execute them.
 *
 * Robolectric's JVM is x86_64 Linux, which is one of the four ABIs the static
 * busybox is packaged for, so the same binary the phone would run is the one
 * these tests run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellRuntimeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun installBusyboxWhereTheAppLooksForIt() = TestBusybox.install(context)

    @Test
    fun `the bundled busybox is present and executable`() {
        val bb = ShellRuntime.binary(context)
        assertNotNull("no busybox in nativeLibraryDir for this ABI", bb)
        assertTrue("busybox is not executable", bb!!.canExecute())
        assertTrue("busybox is suspiciously small", bb.length() > 100_000)
    }

    @Test
    fun `it provides the applets the terminal promises`() {
        ShellRuntime.ensureReady(context)
        val bin = ShellRuntime.binDir(context)
        // A handful of the commands the welcome text names, each a real symlink
        // the applet farm built from `busybox --list`.
        for (name in listOf("sh", "awk", "sed", "grep", "tar", "wget", "find", "xargs", "sort", "xxd")) {
            assertTrue("applet missing from PATH: $name", File(bin, name).exists())
        }
    }

    @Test
    fun `a command really runs and its stdout comes back`() {
        val r = ShellRuntime.run(context, "echo zyraxon-terminal")
        assertTrue("command failed: ${r.stderr}", r.ok)
        assertEquals("zyraxon-terminal", r.stdout.trim())
        assertEquals(0, r.code)
    }

    @Test
    fun `an applet on PATH resolves by name`() {
        // `awk` is not a /system/bin applet; it can only work through the farm.
        val r = ShellRuntime.run(context, "printf 'b\\na\\nc\\n' | sort | tr '\\n' ','")
        assertTrue("pipeline failed: ${r.stderr}", r.ok)
        assertEquals("a,b,c,", r.stdout.trim())
    }

    @Test
    fun `stderr and the exit code are reported separately`() {
        val r = ShellRuntime.run(context, "echo oops >&2; exit 3")
        assertEquals(3, r.code)
        assertFalse(r.ok)
        assertEquals("oops", r.stderr.trim())
        assertEquals("", r.stdout.trim())
    }

    @Test
    fun `the working directory is the one that was asked for`() {
        val dir = File(ShellRuntime.home(context), "sub/deep").apply { mkdirs() }
        val r = ShellRuntime.run(context, "pwd", cwd = dir)
        assertTrue(r.ok)
        assertEquals(dir.canonicalPath, r.stdout.trim())
    }

    @Test
    fun `HOME and PATH point inside the app's own storage`() {
        val r = ShellRuntime.run(context, "echo \$HOME; echo \$PATH")
        assertTrue(r.ok)
        val lines = r.stdout.trim().lines()
        assertEquals(ShellRuntime.home(context).absolutePath, lines[0])
        assertTrue("PATH does not include the applet farm", lines[1].contains(ShellRuntime.binDir(context).absolutePath))
    }

    @Test
    fun `a command that never finishes is cut off, not hung on`() {
        val r = ShellRuntime.run(context, "sleep 30", timeoutMs = 1_500)
        assertTrue("should have timed out", r.timedOut)
        assertFalse(r.ok)
    }

    @Test
    fun `platform_info reports the bundled toolbox`() {
        val text = ShellRuntime.describe(context)
        assertTrue(text, text.contains("busybox"))
        assertTrue(text, text.contains("applets"))
    }
}
