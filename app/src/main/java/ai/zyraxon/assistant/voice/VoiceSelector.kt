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
    fun languageOf(tag: String): String = tag.substringBefore('-').lowercase()

    private fun language(tag: String): String = languageOf(tag)

    /** Google's engine marks gender in the voice name as "#female"/"#male". */
    private fun declaresGender(name: String, gender: String): Boolean {
        val n = name.lowercase()
        return when (gender) {
            "male" -> n.contains("#male") || n.contains("_male") || n.contains("-male")
            else -> n.contains("#female") || n.contains("_female") || n.contains("-female")
        }
    }

    /**
     * True when a voice states the *opposite* gender. All three separators are
     * checked for both genders; the old version only looked for "-male" when the
     * request was male, so a female request happily accepted a voice named
     * "…-male" and the user heard a man while a woman was selected.
     */
    private fun contradicts(name: String, gender: String): Boolean {
        val n = name.lowercase()
        return if (gender == "male") {
            n.contains("female")
        } else {
            n.contains("#male") || n.contains("_male") || n.contains("-male")
        }
    }

    /** True when [name] states the wanted [gender] and not the opposite. */
    fun statesGender(name: String, gender: String): Boolean =
        declaresGender(name, gender) && !contradicts(name, gender)

    /**
     * The pitch to speak with, given the wanted [gender] and whether a voice that
     * actually states that gender was found.
     *
     * Google ships a female English voice but often only one neutral Bangla voice,
     * so a female request produced a female *English* run and a male-sounding
     * *Bangla* run — the mixed-gender answer the user heard. When no gendered
     * voice exists for the language, the gender is carried by pitch instead, and
     * it is pushed further from neutral so the difference is unmistakable.
     */
    fun pitchFor(gender: String, basePitch: Float, hasGenderedVoice: Boolean): Float {
        if (hasGenderedVoice) return basePitch.coerceIn(0.4f, 2.0f)
        val emphasized = if (gender == "male") basePitch * 0.78f else basePitch * 1.14f
        return emphasized.coerceIn(0.4f, 2.0f)
    }

    /**
     * Picks the best voice, or null when none is installed for the language so
     * the caller can fall back to the engine default instead of guessing.
     *
     * Order of preference: an offline voice that states the wanted gender, then a
     * gender-neutral offline voice for the language, then anything for the
     * language. A voice that states the *opposite* gender is never chosen — the
     * old code fell through to `pool.firstOrNull()`, which is how a request for a
     * male voice came back with the female one and the user heard only a woman.
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
        val neutral = pool.filter {
            !declaresGender(it.name, "male") && !declaresGender(it.name, "female")
        }
        // Exact region first, then any region of the same language. The final
        // fallback still refuses a voice of the opposite gender: returning null
        // lets the caller keep the pitch for the wanted gender instead, which
        // sounds like the right person even when only the wrong voice is installed.
        val exactRegion = byGender.filter { it.localeTag.equals(bcp47, ignoreCase = true) }
        return exactRegion.firstOrNull()
            ?: byGender.firstOrNull()
            ?: neutral.firstOrNull()
            ?: pool.firstOrNull { !contradicts(it.name, gender) }
    }

    /**
     * The best voice for a language when no voice states the wanted gender.
     * Returning null here would hand the decision to the engine, whose default
     * for that language is usually the opposite gender — which is exactly how a
     * selected female voice came out male for Bangla while English stayed
     * female. A neutral voice is preferred, then anything, but never nothing
     * when the language is installed.
     */
    fun pickAny(candidates: List<Candidate>, bcp47: String): Candidate? {
        if (candidates.isEmpty()) return null
        val lang = language(bcp47)
        val sameLanguage = candidates.filter { language(it.localeTag) == lang }
        if (sameLanguage.isEmpty()) return null
        val offline = sameLanguage.filterNot { it.requiresNetwork }
        val pool = offline.ifEmpty { sameLanguage }
        val neutral = pool.filter {
            !declaresGender(it.name, "male") && !declaresGender(it.name, "female")
        }
        val exactRegion = neutral.filter { it.localeTag.equals(bcp47, ignoreCase = true) }
        return exactRegion.firstOrNull() ?: neutral.firstOrNull() ?: pool.firstOrNull()
    }

    /** True when the language can be spoken offline, i.e. no data download is needed. */
    fun hasOfflineVoice(candidates: List<Candidate>, bcp47: String): Boolean {
        val lang = language(bcp47)
        return candidates.any { language(it.localeTag) == lang && !it.requiresNetwork }
    }
}
