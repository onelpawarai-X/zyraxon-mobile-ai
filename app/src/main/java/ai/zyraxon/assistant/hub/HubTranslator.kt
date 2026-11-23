package ai.zyraxon.assistant.hub

import ai.zyraxon.assistant.i18n.Loc
import android.content.Context

/** Makes the hub and the navigation read in the chosen language even when no hand-made file exists for it yet. */
object HubTranslator {
    @Volatile private var running = false

    /** Returns true when new words were added (the screen should redraw). */
    suspend fun ensure(context: Context, ask: suspend (String) -> String): Boolean {
        val code = Loc.current
        if (code == Loc.BENGALI || running) return false
        val missing = HubKeys.all.filter { !Loc.has(it) }
        if (missing.isEmpty()) return false
        running = true
        try {
            val name = Loc.languages.firstOrNull { it.code == code }?.name ?: code
            // translate from English where we have it, otherwise from the Bengali original
            val src = missing.withIndex().associate { (i, k) -> "k$i" to (Loc.englishOf(k) ?: k) }
            val done = Translator.translate(src, name, ask)
            val byKey = done.mapNotNull { (id, v) -> missing.getOrNull(id.removePrefix("k").toIntOrNull() ?: -1)?.let { it to v } }.toMap()
            Loc.addTranslations(context, code, byKey)
            return byKey.isNotEmpty()
        } finally { running = false }
    }
}
