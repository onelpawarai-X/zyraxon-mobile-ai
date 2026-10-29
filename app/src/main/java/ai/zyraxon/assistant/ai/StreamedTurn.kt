package ai.zyraxon.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/** One assistant turn as it comes off the wire: spoken text plus requested tools. */
class StreamedTurn {
    val text = StringBuilder()
    val toolCalls = JSONArray()
    var finished = false

    fun appendText(delta: String) { text.append(delta) }

    /**
     * Merge a streamed `tool_calls` delta. OpenAI sends each call split across
     * many chunks, so arguments are concatenated by index rather than replaced.
     */
    fun mergeToolCalls(deltas: JSONArray) {
        for (i in 0 until deltas.length()) {
            val d = deltas.optJSONObject(i) ?: continue
            val index = d.optInt("index", i)
            while (toolCalls.length() <= index) toolCalls.put(JSONObject())
            val slot = toolCalls.getJSONObject(index)
            d.optString("id").takeIf { it.isNotBlank() }?.let { slot.put("id", it) }
            slot.optString("type").takeIf { it.isNotBlank() }?.let { slot.put("type", it) }
                ?: slot.put("type", "function")
            val fn = d.optJSONObject("function") ?: continue
            val slotFn = slot.optJSONObject("function") ?: JSONObject().also { slot.put("function", it) }
            fn.optString("name").takeIf { it.isNotBlank() }?.let { slotFn.put("name", it) }
            val argDelta = fn.optString("arguments")
            if (argDelta.isNotEmpty()) {
                slotFn.put("arguments", slotFn.optString("arguments", "") + argDelta)
            }
        }
    }
}
