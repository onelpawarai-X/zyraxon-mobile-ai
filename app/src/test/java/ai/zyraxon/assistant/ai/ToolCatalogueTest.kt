package ai.zyraxon.assistant.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the shape of the tool catalogue the model actually receives. A tool the
 * user asked for but which never reaches the model is indistinguishable from a
 * tool that does not exist, so each one the app claims is checked here.
 *
 * Robolectric is what supplies a working org.json; the plain stub throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolCatalogueTest {

    private fun schemaNames(): List<String> {
        val arr = ToolRegistry.schemas()
        return (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("function").getString("name") }
    }

    private fun schemaFor(name: String): JSONObject {
        val arr = ToolRegistry.schemas()
        return (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .first { it.getJSONObject("function").getString("name") == name }
    }

    @Test
    fun `every schema uses the OpenAI function shape`() {
        val arr = ToolRegistry.schemas()
        assertTrue("কমপক্ষে ৪০টি টুল থাকতে হবে", arr.length() >= 40)
        for (i in 0 until arr.length()) {
            val entry = arr.getJSONObject(i)
            assertEquals("function", entry.getString("type"))
            val fn = entry.getJSONObject("function")
            assertTrue("নাম ফাঁকা", fn.getString("name").isNotBlank())
            assertTrue("বর্ণনা ফাঁকা: ${fn.getString("name")}", fn.getString("description").isNotBlank())
            assertEquals("object", fn.getJSONObject("parameters").getString("type"))
        }
    }

    @Test
    fun `tool names are unique`() {
        val names = schemaNames()
        assertEquals("ডুপ্লিকেট টুল নাম", names.size, names.toSet().size)
    }

    @Test
    fun `the media, bubble and panel tools the user asked for are all advertised`() {
        val names = schemaNames().toSet()
        listOf(
            "read_media", "floating_bubble", "todo_write", "code_box", "files_panel",
            "open_app", "run_shell", "system_shell", "terminal_run", "read_screen",
            "look_camera", "web_search",
            "write_file", "publish_website", "memory_write", "notify"
        ).forEach { assertTrue("$it টুল নেই", it in names) }
    }

    @Test
    fun `the floating bubble tool accepts on and off`() {
        val params = schemaFor("floating_bubble")
            .getJSONObject("function").getJSONObject("parameters")
        val action = params.getJSONObject("properties").getJSONObject("action")
        val values = (0 until action.getJSONArray("enum").length())
            .map { action.getJSONArray("enum").getString(it) }
        assertTrue("on থাকতে হবে", "on" in values)
        assertTrue("off থাকতে হবে", "off" in values)
    }

    @Test
    fun `the bubble is routed to the UI layer, not the device layer`() {
        assertTrue("floating_bubble UI টুল হতে হবে", "floating_bubble" in ToolRegistry.UI_TOOLS)
        assertFalse("read_media UI টুল নয়", "read_media" in ToolRegistry.UI_TOOLS)
    }
}
