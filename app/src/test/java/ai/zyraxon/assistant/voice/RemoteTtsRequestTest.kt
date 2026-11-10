package ai.zyraxon.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests pin the contract with the ZYRAXON website's own TTS endpoint. The
 * endpoint is picky about three things — an unknown voice is a 400, the locale
 * must be a language and not a region, and the text must survive URL encoding —
 * so each of those is checked here rather than discovered on a phone.
 */
class RemoteTtsRequestTest {

    @Test
    fun `a region tag becomes the plain language the endpoint expects`() {
        assertEquals("bn", RemoteTtsRequest.localeFor("bn-BD"))
        assertEquals("bn", RemoteTtsRequest.localeFor("bn-IN"))
        assertEquals("en", RemoteTtsRequest.localeFor("en-US"))
        assertEquals("bn", RemoteTtsRequest.localeFor("bn"))
        assertEquals("bn", RemoteTtsRequest.localeFor(""))
    }

    @Test
    fun `an underscore tag is normalised too`() {
        assertEquals("bn", RemoteTtsRequest.localeFor("bn_BD"))
    }

    @Test
    fun `the two genders map to what the endpoint knows`() {
        assertEquals("male", RemoteTtsRequest.genderFor("male"))
        assertEquals("male", RemoteTtsRequest.genderFor("Male"))
        assertEquals("female", RemoteTtsRequest.genderFor("female"))
        assertEquals("female", RemoteTtsRequest.genderFor("anything"))
    }

    @Test
    fun `the url carries the text the locale the gender and the tone`() {
        val url = RemoteTtsRequest.buildUrl("হ্যালো", "bn-BD", "female", RemoteTtsRequest.TONE_WARM)
        assertTrue(url.startsWith(RemoteTtsRequest.TTS_URL + "?"))
        assertTrue(url.contains("locale=bn"))
        assertTrue(url.contains("gender=female"))
        assertTrue(url.contains("tone=warm"))
        // Bangla must be percent-encoded, never sent raw in the query string.
        assertFalse(url.contains("হ্যালো"))
    }

    @Test
    fun `a space is encoded so the query cannot break apart`() {
        val url = RemoteTtsRequest.buildUrl("আজ কেমন আছো", "bn-BD", "female")
        assertTrue(url.contains("text=%E0%A6%86%E0%A6%9C+"))
    }

    @Test
    fun `an exclamation is spoken with expression`() {
        assertEquals(RemoteTtsRequest.TONE_EXPRESSIVE, RemoteTtsRequest.toneFor("দারুণ হয়েছে!"))
    }

    @Test
    fun `a question is spoken naturally`() {
        assertEquals(RemoteTtsRequest.TONE_NATURAL, RemoteTtsRequest.toneFor("তুমি কেমন আছো?"))
    }

    @Test
    fun `a warning is spoken clearly`() {
        assertEquals(RemoteTtsRequest.TONE_CLEAR, RemoteTtsRequest.toneFor("সতর্ক হও, বিপদ আছে"))
    }

    @Test
    fun `a plain sentence is spoken warmly`() {
        assertEquals(RemoteTtsRequest.TONE_WARM, RemoteTtsRequest.toneFor("আজ আকাশ পরিষ্কার"))
    }

    @Test
    fun `a long piece is split at a word boundary and loses nothing`() {
        val text = (1..80).joinToString(" ") { "শব্দ$it" }
        val parts = RemoteTtsRequest.splitForUrl(text, max = 100)
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(it.length <= 100) }
        assertEquals(text, parts.joinToString(" "))
    }

    @Test
    fun `a short piece is one request`() {
        assertEquals(listOf("হ্যালো সোনা"), RemoteTtsRequest.splitForUrl("হ্যালো সোনা"))
    }

    @Test
    fun `blank text makes no request at all`() {
        assertTrue(RemoteTtsRequest.splitForUrl("   ").isEmpty())
    }

    @Test
    fun `a piece with no spaces is still cut to the limit`() {
        val parts = RemoteTtsRequest.splitForUrl("ক".repeat(250), max = 100)
        assertEquals(3, parts.size)
        assertEquals("ক".repeat(250), parts.joinToString(""))
    }

    @Test
    fun `the json body quotes the text safely`() {
        val json = RemoteTtsRequest.buildJson("তিনি বললেন \"হ্যালো\"", "bn-BD", "male")
        assertTrue(json.contains("\"locale\":\"bn\""))
        assertTrue(json.contains("\"gender\":\"male\""))
        // The inner quotes must be escaped or the body is not valid JSON.
        assertTrue(json.contains("\\\""))
    }
}
