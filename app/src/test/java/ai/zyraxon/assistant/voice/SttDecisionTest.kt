package ai.zyraxon.assistant.voice

import ai.zyraxon.assistant.voice.SttDecision.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's two worst symptoms, as rules: the assistant's own voice must never
 * be sent to the chat, and it must never stop the assistant mid-sentence — but a
 * real interruption must still get through. These drive the exact decision the
 * ViewModel makes, so the behaviour cannot silently regress.
 */
class SttDecisionTest {

    @Test
    fun `the assistant's own reply is never sent to the chat`() {
        val action = SttDecision.decide(
            text = "আমি তোমাকে ভালোবাসি",
            isFinal = true,
            speakerActive = true,
            repeatsReply = true
        )
        assertEquals("নিজের কথা চ্যাটে পাঠানো হয়েছে", Action.IGNORE, action)
    }

    @Test
    fun `the assistant's own reply never stops it mid-sentence`() {
        // A partial of the reply coming back must not count as an interruption.
        val action = SttDecision.decide(
            text = "আমি তোমাকে",
            isFinal = false,
            speakerActive = true,
            repeatsReply = true
        )
        assertEquals("নিজের কথা শুনে উত্তর থেমে গেছে", Action.IGNORE, action)
    }

    @Test
    fun `a final result during speech is not auto-sent`() {
        val action = SttDecision.decide(
            text = "কিছু একটা শোনা গেল",
            isFinal = true,
            speakerActive = true,
            repeatsReply = false
        )
        assertEquals("বলার সময় ফাইনাল রেজাল্ট চ্যাটে গেছে", Action.IGNORE, action)
    }

    @Test
    fun `a short partial during speech is ignored, not treated as an interruption`() {
        val action = SttDecision.decide(
            text = "তোমার",
            isFinal = false,
            speakerActive = true,
            repeatsReply = false
        )
        assertEquals("ছোট আংশিক কথা উত্তর থামিয়ে দিয়েছে", Action.IGNORE, action)
    }

    @Test
    fun `a spoken interruption during speech is ignored, the aura tap cuts in`() {
        for (word in listOf("একটু থামো", "থামো", "stop", "বন্ধ", "তোমার কথা শুনছি না")) {
            for (final in listOf(true, false)) {
                val action = SttDecision.decide(
                    text = word,
                    isFinal = final,
                    speakerActive = true,
                    repeatsReply = false
                )
                assertEquals("'$word' বলার সময় বক্সে বা চ্যাটে গেছে", Action.IGNORE, action)
            }
        }
    }

    @Test
    fun `a final phrase while silent is sent`() {
        val action = SttDecision.decide(
            text = "আজকের আবহাওয়া কেমন",
            isFinal = true,
            speakerActive = false,
            repeatsReply = false
        )
        assertEquals("শান্ত অবস্থায় কথা পাঠানো হয়নি", Action.SUBMIT, action)
    }

    @Test
    fun `a partial while silent only interrupts, never submits`() {
        val action = SttDecision.decide(
            text = "আজকের",
            isFinal = false,
            speakerActive = false,
            repeatsReply = false
        )
        assertEquals(Action.INTERRUPT, action)
    }

    @Test
    fun `blank input is ignored`() {
        assertEquals(
            Action.IGNORE,
            SttDecision.decide("   ", isFinal = true, speakerActive = false, repeatsReply = false)
        )
    }

    @Test
    fun `the interruption bar needs two words or a stop command`() {
        assertTrue(SttDecision.isRealInterruption("একটু থামো"))
        assertTrue(SttDecision.isRealInterruption("stop now"))
        assertTrue(SttDecision.isRealInterruption("থামো"))
        assertFalse(SttDecision.isRealInterruption("তোমার"))
    }
}
