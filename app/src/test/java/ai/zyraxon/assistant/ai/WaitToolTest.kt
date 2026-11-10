package ai.zyraxon.assistant.ai

import android.app.Application
import ai.zyraxon.assistant.shell.TestBusybox
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `wait` tool: the model's one way to spend time on purpose. It is checked
 * against a real shell rather than a stub, because the whole point of the tool
 * is that it polls something that really exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WaitToolTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun installBusybox() = TestBusybox.install(context)

    private fun wait(vararg pairs: Pair<String, Any>) = kotlinx.coroutines.runBlocking {
        val args = JSONObject()
        for ((k, v) in pairs) args.put(k, v)
        ToolRegistry.execute(context, "wait", args)
    }

    @Test
    fun `it pauses for the requested time`() {
        val result = wait("seconds" to 0.2)
        assertTrue(result.optBoolean("ok"))
        val waited = result.getJSONObject("data").optLong("waited_ms")
        assertTrue("প্রায় ২০০ms অপেক্ষা করা উচিত, পেয়েছি ${waited}ms", waited >= 150)
    }

    @Test
    fun `it stops the moment the polled command contains what was asked`() {
        val result = wait("until" to "condition", "command" to "echo ready-now", "contains" to "ready-now")
        assertTrue(result.optBoolean("ok"))
        val data = result.getJSONObject("data")
        assertEquals("একবারেই মিলে যাওয়া উচিত", 1, data.optInt("polls"))
        assertTrue(data.optString("output").contains("ready-now"))
    }

    @Test
    fun `it polls a command until it exits cleanly`() {
        val result = wait("until" to "command-exit", "command" to "true")
        assertTrue(result.optBoolean("ok"))
        assertEquals(0, result.getJSONObject("data").optInt("exit"))
    }

    @Test
    fun `it asks for something to wait on instead of guessing`() {
        val result = wait()
        assertFalse("কিছু না দিলে ব্যর্থ হওয়া উচিত", result.optBoolean("ok"))
        assertEquals("bad-args", result.optString("error"))
    }
}
