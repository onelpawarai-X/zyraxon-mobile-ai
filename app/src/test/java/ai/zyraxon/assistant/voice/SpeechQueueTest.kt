package ai.zyraxon.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's complaint that the voice stopped after a word or two, and that a
 * new message was not read until the reply arrived.
 *
 * A mixed sentence is several runs; each run must be spoken only after the
 * previous one has finished, or the voice switch silences it. These tests play a
 * multi-run reply through the real queue and assert that every piece is spoken,
 * in order, one at a time.
 */
class SpeechQueueTest {

    private fun utterance(id: String) = SpeechUtterance(id, "bn-BD", 1f, 1f, id)

    /** Plays the queue to the end, returning the ids in the order they were spoken. */
    private fun drain(queue: SpeechQueue): List<String> {
        val spoken = mutableListOf<String>()
        while (true) {
            val next = queue.takeNext() ?: break
            spoken.add(next.id)
            queue.markSpoken(next.id)
            // The engine finishing one piece is what releases the next.
            queue.onFinished(next.id)
        }
        return spoken
    }

    @Test
    fun `every run of a mixed sentence is spoken, in order`() {
        val queue = SpeechQueue()
        queue.enqueue(listOf(utterance("bn1"), utterance("en1"), utterance("bn2")))
        assertEquals(listOf("bn1", "en1", "bn2"), drain(queue))
    }

    @Test
    fun `a second piece is not handed out while the first is still playing`() {
        val queue = SpeechQueue()
        queue.enqueue(listOf(utterance("a"), utterance("b")))
        val first = queue.takeNext()!!
        queue.markSpoken(first.id)
        // Nothing more may start until "a" finishes — that overlap is what cut the
        // voice off after a word or two.
        assertNull("একসাথে দুটো কথা বলা যাবে না", queue.takeNext())
    }

    @Test
    fun `finishing the active piece releases the next`() {
        val queue = SpeechQueue()
        queue.enqueue(listOf(utterance("a"), utterance("b")))
        queue.takeNext()?.let { queue.markSpoken(it.id) }
        queue.onFinished("a")
        assertEquals("b", queue.takeNext()?.id)
    }

    @Test
    fun `a stale finish callback does not free the queue early`() {
        val queue = SpeechQueue()
        queue.enqueue(listOf(utterance("a"), utterance("b")))
        queue.takeNext()?.let { queue.markSpoken(it.id) }
        // "z" is not the active piece, so this must be ignored.
        queue.onFinished("z")
        assertNull("পুরনো কলব্যাক কিউ খুলে দিল", queue.takeNext())
    }

    @Test
    fun `a reply streamed sentence by sentence is fully spoken`() {
        val queue = SpeechQueue()
        // Three sentences arrive one after another, as the model streams.
        queue.enqueue(listOf(utterance("s1")))
        queue.enqueue(listOf(utterance("s2")))
        queue.enqueue(listOf(utterance("s3")))
        assertEquals(listOf("s1", "s2", "s3"), drain(queue))
    }

    @Test
    fun `clearing drops everything still waiting`() {
        val queue = SpeechQueue()
        queue.enqueue(listOf(utterance("a"), utterance("b")))
        queue.takeNext()?.let { queue.markSpoken(it.id) }
        queue.clear()
        assertTrue(queue.isEmpty())
        assertNull(queue.takeNext())
    }
}
