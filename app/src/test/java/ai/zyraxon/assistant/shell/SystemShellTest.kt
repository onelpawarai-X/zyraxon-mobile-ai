package ai.zyraxon.assistant.shell

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The system-level shell is the whole point of the Shizuku work, so its contract
 * is pinned here. There is no Shizuku server in a unit test, which is exactly the
 * case that matters most: the app must not pretend it has system access, and must
 * not crash when it does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SystemShellTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `root is uid zero and shell is uid two thousand`() {
        assertEquals(0, SystemShell.Level.ROOT.uid)
        assertEquals(2000, SystemShell.Level.SHELL.uid)
    }

    @Test
    fun `root falls back to shell and shell falls back to nothing`() {
        assertEquals(SystemShell.Level.SHELL, SystemShell.Level.ROOT.fallback())
        assertNull(SystemShell.Level.SHELL.fallback())
    }

    @Test
    fun `without Shizuku the levels are reported unavailable`() {
        // No Shizuku server is running in a unit test.
        assertFalse("Shizuku ছাড়া available() true হওয়া উচিত নয়", SystemShell.available())
        val probe = SystemShell.probe(context)
        assertEquals(false, probe[SystemShell.Level.ROOT])
        assertEquals(false, probe[SystemShell.Level.SHELL])
    }

    @Test
    fun `without Shizuku a command returns null so the caller can fall back`() {
        assertNull("Shizuku ছাড়া run() null ফেরাতে হবে", SystemShell.run(context, "id -u"))
        assertNull("Shizuku ছাড়া runBest() null ফেরাতে হবে", SystemShell.runBest(context, "id -u"))
    }

    @Test
    fun `without Shizuku reading a system file is null, not a crash`() {
        assertNull(SystemShell.readFile(context, "/system/build.prop"))
    }

    @Test
    fun `the helper directory is the executable system temp`() {
        assertEquals("/data/local/tmp", SystemShell.systemTmp().absolutePath)
    }

    @Test
    fun `a command result knows success from failure`() {
        val good = SystemShell.Result("true", 0, "", "", false, 2000, 1)
        val bad = SystemShell.Result("false", 1, "", "", false, 2000, 1)
        val slow = SystemShell.Result("sleep 99", -1, "", "", true, 2000, 1)
        assertTrue(good.ok)
        assertFalse(bad.ok)
        assertFalse("টাইমআউট সফল নয়", slow.ok)
    }
}
