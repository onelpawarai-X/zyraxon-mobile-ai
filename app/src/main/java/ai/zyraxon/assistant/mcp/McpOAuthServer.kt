package ai.zyraxon.assistant.mcp

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A one-shot local HTTP server that catches the OAuth redirect.
 *
 * It exists because Canva, Cloudflare and Supabase all refuse a custom app scheme
 * and require `http://127.0.0.1:<port>` — the standard native-app loopback flow.
 * The app registers its dynamic client with *this* port, so the redirect lands on
 * a socket only this process can read.
 *
 * It answers exactly one callback, replies with a small page telling the user to
 * return to the app, and shuts down.
 */
class McpOAuthServer(private val expectedPath: String = "/callback") {

    companion object {
        private const val TAG = "McpOAuthServer"

        /** Ports the servers accept; the first free one is used. */
        private val PREFERRED_PORTS = intArrayOf(8765, 8766, 8767, 8768, 8770, 8771, 8772, 8773)

        /**
         * Binds the first free loopback port. Ports are tried in order so the
         * redirect URI is stable across runs, which keeps re-registration rare.
         */
        fun bind(): McpOAuthServer {
            for (port in PREFERRED_PORTS) {
                try {
                    val socket = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
                    return McpOAuthServer().also { it.socket = socket; it.port = port }
                } catch (_: Exception) {
                    // Port busy — try the next one.
                }
            }
            throw McpError("লোকাল পোর্ট পাওয়া যায়নি — অন্য কিছু চলছে কি না দেখো")
        }
    }

    private lateinit var socket: ServerSocket
    private var port: Int = 0

    val redirectUri: String get() = "http://127.0.0.1:$port$expectedPath"

    private val received = CountDownLatch(1)

    @Volatile private var code: String? = null
    @Volatile private var state: String? = null
    @Volatile private var error: String? = null

    /**
     * Serves in the background until the redirect arrives or the wait times out.
     * Returns the authorization code.
     *
     * It loops rather than serving one request, because a browser fires a
     * `/favicon.ico` probe right after the redirect. Answering that probe and
     * stopping would throw away the real callback.
     */
    fun awaitCode(timeoutSeconds: Long = 300): String {
        Thread {
            try {
                while (!socket.isClosed) {
                    val conn = socket.accept()
                    conn.use { handle(it) }
                    // The real callback landed; nothing else is expected.
                    if (code != null || error != null) break
                }
            } catch (e: Exception) {
                if (error == null && code == null) error = e.message ?: "লোকাল সার্ভার বন্ধ হয়ে গেছে"
            } finally {
                received.countDown()
            }
        }.apply { isDaemon = true; name = "mcp-oauth"; start() }

        if (!received.await(timeoutSeconds, TimeUnit.SECONDS)) {
            close()
            throw McpError("সময় শেষ — ব্রাউজারে অনুমতি দেওয়া হয়নি")
        }
        error?.let { throw McpError(it) }
        return code ?: throw McpError("অনুমতির কোড পাওয়া যায়নি")
    }

    fun close() {
        runCatching { socket.close() }
        received.countDown()
    }

    private fun handle(conn: Socket) {
        val requestLine = BufferedReader(InputStreamReader(conn.getInputStream(), Charsets.UTF_8)).readLine().orEmpty()
        val target = requestLine.split(" ").getOrNull(1).orEmpty()
        val params = parseQuery(target.substringAfter("?", ""))

        // Browsers ask for /favicon.ico right after the redirect; ignore it so the
        // real callback is not consumed by the wrong request.
        if (!target.startsWith(expectedPath)) {
            respond(conn, 404, "Not found", "")
            return
        }

        code = params["code"]
        state = params["state"]
        error = params["error_description"] ?: params["error"]

        val ok = code != null && error == null
        respond(conn, 200, if (ok) "Connected" else "Failed", page(ok, error.orEmpty()))
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split("&").mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = part.substringBefore("=")
            val value = URLDecoder.decode(part.substringAfter("=", ""), "UTF-8")
            key to value
        }.toMap()

    private fun respond(conn: Socket, status: Int, reason: String, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        (conn.getOutputStream() as OutputStream).use { out ->
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }
    }

    private fun page(ok: Boolean, detail: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>ZYRAXON</title>
        <style>
          body{margin:0;height:100vh;display:grid;place-items:center;
               font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;
               background:#0a0c14;color:#f4f6fb}
          .card{max-width:22rem;padding:2rem;border-radius:1.25rem;text-align:center;
                background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.14)}
          .mark{font-size:2.5rem}
          h1{font-size:1.1rem;margin:.75rem 0 .4rem}
          p{opacity:.7;font-size:.85rem;line-height:1.5;margin:0}
          .err{color:#ff9aa2}
        </style></head><body>
        <div class="card">
          <div class="mark">${if (ok) "✅" else "⚠️"}</div>
          <h1>${if (ok) "কানেক্টেড!" else "কানেক্ট করা যায়নি"}</h1>
          <p>${if (ok) "ZYRAXON অ্যাপে ফিরে যাও — টুলগুলো এখন পাওয়া যাচ্ছে।" else detail.ifBlank { "আবার চেষ্টা করো।" }}</p>
        </div>
        <script>setTimeout(function(){try{window.close()}catch(e){}},1200)</script>
        </body></html>
    """.trimIndent()
}
