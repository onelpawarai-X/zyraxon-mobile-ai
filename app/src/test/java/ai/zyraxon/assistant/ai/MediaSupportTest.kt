// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What each model is sent, by kind.
 *
 * Sending a part a model does not accept is a hard 400 that fails the whole
 * turn, so the rule that decides is worth checking directly: a kind is sent only
 * when the provider declares it or the model's own name says so, and the link
 * for the file is always in the text beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaSupportTest {

    private val engine = ZyraxonAiEngine(ApplicationProvider.getApplicationContext<Context>())

    private fun content(
        text: String,
        images: List<String> = emptyList(),
        audios: List<String> = emptyList(),
        videos: List<String> = emptyList()
    ): Any {
        val m = ZyraxonAiEngine::class.java.getDeclaredMethod(
            "userContent",
            String::class.java, List::class.java, List::class.java, List::class.java
        )
        m.isAccessible = true
        return m.invoke(engine, text, images, audios, videos) as Any
    }

    // ---- the capability rule ------------------------------------------------

    @Test
    fun `a provider declaration decides, and a name cannot override it`() {
        // OpenRouter states a model takes audio and image; the name says nothing.
        val declared = MediaSupport.capsFor("openrouter", "some/model", listOf("text", "image", "audio"))
        assertTrue("ঘোষিত ছবি", declared.images)
        assertTrue("ঘোষিত অডিও", declared.audio)
        assertFalse("ঘোষণায় ভিডিও নেই", declared.video)

        // A declaration of text only wins even when the name looks like a vision model.
        val textOnly = MediaSupport.capsFor("openrouter", "google/gemini-2.5-pro-vision", listOf("text"))
        assertFalse("ঘোষণা নামের উপরে", textOnly.images)
    }

    @Test
    fun `google audio families are recognised by name`() {
        assertTrue("native-audio অডিও নেয়", MediaSupport.capsFor("google", "gemini-2.5-flash-native-audio", null).audio)
        assertTrue("omni অডিও নেয়", MediaSupport.capsFor("google", "gemini-omni-1.2-flash", null).audio)
        assertFalse("সাধারণ ফ্ল্যাশ অডিও নেয় না", MediaSupport.capsFor("google", "gemini-2.5-flash", null).audio)
    }

    @Test
    fun `a plain text model is offered no media at all`() {
        // DeepSeek's chat models are text-only, and the naming rule knows it.
        val caps = MediaSupport.capsFor("deepseek", "deepseek-chat", null)
        assertFalse("ছবি নয়", caps.images)
        assertFalse("অডিও নয়", caps.audio)
        assertFalse("ভিডিও নয়", caps.video)
    }

    @Test
    fun `capabilities survive the short form used in the model cache`() {
        val caps = MediaSupport.Caps(images = true, audio = false, video = true)
        assertEquals("সংক্ষিপ্ত রূপ", "iv", caps.encode())
        assertEquals(caps, MediaSupport.decode(caps.encode()))
        assertEquals(MediaSupport.NONE, MediaSupport.decode(""))
    }

    // ---- the payload --------------------------------------------------------

    @Test
    fun `an audio part carries bare base64 and its own format name`() {
        val content = content("শুনো", audios = listOf("data:audio/wav;base64,QUJD"))
        assertTrue("অডিও থাকলে পার্টস", content is JSONArray)
        val parts = content as JSONArray
        assertEquals(2, parts.length())
        val part = parts.getJSONObject(1)
        assertEquals("input_audio", part.getString("type"))
        val audio = part.getJSONObject("input_audio")
        assertEquals("data: অংশ বাদ যাবে", "QUJD", audio.getString("data"))
        assertEquals("wav", audio.getString("format"))
    }

    @Test
    fun `an mp3 keeps its own format rather than being called wav`() {
        val content = content("শুনো", audios = listOf("data:audio/mpeg;base64,QUJD")) as JSONArray
        assertEquals("mp3", content.getJSONObject(1).getJSONObject("input_audio").getString("format"))
    }

    @Test
    fun `a clip becomes a video_url part carrying the clip itself`() {
        val clip = "data:video/mp4;base64,QUJD"
        val content = content("দেখো", videos = listOf(clip)) as JSONArray
        assertEquals(2, content.length())
        val part = content.getJSONObject(1)
        assertEquals("video_url", part.getString("type"))
        assertEquals(clip, part.getJSONObject("video_url").getString("url"))
    }

    @Test
    fun `a clip that fell back to a link is never sent as a part`() {
        // A clip too large to inline travels as a link in the prompt instead. The
        // part would make the provider fetch it, which is the case that fails.
        val content = content("দেখো", videos = listOf("https://files.example.com/clip.mp4"))
        assertTrue("লিংক ক্লিপ পার্ট হিসেবে যাবে না", content is String)
    }

    @Test
    fun `one turn can carry a picture, a voice note and a clip at once`() {
        val content = content(
            "সব দেখো",
            images = listOf("data:image/jpeg;base64,AAAA"),
            audios = listOf("data:audio/wav;base64,QUJD"),
            videos = listOf("data:video/mp4;base64,QUJD")
        ) as JSONArray
        assertEquals("টেক্সট + তিনটি মিডিয়া", 4, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals("input_audio", content.getJSONObject(2).getString("type"))
        assertEquals("video_url", content.getJSONObject(3).getString("type"))
    }

    @Test
    fun `a blank entry never becomes a broken part`() {
        val content = content("শুধু টেক্সট", images = listOf("  "), audios = listOf(""), videos = listOf(" "))
        assertTrue("খালি মিডিয়া বাদ যাবে", content is String)
    }
}
