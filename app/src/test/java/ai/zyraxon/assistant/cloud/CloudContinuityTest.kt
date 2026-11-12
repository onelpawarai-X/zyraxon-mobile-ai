// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * What a cloud chat promises when its machine dies, proved against a real HTTP
 * server.
 *
 * A sandbox that will not come back must not cost the chat: it is recognised, it is
 * not offered again as a reuse candidate, and a fresh conversation is built whose
 * first message carries the chat's own past — so the agent meets the user mid-task
 * with its memory intact. And the message a tool result or a mid-task note rides on
 * must actually leave the phone.
 *
 * These are the client and store layers the bridge builds on, exercised directly:
 * a socket-driven turn would test the same code through a connection that only makes
 * the test slower and flakier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudContinuityTest {

    private lateinit var server: MockWebServer
    private lateinit var store: CloudStore
    private lateinit var client: CloudClient

    private val bodies = CopyOnWriteArrayList<String>()
    private val paths = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                paths += path
                request.body.readUtf8().takeIf { it.isNotBlank() }?.let { bodies += it }
                return route(path)
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = CloudStore(context)
        store.clear()
        client = CloudClient(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
            base = server.url("/api/v1").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() {
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

    private fun route(path: String): MockResponse = when {
        path.contains("ids=conv-old") -> MockResponse().setBody(conversation("conv-old", "sb-old", "PAUSED"))
        path.contains("/sandboxes/sb-old/resume") -> MockResponse().setBody("{}")
        path.contains("ids=conv-new") -> MockResponse().setBody(conversation("conv-new", "sb-new", "RUNNING"))
        path.contains("/app-conversations/search") -> MockResponse().setBody("""{"items":[]}""")
        path.endsWith("/app-conversations") -> MockResponse().setBody("""{"id":"task-1"}""")
        path.contains("start-tasks") -> MockResponse().setBody(
            """{"items":[{"id":"task-1","status":"READY","app_conversation_id":"conv-new","sandbox_id":"sb-new"}]}"""
        )
        else -> MockResponse().setBody("{}")
    }

    @Test
    fun `a sandbox that will not come back is reported as not running`() = runBlocking {
        // This is what the bridge tests to decide a chat needs a new machine: a
        // resume that was attempted and did not reach RUNNING.
        val conv = client.ensureRunning("k", "conv-old")
        assertEquals("PAUSED", conv.sandboxStatus)
        assertTrue(
            "the resume must actually have been sent",
            paths.any { it.contains("/sandboxes/sb-old/resume") }
        )
    }

    @Test
    fun `an unbound chat leaves no sandbox for another chat to inherit`() {
        store.bind("chat-1", "conv-old", "sb-old")
        assertEquals("conv-old", store.conversationFor("chat-1"))

        store.unbind("chat-1")

        // Nothing survives the unbind: no binding, and — the part that used to be a
        // pool — no sandbox id anywhere for a later chat to pick up. A remembered
        // id may have been reclaimed, and handing it over is what made a start fail.
        assertNull(store.conversationFor("chat-1"))
        assertTrue("no binding may survive: ${store.bindings()}", store.bindings().isEmpty())

        // A later chat therefore starts clean rather than inheriting sb-old.
        store.bind("chat-2", "conv-new", "sb-new")
        assertEquals("conv-new", store.conversationFor("chat-2"))
    }

    @Test
    fun `a fresh conversation is built when nothing is there to reuse`() = runBlocking {
        // The path the bridge takes after a dead sandbox: no reuse candidate at all.
        val conv = client.startConversation(
            key = "k",
            title = "নতুন চ্যাট",
            suffix = "s",
            reuseSandboxId = null
        )
        assertEquals("conv-new", conv.id)
        val created = bodies.firstOrNull { it.contains("নতুন চ্যাট") }
        assertNotNull("the conversation must be created", created)
        assertTrue(
            "no sandbox may be reused: $created",
            !created!!.contains("sb-old")
        )
    }

    @Test
    fun `the message a turn sends actually leaves the phone`() = runBlocking {
        // Both a tool result and a mid-task note travel this way; the words must
        // reach the cloud rather than stop at the queue.
        client.sendMessage("k", "conv-new", "মাঝপথের কথা")
        assertTrue(
            "the message must be posted",
            bodies.any { it.contains("মাঝপথের কথা") }
        )
    }
}
