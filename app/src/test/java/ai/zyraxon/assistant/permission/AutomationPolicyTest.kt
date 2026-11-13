// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automation level is what stands between "the agent acts" and "the agent
 * asks". These tests pin the table down, because a mistake here is invisible
 * until the day it matters: a level that stops asking for a shell command, or
 * one that asks before reading a file and buries the user in prompts.
 */
class AutomationPolicyTest {

    @Test
    fun `the cautious levels ask for system and personal tools`() {
        assertTrue(AutomationPolicy.asks(AutomationPolicy.LOCKED, "run_shell"))
        assertTrue(AutomationPolicy.asks(AutomationPolicy.LOCKED, "read_sms"))
        assertTrue(AutomationPolicy.asks(AutomationPolicy.SUPERVISED, "run_shell"))
        assertTrue(AutomationPolicy.asks(AutomationPolicy.SUPERVISED, "read_sms"))
    }

    @Test
    fun `balanced asks only for system tools`() {
        assertTrue(AutomationPolicy.asks(AutomationPolicy.BALANCED, "run_shell"))
        assertTrue(AutomationPolicy.asks(AutomationPolicy.BALANCED, "linux_run"))
        assertFalse(AutomationPolicy.asks(AutomationPolicy.BALANCED, "read_sms"))
        assertFalse(AutomationPolicy.asks(AutomationPolicy.BALANCED, "look_camera"))
    }

    @Test
    fun `automatic never asks`() {
        assertFalse(AutomationPolicy.asks(AutomationPolicy.AUTOMATIC, "run_shell"))
        assertFalse(AutomationPolicy.asks(AutomationPolicy.AUTOMATIC, "delete_file"))
        assertFalse(AutomationPolicy.asks(AutomationPolicy.AUTOMATIC, "device_lock"))
    }

    @Test
    fun `safe tools never ask at any level`() {
        val safe = listOf("read_file", "list_dir", "memory_read", "make_document", "sys_info")
        for (level in 1..4) {
            for (tool in safe) {
                assertFalse("$tool asked at level $level", AutomationPolicy.asks(level, tool))
            }
        }
    }

    @Test
    fun `an unknown tool is treated as system level`() {
        assertTrue(AutomationPolicy.isSystem("some_future_tool"))
        assertTrue(AutomationPolicy.asks(AutomationPolicy.BALANCED, "some_future_tool"))
        assertFalse(AutomationPolicy.asks(AutomationPolicy.AUTOMATIC, "some_future_tool"))
    }

    @Test
    fun `an mcp tool asks until the user trusts it`() {
        assertTrue(AutomationPolicy.asks(AutomationPolicy.SUPERVISED, "server__do_thing"))
        assertTrue(AutomationPolicy.isNetwork("server__do_thing"))
    }

    @Test
    fun `the on-device-only promise is kept in code`() {
        for (tool in listOf("web_search", "read_url", "publish_github", "generate_image", "http_request")) {
            assertTrue("$tool must be refused offline", AutomationPolicy.isNetwork(tool))
            assertEquals(tool, AutomationPolicy.offlineRefusal(tool))
        }
        assertFalse(AutomationPolicy.isNetwork("run_shell"))
        assertFalse(AutomationPolicy.isNetwork("make_document"))
    }

    @Test
    fun `levels are ordered and bounded`() {
        assertEquals(1, AutomationPolicy.LOCKED)
        assertEquals(4, AutomationPolicy.AUTOMATIC)
        assertEquals(2, AutomationPolicy.DEFAULT_LEVEL)
    }
}
