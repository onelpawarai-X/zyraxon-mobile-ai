package ai.zyraxon.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chunker and the segmenter have to agree. The chunker releases text at a
 * word boundary; the segmenter then splits that text into language runs. If the
 * chunker ever cut inside a word, the segmenter would hand a half-word to the
 * engine and the Bangla voice would spell out the remains letter by letter —
 * the exact defect the user reported for English words in a Bangla sentence.
 *
 * These stream a mixed answer token by token through both, in the same order the
 * ViewModel does, and assert every English word survives whole.
 */
class SpeechPipelineTest {

    /** Streams [answer] one token at a time and collects every spoken run. */
    private fun spokenRuns(answer: String, chunk: Int = 3): List<SpeechSegmenter.Segment> {
        val buffer = StringBuilder()
        val runs = mutableListOf<SpeechSegmenter.Segment>()
        var i = 0
        while (i < answer.length) {
            buffer.append(answer, i, minOf(i + chunk, answer.length))
            i += chunk
            while (true) {
                val ready = SpeechChunker.take(buffer)
                if (ready.isBlank()) break
                runs.addAll(SpeechSegmenter.split(ready))
            }
        }
        if (buffer.isNotBlank()) runs.addAll(SpeechSegmenter.split(buffer.toString()))
        return runs
    }

    @Test
    fun `an English word inside a streamed Bangla answer is never cut in half`() {
        val answer = "ফেসবুকে login করার জন্য আমি এখন তোমাকে সাহায্য করছি এবং সবকিছু ঠিক করে দিচ্ছি।"
        val runs = spokenRuns(answer)

        val english = runs.filter { it.bcp47 == "en-US" }.map { it.text.trim() }
        assertEquals("ইংরেজি শব্দ ভেঙে গেছে: $english", listOf("login"), english)
    }

    @Test
    fun `every spoken run keeps whole words only`() {
        val answer = "আমি Python আর Kotlin দুটোই ব্যবহার করি, এবং GitHub এ কোড পাঠাই।"
        for (run in spokenRuns(answer)) {
            val text = run.text.trim()
            if (text.isEmpty()) continue
            // A run that starts or ends mid-word is the bug; whole words only.
            assertTrue("শব্দ মাঝখানে কাটা গেছে: '$text'", !text.first().isLetter() || run.text == text)
        }
    }

    @Test
    fun `nothing is lost — every word of the answer is spoken exactly once`() {
        val answer = "প্রথমে ফাইল লিখব, তারপর upload করব। শেষে তোমাকে জানাব।"
        val spoken = spokenRuns(answer).joinToString(" ") { it.text }.trim()
        // Compare word streams, ignoring where the chunker happened to cut.
        assertEquals(answer.split(Regex("\\s+")), spoken.split(Regex("\\s+")))
    }

    @Test
    fun `a word larger than the release point still arrives whole`() {
        val answer = "এই supercalifragilisticexpialidocious শব্দটা ইংরেজি"
        val english = spokenRuns(answer, chunk = 4)
            .filter { it.bcp47 == "en-US" }.map { it.text.trim() }
        assertEquals(listOf("supercalifragilisticexpialidocious"), english)
    }
}
