// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The digest is what a model without eyes is given instead of a screenshot, so
 * it has to carry the two things that let the agent act: what each element says,
 * and where it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudScreenTest {

    private val nodes = """
        [
          {"text":"Send","x":540,"y":1820,"click":true},
          {"text":"Send","x":541,"y":1821,"click":true},
          {"text":"Message","x":300,"y":1750,"editable":true},
          {"text":"বাংলা লেখা","x":100,"y":200},
          {"text":"   ","x":1,"y":1}
        ]
    """.trimIndent()

    @Test
    fun `every element keeps its own label and coordinate`() {
        val d = CloudScreen.digest("WhatsApp", "com.whatsapp", nodes)
        assertTrue(d.contains("\"Send\" @(540,1820) [tap]"))
        assertTrue(d.contains("\"Message\" @(300,1750) [editable]"))
    }

    @Test
    fun `bangla survives because android read it, not an ocr model`() {
        val d = CloudScreen.digest("WhatsApp", "com.whatsapp", nodes)
        assertTrue(d.contains("\"বাংলা লেখা\" @(100,200)"))
    }

    @Test
    fun `a repeated label is listed once`() {
        val d = CloudScreen.digest("WhatsApp", "com.whatsapp", nodes)
        assertEquals(1, Regex("\\\"Send\\\"").findAll(d).count())
    }

    @Test
    fun `the foreground app is named`() {
        val d = CloudScreen.digest("WhatsApp", "com.whatsapp", nodes)
        assertTrue(d.startsWith("অ্যাপ: WhatsApp (com.whatsapp)"))
    }

    @Test
    fun `a blank screen produces nothing, not an empty block`() {
        assertEquals("", CloudScreen.digest("", "", "[]"))
        assertEquals("", CloudScreen.digest("", "", """[{"text":"  ","x":1,"y":1}]"""))
    }

    @Test
    fun `a screen with no readable text is not sent even with an app name`() {
        assertEquals("", CloudScreen.block("WhatsApp", "com.whatsapp", "[]"))
    }

    @Test
    fun `a long list is capped at the line limit and says so`() {
        val many = (1..400).joinToString(",", "[", "]") { """{"text":"item $it","x":$it,"y":$it}""" }
        val d = CloudScreen.digest("App", "com.app", many)
        assertTrue("must not be empty", d.isNotEmpty())
        // One entry per distinct label, capped, and the agent is told there is more.
        assertTrue("must say it was cut", d.contains("আরও আছে"))
        assertEquals(CloudScreen.DEFAULT_MAX_LINES + 2, d.lines().size)
    }

    @Test
    fun `a screen whose labels are enormous is cut by characters too`() {
        val long = "x".repeat(300)
        val many = (1..60).joinToString(",", "[", "]") { """{"text":"$long$it","x":$it,"y":$it}""" }
        val d = CloudScreen.digest("App", "com.app", many)
        assertTrue(d.length <= CloudScreen.DEFAULT_MAX_CHARS + 2)
        assertTrue("the cut must be visible", d.endsWith("…"))
    }

    @Test
    fun `ocr text is marked apart and needs no coordinate`() {
        val block = CloudScreen.block("Gallery", "com.gallery", nodes, "HAPPY BIRTHDAY")
        assertTrue(block.contains("OCR"))
        assertTrue(block.contains("HAPPY BIRTHDAY"))
        assertTrue(block.contains("এখনকার স্ক্রিন"))
    }

    @Test
    fun `no digest and no ocr means no block at all`() {
        assertEquals("", CloudScreen.block("", "", "[]", ""))
    }

    @Test
    fun `a malformed node list does not throw`() {
        // Nothing readable in it, so nothing is sent — but no crash either.
        assertEquals("", CloudScreen.digest("App", "com.app", "not json"))
    }
}
