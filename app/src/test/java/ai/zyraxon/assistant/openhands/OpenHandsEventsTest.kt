package ai.zyraxon.assistant.openhands

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
import java.util.concurrent.LinkedBlockingQueue
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
 * Two service behaviours are modelled exactly, because both were got wrong:
 *  - a page holds at most 100 events, and the *oldest* are returned unless a
 *    sort order is asked for, so a "newest N" read of a long log is really the
 *    start of it;
 *  - a page ends with a `next_page_id`, and only following it reaches the
 *    newest events.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenHandsEventsTest {

    private lateinit var server: MockWebServer
    private val paths = CopyOnWriteArrayList<String>()
    private val queue = LinkedBlockingQueue<MockResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        paths.clear()
        queue.clear()
        // Every request is recorded as it arrives, so the assertions can read the
        // query the client actually built — which is the whole point of these
        // tests. Answers come from the queue the test filled.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths.add(request.path ?: "")
                return queue.poll() ?: MockResponse().setBody("""{"items":[]}""")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client() = OpenHandsClient(
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        base = server.url("/api/v1").toString().trimEnd('/')
    )

    /** An agent-server conversation pointing at this test's own server. */
    private fun conv(id: String = "c1") = OhConversation(
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

    private fun page(events: List<String>, next: String? = null) = JSONObject()
        .put("items", JSONArray(events))
        .put("next_page_id", next ?: JSONObject.NULL)
        .toString()

    private fun answer(body: String) = queue.add(MockResponse().setBody(body))

    @Test
    fun `the tail asks for the events after its cursor, oldest first`() = runBlocking {
        answer(page(listOf(event("e3", "2026-01-01T00:00:03"))))

        val batch = client().eventsSince(conv(), since = "2026-01-01T00:00:00")

        val path = paths.first()
        assertTrue("the cursor must be sent: $path", path.contains("timestamp__gte=2026-01-01T00%3A00%3A00"))
        assertTrue("the read must be oldest-first: $path", path.contains("sort_order=TIMESTAMP"))
        assertTrue("the read must not ask for more than one page: $path", path.contains("limit=100"))
        assertEquals(1, batch.size)
        assertEquals("e3", batch.first().event.id)
        assertEquals("2026-01-01T00:00:03", batch.first().at)
    }

    @Test
    fun `a read longer than one page is walked, not truncated`() = runBlocking {
        // 150 events. The first page is full and the service hands back a cursor;
        // stopping there would lose the newest 50, which are the ones that matter.
        val first = (1..100).map { event("e$it", "2026-01-01T00:00:%02d".format(it % 60)) }
        val second = (101..150).map { event("e$it", "2026-01-01T00:01:%02d".format(it % 60)) }
        answer(page(first, next = "cursor-1"))
        answer(page(second))

        val batch = client().eventsSince(conv(), since = "2026-01-01T00:00:00")

        assertEquals("both pages must be read", 150, batch.size)
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
        val old = (1..100).map { action("a$it", "2026-01-01T00:00:00", "call-old-$it") }
        answer(page(old, next = "cursor-1"))
        answer(page(listOf(action("ev-new", "2026-01-01T00:02:00", "call-recent"))))

        val seen = client().seenActionIds(conv())

        assertTrue("the newest page must be reached: $paths", paths.any { it.contains("page_id=cursor-1") })
        assertTrue("the recent call must be recognised as already done", "call-recent" in seen)
    }

    @Test
    fun `the turn start is the newest recorded event`() = runBlocking {
        answer(page(listOf(event("e9", "2026-01-01T09:00:00"))))

        val at = client().latestTimestamp(conv())

        assertEquals("2026-01-01T09:00:00", at)
        val path = paths.first()
        assertTrue("the newest must be asked for first: $path", path.contains("sort_order=TIMESTAMP_DESC"))
        assertTrue("one event is enough: $path", path.contains("limit=1"))
    }

    @Test
    fun `an empty log is a blank cursor, not a failure`() = runBlocking {
        answer(page(emptyList()))

        assertEquals("", client().latestTimestamp(conv()))
    }

    @Test
    fun `a refused read degrades to nothing rather than breaking the turn`() = runBlocking {
        // The service refuses a limit above 100 with a 422. A read that fails must
        // leave the turn running, not throw into it.
        queue.add(MockResponse().setResponseCode(422).setBody("""{"detail":"limit too large"}"""))

        assertEquals(0, client().eventsSince(conv(), since = "2026-01-01T00:00:00").size)
    }

    @Test
    fun `the cursor rides on every page of the walk`() = runBlocking {
        // It must not be dropped on page two, or that page would come back as the
        // whole conversation from its beginning and the tail would re-read the lot.
        answer(page((1..100).map { event("e$it", "2026-01-01T00:00:00") }, next = "cursor-1"))
        answer(page(emptyList()))

        client().eventsSince(conv(), since = "2026-01-01T00:00:00")

        assertTrue("every page must carry the cursor: $paths", paths.all { it.contains("timestamp__gte=") })
    }
}
