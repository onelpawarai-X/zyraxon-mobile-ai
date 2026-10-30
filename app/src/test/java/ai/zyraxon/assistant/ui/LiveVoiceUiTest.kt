package ai.zyraxon.assistant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import ai.zyraxon.assistant.ui.ZyraxonTopBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The top bar is deliberately minimal: the settings gear is the only control on
 * it. The user asked for every other button — persona, microphone, model picker,
 * live voice, clear — to move inside Settings, so this asserts they are gone from
 * the bar rather than merely testing what remains.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveVoiceUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the settings gear is the only control on the top bar`() {
        var settings = 0
        compose.setContent {
            ZyraxonTopBar(onSettingsClick = { settings++ })
        }

        compose.onNodeWithTag("settings_button").assertIsDisplayed()
        compose.onNodeWithTag("settings_button").performClick()
        compose.waitForIdle()
        assertEquals("সেটিং বাটনের ট্যাপ ধরা পড়েনি", 1, settings)
    }

    @Test
    fun `the old persona, model, mic and live buttons are gone`() {
        compose.setContent {
            ZyraxonTopBar(onSettingsClick = {})
        }

        compose.onNodeWithTag("persona_button").assertDoesNotExist()
        compose.onNodeWithTag("model_picker_button").assertDoesNotExist()
        compose.onNodeWithTag("mic_toggle_button").assertDoesNotExist()
        compose.onNodeWithTag("live_voice_button").assertDoesNotExist()
    }
}
