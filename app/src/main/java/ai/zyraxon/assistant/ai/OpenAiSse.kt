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
            // A bounded *idle* timeout, not an infinite one. `readTimeout(0)`
            // meant "wait forever", so a model that opened the socket and then
            // said nothing held the turn for minutes — the stall the user saw.
            // OkHttp resets this timer on every chunk that arrives, so a reply
            // that keeps streaming never trips it, while a silent endpoint is
            // given up on quickly and the caller can try the next provider.
            client.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(READ_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
            lastError = e.message ?: "সংযোগ বিচ্ছিন্ন"
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

    companion object {
        /**
         * How long the stream may sit silent before the turn is abandoned. A
         * real reply emits chunks continuously, so a gap this long means the
         * endpoint is not going to answer; the failover then tries the next
         * provider instead of leaving the user staring at a frozen cursor.
         */
        private const val READ_IDLE_TIMEOUT_MS = 45_000L
    }
}
