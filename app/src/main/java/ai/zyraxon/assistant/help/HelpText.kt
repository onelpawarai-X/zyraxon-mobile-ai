package ai.zyraxon.assistant.help

import ai.zyraxon.assistant.sealed.SealedAssets
import android.content.Context
import java.io.File

/** A chapter ready to show: its title and its body (the body keeps the "## " and "- " markers). */
class HelpDoc(val title: String, val body: String)

/**
 * Loads a chapter in the reader's language. English and Bengali come from the app's sealed assets;
 * any other language is read from the phone's own cache, where an earlier translation was kept.
 */
object HelpText {

    private const val CACHE = "help_i18n"
    private val HAND_MADE = setOf("en", "bn")

    fun handMade(lang: String): Boolean = lang in HAND_MADE

    fun parse(text: String): HelpDoc {
        val lines = text.replace("\r", "").trim().lines()
        val title = lines.firstOrNull().orEmpty().trim()
        return HelpDoc(title, lines.drop(1).joinToString("\n").trim())
    }

    private fun asset(c: Context, lang: String, ch: HelpChapter): String? =
        SealedAssets.textOrAsset(c, "help/$lang/${ch.file}")

    private fun cacheFile(c: Context, lang: String, ch: HelpChapter): File =
        File(File(File(c.filesDir, CACHE), lang.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }, ch.file)

    /** The English source of a chapter, which every translation starts from. */
    fun english(c: Context, ch: HelpChapter): HelpDoc? = asset(c, "en", ch)?.let(::parse)

    /** The chapter in [lang], or null when it has not been translated yet. */
    fun load(c: Context, lang: String, ch: HelpChapter): HelpDoc? {
        if (handMade(lang)) return asset(c, lang, ch)?.let(::parse) ?: english(c, ch)
        val f = cacheFile(c, lang, ch)
        return if (f.isFile) runCatching { parse(f.readText()) }.getOrNull() else null
    }

    fun save(c: Context, lang: String, ch: HelpChapter, text: String) {
        runCatching { cacheFile(c, lang, ch).writeText(text) }
    }

    /** Forgets every saved translation for [lang] so the next open translates again. */
    fun forget(c: Context, lang: String) {
        runCatching { File(File(c.filesDir, CACHE), lang.replace(Regex("[^A-Za-z0-9_-]"), "_")).deleteRecursively() }
    }
}
