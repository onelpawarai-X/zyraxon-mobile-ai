// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

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

    @Test
    fun dropsEveryEmojiTheOldBlocksMissed() {
        // These sit outside the pictograph ranges the first version covered, so each
        // one was handed to the engine and read aloud as its own name — "trade mark
        // sign", "keycap", "information source" — in the middle of a Bangla answer.
        val spoken = SpeechTextCleaner.clean(
            "ঠিক আছে \u203C \u2049 \u2122 \u2139 \u2934 \u3030 \u303D \u3297 \u3299 চলো"
        )
        assertEquals("ঠিক আছে চলো", spoken)
        // A keycap is a digit followed by U+FE0F U+20E3. The mark is dropped and the
        // digit stays: it is a number the sentence is about.
        assertEquals("নম্বর 1 দেখো", SpeechTextCleaner.clean("নম্বর 1\uFE0F\u20E3 দেখো"))
    }

    @Test
    fun dropsEmojiAboveTheBmp() {
        // The previous rule spelled this as a surrogate-pair class, which java.util.regex
        // does not fold into one code point, so it never matched and every one of these
        // reached the voice endpoint.
        assertEquals("ঠিক আছে চলো", SpeechTextCleaner.clean("ঠিক আছে \uD83D\uDE00 \uD83C\uDF89 চলো"))
        assertEquals("ভালো লাগছে", SpeechTextCleaner.clean("ভালো \uD83D\uDC4D\uD83C\uDFFD লাগছে"))
        assertEquals("দেশ ভালো", SpeechTextCleaner.clean("দেশ \uD83C\uDDE7\uD83C\uDDE9 ভালো"))
    }

    @Test
    fun dropsTagCharactersAndVariationSelectors() {
        // A subdivision flag is a tag sequence: the base is a pictograph, and the four
        // tag characters behind it are not, so they survived as invisible-but-spoken
        // code points.
        val flag = "\uD83C\uDFF4\uDB40\uDC62\uDB40\uDC67\uDB40\uDC6C\uDB40\uDC7F"
        assertEquals("পতাকা দেখো", SpeechTextCleaner.clean("পতাকা $flag দেখো"))
        assertEquals("এক", SpeechTextCleaner.clean("এক\uFE00\uFE0F"))
    }

    @Test
    fun keepsTextTheBlocksAreNotMeantToTouch() {
        // The drops are by block, so nothing adjacent to an emoji is collateral:
        // Bangla, Latin, digits, the Bangla full stop and the question mark all survive.
        assertEquals(
            "কাজ শেষ। 2 + 2 = 4",
            SpeechTextCleaner.clean("কাজ শেষ। 2 + 2 = 4")
        )
        assertEquals(
            "তিনি কী করেন? আমি যাচ্ছি।",
            SpeechTextCleaner.clean("তিনি কী করেন? আমি যাচ্ছি।")
        )
    }
}
