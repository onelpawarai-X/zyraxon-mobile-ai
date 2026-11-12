// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

import ai.zyraxon.assistant.core.safe

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

    private var toolArgsHook: ((Int, String, String) -> Unit)? = null
    private val lastArgEmit = HashMap<Int, Long>()

    fun stream(
        url: String,
        apiKey: String,
        body: JSONObject,
        extraHeaders: Map<String, String> = emptyMap(),
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        // Called while a tool call's arguments are still arriving: (index, name,
        // arguments so far). Throttled, so the UI can show a file being written.
        onToolArgs: ((Int, String, String) -> Unit)? = null
    ): StreamedTurn? {
        toolArgsHook = onToolArgs
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            // A compressed stream is buffered by the gzip decoder until a block
            // fills, which delays the first tokens. Streams are small; ask for
            // them plain so every delta arrives the moment the server sends it.
            .addHeader("Accept-Encoding", "identity")
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
                // No read limit. A thinking model can sit silent for a long time
                // before its first token, and a timer that gives up mid-thought is
                // a timer that cuts real work short — the exact thing the user
                // asked to remove. The connection is still bounded by the socket's
                // own failure, and the caller's own retry/failover handles a
                // provider that is truly dead.
                .connectTimeout(0, TimeUnit.MILLISECONDS)
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
                                          readEvents(BufferedReader(InputStreamReader(source.inputStream())), onText, onReasoning)
                }
        } catch (e: Exception) {
            lastError = e.message ?: "সংযোগ বিচ্ছিন্ন"
            null
        }
    }


    private fun consume(payload: String, turn: StreamedTurn, onText: (String) -> Unit, onReasoning: (String) -> Unit) {
        val json = try { JSONObject(payload) } catch (_: Exception) { return }
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
        val delta = choice.optJSONObject("delta") ?: return
        // Reasoning first: the providers name it differently and it must never be
        // mistaken for the answer. `reasoning_content` is DeepSeek/Groq/Gemini's
        // OpenAI-compatible field; `reasoning` and `thinking` cover the rest.
        // OpenRouter sends `reasoning_details[]` with `{type:"reasoning.text",
        // text}` entries and only sometimes mirrors it into `reasoning`, so the
        // array is read too — without it OpenRouter thinking models showed no
        // thinking at all while still burning reasoning tokens.
        val reason = delta.safe("reasoning_content").ifBlank { delta.safe("reasoning") }
            .ifBlank { delta.safe("thinking") }
            .ifBlank { reasoningDetailsText(delta.optJSONArray("reasoning_details")) }
        if (reason.isNotEmpty()) {
            turn.appendReasoning(reason)
            onReasoning(reason)
        }
        val text = delta.safe("content", "")
        if (text.isNotEmpty()) {
            turn.appendText(text)
            onText(text)
        }
        delta.optJSONArray("tool_calls")?.let { calls ->
            turn.mergeToolCalls(calls)
            val hook = toolArgsHook
            if (hook != null) {
                val now = System.nanoTime()
                for (k in 0 until calls.length()) {
                    val idx = calls.optJSONObject(k)?.optInt("index", k) ?: continue
                    val last = lastArgEmit[idx] ?: 0L
                    if (now - last >= 60_000_000L) {
                        lastArgEmit[idx] = now
                        try { hook(idx, turn.nameOf(idx), turn.argsSoFar(idx)) } catch (_: Exception) {}
                    }
                }
            }
        }
        val finish = choice.opt("finish_reason")
        if (finish != null && finish != JSONObject.NULL) turn.finished = true
    }


    /**
     * Joins the text of every `reasoning.text` entry in an OpenRouter-style
     * `reasoning_details` array. Encrypted or summary entries carry no readable
     * text and are skipped, so a thinking block never shows base64 noise.
     */
    private fun reasoningDetailsText(details: JSONArray?): String {
        if (details == null || details.length() == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until details.length()) {
            val item = details.optJSONObject(i) ?: continue
            val type = item.safe("type")
            if (type.isNotEmpty() && !type.contains("text")) continue
            val text = item.safe("text")
            if (text.isNotEmpty()) sb.append(text)
        }
        return sb.toString()
    }

    /** Pulls the human-readable reason out of a provider error payload. */
    private fun extractError(raw: String): String? {
        if (raw.isBlank()) return null
        try {
            val json = JSONObject(raw)
            json.optJSONObject("error")?.let { e ->
                e.safe("message").takeIf { it.isNotBlank() }?.let { return it }
                e.safe("type").takeIf { it.isNotBlank() }?.let { return it }
            }
            json.safe("message").takeIf { it.isNotBlank() }?.let { return it }
            json.optJSONObject("zyraxon_gate")?.safe("message")?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {}
        return raw.take(400).takeIf { it.isNotBlank() }
    }

    private fun readEvents(reader: BufferedReader, onText: (String) -> Unit, onReasoning: (String) -> Unit): StreamedTurn {
        val turn = StreamedTurn()
        val data = StringBuilder()
        var line = reader.readLine()
        while (line != null) {
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        consume(data.toString(), turn, onText, onReasoning)
                        data.setLength(0)
                    }
                }
            }
            line = reader.readLine()
        }
        if (data.isNotEmpty()) consume(data.toString(), turn, onText, onReasoning)
        turn.finalizeArgs()
        return turn
    }
}
