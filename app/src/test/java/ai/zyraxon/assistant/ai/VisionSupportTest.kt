// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A picture sent to a model without eyes is a hard 400 that fails the turn, so
 * the routing rule is the difference between the feature working and the
 * assistant dying for a reason the user cannot act on.
 */
class VisionSupportTest {

    @Test
    fun `a text-only model is not sent a picture`() {
        assertFalse(VisionSupport.seesImages("deepseek-v4.1-flash"))
        assertFalse(VisionSupport.seesImages("deepseek/deepseek-chat"))
        assertFalse(VisionSupport.seesImages("gpt-3.5-turbo"))
        assertFalse(VisionSupport.seesImages("command-r-plus"))
        assertFalse(VisionSupport.seesImages("gemma-2-27b-it"))
        assertFalse(VisionSupport.seesImages("phi-3-mini"))
        assertFalse(VisionSupport.seesImages("qwen2.5-coder-32b"))
    }

    @Test
    fun `a vision model keeps its picture`() {
        assertTrue(VisionSupport.seesImages("gemini-2.0-flash"))
        assertTrue(VisionSupport.seesImages("claude-sonnet-4"))
        assertTrue(VisionSupport.seesImages("gpt-4o"))
        assertTrue(VisionSupport.seesImages("llama-3.2-11b-vision"))
    }

    @Test
    fun `an explicit vision marker beats a text-only family name`() {
        assertTrue(VisionSupport.seesImages("deepseek-vl-7b"))
        assertTrue(VisionSupport.seesImages("phi-3-vision-128k"))
    }

    @Test
    fun `an unknown model is given the benefit of the doubt`() {
        assertTrue(VisionSupport.seesImages("some-new-model-2026"))
    }

    @Test
    fun `a blank id is never sent a picture`() {
        assertFalse(VisionSupport.seesImages(""))
    }

    @Test
    fun `picturesFor empties the list for a model without eyes`() {
        val pics = listOf("data:image/jpeg;base64,AAA")
        assertEquals(pics, VisionSupport.picturesFor("gpt-4o", pics))
        assertEquals(emptyList<String>(), VisionSupport.picturesFor("deepseek-chat", pics))
    }
}
