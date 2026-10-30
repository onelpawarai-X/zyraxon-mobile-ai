package ai.zyraxon.assistant.shell

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * A loopback HTTP/HTTPS proxy for the bundled toolbox.
 *
 * busybox's `wget` speaks plain HTTP only — it prints "TLS certificate
 * validation not implemented" and gives up on `https://`. That would make the
 * terminal a toy: no GitHub, no apt/pip-style downloads, nothing on the modern
 * web.
 *
 * The app itself has a real TLS stack (the JVM's), so this proxy listens on
 * 127.0.0.1 and does the encrypted leg on busybox's behalf. busybox is handed
 * `http_proxy`/`https_proxy` pointing here, so `wget https://…` simply works.
 *
 * Two shapes are handled, because clients differ:
 *  - `GET https://host/path` (absolute URI) — busybox wget's shape. The proxy
 *    fetches the URL itself and streams the response back.
 *  - `CONNECT host:443` — the tunnel shape. Bytes are piped verbatim.
 *
 * Bound to loopback only, so nothing on the network can reach it.
 */
object HttpProxy {

    private const val TAG = "HttpProxy"

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    /** The live proxy URL, or null when it could not start. */
    val url: String? get() = if (port > 0) "http://127.0.0.1:$port" else null

    /** Starts the proxy once; returns its port, or 0 if it is unavailable. */
    @Synchronized
    fun ensureStarted(): Int {
        if (port > 0 && server?.isClosed == false) return port
        return try {
            val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = s
            port = s.localPort
            Thread({ acceptLoop(s) }, "zyraxon-http-proxy").apply {
                isDaemon = true
                start()
            }
            port
        } catch (_: Exception) {
            port = 0
            0
        }
    }

    /** The environment busybox needs so its tools find this proxy. */
    fun env(): Map<String, String> {
        val p = ensureStarted()
        if (p == 0) return emptyMap()
        val u = "http://127.0.0.1:$p"
        return mapOf(
            "http_proxy" to u,
            "https_proxy" to u,
            "HTTP_PROXY" to u,
            "HTTPS_PROXY" to u,
            // Never route our own loopback traffic back through ourselves.
            "no_proxy" to "localhost,127.0.0.1,::1",
            "NO_PROXY" to "localhost,127.0.0.1,::1"
        )
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (_: Exception) {
                break
            }
            Thread({ handle(client) }, "zyraxon-http-proxy-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 60_000
            val input = BufferedInputStream(client.getInputStream())
            val head = readHead(input) ?: run { client.close(); return }
            val requestLine = head.lineSequence().firstOrNull()?.trim() ?: ""
            val parts = requestLine.split(' ')
            if (parts.size < 3) {
                client.close()
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]
            if (method == "CONNECT") {
                tunnel(client, target)
            } else {
                forward(client, input, method, target, head)
            }
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }

    /** Reads the request head, stopping at the blank line. */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            buf.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                else -> 0
            }
            if (state == 4) return buf.toString("UTF-8")
            if (buf.size() > 65_536) return buf.toString("UTF-8")
        }
    }

    /** `CONNECT host:port` — pipe both directions verbatim. */
    private fun tunnel(client: Socket, target: String) {
        val idx = target.lastIndexOf(':')
        if (idx <= 0) {
            client.close()
            return
        }
        val host = target.substring(0, idx)
        val portNum = target.substring(idx + 1).toIntOrNull() ?: 443
        val upstream = Socket(host, portNum)
        upstream.soTimeout = 60_000
        client.getOutputStream().apply {
            write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            flush()
        }
        val back = Thread({ pipe(upstream, client) }, "zyraxon-proxy-up")
        back.isDaemon = true
        back.start()
        pipe(client, upstream)
        runCatching { upstream.close() }
        runCatching { client.close() }
    }

    /** Absolute-URI request — fetch it here with real TLS and stream it back. */
    private fun forward(client: Socket, input: InputStream, method: String, target: String, head: String) {
        val out = client.getOutputStream()
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            respond(out, 400, "Bad Request", "this proxy only speaks http:// and https:// URLs")
            client.close()
            return
        }
        val conn = (URL(target).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = method
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ZYRAXON/1.0 (Android)")
            // Pass the client's own headers through, minus hop-by-hop ones.
            for (line in head.lineSequence()) {
                val i = line.indexOf(':')
                if (i <= 0) continue
                val name = line.substring(0, i).trim()
                val value = line.substring(i + 1).trim()
                if (name.equals("Host", true) || name.equals("Proxy-Connection", true) ||
                    name.equals("Connection", true) || name.equals("Content-Length", true)
                ) continue
                runCatching { conn.setRequestProperty(name, value) }
            }
            // A POST body, if the client sent one.
            val contentLength = head.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            if (contentLength > 0 && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(body, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                conn.outputStream.use { it.write(body, 0, read) }
            }
            val code = conn.responseCode
            val body = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            val contentType = conn.contentType ?: "application/octet-stream"
            val header = buildString {
                append("HTTP/1.1 ").append(code).append(' ').append(conn.responseMessage ?: "OK").append("\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(header.toByteArray())
            if (method != "HEAD") out.write(body)
            out.flush()
        } catch (e: Exception) {
            respond(out, 502, "Bad Gateway", e.message ?: "proxy fetch failed")
        } finally {
            runCatching { conn.disconnect() }
            runCatching { client.close() }
        }
    }

    private fun respond(out: OutputStream, code: Int, message: String, body: String) {
        val bytes = body.toByteArray()
        out.write(
            ("HTTP/1.1 $code $message\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    private fun pipe(from: Socket, to: Socket) {
        try {
            val buf = ByteArray(8192)
            val inS = from.getInputStream()
            val outS = to.getOutputStream()
            while (true) {
                val n = inS.read(buf)
                if (n <= 0) break
                outS.write(buf, 0, n)
                outS.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { to.close() }
        }
    }

    /** Downloads a URL to a file using the app's own TLS. Used by the tools. */
    fun download(url: String, dest: File, userAgent: String = "ZYRAXON/1.0 (Android)"): Long {
        dest.parentFile?.mkdirs()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }
        conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        val size = dest.length()
        conn.disconnect()
        return size
    }
}
