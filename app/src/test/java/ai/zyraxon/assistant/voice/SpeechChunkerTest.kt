package ai.zyraxon.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user asked for speech that keeps pace with the text. These tests pin the
 * two release points — a sentence end, and a comma inside a long run — so a long
 * clause cannot sit silent while the answer is already on screen.
 */
class SpeechChunkerTest {

    @Test
    fun `a finished sentence is released whole`() {
        val buffer = StringBuilder("আমি ফাইলটা লিখে ফেলেছি। এরপর কী করব?")
        val ready = SpeechChunker.take(buffer)
        assertEquals("আমি ফাইলটা লিখে ফেলেছি।", ready)
        assertEquals(" এরপর কী করব?", buffer.toString())
    }

    @Test
    fun `an unfinished clause waits`() {
        val buffer = StringBuilder("আমি এখন ফাইলটা")
        assertEquals("", SpeechChunker.take(buffer))
        assertEquals("আমি এখন ফাইলটা", buffer.toString())
    }

    @Test
    fun `a long clause with a comma speaks before the full stop arrives`() {
        val long = "আমি এখন তোমার জন্য একটা বড় ফাইল লিখছি, তারপর সেটা তোমাকে দেখাব এবং"
        val buffer = StringBuilder(long)
        val ready = SpeechChunker.take(buffer)
        assertTrue("কমা-সীমা পেরিয়ে কথা শুরু হওয়া উচিত", ready.isNotBlank())
        assertTrue("কমা-অংশটাই আগে যাবে", ready.trimEnd().endsWith(","))
        assertEquals(long.substring(ready.length), buffer.toString())
    }

    @Test
    fun `an English full stop also releases`() {
        val buffer = StringBuilder("Done. Next step")
        assertEquals("Done.", SpeechChunker.take(buffer))
    }

    @Test
    fun `an empty buffer releases nothing`() {
        assertEquals("", SpeechChunker.take(StringBuilder()))
    }
}
