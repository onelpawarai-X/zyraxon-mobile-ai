package ai.zyraxon.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the gender bug: the old heuristic searched the voice name for "female"
 * and, finding nothing on a typical Google engine, kept the engine default — so
 * choosing a female voice still produced a male one. These tests pin the exact
 * behaviour the user described.
 */
class VoiceSelectorTest {

    private fun c(name: String, tag: String, network: Boolean = false) =
        VoiceSelector.Candidate(name, tag, requiresNetwork = network)

    @Test
    fun aFemaleRequestNeverReturnsTheMaleVoice() {
        val voices = listOf(
            c("bn-bd-x-bnb-network", "bn-BD"),
            c("bn-bd-x-bna-network#male", "bn-BD"),
            c("bn-bd-x-bnc-network#female", "bn-BD")
        )
        val picked = VoiceSelector.pick(voices, "bn-BD", "female")
        assertEquals("bn-bd-x-bnc-network#female", picked?.name)
    }

    @Test
    fun aMaleRequestNeverReturnsTheFemaleVoice() {
        val voices = listOf(
            c("bn-bd-x-bnc-network#female", "bn-BD"),
            c("bn-bd-x-bna-network#male", "bn-BD")
        )
        val picked = VoiceSelector.pick(voices, "bn-BD", "male")
        assertEquals("bn-bd-x-bna-network#male", picked?.name)
    }

    @Test
    fun anOfflineVoiceBeatsANetworkVoiceSoSpeechWorksWithoutInternet() {
        val voices = listOf(
            c("bn-bd-network", "bn-BD", network = true),
            c("bn-bd-local#female", "bn-BD", network = false)
        )
        assertEquals("bn-bd-local#female", VoiceSelector.pick(voices, "bn-BD", "female")?.name)
    }

    @Test
    fun englishWordDoesNotPullInTheBanglaVoice() {
        val voices = listOf(
            c("bn-bd-x-bnc-network#female", "bn-BD"),
            c("en-us-x-sfg#female", "en-US")
        )
        // The English run must resolve to the English voice, which is what stops
        // an English word being spelled out by the Bangla voice.
        assertEquals("en-us-x-sfg#female", VoiceSelector.pick(voices, "en-US", "female")?.name)
    }

    @Test
    fun aLanguageWithNoInstalledVoiceReturnsNullRatherThanTheWrongOne() {
        val voices = listOf(c("en-us-x-sfg#female", "en-US"))
        assertNull(VoiceSelector.pick(voices, "hi-IN", "female"))
    }

    @Test
    fun exactRegionWinsOverAnotherRegionOfTheSameLanguage() {
        val voices = listOf(
            c("en-gb-voice#female", "en-GB"),
            c("en-us-voice#female", "en-US")
        )
        assertEquals("en-us-voice#female", VoiceSelector.pick(voices, "en-US", "female")?.name)
    }

    @Test
    fun aGenderNeutralVoiceIsStillUsableWhenNoGenderedVoiceExists() {
        val voices = listOf(c("bn-bd-generic", "bn-BD"))
        // Better a real Bangla voice of unspecified gender than no voice at all.
        assertEquals("bn-bd-generic", VoiceSelector.pick(voices, "bn-BD", "female")?.name)
    }

    @Test
    fun offlineAvailabilityIsReportedSoTheUiCanOfferADownload() {
        val networkOnly = listOf(c("hi-in-network", "hi-IN", network = true))
        assertFalse(VoiceSelector.hasOfflineVoice(networkOnly, "hi-IN"))
        val installed = listOf(c("hi-in-local", "hi-IN", network = false))
        assertTrue(VoiceSelector.hasOfflineVoice(installed, "hi-IN"))
    }
}
