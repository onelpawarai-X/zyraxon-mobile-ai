package ai.zyraxon.assistant.help

import ai.zyraxon.assistant.sealed.SealedAssets
import android.content.Context

/** A chapter ready to show: its title and its body (the body keeps the "## " and "- " markers). */
class HelpDoc(val title: String, val body: String)

/**
 * Loads a chapter in the reader's language from the app's sealed assets.
 *
 * Every language the picker offers ships all twenty-one chapters inside the APK, so this is a
 * plain read: nothing is translated on the phone and nothing is fetched. A chapter a language
 * somehow lacks falls back to the English one.
 */
object HelpText {

    fun parse(text: String): HelpDoc {
        val lines = text.replace("\r", "").trim().lines()
        val title = lines.firstOrNull().orEmpty().trim()
        return HelpDoc(title, lines.drop(1).joinToString("\n").trim())
    }

    private fun asset(c: Context, lang: String, ch: HelpChapter): String? =
        SealedAssets.textOrAsset(c, "help/$lang/${ch.file}")

    /** The English source of a chapter. */
    fun english(c: Context, ch: HelpChapter): HelpDoc? = asset(c, "en", ch)?.let(::parse)

    /** The chapter in [lang], falling back to English when it is missing. */
    fun load(c: Context, lang: String, ch: HelpChapter): HelpDoc? =
        (asset(c, lang, ch) ?: asset(c, "en", ch))?.let(::parse)
}
