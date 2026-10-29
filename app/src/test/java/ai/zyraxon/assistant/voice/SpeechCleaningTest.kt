package ai.zyraxon.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spoken text must carry no markdown or emoji: the Android engine reads those
 * characters aloud as words and the reply sounds broken.
 */
class SpeechCleaningTest {

    @Test
    fun stripsMarkdownAndEmojiFromSpokenText() {
        val spoken = SpeechTextCleaner.clean("**ঠিক আছে!** 😀 আমি `ফাইল` বানাচ্ছি 🎉 [ডক](https://x.dev)")
        assertFalse(spoken.contains("*"))
        assertFalse(spoken.contains("`"))
        assertFalse(spoken.contains("http"))
        assertTrue(spoken.contains("ঠিক আছে"))
        assertTrue(spoken.contains("ফাইল"))
        assertTrue(spoken.startsWith("ঠিক আছে"))
    }

    @Test
    fun keepsPlainBanglaUntouched() {
        val plain = "আমি এখন ফেসবুক খুলছি, একটু অপেক্ষা করো।"
        assertEquals(plain, SpeechTextCleaner.clean(plain))
    }

    @Test
    fun dropsCodeFenceAndBullets() {
        val spoken = SpeechTextCleaner.clean("```kotlin\nval x = 1\n```\n- প্রথম কাজ\n- দ্বিতীয় কাজ")
        assertFalse(spoken.contains("val x"))
        assertFalse(spoken.contains("-"))
        assertTrue(spoken.contains("প্রথম কাজ"))
    }

    @Test
    fun collapsesWhitespace() {
        assertEquals("এক দুই", SpeechTextCleaner.clean("  এক    দুই  "))
    }
}
