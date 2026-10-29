package ai.zyraxon.assistant.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket

/**
 * Exercises the real `read_media` tool end to end: a real file on disk, a real
 * HTTP server serving a real JPEG, and the real execute() path.
 *
 * Nothing here is mocked — the point is to prove the assistant can actually read
 * a picture the user hands it, whether by path or by link, which is what the user
 * asked to be verified rather than claimed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaReadToolTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun writeJpeg(name: String, size: Int = 64): File {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { color = 0xFFEE22AA.toInt() })
        val dir = File(context.filesDir, "zyraxon/testmedia").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        return file
    }

    /**
     * Reads just the request headers. The stream must stay open afterwards so the
     * response can be written on the same socket — closing the reader closes the
     * socket and the client sees an empty reply.
     */
    private fun readHeaders(socket: java.net.Socket) {
        val input = socket.getInputStream()
        val line = StringBuilder()
        var blank = 0
        while (blank < 1) {
            val c = input.read()
            if (c == -1) return
            if (c == '\n'.code) {
                if (line.isEmpty()) blank++ else line.setLength(0)
            } else if (c != '\r'.code) {
                line.append(c.toChar())
            }
        }
    }

    private fun serveOne(server: ServerSocket, bytes: ByteArray, contentType: String) {
        val socket = server.accept()
        readHeaders(socket)
        socket.getOutputStream().apply {
            write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            write(bytes)
            flush()
        }
        socket.close()
    }

    private fun run(name: String, args: JSONObject): JSONObject =
        runBlocking { ToolRegistry.execute(context, name, args) }

    @Test
    fun `a local picture comes back as an image the model can see`() {
        val file = writeJpeg("local.jpg")
        val result = run("read_media", JSONObject().put("path", file.absolutePath))

        assertTrue("ছবি পড়া উচিত: $result", result.getBoolean("ok"))
        val dataUrl = result.getString("image_data_url")
        assertTrue("ডেটা URL হওয়া উচিত", dataUrl.startsWith("data:image/jpeg;base64,"))
        assertTrue("base64 বডি থাকতে হবে", dataUrl.length > 200)
    }

    @Test
    fun `a picture behind a real url is downloaded and read`() {
        val file = writeJpeg("remote.jpg", 96)
        val bytes = file.readBytes()

        val server = ServerSocket(0)
        val port = server.localPort
        val worker = Thread { repeat(2) { try { serveOne(server, bytes, "image/jpeg") } catch (_: Exception) {} } }
        worker.isDaemon = true
        worker.start()

        val result = run(
            "read_media",
            JSONObject().put("url", "http://127.0.0.1:$port/photo.jpg")
        )
        server.close()

        assertTrue("লিংকের ছবি পড়া উচিত: $result", result.getBoolean("ok"))
        assertTrue(
            "ডেটা URL ফেরত আসতে হবে",
            result.getString("image_data_url").startsWith("data:image/jpeg;base64,")
        )
        assertTrue("সামারিতে জানানো উচিত যে লিংক থেকে নামানো", result.getString("summary").contains("নামানো"))
    }

    @Test
    fun `a url with no extension is still recognised from its content type`() {
        val file = writeJpeg("noext.jpg", 48)
        val bytes = file.readBytes()

        val server = ServerSocket(0)
        val port = server.localPort
        val worker = Thread { try { serveOne(server, bytes, "image/jpeg") } catch (_: Exception) {} }
        worker.isDaemon = true
        worker.start()

        val result = run("read_media", JSONObject().put("url", "http://127.0.0.1:$port/media"))
        server.close()

        assertTrue("এক্সটেনশন ছাড়া লিংকও পড়া উচিত: $result", result.getBoolean("ok"))
        assertTrue(result.getString("image_data_url").startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `a dead link is reported as a failure rather than an empty image`() {
        val result = run(
            "read_media",
            JSONObject().put("url", "http://127.0.0.1:1/nothing.jpg")
        )
        assertFalse("ডেড লিংক সফল দেখানো যাবে না", result.getBoolean("ok"))
        assertEquals("download-failed", result.getString("error"))
    }

    @Test
    fun `asking for neither path nor url is refused`() {
        val result = run("read_media", JSONObject())
        assertFalse(result.getBoolean("ok"))
        assertEquals("no-source", result.getString("error"))
    }

    @Test
    fun `a missing local file is reported clearly`() {
        val result = run("read_media", JSONObject().put("path", "/nope/definitely-not-here.jpg"))
        assertFalse(result.getBoolean("ok"))
        assertEquals("not-found", result.getString("error"))
    }

    @Test
    fun `read_media is advertised with a url parameter so the model knows it can pass a link`() {
        val arr = ToolRegistry.schemas()
        val schema = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .firstOrNull { it.getJSONObject("function").getString("name") == "read_media" }
            ?: error("read_media স্কিমা নেই")
        val props = schema.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertTrue("url প্যারামিটার থাকতে হবে", props.has("url"))
        assertTrue("frames প্যারামিটার থাকতে হবে", props.has("frames"))
        assertTrue("path প্যারামিটার থাকতে হবে", props.has("path"))
    }
}
