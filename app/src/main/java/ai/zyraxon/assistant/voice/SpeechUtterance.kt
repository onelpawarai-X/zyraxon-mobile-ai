package ai.zyraxon.assistant.voice

/**
 * One piece of speech the engine is about to say: the text, the language it
 * belongs to, and the tone it should be said in.
 *
 * The engine holds one voice at a time, so a reply that mixes Bangla and English
 * has to be spoken run by run with the voice switched between runs. Keeping the
 * language on the utterance — rather than reading it back off the engine — is
 * what makes that switch reliable.
 */
data class SpeechUtterance(
    val text: String,
    val bcp47: String,
    val pitch: Float,
    val rate: Float,
    val id: String
)

/**
 * Turns a sentence into a tone.
 *
 * The user asked for speech that carries feeling instead of a flat read-out.
 * A phone's engine has no emotion model, but it does have pitch and speed, and
 * punctuation plus a handful of cue words is enough to tell an excited line from
 * a calm one. The shifts are deliberately small: a big swing sounds like a
 * cartoon, not like a person.
 */
object SpeechTone {

    /** Multipliers applied to the user's own pitch and rate settings. */
    data class Tone(val pitchFactor: Float, val rateFactor: Float)

    private val WARM = listOf(
        "ভালোবাসি", "ভালোবাসা", "সোনা", "জান", "প্রিয়", "মিষ্টি", "আদর", "দুঃখ",
        "কষ্ট", "মন", "হৃদয়", "love", "dear", "sweet", "sorry"
    )
    private val EXCITED = listOf(
        "দারুণ", "চমৎকার", "অসাধারণ", "বাহ", "খুশি", "আনন্দ", "অভিনন্দন",
        "great", "awesome", "amazing", "wow", "congratulations"
    )
    private val SERIOUS = listOf(
        "সতর্ক", "বিপদ", "জরুরি", "লক", "হুমকি", "সমস্যা", "ভুল", "সাবধান",
        "warning", "danger", "urgent", "error", "failed"
    )

    fun of(sentence: String): Tone {
        val text = sentence.trim()
        if (text.isEmpty()) return Tone(1f, 1f)
        val lowered = text.lowercase()

        val excited = EXCITED.any { lowered.contains(it) }
        val serious = SERIOUS.any { lowered.contains(it) }
        val warm = WARM.any { lowered.contains(it) }
        val question = text.endsWith("?") || text.endsWith("？")
        val exclaim = text.endsWith("!") || text.endsWith("！")

        return when {
            serious -> Tone(0.95f, 0.95f)
            excited || exclaim -> Tone(1.08f, 1.04f)
            question -> Tone(1.05f, 0.98f)
            warm -> Tone(1.02f, 0.96f)
            else -> Tone(1f, 1f)
        }
    }
}
