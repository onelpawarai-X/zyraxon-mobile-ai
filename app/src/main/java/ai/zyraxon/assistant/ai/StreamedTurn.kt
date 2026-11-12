// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

import ai.zyraxon.assistant.core.safe

import org.json.JSONArray
import org.json.JSONObject

/** One assistant turn as it comes off the wire: spoken text plus requested tools. */
class StreamedTurn {
    val text = StringBuilder()
    /**
     * The model's own reasoning, when the provider streams it (`reasoning_content`
     * on DeepSeek/Groq/Gemini, `reasoning` or `thinking` elsewhere). It is shown on
     * screen and never spoken, so it is kept apart from [text] from the first token.
     */
    val reasoning = StringBuilder()
    val toolCalls = JSONArray()
    var finished = false

    // Arguments are appended to a builder, not to the JSON string: a model that
    // writes a 40 KB file sends thousands of deltas, and re-copying the whole
    // string on every one is quadratic. The JSON slot is filled by [finalizeArgs].
    private val argBuf = HashMap<Int, StringBuilder>()

    /** The arguments of call [index] as they stand right now. */
    fun argsSoFar(index: Int): String = argBuf[index]?.toString() ?: ""

    /** The tool name of call [index], once it has arrived. */
    fun nameOf(index: Int): String =
        toolCalls.optJSONObject(index)?.optJSONObject("function")?.safe("name").orEmpty()

    /** Writes the buffered arguments into the tool-call objects. Call once the stream ends. */
    fun finalizeArgs() {
        for ((i, sb) in argBuf) {
            toolCalls.optJSONObject(i)?.optJSONObject("function")?.put("arguments", sb.toString())
        }
    }

    fun appendText(delta: String) { text.append(delta) }

    fun appendReasoning(delta: String) { reasoning.append(delta) }

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
            d.safe("id").takeIf { it.isNotBlank() }?.let { slot.put("id", it) }
            slot.safe("type").takeIf { it.isNotBlank() }?.let { slot.put("type", it) }
                ?: slot.put("type", "function")
            // Gemini 3.x stamps a thought_signature on every function-call part
            // and rejects the next turn with HTTP 400 INVALID_ARGUMENT
            // ("Function call is missing a thought_signature") when it is not
            // echoed back. It rides inside the tool-call delta, so it has to be
            // kept verbatim through the merge or the whole tool round dies.
            d.optJSONObject("extra_content")?.let { slot.put("extra_content", it) }
            val fn = d.optJSONObject("function") ?: continue
            val slotFn = slot.optJSONObject("function") ?: JSONObject().also { slot.put("function", it) }
            fn.safe("name").takeIf { it.isNotBlank() }?.let { slotFn.put("name", it) }
            val argDelta = fn.safe("arguments")
            if (argDelta.isNotEmpty()) {
                argBuf.getOrPut(index) { StringBuilder(slotFn.safe("arguments", "")) }.append(argDelta)
            }
        }
    }
}
