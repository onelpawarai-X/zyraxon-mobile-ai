package ai.zyraxon.assistant.voice

/**
 * Chooses which installed TTS voice to use for a language and gender.
 *
 * Kept as a pure function over plain data so the choice can be tested without a
 * speech engine. The bug this replaces was a heuristic that looked for "female"
 * in the voice name and quietly fell back to the engine default, which is why
 * picking a female voice still produced a male one — and vice versa.
 */
object VoiceSelector {

    /** One installed voice, reduced to the fields the choice depends on. */
    data class Candidate(
        val name: String,
        val localeTag: String,
        val isNetwork: Boolean = false,
        val requiresNetwork: Boolean = false
    )

    /** Language subtag of a BCP-47 tag, lower-cased ("bn-BD" -> "bn"). */
    private fun language(tag: String): String = tag.substringBefore('-').lowercase()

    /** Google's engine marks gender in the voice name as "#female"/"#male". */
    private fun declaresGender(name: String, gender: String): Boolean {
        val n = name.lowercase()
        return when (gender) {
            "male" -> n.contains("#male") || n.contains("_male") || n.contains("-male")
            else -> n.contains("#female") || n.contains("_female") || n.contains("-female")
        }
    }

    private fun contradicts(name: String, gender: String): Boolean {
        val n = name.lowercase()
        return if (gender == "male") n.contains("female") else n.contains("#male") || n.contains("_male")
    }

    /**
     * Picks the best voice, or null when none is installed for the language so
     * the caller can fall back to the engine default instead of guessing.
     *
     * Order of preference: an offline voice that states the gender, then any
     * offline voice for the language, then a network voice. A voice for the wrong
     * gender is never returned — silence is better than the wrong person.
     */
    fun pick(candidates: List<Candidate>, bcp47: String, gender: String): Candidate? {
        if (candidates.isEmpty()) return null
        val lang = language(bcp47)
        val sameLanguage = candidates.filter { language(it.localeTag) == lang }
        if (sameLanguage.isEmpty()) return null

        val offline = sameLanguage.filterNot { it.requiresNetwork }
        val pool = offline.ifEmpty { sameLanguage }

        val byGender = pool.filter {
            declaresGender(it.name, gender) && !contradicts(it.name, gender)
        }
        // Exact region first, then any region of the same language.
        val exactRegion = byGender.filter { it.localeTag.equals(bcp47, ignoreCase = true) }
        return exactRegion.firstOrNull() ?: byGender.firstOrNull()
            ?: pool.firstOrNull()
    }

    /** True when the language can be spoken offline, i.e. no data download is needed. */
    fun hasOfflineVoice(candidates: List<Candidate>, bcp47: String): Boolean {
        val lang = language(bcp47)
        return candidates.any { language(it.localeTag) == lang && !it.requiresNetwork }
    }
}
