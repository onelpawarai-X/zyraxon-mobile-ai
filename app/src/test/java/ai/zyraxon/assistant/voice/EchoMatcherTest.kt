// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's complaint: the assistant's own spoken answer is typed back into the
 * chat and sent as if the user had said it. The time-based echo guard covers the
 * moment the engine talks; this covers the echo that arrives just after, by
 * comparing the words heard with the reply just spoken.
 */
class EchoMatcherTest {

    @Test
    fun `the assistant's own sentence coming back is recognised`() {
        val reply = "আমি তোমাকে ভালোবাসি, আজ আবহাওয়া খুব সুন্দর।"
        val heard = "আমি তোমাকে ভালোবাসি আজ আবহাওয়া সুন্দর"
        assertTrue(
            "নিজের কথা ফেরত এলে ধরা পড়েনি",
            EchoMatcher.isOwnSpeech(heard, reply)
        )
    }

    @Test
    fun `the user's own new words are not treated as echo`() {
        val reply = "আজ আবহাওয়া খুব সুন্দর।"
        val heard = "আমার নাম কী মনে আছে তোমার"
        assertFalse(
            "ব্যবহারকারীর নতুন কথা ভুল করে আটকে গেছে",
            EchoMatcher.isOwnSpeech(heard, reply)
        )
    }

    @Test
    fun `a short answer like yes is never swallowed`() {
        val reply = "আমি তোমাকে ভালোবাসি, আজ আবহাওয়া খুব সুন্দর।"
        assertFalse("হ্যাঁ জাতীয় ছোট উত্তর আটকে গেছে", EchoMatcher.isOwnSpeech("হ্যাঁ", reply))
        assertFalse("ok আটকে গেছে", EchoMatcher.isOwnSpeech("ok", reply))
    }

    @Test
    fun `english echo is caught the same way`() {
        val reply = "Sure, I can open the camera and take a photo for you."
        val heard = "sure i can open the camera"
        assertTrue("ইংরেজি প্রতিধ্বনি ধরা পড়েনি", EchoMatcher.isOwnSpeech(heard, reply))
    }

    @Test
    fun `no reply yet means nothing is echo`() {
        assertFalse(EchoMatcher.isOwnSpeech("যেকোনো কথা", null))
        assertFalse(EchoMatcher.isOwnSpeech("যেকোনো কথা", ""))
    }

    @Test
    fun `a phrase made only of spoken words is echo even when misheard`() {
        // The reported leak: the recognizer heard the assistant's own sentence
        // slightly differently, so the half-overlap bar missed it and the settle
        // timer posted it as the user's message. Every content word is still one
        // the voice said, which is enough.
        val spoken = "আজকের আবহাওয়া খুব সুন্দর তাই তোমাকে বললাম"
        assertTrue(EchoMatcher.allWordsIn("আবহাওয়া সুন্দর বললাম", spoken))
        assertTrue(EchoMatcher.allWordsIn("তোমাকে আজকের আবহাওয়া", spoken))
    }

    @Test
    fun `a real answer that introduces a new word is not echo`() {
        val spoken = "আজকের আবহাওয়া খুব সুন্দর তাই তোমাকে বললাম"
        assertFalse("নতুন শব্দ থাকা সত্ত্বেও আটকে গেছে", EchoMatcher.allWordsIn("আবহাওয়া কেমন আজকে", spoken))
        assertFalse(EchoMatcher.allWordsIn("হ্যাঁ", spoken))
    }

    @Test
    fun `all words in needs something to compare against`() {
        assertFalse(EchoMatcher.allWordsIn("আজকের আবহাওয়া", null))
        assertFalse(EchoMatcher.allWordsIn("আজকের আবহাওয়া", ""))
        assertFalse(EchoMatcher.allWordsIn("   ", "আজকের আবহাওয়া"))
    }
}
