package ai.zyraxon.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The offline model calls tools by writing one JSON line between tags. */
@RunWith(RobolectricTestRunner::class)
class LocalAgentTest {

    @Test
    fun `a tool call is read from the reply`() {
        val call = LocalAgent.parse("ঠিক আছে\n<tool_call>{\"name\":\"youtube_play\",\"arguments\":{\"query\":\"নাশিদ\"}}</tool_call>")
        assertNotNull(call)
        assertEquals("youtube_play", call!!.first)
        assertEquals("নাশিদ", call.second.getString("query"))
    }

    @Test
    fun `a plain answer is not a tool call`() {
        assertNull(LocalAgent.parse("আজ আবহাওয়া ভালো।"))
    }

    @Test
    fun `an unknown tool name is refused`() {
        assertNull(LocalAgent.parse("<tool_call>{\"name\":\"format_disk\",\"arguments\":{}}</tool_call>"))
    }

    @Test
    fun `a cut-off call with valid json still parses`() {
        assertNotNull(LocalAgent.parse("<tool_call>{\"name\":\"read_screen\",\"arguments\":{}}"))
    }

    @Test
    fun `broken json is not a call`() {
        assertNull(LocalAgent.parse("<tool_call>{name: oops</tool_call>"))
    }

    @Test
    fun `the prompt lists the tools and the format`() {
        val p = LocalAgent.systemPrompt("", "", offline = true)
        assertTrue(p.contains("<tool_call>"))
        assertTrue(p.contains("web_search"))
        assertTrue(p.contains("no internet"))
    }
}
