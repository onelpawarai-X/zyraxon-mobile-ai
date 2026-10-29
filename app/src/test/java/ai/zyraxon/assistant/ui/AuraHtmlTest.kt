package ai.zyraxon.assistant.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the two aura bugs the user reported: the orb drifted to the top of the
 * screen, and its glow bled past the WebView edge and looked sliced.
 *
 * The assertions are about the CSS itself, because that is where both bugs lived
 * and a screenshot test would not explain a regression.
 */
class AuraHtmlTest {

    private val html: String by lazy {
        val candidates = listOf(
            File("app/src/main/assets/aura/aura.html"),
            File("src/main/assets/aura/aura.html")
        )
        candidates.firstOrNull { it.exists() }?.readText()
            ?: error("aura.html পাওয়া যায়নি: ${candidates.map { it.absolutePath }}")
    }

    /** The orb is centred by absolute position, not by flex + 100vh. */
    @Test
    fun stageIsCentredWithTranslateNotViewportHeight() {
        assertTrue("stage must be absolutely positioned", html.contains("position: absolute"))
        assertTrue("stage must sit at 50% / 50%", html.contains("top: 50%") && html.contains("left: 50%"))
        assertTrue(
            "stage must pull itself back by half its size",
            html.contains("translate(-50%, -50%)")
        )
        assertFalse(
            "100vh is what mis-measured the viewport and pushed the aura up",
            html.contains("height: 100vh")
        )
    }

    /** The glow must fade before the WebView edge instead of being cut off. */
    @Test
    fun glowIsMaskedSoItCannotBleedOut() {
        assertTrue("atmosphere needs a fade mask", html.contains("mask-image: radial-gradient"))
        assertTrue("webkit prefix is required on Android WebView", html.contains("-webkit-mask-image"))
        // The exact stop is a tuning value, so assert the shape of the fade
        // rather than a percentage: it has to reach transparent, or the glow
        // ends in a hard edge.
        assertTrue(
            "the fade must reach transparent",
            Regex("transparent \\d+%").containsMatchIn(html)
        )
    }

    /** The four knobs AuraWebView writes must all exist as CSS variables. */
    @Test
    fun exposesTheFourTunableVariables() {
        listOf("--aura-hue", "--aura-size", "--aura-glow", "--aura-speed").forEach { name ->
            assertTrue("missing $name", html.contains("$name:"))
        }
    }

    /** A hue the user picks must actually reach the gradients. */
    @Test
    fun coloursAreDerivedFromTheHueVariable() {
        assertTrue(html.contains("hsl(var(--aura-hue)"))
        assertFalse(
            "a hard-coded pink would ignore the hue slider",
            html.contains("#ff3cac") || html.contains("#ff1493")
        )
    }

    /** Reduced-motion users still get a still frame rather than a strobe. */
    @Test
    fun respectsReducedMotion() {
        assertTrue(html.contains("prefers-reduced-motion"))
    }
}
