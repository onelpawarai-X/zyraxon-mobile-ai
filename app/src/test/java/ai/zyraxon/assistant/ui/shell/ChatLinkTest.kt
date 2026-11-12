// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.shell

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A link in a reply must be tappable and must open the phone's real browser.
 *
 * The rendering and the tap-to-intent wiring are both real here: the test renders
 * [MarkdownText], reads the link annotation Compose actually built, and fires its
 * listener the way a tap would. Only Compose's own hit-testing is skipped, since
 * that is framework behaviour and not what this code owns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatLinkTest {

    @get:Rule
    val compose = createComposeRule()

    private fun linksIn(rendered: String, source: String): List<LinkAnnotation> {
        compose.setContent { MarkdownText(source) }
        val node = compose.onNodeWithText(rendered, substring = true).fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].first()
        return text.getLinkAnnotations(0, text.length).map { it.item }
    }

    @Test
    fun aBareAddressIsALinkNotPlainText() {
        val links = linksIn("সাইটটি এখানে https://onelpawarai-x.github.io/zyraxon/ দেখো", "সাইটটি এখানে https://onelpawarai-x.github.io/zyraxon/ দেখো")
        assertEquals(1, links.size)
        assertEquals("https://onelpawarai-x.github.io/zyraxon/", (links[0] as LinkAnnotation.Url).url)
    }

    @Test
    fun aMarkdownLinkKeepsItsTarget() {
        val links = linksIn("ডক দেখো", "ডক দেখো [ডকুমেন্ট](https://zyraxonai.lovable.app/docs/sdk)")
        assertEquals(1, links.size)
        assertEquals("https://zyraxonai.lovable.app/docs/sdk", (links[0] as LinkAnnotation.Url).url)
    }

    @Test
    fun aTrailingPeriodIsNotPartOfTheLink() {
        val links = linksIn("দেখো https://x.dev/a।", "দেখো https://x.dev/a।")
        assertEquals("https://x.dev/a", (links[0] as LinkAnnotation.Url).url)
    }

    @Test
    fun tappingALinkOpensTheRealBrowser() {
        val links = linksIn("দেখো https://x.dev/a", "দেখো https://x.dev/a")
        val url = links[0] as LinkAnnotation.Url
        val listener = url.linkInteractionListener
        assertNotNull("the link has no tap handler", listener)

        listener!!.onClick(url)

        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("no browser intent was fired", started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://x.dev/a", started.data.toString())
        assertTrue(
            "the intent must survive being fired from a service-drawn chat",
            started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
    }

    @Test
    fun aLinkInsideBoldTextIsStillALink() {
        // The model bolds an address about as often as it writes it bare.
        val links = linksIn("দেখো", "দেখো **https://x.dev/a** এখন")
        assertEquals(1, links.size)
        assertEquals("https://x.dev/a", (links[0] as LinkAnnotation.Url).url)
    }

    @Test
    fun plainTextHasNoLinks() {
        val links = linksIn("কোনো লিংক নেই এখানে", "কোনো লিংক নেই এখানে")
        assertTrue(links.isEmpty())
    }
}
