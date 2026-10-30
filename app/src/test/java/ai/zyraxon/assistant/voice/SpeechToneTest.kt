package ai.zyraxon.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user asked for speech that carries feeling instead of a flat read-out.
 * A phone engine has no emotion model, so the tone is carried by pitch and rate;
 * these tests pin the direction of each shift so it cannot silently invert.
 */
class SpeechToneTest {

    @Test
    fun `an excited line is brighter and slightly faster`() {
        val calm = SpeechTone.of("আমি ফাইলটা লিখে ফেলেছি।")
        val excited = SpeechTone.of("দারুণ! একদম হয়ে গেছে!")
        assertTrue("উচ্ছ্বাসে পিচ বাড়া উচিত", excited.pitchFactor > calm.pitchFactor)
        assertTrue("উচ্ছ্বাসে গতি বাড়া উচিত", excited.rateFactor > calm.rateFactor)
    }

    @Test
    fun `a warning line is lower and slower`() {
        val calm = SpeechTone.of("আমি ফাইলটা লিখে ফেলেছি।")
        val serious = SpeechTone.of("সাবধান, এখানে একটা বিপদ আছে।")
        assertTrue("সতর্কবার্তায় পিচ কমা উচিত", serious.pitchFactor < calm.pitchFactor)
        assertTrue("সতর্কবার্তায় গতি কমা উচিত", serious.rateFactor < calm.rateFactor)
    }

    @Test
    fun `a warm line is softer and slower`() {
        val calm = SpeechTone.of("ফাইল লেখা হয়েছে।")
        val warm = SpeechTone.of("ভালোবাসি সোনা, তোমার কথা ভাবছি।")
        assertTrue("আদরে পিচ সামান্য বাড়ে", warm.pitchFactor > calm.pitchFactor)
        assertTrue("আদরে গতি কমে", warm.rateFactor < calm.rateFactor)
    }

    @Test
    fun `a plain sentence is left alone`() {
        val tone = SpeechTone.of("আজ আবহাওয়া খুব সুন্দর")
        assertEquals(1f, tone.pitchFactor, 0.0001f)
        assertEquals(1f, tone.rateFactor, 0.0001f)
    }

    @Test
    fun `an empty sentence changes nothing`() {
        val tone = SpeechTone.of("   ")
        assertEquals(1f, tone.pitchFactor, 0.0001f)
        assertEquals(1f, tone.rateFactor, 0.0001f)
    }
}
