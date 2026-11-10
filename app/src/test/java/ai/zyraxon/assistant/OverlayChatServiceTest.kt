package ai.zyraxon.assistant

import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * Starts the real overlay service and inspects the real WindowManager, so the
 * bubble the user asked for is proven to be added rather than assumed.
 *
 * The permission gate is the interesting branch: without the overlay permission
 * `addView` throws and the service would die, taking the toggle with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OverlayChatServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun addedViews(service: OverlayChatService): Int {
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // The base shadow has no view list; the impl shadow is the one that
        // actually records addView/removeView.
        val shadow = shadowOf(wm) as org.robolectric.shadows.ShadowWindowManagerImpl
        return shadow.views.size
    }

    @Test
    fun `the bubble is really added, then removed on destroy`() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(OverlayChatService::class.java).create()
        val service = controller.get()

        controller.startCommand(0, 0)
        assertEquals("ঠিক একটা বাবল যোগ হতে হবে", 1, addedViews(service))

        controller.destroy()
        assertEquals("destroy-এ বাবল সরাতে হবে", 0, addedViews(service))
    }

    @Test
    fun `the service stops itself instead of crashing when the permission is missing`() {
        ShadowSettings.setCanDrawOverlays(false)

        val controller = Robolectric.buildService(OverlayChatService::class.java).create()
        val service = controller.get()
        controller.startCommand(0, 0)

        assertEquals("অনুমতি ছাড়া কিছু যোগ করা যাবে না", 0, addedViews(service))
        assertTrue("সার্ভিস নিজেই বন্ধ হতে হবে", shadowOf(service).isStoppedBySelf)

        controller.destroy()
    }

    @Test
    fun `start and stop reach the right service`() {
        OverlayChatService.start(context)
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNotNull("startService-এ সঠিক সার্ভিস থাকতে হবে", started)
        assertEquals(
            "ভুল সার্ভিস শুরু হয়েছে",
            OverlayChatService::class.java.name,
            started.component?.className
        )

        OverlayChatService.stop(context)
        val stopped = shadowOf(RuntimeEnvironment.getApplication()).nextStoppedService
        assertEquals(
            "ভুল সার্ভিস বন্ধ হয়েছে",
            OverlayChatService::class.java.name,
            stopped.component?.className
        )
    }
}
