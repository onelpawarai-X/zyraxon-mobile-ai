package ai.zyraxon.assistant.ai

import ai.zyraxon.assistant.model.ChatMessage
import ai.zyraxon.assistant.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user's complaint: the assistant forgets things from months ago. The recall
 * query was fine; the window was not — recalled turns were placed in front of a
 * long recent transcript and the engine's twelve-turn cut-off then removed them.
 * These tests pin the fix: a recalled memory is always inside the window that is
 * actually sent, however long the recent conversation has grown.
 */
class ContextWindowTest {

    private fun turn(id: String, text: String) = ChatMessage(
        id = id,
        role = MessageRole.USER,
        text = text,
        timestamp = "10:00 AM"
    )

    @Test
    fun `a recalled memory survives a long recent transcript`() {
        val recalled = listOf(turn("old", "তুমি বলেছিলে তোমার বিড়ালের নাম টম"))
        val recent = (1..40).map { turn("r$it", "সাম্প্রতিক কথা $it") }

        val window = ContextWindow.assemble(recalled, recent)

        assertTrue("উইন্ডো ইঞ্জিনের সীমা ছাড়িয়ে গেছে", window.size <= ContextWindow.ENGINE_LIMIT)
        assertTrue(
            "পুরনো স্মৃতি উইন্ডো থেকে কেটে পড়েছে",
            window.any { it.id == "old" }
        )
    }

    @Test
    fun `the newest turns are kept`() {
        val recalled = emptyList<ChatMessage>()
        val recent = (1..40).map { turn("r$it", "কথা $it") }

        val window = ContextWindow.assemble(recalled, recent)

        assertEquals("r40", window.last().id)
        assertEquals("r33", window.first().id)
    }

    @Test
    fun `recalled turns lead the window`() {
        val recalled = listOf(turn("old", "পুরনো কথা"))
        val recent = listOf(turn("new", "নতুন কথা"))

        val window = ContextWindow.assemble(recalled, recent)

        assertEquals("old", window.first().id)
        assertEquals("new", window.last().id)
    }

    @Test
    fun `nothing is dropped when the conversation is short`() {
        val recalled = (1..2).map { turn("old$it", "পুরনো $it") }
        val recent = (1..3).map { turn("new$it", "নতুন $it") }

        val window = ContextWindow.assemble(recalled, recent)

        assertEquals(5, window.size)
    }
}
