package ai.zyraxon.assistant.cloud

import ai.zyraxon.assistant.ai.ToolRegistry
import ai.zyraxon.assistant.model.AgentMode
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cloud is handed the phone's tools by name and runs them here when it calls
 * one. Two failures are silent and both are fatal to the feature:
 *
 *  - a tool offered that nothing implements leaves the agent waiting forever for
 *    a result that never comes (the old list offered exactly such a name), and
 *  - a capability missing from the list means the agent simply cannot do the job
 *    while the user believes it can (the old list hid the browser, the terminal
 *    and every publishing tool).
 *
 * These tests pin both directions against the live registry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudToolsTest {

    /** The names exactly as the cloud receives them (so, with the phone_ prefix). */
    private fun names(mode: AgentMode): List<String> {
        val arr: JSONArray = CloudBridge.phoneTools(mode)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
    }

    /** The names with the prefix taken back off, i.e. what the app really runs. */
    private fun localNames(mode: AgentMode): List<String> =
        names(mode).map { CloudBridge.localName(it) }

    @Test
    fun `every tool the cloud is offered really exists in the registry`() {
        val known = ToolRegistry.names().toSet()
        for (mode in listOf(AgentMode.CHAT, AgentMode.BUILD, AgentMode.AGENT)) {
            for (n in localNames(mode)) {
                assertTrue("offered but not implemented: $n (${mode.name})", n in known)
            }
        }
    }

    @Test
    fun `every published name carries the prefix, so none can collide`() {
        // The cloud agent has its own read_file, write_file, git and terminal_run,
        // and a second tool with one of those names makes its server reject the
        // whole conversation with a 422 — measured live. The prefix is what makes
        // that impossible, so it is asserted on every name rather than assumed.
        val collisions = setOf(
            "read_file", "write_file", "edit_file", "list_dir", "git",
            "terminal_run", "run_shell", "grep_files", "find_files"
        )
        for (mode in listOf(AgentMode.CHAT, AgentMode.BUILD, AgentMode.AGENT)) {
            for (n in names(mode)) {
                if (n.contains("__")) continue
                assertTrue("published without the prefix: $n", n.startsWith(CloudBridge.PHONE_PREFIX))
                assertFalse("would collide with a cloud tool: $n", n in collisions)
            }
        }
    }

    @Test
    fun `the prefix is reversible, so a tool the cloud calls really runs`() {
        assertEquals("preview", CloudBridge.localName("phone_preview"))
        assertEquals("terminal_run", CloudBridge.localName("phone_terminal_run"))
        assertEquals("phone_preview", CloudBridge.publishedName("preview"))
        // An MCP tool keeps the name its own server gave it, on both sides.
        assertEquals("myserver__do", CloudBridge.localName("myserver__do"))
        assertEquals("myserver__do", CloudBridge.publishedName("myserver__do"))
    }

    @Test
    fun `the phantom tool is gone`() {
        for (mode in listOf(AgentMode.CHAT, AgentMode.BUILD, AgentMode.AGENT)) {
            assertFalse("look_at_screen has no implementation", "look_at_screen" in localNames(mode))
        }
    }

    @Test
    fun `the cloud can build, browse and publish, not only drive the ui`() {
        val build = localNames(AgentMode.BUILD)
        for (t in listOf(
            // `edit_file` is the app's file writer — `write_file` exists in the
            // registry but no mode offers it, so it is not asserted here.
            "preview", "terminal_run", "linux_run", "run_shell",
            "read_file", "edit_file", "publish_website",
            "web_search", "read_url", "build_app", "git", "github_pr"
        )) {
            assertTrue("the cloud is missing a real capability: $t", t in build)
        }
    }

    @Test
    fun `the cloud can still drive the phone itself`() {
        val agent = localNames(AgentMode.AGENT)
        for (t in listOf(
            "open_app", "read_screen", "tap", "type_text", "swipe",
            "look_camera", "share", "notify", "device_lock", "read_sms"
        )) {
            assertTrue("the cloud lost a phone tool: $t", t in agent)
        }
    }

    @Test
    fun `tools the cloud must not be handed are withheld`() {
        for (mode in listOf(AgentMode.BUILD, AgentMode.AGENT)) {
            val n = localNames(mode)
            // These would either nest two agents, pull tools out from under the
            // running turn, or produce a result that can never come back.
            for (t in listOf(
                "on_device_model", "sub_agent", "mcp_connect",
                "mcp_disconnect", "request_permission", "schedule_command", "live_voice"
            )) {
                assertFalse("$t must not be offered to the cloud", t in n)
            }
        }
    }

    @Test
    fun `chat mode stays small — it is not an agent turn`() {
        val chat = localNames(AgentMode.CHAT)
        assertTrue("chat should not carry the browser", "preview" !in chat)
        assertTrue("chat should not carry the terminal", "terminal_run" !in chat)
        assertTrue("chat should keep memory", "memory_read" in chat)
    }

    @Test
    fun `the offered set is a real superset of what the phone alone could do`() {
        // The whole point of the fix: a cloud turn is not less capable than a
        // local one. It gets everything the local model gets, minus the handful
        // that cannot work over the cloud, plus nothing invented.
        val agent = localNames(AgentMode.AGENT).toSet()
        val local = ToolRegistry.toolNamesFor(AgentMode.AGENT)
        val missing = local - agent
        assertTrue("unexpected tools withheld: $missing", missing.all {
            it in setOf(
                "on_device_model", "sub_agent", "mcp_connect", "mcp_disconnect",
                "request_permission", "schedule_command", "live_voice"
            )
        })
    }
}
