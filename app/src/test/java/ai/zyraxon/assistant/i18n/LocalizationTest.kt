package ai.zyraxon.assistant.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the real asset tables through [Loc], not a stub.
 *
 * The point of these tests is the contract the UI depends on: a key is the
 * Bengali source string, every language answers every key, positional
 * placeholders survive translation, and a language that has no entry falls back
 * to readable Bengali rather than to a blank or to an id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalizationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Loc.load(context, Loc.BENGALI)
    }

    @Test
    fun `the picker lists every language that has a table`() {
        val languages = Loc.loadLanguages(context)
        assertEquals(60, languages.size)
        assertEquals(60, languages.map { it.code }.toSet().size)
        assertTrue(languages.any { it.code == Loc.BENGALI })
        languages.forEach { language ->
            assertTrue("${language.code} has no name", language.name.isNotBlank())
            assertTrue("${language.code} has no flag", language.flag.isNotBlank())
        }
    }

    @Test
    fun `bengali is the identity table and the fallback`() {
        val key = "ফেসবুক খোলো"
        assertEquals(key, Loc.t(key))
    }

    @Test
    fun `an unknown key is returned as written`() {
        assertEquals("not a key we ship", Loc.t("not a key we ship"))
    }

    @Test
    fun `english translates a known key`() {
        Loc.load(context, "en")
        assertFalse(Loc.t("ফেসবুক খোলো") == "ফেসবুক খোলো")
    }

    @Test
    fun `every language answers the bengali key set`() {
        val keys = readKeys(Loc.BENGALI)
        assertTrue("source table is suspiciously small", keys.size > 200)
        Loc.loadLanguages(context).filter { it.code != Loc.BENGALI }.forEach { language ->
            Loc.load(context, language.code)
            keys.forEach { key ->
                val value = Loc.t(key)
                assertTrue("${language.code} blank for ${key.take(24)}", value.isNotBlank())
            }
        }
    }

    @Test
    fun `positional placeholders are filled from the arguments`() {
        assertEquals("3/7 সম্পন্ন", Loc.t("{0}/{1} সম্পন্ন", 3, 7))
        assertEquals("ZYRAXON 4 টুল", Loc.t("{0} টুল", "ZYRAXON 4"))
    }

    @Test
    fun `a stray brace is kept rather than swallowed`() {
        assertEquals("{oops} 2", Loc.t("{oops} {0}", 2))
    }

    @Test
    fun `switching language changes the table`() {
        val key = "ফেসবুক খোলো"
        Loc.load(context, "en")
        val english = Loc.t(key)
        Loc.load(context, "fr")
        val french = Loc.t(key)
        assertFalse(english == french)
    }

    @Test
    fun `every language carries the whole key set`() {
        val expected = readKeys(Loc.BENGALI).toSet()
        Loc.loadLanguages(context).forEach { language ->
            val actual = readKeys(language.code).toSet()
            val missing = expected - actual
            assertTrue("${language.code} is missing $missing", missing.isEmpty())
        }
    }

    @Test
    fun `runtime display strings are translated too`() {
        // These come from the model/view-model rather than from a `t(...)` call
        // in the UI package, so they are the ones most likely to be forgotten.
        val runtime = listOf("চ্যাট", "প্রেমিকা", "রোমান্টিক সঙ্গিনী", "টোকেন নেই", "মডেল লোড হচ্ছে…", "সম্পন্ন")
        Loc.load(context, "en")
        runtime.forEach { key ->
            assertFalse("english left '$key' untranslated", Loc.t(key) == key)
        }
    }

    private fun readKeys(code: String): List<String> {
        val json = context.assets.open("i18n/$code.json").bufferedReader().use { it.readText() }
        val root = org.json.JSONObject(json)
        return root.keys().asSequence().toList()
    }
}
