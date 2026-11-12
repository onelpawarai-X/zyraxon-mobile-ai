// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The defect these tests guard against: an English word inside a Bangla sentence
 * being read out letter by letter. That happens when the whole sentence is handed
 * to the engine while the Bangla voice is loaded, so the split has to be exact.
 */
class SpeechSegmenterTest {

    @Test
    fun `a pure Bangla sentence stays one run`() {
        val parts = SpeechSegmenter.split("আজ আবহাওয়া খুব সুন্দর")
        assertEquals(1, parts.size)
        assertEquals("bn-BD", parts[0].bcp47)
    }

    @Test
    fun `an English loanword stays inside its Bangla sentence`() {
        // The segmenter deliberately does not split every English loanword: neural
        // TTS reads "login" naturally inside Bangla, and splitting caused robotic
        // token-by-token churn. Only a whole sentence in another language switches.
        val parts = SpeechSegmenter.split("ফেসবুকে login করো")
        assertEquals(1, parts.size)
        assertEquals("bn-BD", parts[0].bcp47)
        assertEquals("ফেসবুকে login করো", parts[0].text)
    }

    @Test
    fun `no character is lost when splitting`() {
        val original = "গুগল প্লে স্টোর থেকে Chrome ইনস্টল করো"
        val rejoined = SpeechSegmenter.split(original).joinToString(" ") { it.text }
        assertEquals(original, rejoined)
    }

    @Test
    fun `numbers stay with the sentence they sit in`() {
        val parts = SpeechSegmenter.split("আমার বয়স 24 বছর")
        assertEquals(1, parts.size)
        assertEquals("bn-BD", parts[0].bcp47)
    }

    @Test
    fun `a fully English sentence is one English run`() {
        val parts = SpeechSegmenter.split("Open the settings page")
        assertEquals(1, parts.size)
        assertEquals("en-US", parts[0].bcp47)
    }

    @Test
    fun `empty text produces nothing to speak`() {
        assertEquals(0, SpeechSegmenter.split("   ").size)
    }
}
