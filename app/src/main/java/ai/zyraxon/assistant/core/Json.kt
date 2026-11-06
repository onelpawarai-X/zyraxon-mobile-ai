package ai.zyraxon.assistant.core

import org.json.JSONObject

/**
 * Reads a string that may be JSON null.
 *
 * Android's `org.json` returns the four letters "null" for a JSON null and even
 * ignores the caller's default, so `obj.optString("summary")` on `{"summary":null}`
 * yields the literal text "null". That text then travels onward as if it were
 * real content — into a tool result the model reads back, into a message the voice
 * speaks — and the assistant ends up saying "null, null" before every answer.
 * Nothing that reads the wire should ever see that word.
 */
internal fun JSONObject.safe(key: String, default: String = ""): String {
    if (!has(key) || isNull(key)) return default
    val v = optString(key, default)
    return if (v == "null") default else v
}

/** The same protection for a value read by index out of a JSON array. */
internal fun org.json.JSONArray.safe(index: Int, default: String = ""): String {
    if (index < 0 || index >= length() || isNull(index)) return default
    val v = optString(index, default)
    return if (v == "null") default else v
}
