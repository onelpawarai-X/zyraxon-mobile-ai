// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui

import ai.zyraxon.assistant.ui.shell.ComposerMicButton
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
 * The composer was rebuilt around the aura, and the button went with it: the
 * control now lives in the shell, so this renders the real composer and asserts
 * the control is present and reports every tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MicButtonUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun composer(micOn: Boolean, onToggle: () -> Unit) {
        compose.setContent {
            ComposerMicButton(isListening = micOn, onMicToggle = onToggle)
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
