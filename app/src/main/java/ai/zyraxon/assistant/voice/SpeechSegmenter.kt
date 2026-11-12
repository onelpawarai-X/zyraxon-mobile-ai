// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.voice

/**
 * Splits text into natural language segments without breaking sentences.
 *
 * Rather than splitting every single English loanword (which causes robotic
 * token-by-token audio stuttering and voice-engine churn), modern neural TTS
 * (like ElevenLabs and Google Neural TTS) handles mixed loanwords naturally.
 *
 * We only switch voice language when a full sentence or major clause is
 * genuinely written in a different language.
 */
object SpeechSegmenter {

    data class Segment(val text: String, val bcp47: String)

    private val BANGLA = Regex("[\\u0980-\\u09FF]")
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
     * Determines the appropriate voice for a sentence or clause.
     * If the text contains any Bengali characters, it stays in Bengali
     * so loanwords are spoken with natural regional prosody.
     */
    private fun detectLanguage(text: String, banglaTag: String, englishTag: String): String {
        val hasBangla = BANGLA.containsMatchIn(text)
        if (hasBangla) return banglaTag

        val hasLatin = LATIN.containsMatchIn(text)
        if (hasLatin) return englishTag

        return banglaTag
    }
}
