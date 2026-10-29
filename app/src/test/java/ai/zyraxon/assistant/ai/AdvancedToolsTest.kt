package ai.zyraxon.assistant.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The three capabilities added after the tool catalogue: real screen locking,
 * live speech-to-speech, and offline on-device inference.
 *
 * Each is checked where it can be checked without a phone — the schema the model
 * receives, the dispatch that runs it, and the prompt text that governs it. A
 * capability the model cannot see is a capability that does not exist, so the
 * schema half of these tests is the important half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdvancedToolsTest {

    private fun schemaNames(): List<String> {
        val arr = ToolRegistry.schemas()
        return (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("function").getString("name") }
    }

    private fun schemaFor(name: String): JSONObject {
        val arr = ToolRegistry.schemas()
        return (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .first { it.getJSONObject("function").getString("name") == name }
    }

    private fun enumValues(tool: String, property: String): List<String> {
        val action = schemaFor(tool).getJSONObject("function")
            .getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject(property)
        return (0 until action.getJSONArray("enum").length())
            .map { action.getJSONArray("enum").getString(it) }
    }

    @Test
    fun `the lock tool is advertised with every action it implements`() {
        assertTrue("device_lock টুল নেই", "device_lock" in schemaNames())
        val values = enumValues("device_lock", "action")
        listOf("lock", "unlock", "enroll", "status", "secure_lock_settings")
            .forEach { assertTrue("device_lock-এ $it নেই", it in values) }
    }

    @Test
    fun `the live voice tool can be switched both ways`() {
        assertTrue("live_voice টুল নেই", "live_voice" in schemaNames())
        val values = enumValues("live_voice", "action")
        assertTrue("on থাকতে হবে", "on" in values)
        assertTrue("off থাকতে হবে", "off" in values)
    }

    @Test
    fun `the on-device tool exposes the whole offline workflow`() {
        assertTrue("on_device_model টুল নেই", "on_device_model" in schemaNames())
        val values = enumValues("on_device_model", "action")
        listOf("list", "load", "unload", "generate", "download", "import")
            .forEach { assertTrue("on_device_model-এ $it নেই", it in values) }
    }

    @Test
    fun `the lock tool reports a missing admin instead of claiming success`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // Robolectric has no device-admin service, which is exactly the
        // not-yet-enrolled state a real phone is in before the user taps Allow.
        val result = kotlinx.coroutines.runBlocking {
            ToolRegistry.execute(context, "device_lock", JSONObject().put("action", "status"))
        }
        assertNotNull(result)
        assertTrue("status সফল হওয়া উচিত", result.optBoolean("ok"))
        assertFalse("অনুমতি ছাড়া adminActive সত্যি হতে পারে না", result.getJSONObject("data").optBoolean("adminActive"))
    }

    @Test
    fun `the live voice tool refuses to run without an activity`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // No MainActivity in a unit test, so the tool must say so rather than
        // throwing or pretending the session started.
        val result = kotlinx.coroutines.runBlocking {
            ToolRegistry.execute(context, "live_voice", JSONObject().put("action", "on"))
        }
        assertFalse("অ্যাক্টিভিটি ছাড়া সফল হওয়া উচিত নয়", result.optBoolean("ok"))
        assertEquals("no-activity", result.optString("error"))
    }

    @Test
    fun `the on-device model starts with nothing installed and a real suggested list`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val engine = OnDeviceModel.get(context)
        assertTrue("শুরুতে কোনো মডেল থাকা উচিত নয়", engine.installedModels().isEmpty())
        assertFalse("শুরুতে ইঞ্জিন প্রস্তুত থাকা উচিত নয়", engine.isReady())
        assertTrue("অন্তত একটি সাজেস্টেড মডেল থাকতে হবে", OnDeviceModel.SUGGESTED.isNotEmpty())
        // Every suggested entry points at a real Hugging Face resolve URL and is
        // public, so a download needs no token. The gated Gemma repos, which
        // answer 401, are deliberately not in this list.
        for (info in OnDeviceModel.SUGGESTED) {
            assertTrue("URL আসল হতে হবে: ${info.url}", info.url.startsWith("https://huggingface.co/"))
            assertTrue("resolve পাথ হতে হবে: ${info.url}", info.url.contains("/resolve/main/"))
            assertTrue("ফাইল নাম .task বা .litertlm হতে হবে: ${info.fileName}",
                info.fileName.endsWith(".task") || info.fileName.endsWith(".litertlm"))
            assertFalse("সাজেস্টেড মডেল টোকেন-মুক্ত হতে হবে", info.requiresToken)
            assertTrue("সাইজ জানা থাকতে হবে", info.approxMb > 0)
        }
    }

    @Test
    fun `the suggested on-device list offers a real spread of sizes`() {
        val mb = OnDeviceModel.SUGGESTED.map { it.approxMb }
        assertTrue("ছোট মডেল থাকতে হবে (≤ 200MB)", mb.any { it <= 200 })
        assertTrue("একটি ২GB-এর কাছাকাছি মডেল থাকতে হবে", mb.any { it in 2000..2700 })
        assertTrue("একটি ৩GB+ মডেল থাকতে হবে", mb.any { it >= 3000 })
    }

    @Test
    fun `a token-free on-device download does not ask for a token`() {
        val info = OnDeviceModel.SUGGESTED.first()
        assertFalse("এই বান্ডেলটি গেটেড হওয়া উচিত নয়", info.requiresToken)
    }

    @Test
    fun `realtime detection follows the provider, not a hardcoded list`() {
        val gemini = ProviderCatalog.find("gemini")!!
        val openai = ProviderCatalog.find("openai")!!
        val xai = ProviderCatalog.find("xai")!!
        val groq = ProviderCatalog.find("groq")!!

        // Providers with a real socket recognise their own live models.
        assertTrue(gemini.isRealtimeModel("gemini-2.5-flash-native-audio-preview-12-2025"))
        assertTrue(gemini.isRealtimeModel("gemini-3.1-flash-live-preview"))
        assertTrue(openai.isRealtimeModel("gpt-4o-realtime-preview"))
        assertTrue(xai.isRealtimeModel("grok-voice-3.0"))

        // A plain text model is not mistaken for a live one.
        assertFalse(gemini.isRealtimeModel("gemini-2.5-flash"))
        assertFalse(openai.isRealtimeModel("gpt-4o"))

        // Groq has no realtime socket (its guessed path 404s), so nothing from
        // it can ever be labelled live, however the model is named.
        assertFalse("Groq-এর রিয়েলটাইম সকেট নেই", groq.supportsRealtime)
        assertFalse(groq.isRealtimeModel("llama-3.3-70b-versatile"))
    }

    @Test
    fun `a live voice is chosen from the protocol's own vocabulary`() {
        val gemini = ai.zyraxon.assistant.voice.LiveVoiceSession.voiceFor(
            ai.zyraxon.assistant.voice.LiveVoiceSession.Protocol.GEMINI, "female"
        )
        val openai = ai.zyraxon.assistant.voice.LiveVoiceSession.voiceFor(
            ai.zyraxon.assistant.voice.LiveVoiceSession.Protocol.OPENAI, "female"
        )
        assertEquals("Aoede", gemini)
        assertEquals("coral", openai)
        assertEquals(
            "Puck",
            ai.zyraxon.assistant.voice.LiveVoiceSession.voiceFor(
                ai.zyraxon.assistant.voice.LiveVoiceSession.Protocol.GEMINI, "male"
            )
        )
    }

    @Test
    fun `the live voice contract exists and differs from the text contract`() {
        val live = File("src/main/assets/knowledge/zyraxon-live-voice-prompt.md")
        val text = File("src/main/assets/knowledge/zyraxon-system-prompt.md")
        assertTrue("লাইভ প্রম্পট ফাইল নেই", live.exists())
        assertTrue("টেক্সট প্রম্পট ফাইল নেই", text.exists())
        val liveText = live.readText()
        assertTrue("লাইভ প্রম্পট ফাঁকা", liveText.isNotBlank())
        // The live contract is about speaking, so it must say so and must warn
        // against the markdown and long turns the text contract allows.
        assertTrue("speech-to-speech উল্লেখ থাকতে হবে", liveText.contains("speech-to-speech"))
        assertTrue("ছোট পালা বলতে হবে", liveText.lowercase().contains("sentence"))
        assertTrue("ইন্টারাপ্ট নিয়ম থাকতে হবে", liveText.lowercase().contains("interrupt"))
        assertFalse("দুটি প্রম্পট এক নয়", liveText == text.readText())
    }

    @Test
    fun `the text contract documents the danger lock and the new tools`() {
        val text = File("src/main/assets/knowledge/zyraxon-system-prompt.md").readText()
        assertTrue("device_lock ডকুমেন্ট করা হয়নি", text.contains("device_lock"))
        assertTrue("live_voice ডকুমেন্ট করা হয়নি", text.contains("live_voice"))
        assertTrue("on_device_model ডকুমেন্ট করা হয়নি", text.contains("on_device_model"))
        assertTrue("বিপদের নিয়ম নেই", text.contains("lock first"))
    }
}
