package ai.zyraxon.assistant.i18n

import android.content.Context
import androidx.compose.runtime.mutableStateOf
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
 * Compose screens read the table through [t], and the app root is keyed on
 * [languageCode], so switching language rebuilds every screen without restarting
 * the app. [t] is deliberately a plain function rather than a composable: the UI
 * also needs it inside `remember { }` blocks and click lambdas, where a
 * composable cannot be called.
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
 * A snapshot state rather than a plain field, so the app root can observe it
 * and rebuild the whole tree when the language changes. Reading the table from
 * inside a `remember { }` block or a click lambda would otherwise capture the
 * old strings, which is why [t] is a plain function and the root is keyed on
 * [languageCode].
 */
val languageCode = mutableStateOf(Loc.BENGALI)

/**
 * Switches the UI language, remembers it for next launch and asks the tree to
 * rebuild.
 */
fun setLanguage(context: Context, code: String) {
    Loc.load(context, code)
    languageCode.value = code
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(PREFS_KEY, code).apply()
}

/** The language the user last chose, or null when they never chose one. */
fun savedLanguage(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREFS_KEY, null)

private const val PREFS = "zyraxon_ui"
private const val PREFS_KEY = "language"

/** Translates [key], falling back to the Bengali source when untranslated. */
fun t(key: String): String = Loc.t(key)

/** Translates [key] with positional arguments. */
fun t(key: String, vararg args: Any?): String = Loc.t(key, *args)
