package ai.zyraxon.assistant.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards a bug that shipped silently: the roster offered রুহি and আরিয়ান, but
 * their models (`female`, and `male` + `donor`) were never in the assets, so two
 * of the seven avatars loaded nothing at all. A missing binary cannot fail the
 * compiler, so it is checked here instead.
 */
class AvatarModelsTest {

    private fun asset(rel: String): File {
        val candidates = listOf(File("app/src/main/assets/$rel"), File("src/main/assets/$rel"))
        return candidates.firstOrNull { it.exists() } ?: error("অ্যাসেট নেই: $rel")
    }

    private val view: String by lazy {
        val c = listOf(
            File("app/src/main/java/ai/zyraxon/assistant/ui/components/AvatarView.kt"),
            File("src/main/java/ai/zyraxon/assistant/ui/components/AvatarView.kt")
        )
        c.firstOrNull { it.exists() }?.readText() ?: error("AvatarView.kt পাওয়া যায়নি")
    }

    private val indexHtml: String by lazy { asset("avatar/index.html").readText() }

    /** The `key` of every roster entry, in order. */
    private fun rosterKeys(): List<String> =
        Regex("""AvatarSpec\("[^"]*",\s*"([^"]+)"""").findAll(view).map { it.groupValues[1] }.toList()

    @Test
    fun `the roster is the size the page expects`() {
        val keys = rosterKeys()
        assertTrue("roster unreadable — regex broke", keys.size >= 7)
    }

    /** The keys the page defines, i.e. the characters it can actually render. */
    private fun pageKeys(): Set<String> =
        Regex("""(?m)^\s*([a-z]+):(?:\{|RBC\()""")
            .findAll(indexHtml).map { it.groupValues[1] }.toSet()

    @Test
    fun `every avatar the roster offers is a character the page can render`() {
        // The roster's key is the page's `?c=` key, not a file name — রুহি is the
        // key `ruhi` and its model is `female`. So the check is that the page knows
        // the key at all.
        val known = pageKeys()
        assertTrue("page defines no characters — regex broke", known.isNotEmpty())
        val unknown = rosterKeys().filterNot { it in known }
        assertTrue("roster offers avatars the page cannot render: $unknown", unknown.isEmpty())
    }

    @Test
    fun `every model the page asks for is present`() {
        // `model:'x'` and `donor:'y'` in the CFG block are the only model names
        // the page can ever load, so they must all resolve.
        val wanted = Regex("""(?:model|donor):'([^']+)'""")
            .findAll(indexHtml).map { it.groupValues[1] }.toSet()
        assertTrue("page asks for nothing — regex broke", wanted.isNotEmpty())
        val missing = wanted.filterNot { asset("avatar/models/$it.glb").isFile }
        assertTrue("page asks for models that are not bundled: $missing", missing.isEmpty())
    }

    @Test
    fun `the four models the roster needs are the ones that used to be missing`() {
        // These were absent while রুহি and আরিয়ান were selectable. Pin them by
        // name so a future cleanup cannot quietly drop them again.
        for (m in listOf("female", "male", "donor", "pori")) {
            assertTrue("$m.glb must stay bundled", asset("avatar/models/$m.glb").length() > 0)
        }
    }
}
