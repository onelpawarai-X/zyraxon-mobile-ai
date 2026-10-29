package ai.zyraxon.assistant.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The server says we are not authenticated (or the token went stale). */
class McpAuthRequired(message: String) : Exception(message)

/** Anything else the server refused. */
class McpError(message: String) : Exception(message)

/**
 * A Streamable-HTTP MCP client: JSON-RPC 2.0 over POST.
 *
 * Two things the spec allows and real servers rely on:
 *  - the response may be plain JSON *or* a one-message SSE stream, so both are
 *    parsed;
 *  - `initialize` may hand back a session id that every later call must echo.
 */
class McpClient(
    private val endpoint: String,
    private val accessToken: String?,
    private val onTokenRefresh: (suspend (String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "McpClient"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Shared: MCP servers are often slow to answer a tools/list. */
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private val ids = AtomicInteger(0)
    private var sessionId: String? = null
    private var negotiatedProtocol: String = McpCatalog.PROTOCOL_VERSION

    /** initialize + notifications/initialized. Must run before any other call. */
    suspend fun connect(): JSONObject {
        val result = call("initialize", JSONObject()
            .put("protocolVersion", McpCatalog.PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject()
                .put("name", "zyraxon-android")
                .put("version", "3.0.0")))
        result.optString("protocolVersion").takeIf { it.isNotBlank() }?.let { negotiatedProtocol = it }
        notify("notifications/initialized", JSONObject())
        return result
    }

    /** Every tool the server offers, following pagination to the end. */
    suspend fun listTools(): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        var cursor: String? = null
        var page = 0
        do {
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val result = call("tools/list", params)
            val arr = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { all.add(it) }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            page++
        } while (cursor != null && page < 20)
        return all
    }

    /** Runs one tool. The raw MCP result is returned so the caller can reshape it. */
    suspend fun callTool(name: String, args: JSONObject): JSONObject =
        call("tools/call", JSONObject().put("name", name).put("arguments", args))

    // ---- transport ----------------------------------------------------------

    private suspend fun notify(method: String, params: JSONObject) {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        post(body.toString(), expectResponse = false)
    }

    private suspend fun call(method: String, params: JSONObject): JSONObject {
        val id = ids.incrementAndGet()
        val body = JSONObject()
            .put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val raw = post(body.toString(), expectResponse = true)
        val obj = raw ?: throw McpError("$method: সার্ভার খালি উত্তর দিয়েছে")
        obj.optJSONObject("error")?.let {
            throw McpError(it.optString("message", "MCP ত্রুটি ${it.optInt("code")}"))
        }
        return obj.optJSONObject("result") ?: JSONObject()
    }

    /** Returns the JSON-RPC envelope, or null when no response is expected. */
    private suspend fun post(payload: String, expectResponse: Boolean): JSONObject? =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("User-Agent", UA)
                .header("MCP-Protocol-Version", negotiatedProtocol)
                .post(payload.toRequestBody(JSON))
            accessToken?.takeIf { it.isNotBlank() }?.let {
                builder.header("Authorization", "Bearer $it")
            }
            sessionId?.let { builder.header("Mcp-Session-Id", it) }

            http.newCall(builder.build()).execute().use { response ->
                response.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }

                if (response.code == 401 || response.code == 403) {
                    throw McpAuthRequired("$endpoint: HTTP ${response.code} — আবার কানেক্ট করতে হবে")
                }
                if (!response.isSuccessful && response.code != 202) {
                    val detail = response.body?.string()?.take(300).orEmpty()
                    throw McpError("$endpoint: HTTP ${response.code} $detail")
                }
                if (!expectResponse || response.code == 202) return@withContext null

                val contentType = response.header("Content-Type").orEmpty()
                val stream = response.body?.byteStream() ?: return@withContext null
                if (contentType.contains("text/event-stream")) {
                    readSseResult(stream)
                } else {
                    val text = stream.bufferedReader().use(BufferedReader::readText).trim()
                    if (text.isEmpty()) null else runCatching { JSONObject(text) }.getOrNull()
                }
            }
        }

    /**
     * A Streamable-HTTP server may answer a POST with an SSE stream carrying one
     * `message` event. Read until that message arrives, then stop.
     */
    private fun readSseResult(stream: java.io.InputStream): JSONObject? {
        var event = ""
        val data = StringBuilder()
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                    line.isEmpty() -> {
                        if (data.isNotEmpty() && (event.isEmpty() || event == "message")) {
                            val parsed = runCatching { JSONObject(data.toString()) }.getOrNull()
                            if (parsed != null) return parsed
                        }
                        event = ""
                        data.setLength(0)
                    }
                }
            }
        }
        return if (data.isNotEmpty()) runCatching { JSONObject(data.toString()) }.getOrNull() else null
    }
}
