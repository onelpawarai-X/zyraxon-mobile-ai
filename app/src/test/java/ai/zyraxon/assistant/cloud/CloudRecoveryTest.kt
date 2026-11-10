package ai.zyraxon.assistant.cloud

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
 * These are the behaviours that decide whether a cloud chat survives a pocket,
 * and every one of them was missing before: a paused sandbox answers 409, the
 * client must wake it and send again; a conversation whose sandbox is asleep
 * must be waited on until RUNNING; and the wake request must actually reach the
 * cloud.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudRecoveryTest {

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

    private fun client() = CloudClient(
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        base = server.url("/api/v1").toString().trimEnd('/')
    )

    private fun conversation(id: String, sandbox: String, status: String) = """
        {"items":[{"id":"$id","title":"t","sandbox_id":"$sandbox","sandbox_status":"$status",
        "session_api_key":"sk-1","conversation_url":"https://agent.example/api/conversations/$id"}]}
    """.trimIndent()

    /** Every path the client called, in order. */
    private fun paths(): List<String> =
        (0 until server.requestCount).map { server.takeRequest().path ?: "" }

    @Test
    fun `a paused sandbox is woken and the message is sent again`() = runBlocking {
        val c = client()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Sandbox is PAUSED"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(conversation("c1", "sb1", "PAUSED")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(conversation("c1", "sb1", "RUNNING")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        c.sendMessage("k", "c1", "hello")

        val p = paths()
        // send refused -> read the sandbox id -> wake it -> confirm it is up -> send again
        assertEquals("/api/v1/app-conversations/c1/send-message", p[0])
        assertEquals("/api/v1/sandboxes/sb1/resume", p[2])
        assertEquals("/api/v1/app-conversations/c1/send-message", p[4])
    }

    /**
     * The bug this pins: a POST with no body is refused by OkHttp before it is
     * ever sent, and the refusal was swallowed — so resume() looked like it
     * worked while the sandbox stayed asleep.
     */
    @Test
    fun `the wake request really carries a body`() = runBlocking {
        val c = client()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Sandbox is PAUSED"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(conversation("c1", "sb1", "PAUSED")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(conversation("c1", "sb1", "RUNNING")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        c.sendMessage("k", "c1", "hi")

        val requests = (0 until server.requestCount).map { server.takeRequest() }
        val wake = requests.single { it.path == "/api/v1/sandboxes/sb1/resume" }
        assertEquals("POST", wake.method)
        assertEquals("{}", wake.body.readUtf8())
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

    /** The cloud may answer a single-id lookup with a bare array; that must not throw. */
    @Test
    fun `a bare array conversation body is accepted`() = runBlocking {
        val c = client()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """[{"id":"c4","title":"t","sandbox_id":"sb4","sandbox_status":"RUNNING","session_api_key":"sk"}]"""
        ))
        val conv = c.ensureRunning("k", "c4")
        assertEquals("c4", conv.id)
        assertEquals("RUNNING", conv.sandboxStatus)
    }
}
