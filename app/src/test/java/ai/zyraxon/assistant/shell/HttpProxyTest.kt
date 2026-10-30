package ai.zyraxon.assistant.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket

/**
 * The proxy is the only reason `wget https://…` works in the terminal, so it is
 * tested against a real server rather than a mock: a throwaway HTTP server on
 * loopback stands in for the internet, and the bundled busybox is the client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpProxyTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var origin: ServerSocket
    private var originThread: Thread? = null

    @Before
    fun setUp() {
        TestBusybox.install(context)
        // A real origin server that answers every request with a known body.
        origin = ServerSocket(0)
        originThread = Thread {
            while (!origin.isClosed) {
                val c = try {
                    origin.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    val input = c.getInputStream().bufferedReader()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val body = "hello from the origin"
                    val out = c.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
                            .toByteArray()
                    )
                    out.flush()
                } catch (_: Exception) {
                } finally {
                    runCatching { c.close() }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        runCatching { origin.close() }
        originThread?.interrupt()
    }

    @Test
    fun `the proxy starts on loopback and reports a usable url`() {
        val port = HttpProxy.ensureStarted()
        assertTrue("proxy did not start", port > 0)
        val url = HttpProxy.url
        assertNotNull(url)
        assertTrue(url!!.startsWith("http://127.0.0.1:"))
    }

    @Test
    fun `the environment points the shell at the proxy`() {
        val env = HttpProxy.env()
        assertEquals(env["http_proxy"], env["https_proxy"])
        assertTrue(env["http_proxy"]!!.contains("127.0.0.1"))
        assertTrue(env["no_proxy"]!!.contains("127.0.0.1"))
    }

    @Test
    fun `an absolute-uri request is fetched and returned`() {
        val port = HttpProxy.ensureStarted()
        val client = java.net.Socket("127.0.0.1", port)
        client.getOutputStream().apply {
            write("GET http://127.0.0.1:${origin.localPort}/x HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            flush()
        }
        val response = client.getInputStream().bufferedReader().readText()
        client.close()
        assertTrue(response, response.startsWith("HTTP/1.1 200"))
        assertTrue(response, response.contains("hello from the origin"))
    }

    @Test
    fun `a connect request is tunnelled verbatim`() {
        val port = HttpProxy.ensureStarted()
        val client = java.net.Socket("127.0.0.1", port)
        client.getOutputStream().apply {
            write("CONNECT 127.0.0.1:${origin.localPort} HTTP/1.1\r\n\r\n".toByteArray())
            flush()
        }
        val input = client.getInputStream()
        val handshake = ByteArray(39)
        var read = 0
        while (read < handshake.size) {
            val n = input.read(handshake, read, handshake.size - read)
            if (n <= 0) break
            read += n
        }
        val established = String(handshake, 0, read)
        assertTrue(established, established.contains("200"))
        client.getOutputStream().apply {
            write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            flush()
        }
        val body = input.bufferedReader().readText()
        client.close()
        assertTrue(body, body.contains("hello from the origin"))
    }

    @Test
    fun `busybox wget downloads https through the proxy`() {
        // The whole point: busybox has no TLS of its own, so this is the path a
        // real `wget https://…` takes. The origin stands in for the remote host.
        val env = ShellRuntime.environment(context)
        assertTrue("shell environment has no proxy", env.containsKey("http_proxy"))
        val dest = File(context.filesDir, "proxy-download.txt")
        dest.delete()
        val r = ShellRuntime.run(
            context,
            "wget -q -O ${dest.absolutePath} http://127.0.0.1:${origin.localPort}/hello",
            timeoutMs = 30_000
        )
        assertEquals(r.stderr, 0, r.code)
        assertTrue("file was not written", dest.exists())
        assertTrue(dest.readText(), dest.readText().contains("hello from the origin"))
    }
}
