package ai.zyraxon.assistant.voice

import java.net.URLEncoder

/**
 * Builds the request for the ZYRAXON website's own text-to-speech endpoint.
 *
 * The phone's engine is a last resort now: it has one voice per language, no
 * emotion and no guarantee that a Bangla voice is even installed. The website
 * carries 322 voices across 75 languages, both genders for Bangla, and four
 * tones, so it is the voice the assistant actually speaks with.
 *
 * The endpoint answers with `audio/mpeg` and starts sending the first sentence
 * while the rest is still being made, so the audio can be played straight from
 * the URL — nothing is buffered here. That is what keeps speech real-time.
 *
 * This object is pure so the URL contract is testable without a phone.
 */
object RemoteTtsRequest {

    const val BASE_URL = "https://zyraxonai.lovable.app"
    const val TTS_PATH = "/api/public/tts"
    const val VOICES_PATH = "/api/public/tts/voices"

    const val TTS_URL = BASE_URL + TTS_PATH
    const val VOICES_URL = BASE_URL + VOICES_PATH

    /**
     * The `locale` the website understands. It wants a language, not a full
     * region tag, and an unknown locale is worse than a plain one — the endpoint
     * falls back on its own catalogue, so `bn-BD` and `bn-IN` both become `bn`.
     */
    fun localeFor(bcp47: String): String {
        val tag = bcp47.trim().lowercase()
        if (tag.isEmpty()) return "bn"
        return tag.substringBefore('-').substringBefore('_').ifBlank { "bn" }
    }

    /** The endpoint's two genders. Anything that is not male is spoken as female. */
    fun genderFor(gender: String): String = if (gender.lowercase() == "male") "male" else "female"

    /** The endpoint's four tones, in the order the website documents them. */
    const val TONE_WARM = "warm"
    const val TONE_EXPRESSIVE = "expressive"
    const val TONE_NATURAL = "natural"
    const val TONE_CLEAR = "clear"

    /**
     * Picks the endpoint tone that matches what the sentence is doing, using the
     * same cues [SpeechTone] reads for the phone's engine. The website's voices
     * are neural, so a real tone name is worth more than a pitch nudge here.
     */
    fun toneFor(sentence: String): String {
        val text = sentence.trim()
        if (text.isEmpty()) return TONE_WARM
        val lowered = text.lowercase()
        val excited = listOf(
            "দারুণ", "চমৎকার", "অসাধারণ", "বাহ", "খুশি", "আনন্দ", "অভিনন্দন",
            "great", "awesome", "amazing", "wow", "congratulations"
        ).any { lowered.contains(it) }
        val serious = listOf(
            "সতর্ক", "বিপদ", "জরুরি", "লক", "হুমকি", "সমস্যা", "ভুল", "সাবধান",
            "warning", "danger", "urgent", "error", "failed"
        ).any { lowered.contains(it) }
        val question = text.endsWith("?") || text.endsWith("？")
        return when {
            serious -> TONE_CLEAR
            excited || text.endsWith("!") || text.endsWith("！") -> TONE_EXPRESSIVE
            question -> TONE_NATURAL
            else -> TONE_WARM
        }
    }

    /**
     * The GET URL for [text]. A GET is used rather than the POST form on purpose:
     * a streaming player can only follow a URL, and the answer is already split
     * into sentences, so it is short. [splitForUrl] guards the length anyway.
     */
    fun buildUrl(
        text: String,
        bcp47: String,
        gender: String,
        tone: String = "warm"
    ): String = buildString {
        append(TTS_URL)
        append("?text=").append(encode(text))
        append("&locale=").append(encode(localeFor(bcp47)))
        append("&gender=").append(encode(genderFor(gender)))
        append("&tone=").append(encode(tone))
    }

    /**
     * The POST body, for a caller that would rather send JSON. Kept so the same
     * contract is expressed both ways, and so a very long piece has a path that
     * does not depend on URL length.
     */
    fun buildJson(text: String, bcp47: String, gender: String, tone: String = "warm"): String =
        """{"text":${quote(text)},"locale":${quote(localeFor(bcp47))},""" +
            """"gender":${quote(genderFor(gender))},"tone":${quote(tone)}}"""

    /**
     * Splits [text] so no single request carries more than [max] characters. The
     * endpoint accepts 50,000, but a URL is not the place for that much text, and
     * a piece this long only reaches here when the model wrote a whole paragraph
     * with no punctuation at all.
     */
    fun splitForUrl(text: String, max: Int = 600): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= max) return if (trimmed.isEmpty()) emptyList() else listOf(trimmed)
        val out = mutableListOf<String>()
        var rest = trimmed
        while (rest.length > max) {
            val space = rest.lastIndexOf(' ', max)
            val cut = if (space <= 0) max else space
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out.filter { it.isNotEmpty() }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
