package ai.zyraxon.assistant.openhands

import ai.zyraxon.assistant.model.AgentMode
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * What a cloud chat promises when its machine dies.
 *
 * A sandbox that is really gone must not cost the chat: a fresh conversation is
 * built and the words already spoken ride into it, so the agent meets the user
 * mid-task with its memory intact. The dead sandbox must not be offered again as
 * a reuse candidate either, or every later start pays a failed attempt first.
 *
 * The server answers by route rather than by position, and its event socket really
 * upgrades and really pushes frames, because the end of a turn is driven by that
 * socket and a stubbed one would prove nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenHandsContinuityTest {

    private lateinit var server: MockWebServer
    private lateinit var store: OpenHandsStore
    private lateinit var bridge: OpenHandsBridge

    private val bodies = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                // A websocket upgrade is not an ordinary request: its body is the
                // raw socket, and reading it here throws and kills the connection.
                if (!path.contains("/sockets/events/")) {
                    runCatching { request.body.readUtf8() }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { bodies += it }
                }
                return route(path)
            }
        }
        server.start()

        val context = ApplicationProvider.getApplicationContext<Context>()
        store = OpenHandsStore(context)
        store.clear()
        val client = OpenHandsClient(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                // No read timeout: a websocket carries frames only when something
                // happens, so a short one kills the very socket this test needs.
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build(),
            base = server.url("/api/v1").toString().trimEnd('/')
        )
        bridge = OpenHandsBridge(client, store, online = { true })
    }

    @After
    fun tearDown() {
        // A websocket the turn left open makes the server's own shutdown throw; the
        // socket is torn down with the test, so the error is not worth reporting.
        runCatching { server.shutdown() }
    }

    private fun conversation(id: String, sandbox: String, status: String) = JSONObject()
        .put(
            "items",
            JSONArray().put(
                JSONObject()
                    .put("id", id)
                    .put("title", "t")
                    .put("sandbox_id", sandbox)
                    .put("sandbox_status", status)
                    .put("execution_status", "idle")
                    .put("session_api_key", "sk-1")
                    .put("conversation_url", "${server.url("/")}api/conversations/$id")
            )
        ).toString()

    /**
     * The conversation's live socket.
     *
     * The first state frame ends the replay, exactly as the real cloud does, and a
     * later one ends the turn. That is the moment the bridge must look for words
     * the user typed while it worked.
     */
    private fun eventSocket() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(
                """{"kind":"ConversationStateUpdateEvent","key":"execution_status","value":"idle"}"""
            )
            Thread {
                Thread.sleep(1500)
                webSocket.send(
                    """{"kind":"ConversationStateUpdateEvent","key":"execution_status","value":"finished"}"""
                )
            }.start()
        }
    })

    private fun route(path: String): MockResponse = when {
        path.contains("/sockets/events/") -> eventSocket()
        path.contains("ids=conv-old") -> MockResponse().setBody(conversation("conv-old", "sb-old", "PAUSED"))
        path.contains("/sandboxes/sb-old/resume") -> MockResponse().setBody("{}")
        path.contains("ids=conv-new") -> MockResponse().setBody(conversation("conv-new", "sb-new", "RUNNING"))
        path.contains("ids=conv-live") -> MockResponse().setBody(conversation("conv-live", "sb-live", "RUNNING"))
        path.contains("/app-conversations/search") -> MockResponse().setBody("""{"items":[]}""")
        path.endsWith("/app-conversations") -> MockResponse().setBody("""{"id":"task-1"}""")
        path.contains("start-tasks") -> MockResponse().setBody(
            """{"items":[{"id":"task-1","status":"READY","app_conversation_id":"conv-new","sandbox_id":"sb-new"}]}"""
        )
        else -> MockResponse().setBody("{}")
    }

    /** Runs a turn until [done] holds, then stops it. The socket never ends here. */
    private suspend fun turnUntil(
        timeoutMs: Long = 30_000,
        turn: () -> Job,
        done: () -> Boolean
    ) {
        val job = turn()
        try {
            withTimeout(timeoutMs) { while (!done()) delay(50) }
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `a sandbox that will not come back yields a fresh conversation carrying the chat's past`() =
        runBlocking {
            store.bind("chat-1", "conv-old", "sb-old")
            var seedAsked = false

            turnUntil(
                turn = {
                    launch {
                        bridge.runTurn(
                            apiKey = "k",
                            chatId = "chat-1",
                            prompt = "নতুন কাজ",
                            mode = AgentMode.AGENT,
                            systemSuffix = "s",
                            existing = {
                                OhConversation(
                                    id = "conv-old", title = "t", sandboxId = "sb-old",
                                    sandboxStatus = "PAUSED", executionStatus = "idle",
                                    agentBase = server.url("/").toString().trimEnd('/'),
                                    sessionKey = "sk-1", llmModel = "m"
                                )
                            },
                            historySeed = {
                                seedAsked = true
                                "ব্যবহারকারী: আগে যা বলেছিলাম\n"
                            }
                        ).collect { }
                    }
                },
                // The prompt's own words are not enough to wait for: they are also
                // the title of the conversation being created. Only the seed proves
                // the message that starts the new conversation has gone out.
                done = { bodies.any { it.contains("আগে যা বলেছিলাম") } }
            )

            assertTrue("a fresh conversation must be built", store.conversationFor("chat-1") == "conv-new")
            assertTrue("the chat's past must be seeded into the new conversation", seedAsked)
            val started = bodies.first { it.contains("আগে যা বলেছিলাম") }
            assertTrue("the seed must ride with the prompt: $started", started.contains("নতুন কাজ"))
            assertTrue("the dead sandbox must be forgotten", store.takeWarmSandbox() != "sb-old")
        }

    @Test
    fun `words typed while the agent works are sent on rather than dropped`() = runBlocking {
        store.bind("chat-1", "conv-live", "sb-live")
        val queued = ArrayDeque(listOf("মাঝপথের কথা"))

        turnUntil(
            turn = {
                launch {
                    bridge.runTurn(
                        apiKey = "k",
                        chatId = "chat-1",
                        prompt = "শুরু করো",
                        mode = AgentMode.AGENT,
                        systemSuffix = "s",
                        existing = {
                            OhConversation(
                                id = "conv-live", title = "t", sandboxId = "sb-live",
                                sandboxStatus = "RUNNING", executionStatus = "idle",
                                agentBase = server.url("/").toString().trimEnd('/'),
                                sessionKey = "sk-1", llmModel = "m"
                            )
                        },
                        drainInjections = {
                            val out = queued.toList()
                            queued.clear()
                            out
                        }
                    ).collect { }
                }
            },
            done = { bodies.any { it.contains("মাঝপথের কথা") } }
        )

        assertTrue(
            "the words typed mid-turn must reach the cloud",
            bodies.any { it.contains("মাঝপথের কথা") }
        )
    }
}
