package ai.zyraxon.assistant.hub

import org.json.JSONObject

/**
 * Translates text into any language with the AI model the user already uses. Used for the words of the Automation hub
 * and for the documentation in languages that have no hand-made file; the result is saved on the phone.
 */
object Translator {
    /** [items] maps an id to the text; the answer maps the same ids to the translated text (ids the model skipped are left out). */
    suspend fun translate(items: Map<String, String>, languageName: String, ask: suspend (String) -> String, maxChars: Int = 5000): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val chunk = LinkedHashMap<String, String>(); var size = 0
        suspend fun flush() {
            if (chunk.isEmpty()) return
            val prompt = "Translate every value of this JSON object into $languageName. Keep the keys exactly as they are. Keep emoji, bullets, line breaks (\\n), numbers, names of apps and web addresses unchanged. " +
                "Use natural, everyday wording that a native speaker would use on a phone. Return ONLY the JSON object, with no code fences and no commentary.\n" + JSONObject(chunk as Map<*, *>).toString()
            val a = runCatching { ask(prompt) }.getOrDefault("")
            val i = a.indexOf('{'); val j = a.lastIndexOf('}')
            if (i >= 0 && j > i) runCatching {
                val o = JSONObject(a.substring(i, j + 1))
                o.keys().forEach { k -> val v = o.optString(k); if (chunk.containsKey(k) && v.isNotBlank()) out[k] = v }
            }
            chunk.clear(); size = 0
        }
        for ((k, v) in items) { if (size + v.length > maxChars && chunk.isNotEmpty()) flush(); chunk[k] = v; size += v.length + k.length }
        flush()
        return out
    }
}
