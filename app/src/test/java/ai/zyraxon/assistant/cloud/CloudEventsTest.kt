// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.cloud

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The live REST tail, proved against a server that answers the way the service
 * really does.
 *
 * A phone whose socket is dropped has one job: pick the conversation back up
 * without losing a word and without showing a word twice. The reads that do it
 * are pinned here, because every way they can be wrong is invisible until it
 * matters — on a long conversation, mid-answer, on a real phone.
 *
 * The fake server answers from the query itself, the way the real one does, so
 * a wrong read gets a wrong page rather than a lucky one:
 *  - a page holds at most 100 events, and the *oldest* are returned unless a
 *    sort order is asked for — so a "newest N" read of a long log is really the
 *    start of it, which is the bug this pins;
 *  - a page ends with a `next_page_id`, and only following it reaches the
 *    newest events.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudEventsTest {

    private lateinit var server: MockWebServer
    private val paths = CopyOnWriteArrayList<String>()

    /**
     * The page the service hands back first, and what it points at next.
     *
     * Written by the test thread and read by MockWebServer's own dispatcher
     * thread, so these are volatile: without it the dispatcher can read the
     * stale default and answer an empty page, which reads as a client bug.
     */
    @Volatile private var firstPage = """{"items":[]}"""
    @Volatile private var secondPage = """{"items":[]}"""
    @Volatile private var newestPage = """{"items":[]}"""
    @Volatile private var refuse = false

    @Before
    fun setUp() {
        server = MockWebServer()
        paths.clear()
        firstPage = """{"items":[]}"""
        secondPage = """{"items":[]}"""
        newestPage = """{"items":[]}"""
        refuse = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                paths.add(path)
                if (refuse) {
                    return MockResponse().setResponseCode(422).setBody("""{"detail":"limit too large"}""")
                }
                val body = when {
                    // Every page after the first is asked for by its cursor.
                    path.contains("page_id=") -> secondPage
                    // A newest-first read: the turn's own start, and the action scan.
                    path.contains("sort_order=TIMESTAMP_DESC") -> newestPage
                    else -> firstPage
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client() = CloudClient(
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        base = server.url("/api/v1").toString().trimEnd('/')
    )

    /** An agent-server conversation pointing at this test's own server. */
    private fun conv(id: String = "c1") = CloudConversation(
        id = id,
        title = "t",
        sandboxId = "sb1",
        sandboxStatus = "RUNNING",
        executionStatus = "running",
        agentBase = server.url("/").toString().trimEnd('/'),
        sessionKey = "sk-1",
        llmModel = "m"
    )

    private fun event(id: String, at: String, text: String = "") = JSONObject()
        .put("id", id)
        .put("timestamp", at)
        .put("kind", "StreamingDeltaEvent")
        .put("content", text)
        .toString()

    /** An ActionEvent whose tool call id is what the action scan must collect. */
    private fun action(id: String, at: String, callId: String) = JSONObject()
        .put("id", id)
        .put("timestamp", at)
        .put("kind", "ActionEvent")
        .put("tool_name", "open_app")
        .put("tool_call", JSONObject().put("id", callId).put("name", "open_app"))
        .toString()

    /**
     * A page body, built from real JSON objects.
     *
     * `JSONArray(listOfStrings)` stores each element as a *string*, so
     * `optJSONObject(i)` returns null and every page reads back empty — which
     * looks exactly like a client that loses events. Parsing each event into a
     * JSONObject first is what makes the fake server honest.
     */
    private fun page(events: List<String>, next: String? = null) = JSONObject()
        .put("items", JSONArray(events.map { JSONObject(it) }))
        .put("next_page_id", next ?: JSONObject.NULL)
        .toString()

    @Test
    fun `the tail asks for the events after its cursor, oldest first`() = runBlocking {
        firstPage = page(listOf(event("e3", "2026-01-01T00:00:03", "hello")))

        val batch = client().eventsSince(conv(), since = "2026-01-01T00:00:00")

        val path = paths.first()
        assertTrue("the cursor must be sent: $path", path.contains("timestamp__gte=2026-01-01T00%3A00%3A00"))
        assertTrue("the read must be oldest-first: $path", path.contains("sort_order=TIMESTAMP"))
        assertTrue("the read must not ask for more than one page: $path", path.contains("limit=100"))
        assertEquals("one event was recorded, so one must come back: $batch", 1, batch.size)
        assertEquals("e3", batch.first().event.id)
        assertEquals("2026-01-01T00:00:03", batch.first().at)
    }

    @Test
    fun `a read longer than one page is walked, not truncated`() = runBlocking {
        // The first page is full and the service hands back a cursor; stopping
        // there would lose the newest events, which are the ones that matter.
        firstPage = page((1..100).map { event("e$it", "2026-01-01T00:00:00") }, next = "cursor-1")
        secondPage = page((101..150).map { event("e$it", "2026-01-01T00:01:00") })

        val batch = client().eventsSince(conv(), since = "2026-01-01T00:00:00")

        assertEquals("both pages must be read: $paths", 150, batch.size)
        assertTrue(
            "the second page must be asked for by cursor: $paths",
            paths.any { it.contains("page_id=cursor-1") }
        )
        // The tail is read forwards, so the newest event is last — the order the
        // turn's own consumer expects.
        assertEquals("e150", batch.last().event.id)
    }

    @Test
    fun `the action scan reaches the newest page, so a long chat does not re-run old calls`() = runBlocking {
        // The bug this pins: the scan asked once, for 100 events, with no sort
        // order — so on a long chat it got the *oldest* page and saw none of the
        // recent tool calls. Every one of them then looked new, and was run again
        // on the phone: an app opening that the user never asked for.
        // The scan reads newest-first, so its first page is the newestPage slot.
        newestPage = page((1..100).map { action("a$it", "2026-01-01T00:00:00", "call-old-$it") }, next = "cursor-1")
        secondPage = page(listOf(action("ev-new", "2026-01-01T00:02:00", "call-recent")))

        val seen = client().seenActionIds("test-key", conv())

        assertTrue("the newest page must be reached: $paths", paths.any { it.contains("page_id=cursor-1") })
        assertTrue("the recent call must be recognised as already done: $seen", "call-recent" in seen)
    }

    @Test
    fun `the turn start is the newest recorded event`() = runBlocking {
        newestPage = page(listOf(event("e9", "2026-01-01T09:00:00")))

        val at = client().latestTimestamp(conv())

        assertEquals("2026-01-01T09:00:00", at)
        val path = paths.first()
        assertTrue("the newest must be asked for first: $path", path.contains("sort_order=TIMESTAMP_DESC"))
        assertTrue("one event is enough: $path", path.contains("limit=1"))
    }

    @Test
    fun `an empty log is a blank cursor, not a failure`() = runBlocking {
        newestPage = page(emptyList())

        assertEquals("", client().latestTimestamp(conv()))
    }

    @Test
    fun `a refused read degrades to nothing rather than breaking the turn`() = runBlocking {
        refuse = true

        assertEquals(0, client().eventsSince(conv(), since = "2026-01-01T00:00:00").size)
    }

    @Test
    fun `the cursor rides on every page of the walk`() = runBlocking {
        // It must not be dropped on page two, or that page would come back as the
        // whole conversation from its beginning and the tail would re-read the lot.
        firstPage = page((1..100).map { event("e$it", "2026-01-01T00:00:00") }, next = "cursor-1")
        secondPage = page(emptyList())

        client().eventsSince(conv(), since = "2026-01-01T00:00:00")

        assertTrue("every page must carry the cursor: $paths", paths.size >= 2 && paths.all { it.contains("timestamp__gte=") })
    }
}
