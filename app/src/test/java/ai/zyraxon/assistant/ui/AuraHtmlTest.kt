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

    /**
     * Every aura style the settings pane offers must be a real style here.
     * A chip that writes a name no rule matches would look like the app ignored
     * the tap, which is exactly the kind of thing the user calls "fake".
     */
    @Test
    fun everyOfferedStyleHasARealRule() {
        listOf("twin", "vortex", "bloom", "helix", "core").forEach { style ->
            assertTrue(
                "no CSS rule for aura style '$style'",
                html.contains("""[data-aura="$style"]""")
            )
        }
    }

    /** The elements the style rules address must exist in the markup. */
    @Test
    fun styleElementsArePresentInTheMarkup() {
        listOf("vortex", "bloom", "helix", "shock").forEach { cls ->
            assertTrue(
                "markup has no element for '$cls'",
                Regex("""class="[^"]*\b$cls\b""").containsMatchIn(html)
            )
        }
    }

    /** The default look must stay the untouched original. */
    @Test
    fun theOriginalLookIsStillTheDefault() {
        assertTrue("orb must still exist", html.contains("class=\"orb\""))
        assertTrue(html.contains("ring ring-one"))
        assertTrue(html.contains("ring ring-two"))
        assertFalse(
            "the style must be chosen at runtime, not baked into the markup",
            Regex("""<html[^>]*data-aura=""").containsMatchIn(html)
        )
    }

    // --- Shader modes -----------------------------------------------------

    /** All four shader scenes must exist as real fragment-shader bodies. */
    @Test
    fun everyShaderModeHasItsOwnScene() {
        listOf("cosmic", "portal", "cyber", "nebula").forEach { name ->
            assertTrue(
                "no fragment shader body for '$name'",
                Regex("""^\s*$name:\s*\[""", RegexOption.MULTILINE).containsMatchIn(html)
            )
        }
        assertTrue(
            "the engine must advertise the same four names",
            Regex("""NAMES\s*=\s*\[[^\]]*'cosmic'[^\]]*'portal'[^\]]*'cyber'[^\]]*'nebula'[^\]]*\]""")
                .containsMatchIn(html)
        )
    }

    /** A shader is only real if it is actually compiled and linked as GLSL. */
    @Test
    fun shadersAreCompiledAndLinkedForReal() {
        listOf(
            "gl.createShader",
            "gl.compileShader",
            "gl.linkProgram",
            "gl.drawArrays",
            "gl_FragColor"
        ).forEach { call ->
            assertTrue("missing WebGL call: $call", html.contains(call))
        }
        assertTrue("a fragment shader must declare precision", html.contains("precision highp float"))
    }

    /** The aura must never be left blank, whatever the GPU does. */
    @Test
    fun theAuraCanNeverBeLeftBlank() {
        // A lost context has to fall back to the CSS aura instead of a black box.
        assertTrue(
            "context loss must be handled",
            html.contains("webglcontextlost")
        )
        // The CSS aura is hidden *only* while a shader is really active, so
        // removing the attribute brings it straight back.
        assertTrue(
            "the CSS aura must be restored when the shader is dropped",
            html.contains("""removeAttribute('data-shader')""")
        )
        // The rule that hides the CSS aura must be guarded by the shader
        // attribute; an unguarded one would blank the aura for good.
        val stageRule = Regex("""([^{}]*)\{[^{}]*display:\s*none""").findAll(html)
            .firstOrNull { it.groupValues[1].contains(".aura-stage") }
        assertTrue("there must be a rule that hides .aura-stage", stageRule != null)
        assertTrue(
            "hiding .aura-stage must be conditional on data-shader, was: ${stageRule?.groupValues?.get(1)}",
            stageRule!!.groupValues[1].contains("data-shader")
        )
    }

    /** The shader is driven by the same four settings as the CSS aura. */
    @Test
    fun shaderReadsTheLiveSettingsAndVoice() {
        listOf("u_hue", "u_glow", "u_level", "u_time").forEach { uniform ->
            assertTrue("shader ignores $uniform", html.contains(uniform))
        }
        // Speed is not a uniform: it scales the clock, so the test has to look
        // for the multiplication rather than a name that does not exist.
        assertTrue(
            "the speed setting must reach the shader clock",
            Regex("""look\.speed""").containsMatchIn(html)
        )
    }

    /** "none" is a real mode: it hands the aura back to the CSS shapes. */
    @Test
    fun noneMeansTheCssAura() {
        assertTrue(
            "the canvas is only shown for a real shader name",
            Regex("""data-shader\]:not\(\[data-shader="none"\]\)""").containsMatchIn(html)
        )
        assertTrue(
            "set() must treat none as 'stop the shader'",
            html.contains("=== 'none'")
        )
    }
}
