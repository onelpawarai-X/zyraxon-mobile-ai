// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

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
    fun `a final arriving in the echo tail is the user answering, and is sent`() {
        // The bug: the guard tail after the last word kept the ear "busy", so a
        // final landing in it was dropped — the reply had just ended, which is
        // exactly when a person answers, and the mic looked deaf for a beat.
        val action = SttDecision.decide(
            text = "আজকের আবহাওয়া কেমন",
            isFinal = true,
            speakerActive = true,
            repeatsReply = false,
            echoTail = true
        )
        assertEquals("উত্তর শেষ হওয়ার পরের কথা চ্যাটে যায়নি", Action.SUBMIT, action)
    }

    @Test
    fun `the echo tail still drops the assistant's own sentence`() {
        // The exception is only for the *user's* words. A piece of the reply
        // arriving in the tail is caught by the text match, not by timing.
        val action = SttDecision.decide(
            text = "আমি তোমাকে ভালোবাসি",
            isFinal = true,
            speakerActive = true,
            repeatsReply = true,
            echoTail = true
        )
        assertEquals("নিজের বাক্য শেষের ফাঁকে চ্যাটে গেছে", Action.IGNORE, action)
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
    fun `a spoken interruption during speech cuts the assistant off`() {
        // The user talking over the assistant is heard as a partial; a full
        // sentence — or a stop command — is a real interruption and stops it so
        // the user can finish.
        for (word in listOf("একটু থামো", "থামো", "stop", "বন্ধ", "তোমার কথা শুনছি না")) {
            val action = SttDecision.decide(
                text = word,
                isFinal = false,
                speakerActive = true,
                repeatsReply = false
            )
            assertEquals("'$word' বলে থামানো যায়নি", Action.INTERRUPT, action)
        }
    }

    @Test
    fun `a final heard while the assistant speaks is dropped, never sent`() {
        // The assistant's own sentence comes back as a final; the user cutting in
        // never does. A final during speech is therefore never the user's turn.
        for (word in listOf("আমি তোমাকে ভালোবাসি", "তোমার কথা শুনছি না")) {
            val action = SttDecision.decide(
                text = word,
                isFinal = true,
                speakerActive = true,
                repeatsReply = false
            )
            assertEquals("'$word' বলার সময় চ্যাটে গেছে", Action.IGNORE, action)
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

    // ── one turn, one send ────────────────────────────────────────────────
    //
    // The user's report: "এক কথা দুইবার করে উঠতেছে". A partial that holds still
    // is sent without waiting for the engine's silence window, and the engine's
    // own final then carries the same words. These lock in that the final is
    // dropped — and that a phrase genuinely repeated later is not.

    @Test
    fun `the final that repeats its own settled partial is the same turn`() {
        assertTrue(
            SttDecision.isSameTurn(
                text = "আজকের আবহাওয়া কেমন",
                previous = "আজকের আবহাওয়া কেমন",
                elapsedMs = 400L
            )
        )
    }

    @Test
    fun `an engine that re-spaces or re-cases the final is still the same turn`() {
        assertTrue(
            SttDecision.isSameTurn(
                text = "  আজকের   আবহাওয়া কেমন ",
                previous = "আজকের আবহাওয়া কেমন",
                elapsedMs = 400L
            )
        )
        assertTrue(
            SttDecision.isSameTurn(
                text = "Open the   Settings",
                previous = "open the settings",
                elapsedMs = 300L
            )
        )
    }

    @Test
    fun `the same words said again later are a new turn`() {
        assertFalse(
            SttDecision.isSameTurn(
                text = "আজকের আবহাওয়া কেমন",
                previous = "আজকের আবহাওয়া কেমন",
                elapsedMs = 30_000L
            )
        )
    }

    @Test
    fun `different words are never the same turn`() {
        assertFalse(
            SttDecision.isSameTurn("আজকের আবহাওয়া কেমন", "কালকের আবহাওয়া কেমন", 200L)
        )
        assertFalse(SttDecision.isSameTurn("যেকোনো কথা", "", 100L))
    }
}
