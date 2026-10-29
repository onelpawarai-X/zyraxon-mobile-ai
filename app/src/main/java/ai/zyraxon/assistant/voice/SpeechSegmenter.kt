package ai.zyraxon.assistant.voice

/**
 * Splits mixed Bangla/English text into language runs.
 *
 * A speech engine has one language loaded at a time. Hand it "ফেসবুকে login করো"
 * while Bangla is loaded and it reads the English word letter by letter
 * ("l-o-g-i-n"), because the Bangla voice has no idea what an English word is.
 * Splitting the sentence and switching the engine per run fixes that, and it is
 * the only fix that works on every engine, since none of them switch on their own.
 */
object SpeechSegmenter {

    /** One run of text that is all one language. */
    data class Segment(val text: String, val bcp47: String)

    private val BANGLA = Regex("[\\u0980-\\u09FF]")
    private val LATIN = Regex("[A-Za-z]")

    /** Characters that never decide a language on their own. */
    private val NEUTRAL = Regex("^[^A-Za-z\\u0980-\\u09FF]*$")

    /**
     * Cuts [text] into consecutive same-language runs, preserving every character
     * and every space so the spoken words are exactly the written ones.
     */
    fun split(text: String, banglaTag: String = "bn-BD", englishTag: String = "en-US"): List<Segment> {
        if (text.isBlank()) return emptyList()

        val tokens = text.split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        val out = mutableListOf<Segment>()
        val run = StringBuilder()
        var runLang: String? = null

        fun flush() {
            if (run.isNotEmpty() && runLang != null) {
                out.add(Segment(run.toString(), if (runLang == "bn") banglaTag else englishTag))
            }
            run.clear()
        }

        for (token in tokens) {
            val lang = languageOf(token, runLang)
            if (lang != runLang) {
                flush()
                runLang = lang
            }
            if (run.isNotEmpty()) run.append(' ')
            run.append(token)
        }
        flush()
        return out
    }

    /**
     * The language a token belongs to. A neutral token (a number, a symbol, a
     * lone emoji that survived cleaning) stays with the run it sits in rather
     * than starting a new one, which would otherwise break a sentence in half.
     */
    private fun languageOf(token: String, current: String?): String {
        val hasBangla = BANGLA.containsMatchIn(token)
        val hasLatin = LATIN.containsMatchIn(token)
        return when {
            hasBangla -> "bn"
            hasLatin -> "en"
            current != null && NEUTRAL.matches(token) -> current
            else -> current ?: "bn"
        }
    }
}
