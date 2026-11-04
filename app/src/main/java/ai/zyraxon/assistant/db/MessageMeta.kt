package ai.zyraxon.assistant.db

import ai.zyraxon.assistant.model.MediaItem
import ai.zyraxon.assistant.model.ToolCallRecord
import org.json.JSONArray
import org.json.JSONObject

/** The tool calls and generated media of one message, stored as one JSON text column. */
object MessageMeta {
    fun encode(tools: List<ToolCallRecord>, media: List<MediaItem>): String {
        if (tools.isEmpty() && media.isEmpty()) return ""
        val o = JSONObject()
        o.put("tools", JSONArray().also { a ->
            tools.forEach {
                a.put(
                    JSONObject().put("n", it.name).put("d", it.detail.take(600)).put("i", it.icon).put("s", it.summary)
                        .put("st", it.status.let { st -> if (st == "running") "ok" else st })
                        .put("o", it.output.take(if (it.name == "edit_file") 16000 else 4000)).put("ms", it.ms)
                )
            }
        })
        o.put("media", JSONArray().also { a ->
            media.forEach { a.put(JSONObject().put("k", it.kind).put("p", it.path).put("t", it.title)) }
        })
        return o.toString()
    }

    fun toolCalls(meta: String): List<ToolCallRecord> = parse(meta) { o ->
        val a = o.optJSONArray("tools") ?: return@parse emptyList()
        (0 until a.length()).map {
            val j = a.getJSONObject(it)
            ToolCallRecord(
                name = j.optString("n"), detail = j.optString("d"), icon = j.optString("i", "🛠️"), summary = j.optString("s"),
                status = j.optString("st", "ok"), output = j.optString("o"), ms = j.optLong("ms")
            )
        }
    }

    fun media(meta: String): List<MediaItem> = parse(meta) { o ->
        val a = o.optJSONArray("media") ?: return@parse emptyList()
        (0 until a.length()).map {
            val j = a.getJSONObject(it)
            MediaItem(kind = j.optString("k", "file"), path = j.optString("p"), title = j.optString("t"))
        }
    }

    private fun <T> parse(meta: String, f: (JSONObject) -> List<T>): List<T> {
        if (meta.isBlank()) return emptyList()
        return try { f(JSONObject(meta)) } catch (_: Exception) { emptyList() }
    }
}
