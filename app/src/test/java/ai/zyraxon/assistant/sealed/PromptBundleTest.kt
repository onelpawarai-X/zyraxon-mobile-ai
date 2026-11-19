// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.sealed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle the app downloads, and the rules the rest of the app relies on.
 *
 * The prompts are not in this repository any more, so the text itself is checked
 * where it lives, in the website repository. What is checked here is the shape:
 * two contracts, six characters, one contract per mode — the parts a refactor
 * breaks silently.
 */
class PromptBundleTest {

    private val bundle = PromptStore.Bundle(
        agent = "AGENT CONTRACT",
        build = "BUILD CONTRACT",
        characters = mapOf(
            "lover" to "lover text", "boyfriend" to "boyfriend text",
            "friend" to "friend text", "assistant" to "assistant text",
            "cyber" to "cyber text", "philosopher" to "philosopher text"
        )
    )

    @Test
    fun `each mode gets its own contract`() {
        assertEquals("AGENT CONTRACT", bundle.forMode(build = false))
        assertEquals("BUILD CONTRACT", bundle.forMode(build = true))
    }

    @Test
    fun `a character comes back by id`() {
        assertEquals("lover text", bundle.character("lover"))
        assertEquals("philosopher text", bundle.character("philosopher"))
    }

    @Test
    fun `an unknown character is blank, not an exception`() {
        // A stale id saved in preferences must not crash the first screen.
        assertEquals("", bundle.character("no-such-character"))
    }

    @Test
    fun `the six character ids are the ones the picker draws`() {
        assertEquals(
            listOf("lover", "boyfriend", "friend", "assistant", "cyber", "philosopher"),
            PromptStore.CHARACTER_IDS
        )
        PromptStore.CHARACTER_IDS.forEach { id ->
            assertTrue("$id has no text in the bundle", bundle.character(id).isNotBlank())
        }
    }

    @Test
    fun `character ids are bare, because the file name is built from them`() {
        PromptStore.CHARACTER_IDS.forEach { id ->
            assertFalse("id looks like a file name: $id", id.endsWith(".txt"))
        }
    }
}
