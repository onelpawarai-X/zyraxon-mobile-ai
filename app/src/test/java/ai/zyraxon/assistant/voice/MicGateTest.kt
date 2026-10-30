package ai.zyraxon.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's worst symptom: the microphone went off and on again for every
 * sentence of a streamed answer. This is the exact scenario, played through the
 * real gate: a three-sentence reply must close the ear once and open it once, not
 * six times. It fails loudly if the churn ever comes back.
 */
class MicGateTest {

    /** Plays a reply of [sentences] through the gate, returning how many times the ear moved. */
    private fun playReply(gate: MicGate, sentences: Int): Pair<Int, Int> {
        var closes = 0
        var opens = 0
        gate.beginReply()
        for (s in 1..sentences) {
            // The engine reports "still speaking" for every sentence but the last,
            // which is exactly the mid-answer idle gap that caused the churn.
            val engineBusyAfter = s < sentences
            if (gate.onUtteranceStart()) closes++
            if (gate.onUtteranceDone(engineBusy = engineBusyAfter)) opens++
        }
        if (gate.endReply(engineBusy = false)) opens++
        return closes to opens
    }

    @Test
    fun `a three sentence reply moves the ear exactly once each way`() {
        val (closes, opens) = playReply(MicGate(), sentences = 3)
        assertEquals("প্রতি বাক্যে মাইক বন্ধ হচ্ছে", 1, closes)
        assertEquals("প্রতি বাক্যে মাইক চালু হচ্ছে", 1, opens)
    }

    @Test
    fun `a ten sentence reply still moves the ear once`() {
        val (closes, opens) = playReply(MicGate(), sentences = 10)
        assertEquals(1, closes)
        assertEquals(1, opens)
    }

    @Test
    fun `the ear stays shut while the reply is still streaming`() {
        val gate = MicGate()
        gate.beginReply()
        assertTrue(gate.onUtteranceStart())
        // First sentence done, engine idle, but the reply is not over.
        assertFalse("উত্তর শেষ হওয়ার আগে মাইক খোলা যাবে না", gate.onUtteranceDone(engineBusy = false))
    }

    @Test
    fun `the ear reopens only after the last sentence`() {
        val gate = MicGate()
        gate.beginReply()
        gate.onUtteranceStart()
        gate.onUtteranceDone(engineBusy = false)
        // Now the reply ends while the last word is still playing.
        assertFalse("শেষ শব্দ বাজা অবস্থায় মাইক খোলা যাবে না", gate.endReply(engineBusy = true))
        assertTrue("শেষ শব্দের পর মাইক খুলতে হবে", gate.onUtteranceDone(engineBusy = false))
    }

    @Test
    fun `a plain non streamed sentence closes and opens once`() {
        val gate = MicGate()
        assertTrue(gate.onUtteranceStart())
        assertTrue(gate.onUtteranceDone(engineBusy = false))
    }

    @Test
    fun `a second reply pauses the ear again`() {
        val gate = MicGate()
        gate.beginReply()
        gate.onUtteranceStart()
        gate.endReply(engineBusy = false)

        gate.beginReply()
        assertTrue("দ্বিতীয় উত্তরে আবার থামা উচিত", gate.onUtteranceStart())
    }

    @Test
    fun `being cut off resets the gate`() {
        val gate = MicGate()
        gate.beginReply()
        gate.onUtteranceStart()
        gate.reset()
        assertFalse(gate.burstActive)
        assertFalse(gate.replyActive)
    }
}
