package ai.zyraxon.assistant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The user asked for a real microphone button in the chat box — one tap to hold
 * the mic closed, one tap to open it — instead of having to dig into Settings.
 * This renders the real composer and asserts the button is there and that it
 * reports every tap, which is what drives the ViewModel's own switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MicButtonUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun composer(micOn: Boolean, onToggle: () -> Unit) {
        compose.setContent {
            BottomComposer(
                text = "",
                onTextChange = {},
                onSend = {},
                isListening = micOn,
                micOn = micOn,
                onMicToggle = onToggle,
                onQuickAction = {}
            )
        }
    }

    @Test
    fun `the microphone button is in the composer`() {
        composer(micOn = true) {}
        compose.onNodeWithTag("mic_button").assertIsDisplayed()
    }

    @Test
    fun `tapping the microphone button reports one toggle`() {
        var toggles = 0
        composer(micOn = true) { toggles++ }
        compose.onNodeWithTag("mic_button").performClick()
        compose.waitForIdle()
        assertEquals("মাইক বাটনের ট্যাপ ধরা পড়েনি", 1, toggles)
    }

    @Test
    fun `the button is still there when the mic is off`() {
        var toggles = 0
        composer(micOn = false) { toggles++ }
        compose.onNodeWithTag("mic_button").assertIsDisplayed()
        compose.onNodeWithTag("mic_button").performClick()
        compose.waitForIdle()
        assertEquals("বন্ধ অবস্থায় মাইক আবার চালু করা যায় না", 1, toggles)
    }
}
