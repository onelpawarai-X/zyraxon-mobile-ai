package ai.zyraxon.assistant.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The UI string tables.
 *
 * Every string the user sees is written in Bengali in the source, and the
 * tables in `assets/i18n` map that Bengali text to each language. Keying by the
 * source text rather than by an invented id means a missing translation still
 * shows something readable instead of a key like `settings_title`, and it lets
 * a language be corrected by editing one JSON file.
 *
 * Compose screens read the table through [LocalStrings], so switching language
 * recomposes without restarting the app. Code outside composition (the
 * ViewModel, the engine) uses [Loc.t], which reads the same table.
 */
object Loc {

    const val BENGALI = "bn"

    private const val ASSET_DIR = "i18n"

    /** Every language the picker offers, in the order it should be listed. */
    @Volatile
    var languages: List<Language> = emptyList()
        private set

    @Volatile
    private var table: Map<String, String> = emptyMap()

    @Volatile
    private var bengali: Map<String, String> = emptyMap()

    @Volatile
    var current: String = BENGALI
        private set

    /** The languages, loaded once from the bundled asset. */
    fun loadLanguages(context: Context): List<Language> {
        languages.takeIf { it.isNotEmpty() }?.let { return it }
        val parsed = runCatching {
            val json = context.assets.open("$ASSET_DIR/languages.json")
                .bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Language(
                    code = item.getString("code"),
                    name = item.getString("name"),
                    flag = item.getString("flag")
                )
            }
        }.getOrDefault(emptyList())
        languages = parsed
        return parsed
    }

    /**
     * Switches the UI language.
     *
     * Bengali is always kept in memory as the fallback, so a table that is
     * missing a string shows the original rather than a blank.
     */
    fun load(context: Context, code: String) {
        if (bengali.isEmpty()) bengali = read(context, BENGALI)
        current = code
        table = if (code == BENGALI) bengali else read(context, code)
    }

    private fun read(context: Context, code: String): Map<String, String> = runCatching {
        val json = context.assets.open("$ASSET_DIR/$code.json")
            .bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        buildMap(root.length()) {
            root.keys().forEach { key -> put(key, root.getString(key)) }
        }
    }.getOrDefault(emptyMap())

    /** The translation of [key], or [key] itself when the table has no entry. */
    fun t(key: String): String = table[key] ?: bengali[key] ?: key

    /**
     * The translation of [key] with `{0}`, `{1}` … replaced by [args].
     *
     * The tokens are positional because a translator is free to move a value
     * elsewhere in the sentence, which a `%s`-style format cannot express.
     */
    fun t(key: String, vararg args: Any?): String = fill(t(key), args)

    internal fun fill(template: String, args: Array<out Any?>): String {
        if (args.isEmpty() || !template.contains('{')) return template
        val out = StringBuilder(template.length + 16)
        var index = 0
        while (index < template.length) {
            val ch = template[index]
            if (ch != '{') {
                out.append(ch)
                index++
                continue
            }
            val close = template.indexOf('}', index + 1)
            val number = if (close > index + 1) template.substring(index + 1, close).toIntOrNull() else null
            if (number == null || number !in args.indices) {
                // Not a placeholder: a stray brace is kept as written.
                out.append(ch)
                index++
                continue
            }
            out.append(args[number]?.toString() ?: "")
            index = close + 1
        }
        return out.toString()
    }

    /**
     * The language to start in. The phone's own locale wins when the app speaks
     * it, otherwise Bengali, which is the language the strings are written in.
     */
    fun systemDefault(available: List<Language>): String {
        val locale = Locale.getDefault()
        val tag = locale.toLanguageTag()
        val language = locale.language
        return available.firstOrNull { it.code.equals(tag, ignoreCase = true) }?.code
            ?: available.firstOrNull { it.code.substringBefore('-').equals(language, ignoreCase = true) }?.code
            ?: BENGALI
    }
}

/** One selectable language. */
data class Language(val code: String, val name: String, val flag: String)

/**
 * The table the current composition should read.
 *
 * A composition local rather than a plain object read, so a language change
 * invalidates every composable that displayed a string. That is what makes the
 * switch instant instead of requiring a restart.
 */
val LocalStrings: ProvidableCompositionLocal<Map<String, String>> =
    compositionLocalOf { emptyMap() }

/** Translates inside a composable, subscribing it to language changes. */
@Composable
fun t(key: String): String = LocalStrings.current[key] ?: Loc.t(key)

/** Translates with positional arguments inside a composable. */
@Composable
fun t(key: String, vararg args: Any?): String = Loc.fill(t(key), args)
