package ai.zyraxon.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A published site belongs in the app's own preview.
 *
 * The defect: `publish_website` handed the finished page to Chrome, which opened
 * over the assistant, so the site the agent had just built vanished behind an
 * external app. These assertions pin the hand-off the UI relies on — one offer,
 * taken exactly once, so the panel switches and a rotation does not reopen it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PublishedSiteTest {

    @Test
    fun `a published site is offered and taken exactly once`() {
        PreviewBridge.publishedSite.value = null
        PreviewBridge.offerPublishedSite("http://127.0.0.1:8791/")
        assertEquals("http://127.0.0.1:8791/", PreviewBridge.publishedSite.value)
        assertEquals("http://127.0.0.1:8791/", PreviewBridge.takePublishedSite())
        assertNull("the offer must not survive being taken", PreviewBridge.publishedSite.value)
        assertNull(PreviewBridge.takePublishedSite())
    }

    @Test
    fun `a public tunnel link is offered the same way`() {
        PreviewBridge.publishedSite.value = null
        PreviewBridge.offerPublishedSite("https://calm-river.trycloudflare.com")
        assertEquals("https://calm-river.trycloudflare.com", PreviewBridge.takePublishedSite())
    }
}
