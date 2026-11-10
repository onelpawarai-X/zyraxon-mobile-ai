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
        val spoken = SpeechTextCleaner.clean("**ঠিক আছে!** 😀 আমি ফাইল বানাচ্ছি 🎉 [ডক](https://x.dev)")
        assertFalse(spoken.contains("*"))
        assertFalse(spoken.contains("`"))
        assertFalse(spoken.contains("http"))
        assertTrue(spoken.contains("ঠিক আছে"))
        assertTrue(spoken.contains("ফাইল"))
        assertTrue(spoken.startsWith("ঠিক আছে"))
    }

    @Test
    fun neverSpeaksAToolName() {
        // The engine injects a status line per tool call. It is on screen and must
        // never reach the speaker, or the reply is punctuated with "run shell ok".
        val spoken = SpeechTextCleaner.clean(
            "ঠিক আছে, `run_shell` আর `open_app` দিয়ে দেখছি। এখন কী করব?"
        )
        assertFalse("tool name was spoken", spoken.contains("run_shell"))
        assertFalse("tool name was spoken", spoken.contains("open_app"))
        assertTrue(spoken.contains("ঠিক আছে"))
        assertTrue(spoken.contains("এখন কী করব?"))
    }

    @Test
    fun neverSpeaksAModelOrProviderId() {
        // The failover line names the provider it switched to in backticks. Read
        // aloud it is "g-p-t four o", so the whole span is dropped.
        val spoken = SpeechTextCleaner.clean("\n\n`gpt-4o` — আগের প্রোভাইডার সীমায় পৌঁছেছে, চালিয়ে যাচ্ছি।\n\n")
        assertFalse(spoken.contains("gpt-4o"))
        assertTrue(spoken.contains("চালিয়ে যাচ্ছি"))
    }

    @Test
    fun neverSpellsOutALink() {
        // Reading "h-t-t-p-s colon slash slash" is minutes of noise mid-answer.
        val spoken = SpeechTextCleaner.clean(
            "সাইটটি এখানে দেখো: https://onelpawarai-x.github.io/zyraxon/ — আর www.example.com/path?x=1 ।"
        )
        assertFalse("a link was spoken", spoken.contains("http"))
        assertFalse("a link was spoken", spoken.contains("www."))
        assertFalse(spoken.contains("example.com"))
        assertTrue(spoken.contains("সাইটটি এখানে দেখো"))
    }

    @Test
    fun keepsASentenceIntactAroundALink() {
        // The address is spoken as a natural phrase, and the sentence around it is
        // never broken — the words on either side stay in order.
        val spoken = SpeechTextCleaner.clean("ফলাফল https://x.dev/a বেরিয়ে এসেছে।")
        assertEquals("ফলাফল x ওয়েবসাইটের লিঙ্ক বেরিয়ে এসেছে।", spoken)
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
