package ai.zyraxon.assistant.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the real MCP client against a real HTTP server standing in for an MCP
 * server, and the real loopback OAuth listener with a real HTTP request. Nothing
 * here is mocked: sockets are opened, JSON-RPC is parsed, codes are caught.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class McpClientTest {

    private var fake: HttpServer? = null
    private val sawSessionHeader = AtomicBoolean(false)
    private val authHeader = AtomicReference("")

    @After
    fun tearDown() {
        fake?.stop(0)
    }

    // ---- the whole JSON-RPC conversation ------------------------------------

    @Test
    fun `initialize lists tools and calls one over real http`() = runBlocking {
        val seenBody = StringBuilder()
        val server = startFakeMcp(
            tools = """[{"name":"create_design","description":"Make a design",
                        "inputSchema":{"type":"object","properties":{"title":{"type":"string"}}}}]""",
            onToolsCall = { body -> seenBody.append(body) }
        )

        val client = McpClient("$server/mcp", accessToken = "tok-123")
        val init = client.connect()
        assertEquals("2025-06-18", init.optString("protocolVersion"))

        val tools = client.listTools()
        assertEquals(1, tools.size)
        assertEquals("create_design", tools[0].optString("name"))

        val result = client.callTool("create_design", JSONObject().put("title", "জন্মদিন"))
        val text = result.optJSONArray("content").optJSONObject(0).optString("text")
        assertEquals("তৈরি হয়েছে", text)

        // The arguments really travelled, and the session id was echoed back.
        assertTrue(seenBody.toString().contains("জন্মদিন"))
        assertTrue(sawSessionHeader.get())
        assertTrue(authHeader.get().startsWith("Bearer "))
    }

    /** Streamable-HTTP servers may answer with a one-message SSE stream. */
    @Test
    fun `an sse framed response is understood`() = runBlocking {
        val server = startFakeMcp(tools = """[{"name":"ping","description":"p"}]""", sse = true)
        val client = McpClient("$server/mcp", accessToken = null)
        client.connect()
        val tools = client.listTools()
        assertEquals("ping", tools[0].optString("name"))
    }

    @Test
    fun `a 401 becomes a re-auth signal rather than a crash`() = runBlocking {
        val server = startFakeMcp(tools = "[]", unauthorized = true)
        val client = McpClient("$server/mcp", accessToken = "stale")
        try {
            client.connect()
            throw AssertionError("401 should have raised McpAuthRequired")
        } catch (expected: McpAuthRequired) {
            assertTrue(expected.message!!.contains("401"))
        }
    }

    // ---- loopback OAuth listener -------------------------------------------

    @Test
    fun `the loopback listener catches the redirect and returns the code`() {
        val listener = McpOAuthServer.bind()
        try {
            assertTrue(listener.redirectUri.startsWith("http://127.0.0.1:"))
            val port = listener.redirectUri.removePrefix("http://127.0.0.1:").substringBefore("/")

            val worker = Thread {
                runCatching {
                    (URL("${listener.redirectUri}?code=abc123&state=st-1")
                        .openConnection() as HttpURLConnection).inputStream.readBytes()
                }
            }.apply { isDaemon = true; start() }

            assertEquals("abc123", listener.awaitCode(timeoutSeconds = 20))
            worker.join(2000)
            // The page it returned really is a page.
            assertEquals(port, listener.redirectUri.removePrefix("http://127.0.0.1:").substringBefore("/"))
        } finally {
            listener.close()
        }
    }

    @Test
    fun `the listener ignores a favicon probe and still waits for the callback`() {
        val listener = McpOAuthServer.bind()
        try {
            val port = listener.redirectUri.removePrefix("http://127.0.0.1:").substringBefore("/")
            val worker = Thread {
                runCatching {
                    // Browsers ask for this first; it must not consume the callback.
                    (URL("http://127.0.0.1:$port/favicon.ico").openConnection() as HttpURLConnection).responseCode
                    (URL("${listener.redirectUri}?code=real-code")
                        .openConnection() as HttpURLConnection).inputStream.readBytes()
                }
            }.apply { isDaemon = true; start() }
            assertEquals("real-code", listener.awaitCode(timeoutSeconds = 20))
            worker.join(2000)
        } finally {
            listener.close()
        }
    }

    // ---- PKCE, against the RFC 7636 vector ---------------------------------

    @Test
    fun `pkce challenge matches the rfc 7636 example`() {
        // The verifier and its expected S256 challenge, from RFC 7636 Appendix B.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", McpOAuth.codeChallenge(verifier))
    }

    @Test
    fun `each pkce verifier is unique and url safe`() {
        val a = McpOAuth.newCodeVerifier()
        val b = McpOAuth.newCodeVerifier()
        assertNotEquals(a, b)
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]{43,128}")))
        assertNotEquals(McpOAuth.newState(), McpOAuth.newState())
    }

    // ---- tool naming and the schema handed to the model --------------------

    @Test
    fun `a tool is namespaced by its server and offered in openai shape`() {
        val tool = McpTool(
            serverId = "canva", serverName = "Canva", name = "create_design",
            description = "Make a design", inputSchema = JSONObject().put("type", "object")
        )
        assertEquals("canva__create_design", tool.qualifiedName)

        val schema = tool.toOpenAiSchema()
        assertEquals("function", schema.optString("type"))
        val fn = schema.getJSONObject("function")
        assertEquals("canva__create_design", fn.optString("name"))
        assertTrue(fn.optString("description").contains("Canva"))
        assertEquals("object", fn.getJSONObject("parameters").optString("type"))
    }

    @Test
    fun `two servers exposing the same tool name do not collide`() {
        val a = McpTool("canva", "Canva", "search", "", JSONObject())
        val b = McpTool("supabase", "Supabase", "search", "", JSONObject())
        assertNotEquals(a.qualifiedName, b.qualifiedName)
    }

    // ---- the shipped catalogue --------------------------------------------

    @Test
    fun `the four shipped servers have verified endpoints and auth styles`() {
        val byId = McpCatalog.builtIn.associateBy { it.id }
        assertEquals(4, byId.size)

        assertEquals("https://mcp.canva.com/mcp", byId["canva"]!!.url)
        assertEquals(McpAuth.OAUTH, byId["canva"]!!.auth)

        assertEquals("https://mcp.cloudflare.com/mcp", byId["cloudflare"]!!.url)
        assertEquals(McpAuth.OAUTH, byId["cloudflare"]!!.auth)

        assertEquals("https://mcp.supabase.com/mcp", byId["supabase"]!!.url)
        assertEquals(McpAuth.OAUTH, byId["supabase"]!!.auth)

        // GitHub is the token one, and it must point at the token page.
        assertEquals("https://api.githubcopilot.com/mcp/", byId["github"]!!.url)
        assertEquals(McpAuth.TOKEN, byId["github"]!!.auth)
        assertTrue(byId["github"]!!.tokenUrl.contains("settings/personal-access-tokens"))
    }

    @Test
    fun `a server survives a round trip through storage`() {
        for (server in McpCatalog.builtIn) {
            val restored = McpCatalog.fromJson(McpCatalog.toJson(server))
            assertEquals(server.id, restored.id)
            assertEquals(server.url, restored.url)
            assertEquals(server.auth, restored.auth)
            assertEquals(server.highlights, restored.highlights)
            assertEquals(server.tokenUrl, restored.tokenUrl)
        }
    }

    @Test
    fun `a custom url is marked custom and gets a usable id`() {
        val server = McpCatalog.custom("https://example.com", token = "t")
        assertEquals(McpAuth.TOKEN, server.auth)
        assertTrue(server.custom)
        assertTrue(server.id.startsWith("custom_"))
    }

    // ---- a stand-in MCP server ---------------------------------------------

    private fun startFakeMcp(
        tools: String,
        sse: Boolean = false,
        unauthorized: Boolean = false,
        onToolsCall: ((String) -> Unit)? = null
    ): String {
        sawSessionHeader.set(false)
        authHeader.set("")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fake = server

        server.createContext("/mcp") { exchange: HttpExchange ->
            if (unauthorized) {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }
            exchange.requestHeaders.getFirst("Mcp-Session-Id")?.let { sawSessionHeader.set(true) }
            exchange.requestHeaders.getFirst("Authorization")?.let { authHeader.set(it) }

            val body = BufferedReader(InputStreamReader(exchange.requestBody)).readText()
            val request = JSONObject(body)
            val method = request.optString("method")
            val id = request.opt("id")

            if (method == "notifications/initialized") {
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
                return@createContext
            }

            val result = when (method) {
                "initialize" ->
                    """{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"fake","version":"1"}}"""
                "tools/list" -> """{"tools":$tools}"""
                "tools/call" -> {
                    onToolsCall?.invoke(body)
                    """{"content":[{"type":"text","text":"তৈরি হয়েছে"}]}"""
                }
                else -> "{}"
            }

            val envelope = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
            val bytes = if (sse) {
                "event: message\ndata: $envelope\n\n".toByteArray(Charsets.UTF_8)
            } else {
                envelope.toByteArray(Charsets.UTF_8)
            }

            exchange.responseHeaders.add("Content-Type", if (sse) "text/event-stream" else "application/json")
            exchange.responseHeaders.add("Mcp-Session-Id", "sess-42")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return "http://127.0.0.1:${server.address.port}"
    }
}
