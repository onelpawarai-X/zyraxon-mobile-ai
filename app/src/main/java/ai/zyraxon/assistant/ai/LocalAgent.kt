// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

import ai.zyraxon.assistant.core.safe

import org.json.JSONObject

/**
 * Tool calling for the on-device model.
 *
 * A phone-sized model cannot be given the full operating contract (about seven
 * thousand tokens) or all of the tool schemas: Qwen3 4B has a 2048-token window.
 * So the offline path uses a short prompt and a short tool list, and asks the
 * model to call a tool by writing one line of JSON between `<tool_call>` tags —
 * the format Qwen and Gemma both follow well. The engine parses that line, runs
 * the real tool, and feeds the result back, exactly like the hosted path does.
 */
object LocalAgent {

    const val OPEN = "<tool_call>"
    const val CLOSE = "</tool_call>"
    const val MAX_ROUNDS = 24

    /** The tools small models handle reliably, with their arguments. */
    private val TOOLS = listOf(
        "web_search" to "query",
        "read_url" to "url",
        "open_app" to "name",
        "youtube_play" to "query",
        "launch_url" to "url",
        "read_screen" to "",
        "tap_text" to "text",
        "type_text" to "text",
        "press_key" to "key",
        "read_sms" to "contains, count, wait_seconds",
        "call_dial" to "number",
        "notify" to "title, body",
        "write_file" to "path, content",
        "read_file" to "path",
        "run_shell" to "command",
        "publish_cloudflare" to "name, html",
        "sys_info" to ""
    )

    fun systemPrompt(persona: String, memories: String, offline: Boolean): String = buildString {
        append("You are ZYRAXON, an assistant living inside the user's Android phone. ")
        append("Answer in the language the user writes in (Bangla, English or any other). ")
        append("Be exact and short. Do the task instead of describing it.\n")
        append("You are not limited to the phone: you also have your own cloud workspace, and you ")
        append("use it for heavy work, long builds and big downloads. Never assume you can only do ")
        append("what the phone can — the cloud is yours too, and you pick the side that fits.\n")
        append("When the user attaches a file it arrives as an HTTPS link. If that link is held by ")
        append("ZYRAXON's own storage it carries an id and a delete_token: read the file, then ")
        append("DELETE https://zyraxonai.lovable.app/api/public/zyraxonai/v1/upload/<id> with the ")
        append("header X-Delete-Token: <delete_token>, so the upload does not stay on a public link.\n")
        append("A picture, a voice note or a clip may also arrive directly, as real media in the ")
        append("message rather than a link — when that happens you are seeing or hearing it already, ")
        append("so answer from it and do not try to download a link for it.\n")
        if (offline) append("The phone has no internet right now, so web tools will fail; say so plainly if asked.\n")
        append("\nTo use a tool, reply with ONLY one line:\n")
        append(OPEN).append("{\"name\":\"TOOL\",\"arguments\":{...}}").append(CLOSE).append("\n")
        append("Then wait: the tool result will arrive and you continue. Use at most one tool per reply. ")
        append("When the job is done, reply normally with no tool call.\n\nTools:\n")
        for ((name, args) in TOOLS) append("- ").append(name).append("(").append(args).append(")\n")
        if (persona.isNotBlank()) append("\nCharacter: ").append(persona.take(400)).append("\n")
        if (memories.isNotBlank()) append("\nWhat you remember about the user:\n").append(memories.take(600)).append("\n")
    }

    /** The first tool call in [text], or null when the reply is a plain answer. */
    fun parse(text: String): Pair<String, JSONObject>? {
        val start = text.indexOf(OPEN)
        if (start < 0) return null
        val bodyStart = start + OPEN.length
        val end = text.indexOf(CLOSE, bodyStart).let { if (it < 0) text.length else it }
        val raw = text.substring(bodyStart, end).trim()
        return try {
            val json = JSONObject(raw)
            val name = json.safe("name")
            if (name.isBlank() || TOOLS.none { it.first == name }) return null
            name to (json.optJSONObject("arguments") ?: JSONObject())
        } catch (_: Exception) {
            null
        }
    }
}
