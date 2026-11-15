// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.cloud

import androidx.test.core.app.ApplicationProvider
import android.content.Context
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
import org.junit.Assert.assertFalse
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
 * The events a cloud chat produced while the phone was away, and getting them back.
 *
 * This is the failure the live socket cannot cover: the socket only exists while the
 * app does, and a cloud turn does not stop when the phone does. The read cursor used
 * to live in a local `var` inside a turn, so closing the app — or losing the network
 * with the app in the background — threw it away and every event produced afterwards
 * was never asked for again.
 *
 * Proved against a real HTTP server, at the client and store layers the bridge is
 * built on, because the behaviour under test *is* those two layers: a cursor that
 * survives the process, a read that resumes from it, and a status that says whether
 * the turn is still going.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudCatchUpTest {

    private lateinit var server: MockWebServer
    private lateinit var store: CloudStore
    private lateinit var client: CloudClient
    private lateinit var bridge: CloudBridge

    private val paths = CopyOnWriteArrayList<String>()

    /** One recorded event, as the service returns it in a search page. */
    private fun event(id: String, kind: String, at: String, extra: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().put("id", id).put("kind", kind).put("timestamp", at).apply(extra)

    private fun page(vararg items: JSONObject): String =
        JSONObject().put("items", JSONArray(items.toList())).toString()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = CloudStore(context)
        store.clear()
        client = CloudClient(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build(),
            base = server.url("/api/v1").toString().trimEnd('/')
        )
        bridge = CloudBridge(client, store) { true }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun conversation(id: String, status: String) = JSONObject()
        .put(
            "items",
            JSONArray().put(
                JSONObject()
                    .put("id", id)
                    .put("title", "t")
                    .put("sandbox_id", "sb-1")
                    .put("sandbox_status", "RUNNING")
                    .put("execution_status", status)
                    .put("session_api_key", "sk-1")
                    .put("conversation_url", "${server.url("/")}api/conversations/$id")
            )
        ).toString()

    /** Serves the account read, then each search page in order. */
    private fun serve(vararg pages: String) {
        server.dispatcher = object : Dispatcher() {
            private var search = 0
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                paths.add(path)
                return when {
                    path.contains("/events/search") -> {
                        val body = if (search < pages.size) pages[search] else """{"items":[]}"""
                        search++
                        MockResponse().setBody(body)
                    }
                    path.contains("app-conversations") -> MockResponse().setBody(conversation("conv-1", "running"))
                    else -> MockResponse().setBody("{}")
                }
            }
        }
    }

    @Test
    fun `the cursor outlives the process, so a relaunch resumes where the phone left off`() {
        // Written down, then read back by a *new* store — which is exactly what a
        // relaunch is, since the old process and its memory are gone.
        store.setCursor("c1", "2026-11-15T10:00:00Z")
        val afterRelaunch = CloudStore(ApplicationProvider.getApplicationContext<Context>())
        assertEquals("2026-11-15T10:00:00Z", afterRelaunch.cursorFor("c1"))
    }

    @Test
    fun `a cursor of blank is treated as absent rather than a position`() {
        store.setCursor("c1", "   ")
        assertNull(store.cursorFor("c1"))
    }

    @Test
    fun `dropping the binding drops the cursor with it`() {
        // A cursor is a position in one conversation's log. A fresh conversation is
        // built precisely because the old one is gone, and its log starts empty — a
        // cursor kept from the dead one would ask the new log for events after a
        // timestamp it has never reached, silently skipping the first messages.
        store.bind("c1", "conv-1", "sb-1")
        store.setCursor("c1", "2026-11-15T10:00:00Z")
        store.setRunning("c1", true)
        store.unbind("c1")
        assertNull(store.cursorFor("c1"))
        assertFalse(store.runningFor("c1"))
    }

    @Test
    fun `the running marker outlives the process`() {
        // A turn does not stop because the phone went away, so this is what tells a
        // relaunched chat to keep showing it as working.
        store.bind("c1", "conv-1", "sb-1")
        store.setRunning("c1", true)
        assertTrue(CloudStore(ApplicationProvider.getApplicationContext<Context>()).runningFor("c1"))
    }

    @Test
    fun `catch-up reads the events after the cursor, oldest first`() = runBlocking {
        store.bind("c1", "conv-1", "sb-1")
        store.setCursor("c1", "2026-11-15T10:00:00Z")
        serve(
            page(
                event("e1", "MessageEvent", "2026-11-15T10:00:00Z"),
                event("e2", "StreamingDeltaEvent", "2026-11-15T10:01:00Z") { put("content", "হ্যালো") },
                event("e3", "StreamingDeltaEvent", "2026-11-15T10:02:00Z") { put("content", " বিশ্ব") }
            )
        )
        val catchUp = bridge.catchUp("k", "c1")
        assertNotNull(catchUp)
        // The cursor event itself is not news — it is the last thing already shown.
        assertEquals(2, catchUp!!.events.size)
        assertEquals("হ্যালো", (catchUp.events[0] as CloudEvent.Delta).text)
        assertEquals(" বিশ্ব", (catchUp.events[1] as CloudEvent.Delta).text)
        // And the cursor has moved to the newest event read.
        assertEquals("2026-11-15T10:02:00Z", catchUp.cursor)
        assertEquals("2026-11-15T10:02:00Z", store.cursorFor("c1"))
    }

    @Test
    fun `catch-up asks the service for events at or after the cursor`() = runBlocking {
        store.bind("c1", "conv-1", "sb-1")
        store.setCursor("c1", "2026-11-15T10:00:00Z")
        serve(page())
        bridge.catchUp("k", "c1")
        val search = paths.firstOrNull { it.contains("/events/search") }
        assertNotNull("the catch-up must actually read the log", search)
        assertTrue(
            "the read must be bounded by the cursor, not the whole log: $search",
            search!!.contains("timestamp__gte=")
        )
        assertTrue("the read must be oldest-first", search.contains("sort_order=TIMESTAMP"))
    }

    @Test
    fun `a turn still running is reported as running`() = runBlocking {
        store.bind("c1", "conv-1", "sb-1")
        store.setCursor("c1", "2026-11-15T10:00:00Z")
        serve(
            page(
                event("e1", "MessageEvent", "2026-11-15T10:00:00Z"),
                event("e2", "ConversationStateUpdateEvent", "2026-11-15T10:01:00Z") {
                    put("key", "execution_status").put("value", "running")
                }
            )
        )
        val catchUp = bridge.catchUp("k", "c1")
        assertTrue("the cloud is still working, and the phone must know", catchUp!!.running)
    }

    @Test
    fun `a turn that finished is reported as finished`() = runBlocking {
        store.bind("c1", "conv-1", "sb-1")
        store.setCursor("c1", "2026-11-15T10:00:00Z")
        serve(
            page(
                event("e1", "ConversationStateUpdateEvent", "2026-11-15T10:01:00Z") {
                    put("key", "execution_status").put("value", "running")
                },
                event("e2", "ConversationStateUpdateEvent", "2026-11-15T10:02:00Z") {
                    put("key", "execution_status").put("value", "finished")
                }
            )
        )
        val catchUp = bridge.catchUp("k", "c1")
        assertFalse("a finished turn must not be shown as running", catchUp!!.running)
    }

    @Test
    fun `a chat that was never read here is not caught up from the whole log`() = runBlocking {
        // No cursor means the transcript was never pulled on this phone. The history
        // import reads it whole; reading it here as well would show it twice.
        store.bind("c1", "conv-1", "sb-1")
        serve(page(event("e1", "MessageEvent", "2026-11-15T10:00:00Z")))
        assertNull(bridge.catchUp("k", "c1"))
        assertTrue("nothing may be read without a cursor", paths.none { it.contains("/events/search") })
    }

    @Test
    fun `an unbound chat has nothing to catch up`() = runBlocking {
        assertNull(bridge.catchUp("k", "never-bound"))
    }

    @Test
    fun `offline is not treated as a finished turn`() = runBlocking {
        // The marker is what keeps a still-running turn visible, and offline is not
        // evidence the cloud stopped. A read that cannot happen must not clear it.
        store.bind("c1", "conv-1", "sb-1")
        store.setCursor("c1", "2026-11-15T10:00:00Z")
        store.setRunning("c1", true)
        val offline = CloudBridge(client, store) { false }
        assertNull(offline.catchUp("k", "c1"))
        assertTrue("the turn must still be shown as running", store.runningFor("c1"))
    }

    @Test
    fun `catch-up walks past one page rather than stopping at it`() = runBlocking {
        store.bind("c1", "conv-1", "sb-1")
        store.setCursor("c1", "2026-11-15T10:00:00Z")
        val first = (0 until CloudClient.EVENTS_PAGE).map {
            event("p1-$it", "StreamingDeltaEvent", "2026-11-15T10:0${it % 9}:00Z") { put("content", "a") }
        }
        serve(
            JSONObject().put("items", JSONArray(first.toList())).put("next_page_id", "p2").toString(),
            page(event("last", "StreamingDeltaEvent", "2026-11-15T11:00:00Z") { put("content", "শেষ") })
        )
        val catchUp = bridge.catchUp("k", "c1")
        assertNotNull(catchUp)
        assertEquals(
            "the second page must be walked, not dropped",
            "শেষ",
            (catchUp!!.events.last() as CloudEvent.Delta).text
        )
        assertEquals("2026-11-15T11:00:00Z", catchUp.cursor)
    }

    @Test
    fun `the later of two cursors is the one used`() {
        // The resume point is the later of the last cursor this chat handled and the
        // turn boundary; taking the earlier would re-read events already on screen.
        assertEquals("2026-11-15T11:00:00Z", laterOf("2026-11-15T10:00:00Z", "2026-11-15T11:00:00Z"))
        assertEquals("2026-11-15T11:00:00Z", laterOf("2026-11-15T11:00:00Z", "2026-11-15T10:00:00Z"))
        assertEquals("2026-11-15T10:00:00Z", laterOf("", "2026-11-15T10:00:00Z"))
        assertEquals("2026-11-15T10:00:00Z", laterOf("2026-11-15T10:00:00Z", ""))
        assertEquals("", laterOf("", ""))
    }

    /**
     * The whole scenario, in the order it really happens.
     *
     * A conversation is running. The phone loses its network, or the app is closed,
     * or the process is killed — all three are the same thing here: no reader. The
     * cloud keeps working and recording. The phone comes back and must learn
     * everything it missed, and must keep showing the turn as running while it still
     * is.
     *
     * Each step is checked against the previous one, because the failure this guards
     * against is not any single read being wrong — it is state that does not survive
     * the gap between them.
     */
    @Test
    fun `a turn that ran while the phone was away is recovered whole and still shown running`() = runBlocking {
        // 1. A conversation the phone has been reading, and a turn running on it.
        store.bind("chat-1", "conv-1", "sb-1")
        store.setCursor("chat-1", "2026-11-15T10:00:00Z")
        store.setRunning("chat-1", true)

        // 2. The phone goes away. The cloud records what it does in that time.
        serve(
            page(
                event("e1", "MessageEvent", "2026-11-15T10:00:00Z"),
                event("e2", "ConversationStateUpdateEvent", "2026-11-15T10:00:30Z") {
                    put("key", "execution_status").put("value", "running")
                },
                event("e3", "ActionEvent", "2026-11-15T10:01:00Z") {
                    put("tool_name", "terminal")
                    put("tool_call", JSONObject().put("id", "call-1").put("name", "terminal"))
                },
                event("e4", "ObservationEvent", "2026-11-15T10:01:30Z") {
                    put("tool_name", "terminal").put("observation", JSONObject().put("content", "done"))
                },
                event("e5", "StreamingDeltaEvent", "2026-11-15T10:02:00Z") { put("content", "কাজ ") },
                event("e6", "StreamingDeltaEvent", "2026-11-15T10:02:10Z") { put("content", "শেষ।") }
            )
        )

        // 3. The phone comes back. This is the one read that repairs the gap.
        val catchUp = bridge.catchUp("k", "chat-1")
        assertNotNull("the missed events must be read back", catchUp)

        // Everything after the cursor is there, in order, and the event the cursor
        // already named is not repeated.
        assertEquals(listOf("e2", "e3", "e4", "e5", "e6"), catchUp!!.events.map { it.id })
        assertEquals("কাজ ", (catchUp.events[3] as CloudEvent.Delta).text)
        assertEquals("শেষ।", (catchUp.events[4] as CloudEvent.Delta).text)

        // The action the cloud ran while the phone was away is reported as a tool
        // call, not re-run — replaying a user's tool is worse than a stale line.
        assertTrue(catchUp.events[1] is CloudEvent.Action)
        assertTrue(catchUp.events[2] is CloudEvent.Observation)

        // 4. The cursor has advanced past everything just read, so a second read is
        // not a repeat of the first.
        assertEquals("2026-11-15T10:02:10Z", store.cursorFor("chat-1"))

        // 5. And the turn is still running on the other side, which the phone must
        // keep showing — this is the difference between a chat that looks finished
        // and one that is still working.
        assertTrue("a turn still running must be reported as running", catchUp.running)
    }

    @Test
    fun `a second read after a catch-up finds nothing, so nothing is shown twice`() = runBlocking {
        store.bind("chat-1", "conv-1", "sb-1")
        store.setCursor("chat-1", "2026-11-15T10:00:00Z")
        serve(
            page(
                event("e1", "StreamingDeltaEvent", "2026-11-15T10:01:00Z") { put("content", "ক") },
                event("e2", "StreamingDeltaEvent", "2026-11-15T10:02:00Z") { put("content", "খ") }
            )
        )
        val first = bridge.catchUp("k", "chat-1")
        assertEquals(2, first!!.events.size)

        // The same server is read again from the cursor the first read left behind.
        val second = bridge.catchUp("k", "chat-1")
        assertEquals(
            "the events just shown must not come back a second time",
            0,
            second!!.events.size
        )
    }
}
