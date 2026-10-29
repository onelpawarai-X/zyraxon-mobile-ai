package ai.zyraxon.assistant.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Streams one model turn over server-sent events. */
class OpenAiSse(private val client: OkHttpClient) {

    /**
     * The provider's own words when a turn fails, so the phone can repeat the
     * real reason (a gate, a bad key, a quota) instead of a guess.
     */
    var lastError: String? = null
        private set

    fun stream(
        url: String,
        apiKey: String,
        body: JSONObject,
        extraHeaders: Map<String, String> = emptyMap(),
        onText: (String) -> Unit
    ): StreamedTurn? {
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .apply {
                if (apiKey.isNotEmpty()) {
                    addHeader("Authorization", "Bearer " + apiKey)
                    addHeader("x-api-key", apiKey)
                }
                for ((k, v) in extraHeaders) addHeader(k, v)
            }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // The failure body is small; read it whole so the real
                        // reason survives to the UI.
                        lastError = extractError(response.body?.string().orEmpty())
                            ?: "HTTP ${response.code}"
                        return null
                    }
                    val source = response.body?.source() ?: return null
                    // Read line by line off the live socket: deltas must reach the
                    // caller as they arrive, never after the answer ends.
                    readEvents(BufferedReader(InputStreamReader(source.inputStream())), onText)
                }
        } catch (e: Exception) {
            lastError = e.message
            null
        }
    }


    private fun consume(payload: String, turn: StreamedTurn, onText: (String) -> Unit) {
        val json = try { JSONObject(payload) } catch (_: Exception) { return }
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
        val delta = choice.optJSONObject("delta") ?: return
        val text = delta.optString("content", "")
        if (text.isNotEmpty()) {
            turn.appendText(text)
            onText(text)
        }
        delta.optJSONArray("tool_calls")?.let { turn.mergeToolCalls(it) }
        val reason = choice.opt("finish_reason")
        if (reason != null && reason != JSONObject.NULL) turn.finished = true
    }


    /** Pulls the human-readable reason out of a provider error payload. */
    private fun extractError(raw: String): String? {
        if (raw.isBlank()) return null
        try {
            val json = JSONObject(raw)
            json.optJSONObject("error")?.let { e ->
                e.optString("message").takeIf { it.isNotBlank() }?.let { return it }
                e.optString("type").takeIf { it.isNotBlank() }?.let { return it }
            }
            json.optString("message").takeIf { it.isNotBlank() }?.let { return it }
            json.optJSONObject("zyraxon_gate")?.optString("message")?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {}
        return raw.take(400).takeIf { it.isNotBlank() }
    }

    private fun readEvents(reader: BufferedReader, onText: (String) -> Unit): StreamedTurn {
        val turn = StreamedTurn()
        val data = StringBuilder()
        var line = reader.readLine()
        while (line != null) {
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        consume(data.toString(), turn, onText)
                        data.setLength(0)
                    }
                }
            }
            line = reader.readLine()
        }
        if (data.isNotEmpty()) consume(data.toString(), turn, onText)
        return turn
    }
}
