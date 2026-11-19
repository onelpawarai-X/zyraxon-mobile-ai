// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.db

import ai.zyraxon.assistant.core.safe

import ai.zyraxon.assistant.model.MediaItem
import ai.zyraxon.assistant.model.ToolCallRecord
import ai.zyraxon.assistant.model.TurnEvent
import org.json.JSONArray
import org.json.JSONObject

/** The tool calls, generated media and reasoning of one message, stored as one JSON text column. */
object MessageMeta {
    fun encode(
        tools: List<ToolCallRecord>,
        media: List<MediaItem>,
        thinking: String = "",
        events: List<TurnEvent> = emptyList()
    ): String {
        if (tools.isEmpty() && media.isEmpty() && thinking.isBlank() && events.isEmpty()) return ""
        val o = JSONObject()
        // The reasoning is kept beside the tool calls in the same JSON column:
        // without it a restarted app showed the answer with its thinking gone.
        if (thinking.isNotBlank()) o.put("th", thinking.take(8000))
        o.put("tools", JSONArray().also { a ->
            tools.forEach {
                a.put(
                    JSONObject().put("id", it.id).put("n", it.name).put("d", it.detail.take(600)).put("i", it.icon).put("s", it.summary)
                        .put("st", it.status.let { st -> if (st == "running") "ok" else st })
                        .put("o", it.output.take(if (it.name == "edit_file") 16000 else 4000)).put("ms", it.ms)
                )
            }
        })
        o.put("media", JSONArray().also { a ->
            media.forEach { a.put(JSONObject().put("k", it.kind).put("p", it.path).put("t", it.title)) }
        })
        // The order the turn ran in. Without it a reopened chat showed the answer
        // above the calls that produced it, which is not what happened.
        if (events.isNotEmpty()) {
            o.put("ev", JSONArray().also { a ->
                events.forEach {
                    a.put(JSONObject().put("k", it.kind).put("t", it.text.take(4000)).put("id", it.toolId))
                }
            })
        }
        return o.toString()
    }

    fun toolCalls(meta: String): List<ToolCallRecord> = parse(meta) { o ->
        val a = o.optJSONArray("tools") ?: return@parse emptyList()
        (0 until a.length()).map {
            val j = a.getJSONObject(it)
            ToolCallRecord(
                id = j.safe("id", ""), name = j.safe("n"), detail = j.safe("d"), icon = j.safe("i", ""), summary = j.safe("s"),
                status = j.safe("st", "ok"), output = j.safe("o"), ms = j.optLong("ms")
            )
        }
    }

    /** The turn's steps in order, or an empty list for a message saved before this existed. */
    fun events(meta: String): List<TurnEvent> = parse(meta) { o ->
        val a = o.optJSONArray("ev") ?: return@parse emptyList()
        (0 until a.length()).map {
            val j = a.getJSONObject(it)
            TurnEvent(kind = j.safe("k"), text = j.safe("t"), toolId = j.safe("id"))
        }
    }

    /** The reasoning that produced the message, or "" when none was recorded. */
    fun thinking(meta: String): String = parse(meta) { o -> listOf(o.safe("th")) }.firstOrNull().orEmpty()

    fun media(meta: String): List<MediaItem> = parse(meta) { o ->
        val a = o.optJSONArray("media") ?: return@parse emptyList()
        (0 until a.length()).map {
            val j = a.getJSONObject(it)
            MediaItem(kind = j.safe("k", "file"), path = j.safe("p"), title = j.safe("t"))
        }
    }

    private fun <T> parse(meta: String, f: (JSONObject) -> List<T>): List<T> {
        if (meta.isBlank()) return emptyList()
        return try { f(JSONObject(meta)) } catch (_: Exception) { emptyList() }
    }
}
