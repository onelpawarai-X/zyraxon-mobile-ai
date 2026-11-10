package ai.zyraxon.assistant.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the payload the model actually receives when the phone hands it a
 * picture. This is the part that decides whether the assistant can see at all,
 * so it is checked against the real builder rather than a stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VisionPayloadTest {

    private val engine = ZyraxonAiEngine(ApplicationProvider.getApplicationContext<Context>())

    private fun userContent(text: String, image: String): Any {
        val m = ZyraxonAiEngine::class.java
            .getDeclaredMethod("userContent", String::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(engine, text, image) as Any
    }

    @Test
    fun `plain text stays a plain string when there is no image`() {
        val content = userContent("হ্যালো", "")
        assertTrue("ছবি ছাড়া কনটেন্ট সাধারণ টেক্সটই থাকবে", content is String)
        assertEquals("হ্যালো", content as String)
    }

    @Test
    fun `an image becomes the OpenAI content-parts array`() {
        val dataUrl = "data:image/jpeg;base64,AAAA"
        val content = userContent("এটা কী?", dataUrl)

        assertTrue("ছবি থাকলে কনটেন্ট পার্টস হবে", content is JSONArray)
        val parts = content as JSONArray
        assertEquals("টেক্সট + ছবি — দুটি পার্ট", 2, parts.length())

        val textPart = parts.getJSONObject(0)
        assertEquals("text", textPart.getString("type"))
        assertEquals("এটা কী?", textPart.getString("text"))

        val imagePart = parts.getJSONObject(1)
        assertEquals("image_url", imagePart.getString("type"))
        assertEquals(dataUrl, imagePart.getJSONObject("image_url").getString("url"))
    }

    @Test
    fun `whitespace-only image is ignored rather than sent broken`() {
        val content = userContent("টেক্সট", "   ")
        assertTrue("খালি ছবি পাঠানো হবে না", content is String)
    }
}
