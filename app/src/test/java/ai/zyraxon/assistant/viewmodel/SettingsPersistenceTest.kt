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
    fun `the voice gender survives a restart`() {
        val first = newViewModel()
        first.applyVoice(gender = "male")

        val second = newViewModel()
        assertEquals("কণ্ঠের লিঙ্গ সেভ হয়নি", "male", second.voiceManager.currentGender)
    }
}
