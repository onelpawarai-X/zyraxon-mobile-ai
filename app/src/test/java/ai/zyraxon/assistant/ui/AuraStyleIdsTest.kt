package ai.zyraxon.assistant.ui

import ai.zyraxon.assistant.ui.components.AuraStyleIds
import ai.zyraxon.assistant.ui.components.auraStyleLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The aura gallery: every style has a name and the original eye stays first. */
class AuraStyleIdsTest {

    @Test
    fun `the watching eye is the first and default style`() {
        assertEquals("orb", AuraStyleIds.first())
    }

    @Test
    fun `twelve distinct styles`() {
        assertEquals(12, AuraStyleIds.size)
        assertEquals(AuraStyleIds.size, AuraStyleIds.toSet().size)
    }

    @Test
    fun `every style has a display name`() {
        for (id in AuraStyleIds) {
            assertTrue("$id has no label", auraStyleLabel(id) != id)
        }
    }

    @Test
    fun `styles saved by older versions are still valid`() {
        for (old in listOf("orb", "twin", "vortex", "bloom", "helix", "core")) {
            assertTrue("$old was dropped", old in AuraStyleIds)
        }
    }
}
