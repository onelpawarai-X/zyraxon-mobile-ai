package ai.zyraxon.assistant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import ai.zyraxon.assistant.ui.ZyraxonTopBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real top bar, so a broken live-voice button fails here rather than
 * on the phone. The bar is stateless, which is why it can be driven directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveVoiceUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the live voice button is on screen and reports taps`() {
        var taps = 0
        compose.setContent {
            ZyraxonTopBar(
                personaName = "প্রেমিকা",
                personaEmoji = "💖",
                modelName = "gemini",
                liveActive = false,
                onPersonaClick = {},
                onModelClick = {},
                onLiveClick = { taps++ },
                onSettingsClick = {},
                onClearClick = {}
            )
        }

        compose.onNodeWithTag("live_voice_button").assertIsDisplayed()
        compose.onNodeWithTag("live_voice_button").performClick()
        compose.waitForIdle()
        assertEquals("লাইভ বাটনের ট্যাপ ধরা পড়েনি", 1, taps)
    }

    @Test
    fun `the persona and model pills still work next to the live button`() {
        var persona = 0
        var model = 0
        compose.setContent {
            ZyraxonTopBar(
                personaName = "প্রেমিকা",
                personaEmoji = "💖",
                modelName = "gemini",
                liveActive = true,
                onPersonaClick = { persona++ },
                onModelClick = { model++ },
                onLiveClick = {},
                onSettingsClick = {},
                onClearClick = {}
            )
        }

        compose.onNodeWithTag("persona_button").performClick()
        compose.onNodeWithTag("model_picker_button").performClick()
        compose.waitForIdle()
        assertTrue("পার্সোনা বাটন কাজ করছে না", persona == 1)
        assertTrue("মডেল বাটন কাজ করছে না", model == 1)
    }
}
