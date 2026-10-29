package ai.zyraxon.assistant.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private var executor: java.util.concurrent.ExecutorService? = null
    private val clients = mutableListOf<McpClient>()
    private val sawSessionHeader = AtomicBoolean(false)
    private val authHeader = AtomicReference("")
    private val initializeCount = AtomicInteger(0)
    /** The last JSON-RPC envelope the fake server received that was a reply. */
    private val lastReply = AtomicReference<JSONObject?>(null)
    /** Every JSON-RPC method the fake server was asked for, in order. */
    private val methodsSeen = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /**
     * Every client opened by a test, tracked so it can be closed. A client holds a
     * live SSE channel; leaving one open would keep the JVM alive after the test.
     */
    private fun newClient(
        endpoint: String,
        accessToken: String?,
        onNotification: ((String, JSONObject) -> Unit)? = null,
        onRequest: (suspend (String, JSONObject) -> JSONObject)? = null
    ): McpClient {
        val client = McpClient(endpoint, accessToken, onNotification = onNotification, onRequest = onRequest)
        clients.add(client)
        return client
    }

    /**
     * Blocks until the fake server has received a reply envelope, or the wait
     * expires. Used by the requests a server sends us (sampling, elicitation).
     */
    private fun awaitReply(timeoutSeconds: Long = 20) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (lastReply.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
    }

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        fake?.stop(0)
        // HttpServer.stop() does not shut down the executor it was given, and a
        // non-daemon pool keeps the test JVM alive forever. Both are done here.
        executor?.shutdownNow()
        executor = null
        fake = null
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

        val client = newClient("$server/mcp", accessToken = "tok-123")
        val init = client.ensureConnected()
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

    /**
     * The regression that made tools seem to vanish: a second call must reuse the
     * session rather than running `initialize` again.
     */
    @Test
    fun `a second tool call reuses the session instead of reinitialising`() = runBlocking {
        val server = startFakeMcp(tools = """[{"name":"ping","description":"p"}]""")
        val client = newClient("$server/mcp", accessToken = "tok")
        client.ensureConnected()
        client.ensureConnected()
        client.callTool("ping", JSONObject())
        client.callTool("ping", JSONObject())

        assertEquals("initialize must run exactly once", 1, initializeCount.get())
    }

    /** Streamable-HTTP servers may answer with a one-message SSE stream. */
    @Test
    fun `an sse framed response is understood`() = runBlocking {
        val server = startFakeMcp(tools = """[{"name":"ping","description":"p"}]""", sse = true)
        val client = newClient("$server/mcp", accessToken = null)
        client.ensureConnected()
        val tools = client.listTools()
        assertEquals("ping", tools[0].optString("name"))
    }

    @Test
    fun `a 401 becomes a re-auth signal rather than a crash`() = runBlocking {
        val server = startFakeMcp(tools = "[]", unauthorized = true)
        val client = newClient("$server/mcp", accessToken = "stale")
        try {
            client.ensureConnected()
            throw AssertionError("401 should have raised McpAuthRequired")
        } catch (expected: McpAuthRequired) {
            assertTrue(expected.message!!.contains("401"))
        }
    }

    /** A dropped session is retried once, transparently. */
    @Test
    fun `a dropped session is re-established and the call retried`() = runBlocking {
        val server = startFakeMcp(tools = """[{"name":"ping","description":"p"}]""", dropSessionOnce = true)
        val client = newClient("$server/mcp", accessToken = "tok")
        client.ensureConnected()
        // The next call gets a 404 because the fake forgot the session.
        val tools = client.listTools()
        assertEquals("ping", tools[0].optString("name"))
        assertEquals(2, initializeCount.get())
    }

    // ---- resources and prompts ---------------------------------------------

    @Test
    fun `resources and templates are listed and a resource is read`() = runBlocking {
        val server = startFakeMcp(
            tools = "[]",
            capabilities = """{"resources":{"listChanged":true,"subscribe":true},"prompts":{"listChanged":true}}""",
            resources = """[{"uri":"file://readme.md","name":"README","mimeType":"text/markdown"}]""",
            resourceTemplates = """[{"uriTemplate":"db://{table}","name":"table"}]""",
            resourceText = "আসল ফাইলের বিষয়বস্তু"
        )
        val client = newClient("$server/mcp", accessToken = null)
        client.ensureConnected()

        assertTrue(client.supportsResources)
        assertTrue(client.supportsResourcesListChanged())

        val resources = client.listResources()
        assertEquals(1, resources.size)
        assertEquals("file://readme.md", resources[0].optString("uri"))

        val templates = client.listResourceTemplates()
        assertEquals("db://{table}", templates[0].optString("uriTemplate"))

        val read = client.readResource("file://readme.md")
        val text = read.optJSONArray("contents").optJSONObject(0).optString("text")
        assertEquals("আসল ফাইলের বিষয়বস্তু", text)
    }

    @Test
    fun `prompts are listed with their arguments and rendered`() = runBlocking {
        val server = startFakeMcp(
            tools = "[]",
            capabilities = """{"prompts":{"listChanged":true}}""",
            prompts = """[{"name":"review","description":"Review code",
                          "arguments":[{"name":"code","description":"The code","required":true}]}]""",
            promptText = "এবার কোডটা রিভিউ করো"
        )
        val client = newClient("$server/mcp", accessToken = null)
        client.ensureConnected()

        assertTrue(client.supportsPrompts)
        val prompts = client.listPrompts()
        assertEquals("review", prompts[0].optString("name"))
        assertEquals("code", prompts[0].optJSONArray("arguments").optJSONObject(0).optString("name"))

        val rendered = client.getPrompt("review", JSONObject().put("code", "x=1"))
        val message = rendered.optJSONArray("messages").optJSONObject(0)
        assertEquals("user", message.optString("role"))
        assertEquals("এবার কোডটা রিভিউ করো", message.optJSONObject("content").optString("text"))
    }

    /** A server that never advertised the capability must not be asked. */
    @Test
    fun `a server without resource support is not asked for resources`() = runBlocking {
        val server = startFakeMcp(tools = """[{"name":"ping","description":"p"}]""", capabilities = """{"tools":{}}""")
        val client = newClient("$server/mcp", accessToken = null)
        client.ensureConnected()
        assertFalse(client.supportsResources)
        assertFalse(client.supportsPrompts)
        assertEquals(0, client.listResources().size)
        assertEquals(0, client.listPrompts().size)
    }

    // ---- notifications and sampling ----------------------------------------

    /**
     * The point of the persistent channel: a server that adds a tool is noticed
     * without the user reconnecting.
     */
    @Test
    fun `a list_changed notification is delivered without a reconnect`() = runBlocking {
        val got = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val server = startFakeMcp(
            tools = """[{"name":"ping","description":"p"}]""",
            capabilities = """{"tools":{"listChanged":true}}""",
            notifyAfterInitialize = "notifications/tools/list_changed"
        )
        val client = newClient(
            "$server/mcp", accessToken = null,
            onNotification = { method, _ ->
                got.set(method)
                latch.countDown()
            }
        )
        client.ensureConnected()

        assertTrue("notification must arrive on the channel", latch.await(20, TimeUnit.SECONDS))
        assertEquals("notifications/tools/list_changed", got.get())
        // And no reconnect happened to learn it.
        assertEquals(1, initializeCount.get())
    }

    /** sampling: the server asks us to run a model, and gets our answer back. */
    @Test
    fun `a sampling request is answered over the channel`() = runBlocking {
        val asked = AtomicReference<String?>(null)
        val messageCount = AtomicInteger(0)
        val answered = CountDownLatch(1)
        val server = startFakeMcp(
            tools = "[]",
            capabilities = """{"tools":{}}""",
            samplingAfterInitialize = true,
            onSamplingReply = { answered.countDown() }
        )
        val client = newClient(
            "$server/mcp", accessToken = null,
            onRequest = { method, params ->
                // Values are recorded and asserted on the test thread; an assertion
                // thrown here would only become an error reply.
                asked.set(method)
                messageCount.set(params.optJSONArray("messages")?.length() ?: 0)
                JSONObject().put("role", "assistant")
                    .put("content", JSONObject().put("type", "text").put("text", "আমার উত্তর"))
                    .put("model", "test-model")
            }
        )
        client.ensureConnected()

        assertTrue("the server must receive our sampling reply", answered.await(20, TimeUnit.SECONDS))
        assertEquals("sampling/createMessage", asked.get())
        assertTrue("the server's messages must reach the runner", messageCount.get() > 0)
    }

    // ---- loopback OAuth listener -------------------------------------------

    @Test
    fun `the loopback listener catches the redirect and returns the code`() {
        val listener = McpOAuthServer.bind()
        try {
            assertTrue(listener.redirectUri.startsWith("http://127.0.0.1:"))
            val worker = Thread {
                runCatching {
                    (URL("${listener.redirectUri}?code=abc123&state=st-1")
                        .openConnection() as HttpURLConnection).inputStream.readBytes()
                }
            }.apply { isDaemon = true; start() }

            assertEquals("abc123", listener.awaitCode(timeoutSeconds = 20))
            worker.join(2000)
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

    // ---- naming, schemas and routing ---------------------------------------

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

    /** A resource carries its server so a read routes back to the right place. */
    @Test
    fun `a resource is namespaced by its server`() {
        val r = McpResource("supabase", "Supabase", "db://users", "users", "", "text/plain")
        assertEquals("supabase__db://users", r.qualifiedUri)
    }

    @Test
    fun `a prompt is namespaced by its server`() {
        val p = McpPrompt("github", "GitHub", "review", "", emptyList())
        assertEquals("github__review", p.qualifiedName)
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

    @Test
    fun `a punycode host does not put a namespace separator inside its id`() {
        // `xn--80ak6aa92e.com` is a real internationalised host. Naively replacing
        // each non-alphanumeric with `_` turns the double hyphen into `__`, which is
        // the separator used to namespace tools — the id would then split in the
        // wrong place and every tool on that server would fail to route.
        val server = McpCatalog.custom("https://xn--80ak6aa92e.com/mcp", token = null)
        assertFalse("id must not contain the separator: ${server.id}", server.id.contains("__"))
    }

    @Test
    fun `two long hosts sharing a prefix get different ids`() {
        // The ids must stay distinct even when the first 28 characters are identical,
        // which a plain `take(28)` cannot guarantee — two servers would then share an
        // id and each would overwrite the other's tools.
        val stem = "a".repeat(30)
        val a = McpCatalog.custom("https://$stem-one.example.com/mcp", token = null)
        val b = McpCatalog.custom("https://$stem-two.example.com/mcp", token = null)
        assertNotEquals(a.id, b.id)
    }

    // ---- the rest of the protocol ------------------------------------------

    @Test
    fun `ping, subscribe and completion reach the server`() = runBlocking {
        val server = startFakeMcp(
            tools = "[]",
            capabilities = """{"resources":{"subscribe":true},"completions":{}}"""
        )
        val client = newClient("$server/mcp", accessToken = null)
        client.ensureConnected()

        assertTrue("subscribe must be advertised", client.supportsResourceSubscribe)
        assertTrue("completion must be advertised", client.supportsCompletion)

        client.ping()
        client.subscribeResource("file://readme.md")
        client.unsubscribeResource("file://readme.md")
        client.setLogLevel("debug")
        val completion = client.complete("ref/prompt", "greet", JSONObject().put("name", "lang").put("value", "বা"))
        assertEquals(2, completion.optJSONObject("completion")?.optJSONArray("values")?.length())
        // Each call must really have been sent, not answered by a catch-all.
        for (expected in listOf(
            "ping", "resources/subscribe", "resources/unsubscribe",
            "logging/setLevel", "completion/complete"
        )) {
            assertTrue("সার্ভারে $expected পৌঁছায়নি", methodsSeen.contains(expected))
        }
        // All of these reuse the one session.
        assertEquals(1, initializeCount.get())
    }

    @Test
    fun `an unadvertised capability is reported as unsupported`() = runBlocking {
        val server = startFakeMcp(tools = "[]", capabilities = """{"resources":{}}""")
        val client = newClient("$server/mcp", accessToken = null)
        client.ensureConnected()
        assertFalse("subscribe is opt-in", client.supportsResourceSubscribe)
        assertFalse("completion is opt-in", client.supportsCompletion)
    }

    @Test
    fun `an elicitation request is answered with a decline`() = runBlocking {
        // A server that asks the user something must get an answer, or its call
        // never returns. The agent has no prompt surface, so it declines — and the
        // reply must actually reach the server.
        val server = startFakeMcp(
            tools = "[]",
            capabilities = "{}",
            requestAfterInitialize = "elicitation/create"
        )
        val client = newClient(
            "$server/mcp", accessToken = null,
            onRequest = { method, _ ->
                // This mirrors what McpManager.onServerRequest answers.
                when (method) {
                    "elicitation/create" -> JSONObject().put("action", "decline")
                    else -> JSONObject()
                }
            }
        )
        client.ensureConnected()

        awaitReply()
        val reply = lastReply.get()
        assertNotNull("the server must receive a reply, not be left hanging", reply)
        assertEquals("decline", reply!!.optJSONObject("result")?.optString("action"))
        assertEquals(901, reply.opt("id"))
    }

    @Test
    fun `a resources updated notification refreshes the resource text`() = runBlocking {
        val server = startFakeMcp(
            tools = "[]",
            capabilities = """{"resources":{"listChanged":true,"subscribe":true}}""",
            resources = """[{"uri":"file://readme.md","name":"readme"}]""",
            resourceText = "প্রথম সংস্করণ",
            resourceTextAfterUpdate = "দ্বিতীয় সংস্করণ",
            notifyOnSubscribe = true
        )
        val client = newClient("$server/mcp", accessToken = null)
        client.ensureConnected()
        assertTrue(client.isConnected)
        assertEquals(1, initializeCount.get())
    }

    /**
     * The whole subscribe→notify→re-read loop through the real manager: after the
     * server says the resource changed, the cached text must be the new text, not
     * the copy read earlier.
     */
    @Test
    fun `the manager caches the refreshed text after an updated notification`() = runBlocking {
        val server = startFakeMcp(
            tools = "[]",
            capabilities = """{"resources":{"listChanged":true,"subscribe":true}}""",
            resources = """[{"uri":"file://readme.md","name":"readme"}]""",
            resourceText = "প্রথম সংস্করণ",
            resourceTextAfterUpdate = "দ্বিতীয় সংস্করণ",
            notifyOnSubscribe = true
        )
        val manager = newManager()
        try {
            val conn = manager.connectWithToken(McpCatalog.custom("$server/mcp", null), null)
            val qualified = "${conn.server.id}__file://readme.md"

            val first = manager.readResource(qualified)
            assertTrue("প্রথম পাঠ ব্যর্থ: $first", first.optBoolean("ok"))
            assertEquals("প্রথম সংস্করণ", manager.cachedResourceText(qualified)?.optString("output"))

            val subscribed = manager.subscribeResource(conn.server.id, "file://readme.md")
            assertTrue("সাবস্ক্রাইব ব্যর্থ: $subscribed", subscribed.optBoolean("ok"))

            // The notification is pushed from the subscribe response, so the refresh
            // runs right after. Wait for the cache to catch up.
            val deadline = System.currentTimeMillis() + 20_000
            var text = manager.cachedResourceText(qualified)?.optString("output")
            while (text != "দ্বিতীয় সংস্করণ" && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
                text = manager.cachedResourceText(qualified)?.optString("output")
            }
            assertEquals("দ্বিতীয় সংস্করণ", text)

            // The change is also queued for the model's next turn, which is what
            // makes a subscription useful rather than a cache nobody reads.
            val deadline2 = System.currentTimeMillis() + 20_000
            var notes = manager.drainResourceUpdates()
            while (notes.isEmpty() && System.currentTimeMillis() < deadline2) {
                Thread.sleep(50)
                notes = manager.drainResourceUpdates()
            }
            assertTrue("বদলানোর নোট কিউতে আসেনি", notes.any { it.contains("দ্বিতীয় সংস্করণ") })
        } finally {
            manager.close()
        }
    }

    /**
     * `roots/list` must name the real workspace. An empty answer tells the server
     * it has nowhere to write, which is not true.
     */
    @Test
    fun `a roots list request names the assistant workspace`() = runBlocking {
        val server = startFakeMcp(
            tools = "[]",
            capabilities = "{}",
            requestAfterInitialize = "roots/list"
        )
        val manager = newManager()
        try {
            manager.connectWithToken(McpCatalog.custom("$server/mcp", null), null)
            awaitReply()
            val roots = lastReply.get()?.optJSONObject("result")?.optJSONArray("roots")
            assertNotNull("roots/list এর উত্তর আসেনি", roots)
            assertTrue("অন্তত একটা রুট থাকতে হবে", (roots?.length() ?: 0) > 0)
            val uri = roots!!.optJSONObject(0).optString("uri")
            assertTrue("রুটের uri workspace দেখাতে হবে: $uri", uri.startsWith("file://") && uri.contains("zyraxon"))
        } finally {
            manager.close()
        }
    }

    /** A manager over the Robolectric context, with an optional sampling runner. */
    private fun newManager(
        samplingRunner: (suspend (JSONObject) -> JSONObject)? = null
    ): McpManager = McpManager(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        samplingRunner = samplingRunner
    )

    // ---- a stand-in MCP server ---------------------------------------------

    /**
     * A real HTTP server that speaks enough MCP to exercise the client: JSON-RPC,
     * an optional SSE answer, an optional notification channel, resources, prompts
     * and a sampling request. It also counts `initialize` calls, which is how the
     * "do not reconnect" tests are checked.
     */
    private fun startFakeMcp(
        tools: String,
        sse: Boolean = false,
        unauthorized: Boolean = false,
        dropSessionOnce: Boolean = false,
        capabilities: String = "{}",
        resources: String = "[]",
        resourceTemplates: String = "[]",
        resourceText: String = "",
        resourceTextAfterUpdate: String? = null,
        notifyOnSubscribe: Boolean = false,
        prompts: String = "[]",
        promptText: String = "",
        notifyAfterInitialize: String? = null,
        notifyParams: String = "{}",
        samplingAfterInitialize: Boolean = false,
        requestAfterInitialize: String? = null,
        onSamplingReply: (() -> Unit)? = null,
        onToolsCall: ((String) -> Unit)? = null
    ): String {
        sawSessionHeader.set(false)
        authHeader.set("")
        initializeCount.set(0)
        lastReply.set(null)
        methodsSeen.clear()
        val dropped = AtomicBoolean(false)
        val textNow = AtomicReference(resourceText)
        // The channel thread owns the writer; a POST thread can ask it to push.
        val pushChannel = AtomicReference<((String) -> Unit)?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // The channel GET blocks for seconds, so a single-threaded server would
        // deadlock and no POST could be answered. The threads are daemons and the
        // pool is shut down in tearDown, so the test JVM can still exit.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8) { r ->
            Thread(r, "fake-mcp").apply { isDaemon = true }
        }
        executor = pool
        server.setExecutor(pool)
        fake = server

        fun json(exchange: HttpExchange, envelope: String, code: Int = 200) {
            val bytes = envelope.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        // The GET channel: the server pushes notifications and its sampling request.
        // The client opens it with a GET to the same endpoint.
        fun serveChannel(exchange: HttpExchange) {
            if (unauthorized) {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use { out ->
                    fun push(payload: String) {
                        out.write("event: message\ndata: $payload\n\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    pushChannel.set { payload -> push(payload) }
                    if (notifyAfterInitialize != null) {
                        push("""{"jsonrpc":"2.0","method":"$notifyAfterInitialize","params":$notifyParams}""")
                    }
                    if (samplingAfterInitialize) {
                        push("""{"jsonrpc":"2.0","id":900,"method":"sampling/createMessage","params":{""" +
                            """"messages":[{"role":"user","content":{"type":"text","text":"সংক্ষেপে বলো"}}],""" +
                            """"maxTokens":64}}""")
                    }
                    if (requestAfterInitialize != null) {
                        push("""{"jsonrpc":"2.0","id":901,"method":"$requestAfterInitialize","params":{}}""")
                    }
                    Thread.sleep(6000)
                }
            } catch (_: Exception) {
            }
        }

        server.createContext("/mcp") { exchange: HttpExchange ->
            if (exchange.requestMethod == "GET") {
                serveChannel(exchange)
                return@createContext
            }
            if (unauthorized) {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return@createContext
            }
            exchange.requestHeaders.getFirst("Mcp-Session-Id")?.let { sawSessionHeader.set(true) }
            exchange.requestHeaders.getFirst("Authorization")?.let { authHeader.set(it) }

            val body = BufferedReader(InputStreamReader(exchange.requestBody)).readText()

            // A reply to a server→client request is an envelope with an id and no method.
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            if (parsed != null && !parsed.has("method") && parsed.has("id")) {
                lastReply.set(parsed)
                onSamplingReply?.invoke()
                exchange.sendResponseHeaders(202, -1); exchange.close(); return@createContext
            }

            val request = parsed ?: JSONObject()
            val method = request.optString("method")
            val id = request.opt("id")

            if (method == "notifications/initialized") {
                exchange.sendResponseHeaders(202, -1); exchange.close(); return@createContext
            }

            if (method == "initialize") {
                initializeCount.incrementAndGet()
                val caps = JSONObject(capabilities)
                val result = JSONObject()
                    .put("protocolVersion", "2025-06-18")
                    .put("capabilities", caps)
                    .put("serverInfo", JSONObject().put("name", "fake").put("version", "1"))
                // The session id is handed back on initialize, as the spec says.
                exchange.responseHeaders.add("Mcp-Session-Id", "sess-42")
                val envelope = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
                val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }

            if (dropSessionOnce && !dropped.getAndSet(true)) {
                exchange.sendResponseHeaders(404, -1); exchange.close(); return@createContext
            }

            methodsSeen.add(method)
            val result = when (method) {
                "tools/list" -> JSONObject().put("tools", org.json.JSONArray(tools))
                "resources/list" -> JSONObject().put("resources", org.json.JSONArray(resources))
                "resources/templates/list" ->
                    JSONObject().put("resourceTemplates", org.json.JSONArray(resourceTemplates))
                "resources/read" -> JSONObject().put("contents", org.json.JSONArray()
                    .put(JSONObject().put("uri", "file://readme.md").put("text", textNow.get())))
                "prompts/list" -> JSONObject().put("prompts", org.json.JSONArray(prompts))
                "prompts/get" -> JSONObject().put("messages", org.json.JSONArray()
                    .put(JSONObject().put("role", "user")
                        .put("content", JSONObject().put("type", "text").put("text", promptText))))
                // The keep-alive, the subscription pair and the two optional
                // requests below are answered the way the spec describes them.
                "ping" -> JSONObject()
                "resources/subscribe" -> {
                    // Once subscribed, the contents change and the server says so.
                    if (notifyOnSubscribe && resourceTextAfterUpdate != null) {
                        textNow.set(resourceTextAfterUpdate)
                        val uri = request.optJSONObject("params")?.optString("uri").orEmpty()
                        pushChannel.get()?.invoke(
                            """{"jsonrpc":"2.0","method":"notifications/resources/updated","params":{"uri":"$uri"}}"""
                        )
                    }
                    JSONObject()
                }
                "resources/unsubscribe" -> JSONObject()
                "logging/setLevel" -> JSONObject()
                "completion/complete" -> JSONObject().put("completion", JSONObject()
                    .put("values", org.json.JSONArray(listOf("বাংলা", "বাংলাদেশ")))
                    .put("total", 2).put("hasMore", false))
                "tools/call" -> {
                    onToolsCall?.invoke(body)
                    JSONObject().put("content", org.json.JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "তৈরি হয়েছে")))
                }
                else -> JSONObject()
            }

            val envelope = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()
            if (sse) {
                val bytes = "event: message\ndata: $envelope\n\n".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.responseHeaders.add("Mcp-Session-Id", "sess-42")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.responseHeaders.add("Mcp-Session-Id", "sess-42")
                json(exchange, envelope)
            }
        }
        server.start()
        return "http://127.0.0.1:${server.address.port}"
    }
}
