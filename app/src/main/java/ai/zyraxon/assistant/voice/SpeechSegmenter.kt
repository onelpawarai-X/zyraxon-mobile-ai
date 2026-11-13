// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.voice

/**
 * Splits text into natural language segments without breaking sentences.
 *
 * Rather than splitting every single English loanword (which causes robotic
 * token-by-token audio stuttering and voice-engine churn), modern neural TTS
 * handles mixed loanwords naturally.
 *
 * We switch voice language when a full sentence or major clause is
 * written in a different script/language (e.g. Bengali, English, Arabic,
 * Hindi, Japanese, Chinese, Russian).
 */
object SpeechSegmenter {

    data class Segment(val text: String, val bcp47: String)

    private val BANGLA = Regex("[\\u0980-\\u09FF]")
    private val ARABIC = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF]")
    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")
    private val JAPANESE = Regex("[\\u3040-\\u309F\\u30A0-\\u30FF]")
    private val CHINESE = Regex("[\\u4E00-\\u9FFF]")
    private val CYRILLIC = Regex("[\\u0400-\\u04FF]")
    private val LATIN = Regex("[A-Za-z]")

    /**
     * Splits [text] into natural phrase-level runs.
     * Preserves sentence prosody and prevents mid-sentence voice resets.
     */
    fun split(text: String, banglaTag: String = "bn-BD", englishTag: String = "en-US"): List<Segment> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()

        // Split by sentence terminators or newlines first
        val sentences = trimmed.split(Regex("(?<=[।!?\\n])\\s+")).filter { it.isNotBlank() }
        if (sentences.isEmpty()) {
            return listOf(Segment(trimmed, detectLanguage(trimmed, banglaTag, englishTag)))
        }

        val out = mutableListOf<Segment>()
        for (sentence in sentences) {
            val lang = detectLanguage(sentence, banglaTag, englishTag)
            // Combine with previous segment if they share the same language
            val last = out.lastOrNull()
            if (last != null && last.bcp47 == lang) {
                out[out.size - 1] = Segment("${last.text} $sentence", lang)
            } else {
                out.add(Segment(sentence, lang))
            }
        }
        return out
    }

    /**
     * Determines the appropriate voice tag for a sentence or clause.
     * Detects Bengali, Arabic, Hindi, Japanese, Chinese, Russian, English.
     */
    private fun detectLanguage(text: String, banglaTag: String, englishTag: String): String {
        if (BANGLA.containsMatchIn(text)) return banglaTag
        if (ARABIC.containsMatchIn(text)) return "ar-SA"
        if (DEVANAGARI.containsMatchIn(text)) return "hi-IN"
        if (JAPANESE.containsMatchIn(text)) return "ja-JP"
        if (CHINESE.containsMatchIn(text)) return "zh-CN"
        if (CYRILLIC.containsMatchIn(text)) return "ru-RU"
        if (LATIN.containsMatchIn(text)) return englishTag
        return banglaTag
    }
}
