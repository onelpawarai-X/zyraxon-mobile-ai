package ai.zyraxon.assistant.openhands

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The recovery a phone chat depends on, proved against a real HTTP server.
 *
 * These are the three behaviours that decide whether a cloud chat survives a
 * pocket, and every one of them was missing before: a paused sandbox answers
 * 409, the client must wake it and send again; a conversation whose sandbox is
 * asleep must be waited on until RUNNING; and a wake must be cheap when there is
 * nothing to do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenHandsRecoveryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = OpenHandsClient(
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        base = server.url("/api/v1").toString().trimEnd('/')
    )

    private fun conversation(id: String, sandbox: String, status: String) = """
        {"items":[{"id":"$id","title":"t","sandbox_id":"$sandbox","sandbox_status":"$status"}]}
    """.trimIndent()

    @Test
    fun `a paused sandbox is woken and the message is sent again`() = runBlocking {
        val c = client()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Sandbox is PAUSED"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(conversation("c1", "sb1", "PAUSED")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(conversation("c1", "sb1", "RUNNING")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        c.sendMessage("k", "c1", "hello")

        val send = server.takeRequest()
        assertEquals("/api/v1/app-conversations/c1/send-message", send.path)
        val wake = server.takeRequest()
        assertEquals("/api/v1/sandboxes/sb1/resume", wake.path)
        val again = server.takeRequest()
        assertEquals("/api/v1/app-conversations/c1/send-message", again.path)
        assertEquals("hello", again.body.readUtf8().let { org.json.JSONObject(it).getJSONArray("content").getJSONObject(0).getString("text") })
    }

    @Test
    fun `a refusal that is not a pause is left alone`() = runBlocking {
        val c = client()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"bad"}"""))
        val failed = runCatching { c.sendMessage("k", "c1", "hi") }.isFailure
        assertTrue("a 400 must not be swallowed", failed)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a running conversation is returned without a wake`() = runBlocking {
        val c = client()
        server.enqueue(MockResponse().setResponseCode(200).setBody(conversation("c2", "sb2", "RUNNING")))
        val conv = c.ensureRunning("k", "c2")
        assertEquals("RUNNING", conv.sandboxStatus)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `ensureRunning wakes a paused sandbox and waits for RUNNING`() = runBlocking {
        val c = client()
        server.enqueue(MockResponse().setResponseCode(200).setBody(conversation("c3", "sb3", "PAUSED")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(conversation("c3", "sb3", "PAUSED")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(conversation("c3", "sb3", "RUNNING")))

        val conv = c.ensureRunning("k", "c3")

        assertEquals("RUNNING", conv.sandboxStatus)
        val paths = (0 until server.requestCount).map { server.takeRequest().path }
        assertTrue(paths.contains("/api/v1/sandboxes/sb3/resume"))
    }
}
