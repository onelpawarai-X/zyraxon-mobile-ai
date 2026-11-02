package ai.zyraxon.assistant.shell

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ZYRAXON carries Shizuku inside its own APK, so the two things that can quietly
 * break are pinned here: that the bundled file is really there and really an APK,
 * and that a phone without Shizuku is reported honestly instead of pretending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShizukuBundleTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `the bundled Shizuku apk is present and is a real apk`() {
        val apk = ShizukuBundle.apkFile(context)
        assertNotNull("বান্ডেল করা Shizuku APK পাওয়া যায়নি", apk)
        assertTrue("বান্ডেল করা APK খালি", apk!!.length() > 100_000)
        // A zip (and therefore an APK) starts with "PK".
        val header = apk.inputStream().use { input ->
            val b = ByteArray(2)
            input.read(b)
            String(b)
        }
        assertEquals("বান্ডেল করা ফাইল APK নয়", "PK", header)
    }

    @Test
    fun `the bundled version and package are the ones we ship`() {
        assertEquals("moe.shizuku.privileged.api", ShizukuBundle.PACKAGE)
        assertEquals("13.6.0", ShizukuBundle.VERSION)
    }

    @Test
    fun `a phone without Shizuku reports not-installed, not active`() {
        // No Shizuku app and no Shizuku server exist in a unit test, which is the
        // common case on a fresh phone: the state must be honest.
        assertFalse(ShizukuBundle.isInstalled(context))
        assertEquals(ShizukuState.NotInstalled, ShizukuBundle.state(context))
    }
}
