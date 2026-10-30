package ai.zyraxon.assistant.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gemini 3.x refuses the turn after a tool call unless every function-call part
 * carries back the `thought_signature` the model stamped on it, answering HTTP
 * 400 INVALID_ARGUMENT ("Function call is missing a thought_signature"). That is
 * the error the user saw with Google's API, and it turned a one-second tool call
 * into a multi-minute failover hunt. These assert the signature survives the
 * streamed merge, since dropping it there is what caused the 400.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThoughtSignatureTest {

    @Test
    fun `a streamed tool call keeps its thought signature`() {
        val turn = StreamedTurn()
        turn.mergeToolCalls(JSONArray().put(JSONObject()
            .put("index", 0)
            .put("id", "call_1")
            .put("type", "function")
            .put("extra_content", JSONObject().put("google",
                JSONObject().put("thought_signature", "SIG-ABC")))
            .put("function", JSONObject()
                .put("name", "get_time")
                .put("arguments", "{\"tz\":\"Asia/Dhaka\"}"))))

        val call = turn.toolCalls.getJSONObject(0)
        val sig = call.optJSONObject("extra_content")
            ?.optJSONObject("google")
            ?.optString("thought_signature")
        assertEquals("থট সিগনেচার হারিয়ে গেছে — গুগল ৪০০ দেবে", "SIG-ABC", sig)
    }

    @Test
    fun `arguments are still concatenated across deltas with a signature present`() {
        val turn = StreamedTurn()
        turn.mergeToolCalls(JSONArray().put(JSONObject()
            .put("index", 0).put("id", "call_1")
            .put("extra_content", JSONObject().put("google",
                JSONObject().put("thought_signature", "SIG-ABC")))
            .put("function", JSONObject().put("name", "get_time").put("arguments", "{\"tz\":"))))
        turn.mergeToolCalls(JSONArray().put(JSONObject()
            .put("index", 0)
            .put("function", JSONObject().put("arguments", "\"Asia/Dhaka\"}"))))

        val call = turn.toolCalls.getJSONObject(0)
        assertEquals("{\"tz\":\"Asia/Dhaka\"}",
            call.getJSONObject("function").optString("arguments"))
        assertEquals("SIG-ABC", call.optJSONObject("extra_content")
            ?.optJSONObject("google")?.optString("thought_signature"))
    }

    @Test
    fun `a call without a signature does not gain an empty one`() {
        val turn = StreamedTurn()
        turn.mergeToolCalls(JSONArray().put(JSONObject()
            .put("index", 0).put("id", "call_1")
            .put("function", JSONObject().put("name", "x").put("arguments", "{}"))))
        assertTrue(turn.toolCalls.getJSONObject(0).opt("extra_content") == null ||
            turn.toolCalls.getJSONObject(0).opt("extra_content") == JSONObject.NULL)
        assertNotNull(turn.toolCalls.getJSONObject(0).getJSONObject("function"))
    }
}
