package ai.zyraxon.assistant.ai

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.StringReader

/** Drives the real SSE parser and tool-call merger — no mocks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenAiSseTest {

    private fun readSse(raw: String): Pair<StreamedTurn, StringBuilder> {
        val seen = StringBuilder()
        val sse = OpenAiSse(OkHttpClient())
        val method = OpenAiSse::class.java.getDeclaredMethod(
            "readEvents", BufferedReader::class.java, Function1::class.java
        )
        method.isAccessible = true
        val turn = method.invoke(sse, BufferedReader(StringReader(raw)), { s: String -> seen.append(s) })
        return (turn as StreamedTurn) to seen
    }

    private fun data(vararg chunks: String): String =
        chunks.joinToString("") { "data: $it\n\n" }

    @Test
    fun textDeltasAreConcatenatedInOrder() {
        val raw = data(
            """{"choices":[{"delta":{"content":"আমি "}}]}""",
            """{"choices":[{"delta":{"content":"তোমার "}}]}""",
            """{"choices":[{"delta":{"content":"পাশে আছি।"}}]}"""
        )
        val (turn, seen) = readSse(raw)
        assertEquals("আমি তোমার পাশে আছি।", turn.text.toString())
        assertEquals("আমি তোমার পাশে আছি।", seen.toString())
    }

    @Test
    fun providerErrorMessageSurvivesToTheUser() {
        val sse = OpenAiSse(OkHttpClient())
        val m = OpenAiSse::class.java.getDeclaredMethod("extractError", String::class.java)
        m.isAccessible = true
        val gated = """{"error":{"message":"ZYRAXON প্রোভাইডার এখনো চালু হয়নি.","type":"provider_not_open","code":"zyraxon_provider_star_gate"}}"""
        assertEquals("ZYRAXON প্রোভাইডার এখনো চালু হয়নি.", m.invoke(sse, gated))
        val simple = """{"message":"bad api key"}"""
        assertEquals("bad api key", m.invoke(sse, simple))
        assertEquals(null, m.invoke(sse, ""))
    }

    @Test
    fun everyAdvertisedToolHasARealImplementation() {
        val schemas = ToolRegistry.schemas()
        val offered = (0 until schemas.length()).map {
            schemas.getJSONObject(it).getJSONObject("function").getString("name")
        }
        // The registry must offer a real, non-empty toolset to the model.
        assertTrue("toolset is empty", offered.size >= 30)
        assertTrue(offered.contains("open_app"))
        assertTrue(offered.contains("read_screen"))
        assertTrue(offered.contains("publish_cloudflare"))
        assertTrue(offered.contains("todo_write"))
        assertTrue(offered.contains("channel_post"))
        assertTrue(offered.contains("serve_local"))
        assertTrue(offered.contains("publish_github"))
        assertTrue(offered.contains("run_shell"))
        // Advertised name and implemented name must be the same set.
        assertEquals(offered.toSet(), ToolRegistry.names().toSet())
    }

    @Test
    fun splitToolCallArgumentsAreMergedByIndex() {
        val raw = data(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"open_app","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"package\":\"com"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":".facebook.katana\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
        )
        val (turn, _) = readSse(raw)
        assertTrue(turn.finished)
        assertEquals(1, turn.toolCalls.length())
        val call = turn.toolCalls.getJSONObject(0)
        assertEquals("open_app", call.getJSONObject("function").getString("name"))
        val args = JSONObject(call.getJSONObject("function").getString("arguments"))
        assertEquals("com.facebook.katana", args.getString("package"))
    }
}
