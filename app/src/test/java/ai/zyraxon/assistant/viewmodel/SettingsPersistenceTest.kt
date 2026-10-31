package ai.zyraxon.assistant.viewmodel

import ai.zyraxon.assistant.ai.ZyraxonAiEngine
import ai.zyraxon.assistant.model.AiModel
import ai.zyraxon.assistant.model.AppTab
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The user's complaint: change a setting, close the app, open it again, and the
 * old value is back. These build the real ViewModel twice against the same
 * SharedPreferences — no mocks — so a setting that is not written, or written
 * under a different key than it is read, fails here instead of on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsPersistenceTest {

    private fun newViewModel(): ZyraxonViewModel =
        ZyraxonViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun `the chosen model survives a restart`() {
        val first = newViewModel()
        val model = AiModel(
            id = "gemini-2.5-flash",
            name = "Gemini Flash",
            category = "General",
            description = "test",
            provider = "gemini",
            isFree = true
        )
        first.setModel(model)

        val second = newViewModel()
        assertEquals("নির্বাচিত মডেল রিস্টার্টে হারিয়ে গেছে", model.id, second.selectedModel.value.id)
        assertEquals(model.provider, second.selectedModel.value.provider)
    }

    @Test
    fun `a model id the provider retired is replaced, not kept`() {
        // Google retires ids (gemini-2.5-flash now answers 404 for new users).
        // A saved id that is gone from the catalogue must not be kept, or every
        // turn fails against a model that no longer exists.
        val vm = newViewModel()
        val retired = AiModel(
            id = "gemini-2.5-flash", name = "Gemini Flash",
            category = "Gemini", description = "Gemini", provider = "gemini", isFree = true
        )
        vm.setModel(retired)

        val live = listOf(
            AiModel("gemini-flash-latest", "Gemini Flash", "Gemini", "Gemini", "gemini", true),
            AiModel("gemini-flash-lite-latest", "Gemini Lite", "Gemini", "Gemini", "gemini", true)
        )
        vm.adoptDefaultIfUnset(live)

        assertEquals("বাতিল মডেল বদলানো হয়নি",
            "gemini-flash-latest", vm.selectedModel.value.id)
        // The replacement is persisted, so the next launch does not restore the
        // dead id again.
        assertEquals("নতুন মডেল সেভ হয়নি",
            "gemini-flash-latest", newViewModel().selectedModel.value.id)
    }

    @Test
    fun `a saved model that is still offered keeps its place`() {
        val vm = newViewModel()
        val saved = AiModel(
            id = "gemini-flash-latest", name = "পুরনো নাম",
            category = "Gemini", description = "Gemini", provider = "gemini", isFree = true
        )
        vm.setModel(saved)
        vm.adoptDefaultIfUnset(listOf(
            AiModel("gemini-flash-latest", "Gemini Flash", "Gemini", "Gemini", "gemini", true)
        ))
        assertEquals("gemini-flash-latest", vm.selectedModel.value.id)
        assertEquals("ক্যাটালগের নাম দিয়ে রিফ্রেশ হয়নি",
            "Gemini Flash", vm.selectedModel.value.name)
    }

    @Test
    fun `the chosen persona survives a restart`() {
        val first = newViewModel()
        val persona = ZyraxonAiEngine.PERSONAS.first { it.id != first.selectedPersona.value.id }
        first.setPersona(persona)

        val second = newViewModel()
        assertEquals("পার্সোনা রিস্টার্টে হারিয়ে গেছে", persona.id, second.selectedPersona.value.id)
    }

    @Test
    fun `the microphone switch survives a restart`() {
        val first = newViewModel()
        first.setMicEnabled(false)
        assertFalse("মাইক বন্ধ অবস্থা সেভ হয়নি", newViewModel().alwaysListening.value)

        val second = newViewModel()
        second.setMicEnabled(true)
        assertTrue("মাইক চালু অবস্থা সেভ হয়নি", newViewModel().alwaysListening.value)
    }

    @Test
    fun `the open tab survives a restart`() {
        val first = newViewModel()
        first.setTab(AppTab.TOOLS)

        val second = newViewModel()
        assertEquals("খোলা ট্যাব রিস্টার্টে ফিরে যায়নি", AppTab.TOOLS, second.activeTab.value)
    }

    @Test
    fun `the chosen voice survives a restart`() {
        val first = newViewModel()
        // Both voices must work, so a male request really applies the male voice
        // and that choice is kept across a restart instead of resetting.
        first.applyVoice(gender = "male")
        assertEquals("পুরুষ কণ্ঠ প্রয়োগ হয়নি", "male", first.voiceManager.currentGender)

        val second = newViewModel()
        assertEquals("কণ্ঠ রিস্টার্টে হারিয়ে গেছে", "male", second.voiceManager.currentGender)
    }

    @Test
    fun `the default voice is female`() {
        assertEquals("ডিফল্ট কণ্ঠ মেয়ের হওয়া উচিত", "female", newViewModel().voiceManager.currentGender)
    }

    @Test
    fun `the website voice is the default`() {
        assertTrue("ওয়েবসাইটের কণ্ঠ ডিফল্ট হওয়া উচিত", newViewModel().useRemoteVoice.value)
    }

    @Test
    fun `turning the website voice off survives a restart`() {
        val first = newViewModel()
        first.setUseRemoteVoice(false)
        assertFalse("ওয়েবসাইটের কণ্ঠ বন্ধ হয়নি", first.useRemoteVoice.value)
        assertFalse("ম্যানেজারে সেটিং পৌঁছায়নি", first.voiceManager.isUsingRemoteVoice)

        val second = newViewModel()
        assertFalse("কণ্ঠের উৎস রিস্টার্টে ফিরে এসেছে", second.useRemoteVoice.value)
        assertFalse("রিস্টার্টে ম্যানেজারে প্রয়োগ হয়নি", second.voiceManager.isUsingRemoteVoice)
    }

    @Test
    fun `voice speed and pitch survive a restart`() {
        val first = newViewModel()
        first.setTtsRate(1.4f)
        first.setTtsPitch(0.9f)

        val second = newViewModel()
        assertEquals("গতি সেভ হয়নি", 1.4f, second.ttsRate.value, 0.0001f)
        assertEquals("পিচ সেভ হয়নি", 0.9f, second.ttsPitch.value, 0.0001f)
    }

    @Test
    fun `the auto send choice survives a restart`() {
        val first = newViewModel()
        first.setAutoSend(false)
        assertFalse("অটো-সেন্ড সেভ হয়নি", newViewModel().autoSend.value)
    }

    @Test
    fun `the voice language survives a restart`() {
        val first = newViewModel()
        first.setVoiceLanguage("en-US")
        assertEquals("ভাষা সেভ হয়নি", "en-US", newViewModel().voiceManager.voiceLanguage.value)
    }

    @Test
    fun `the live voice preference survives a restart`() {
        // The preference used to be written but never read back, so the model's
        // own speech-to-speech voice worked once and then never returned.
        val first = newViewModel()
        first.setLiveVoicePreferred(true)

        assertTrue("লাইভ ভয়েস পছন্দ সেভ হয়নি", newViewModel().liveVoicePreferred.value)
    }

    @Test
    fun `a partial catalogue never replaces the saved model`() {
        // Providers answer one at a time, so a partial list is missing most of
        // the catalogue. Adopting from it discarded the user's saved model the
        // moment their own provider had not replied yet.
        val vm = newViewModel()
        val saved = AiModel(
            id = "gemini-flash-latest", name = "Gemini Flash",
            category = "Gemini", description = "Gemini", provider = "gemini", isFree = true
        )
        vm.setModel(saved)

        // A partial list that does not include the saved model yet.
        vm.adoptDefaultIfUnset(
            listOf(AiModel("llama-3.3-70b-versatile", "Llama", "Groq", "Groq", "groq", true)),
            complete = false
        )
        assertEquals("আংশিক তালিকায় সেভ করা মডেল বদলে গেছে",
            "gemini-flash-latest", vm.selectedModel.value.id)
    }

    @Test
    fun `the background mode survives a restart`() {
        val first = newViewModel()
        first.setBgMode(ai.zyraxon.assistant.model.BgAnimationMode.entries.first { it != first.bgMode.value })
        val chosen = first.bgMode.value
        assertEquals("ব্যাকগ্রাউন্ড মোড সেভ হয়নি", chosen, newViewModel().bgMode.value)
    }

    @Test
    fun `the aura style survives a restart`() {
        val first = newViewModel()
        first.setAuraStyle("vortex")
        assertEquals("ওরা স্টাইল সেভ হয়নি", "vortex", newViewModel().auraStyle.value)
    }
}
