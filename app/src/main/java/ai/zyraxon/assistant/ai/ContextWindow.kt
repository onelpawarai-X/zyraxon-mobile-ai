package ai.zyraxon.assistant.ai

import ai.zyraxon.assistant.model.ChatMessage
import ai.zyraxon.assistant.model.MessageRole

/**
 * How much conversation the model is shown, and in what order.
 *
 * The engine keeps only the last [ENGINE_LIMIT] turns. That cut-off is what made
 * long-term memory useless: recalled turns were placed in front of the *entire*
 * recent transcript, so as soon as the user had more than a dozen recent turns
 * the engine trimmed the recalled ones away and the model never saw the memory
 * the database had just found. Budgeting both halves to fit inside the limit is
 * the fix.
 */
object ContextWindow {

    /** The engine's own window, in turns. */
    const val ENGINE_LIMIT = 12

    /** How many recalled old turns may lead the window. */
    const val RECALL_LIMIT = 4

    /** How many of the newest turns may follow them. */
    const val RECENT_LIMIT = 8

    /**
     * How many old turns are pulled from the database and summarised into the
     * system prompt. This is the long-term memory: the transcript itself is kept
     * forever, but what the model sees is a compact digest of the turns that match
     * what the user is talking about now, however long ago they happened. A dozen
     * turns of chat is not "months of memory"; this is.
     */
    const val RECALL_LIMIT_LONG = 24

    /**
     * The recalled turns first, then the newest turns, trimmed so the whole thing
     * still fits the engine's window — which means nothing here is thrown away.
     */
    fun assemble(recalled: List<ChatMessage>, recent: List<ChatMessage>): List<ChatMessage> {
        val head = recalled.takeLast(RECALL_LIMIT)
        val tail = recent.takeLast(RECENT_LIMIT)
        return (head + tail).takeLast(ENGINE_LIMIT)
    }

    /**
     * A compact, dated digest of older turns for the system prompt, so a detail
     * from months ago is in front of the model even when it falls outside the
     * short window. Oldest first so the story reads in order, and trimmed so a
     * very chatty topic cannot crowd out the prompt.
     */
    fun digest(recalled: List<ChatMessage>, maxChars: Int = 2000): String {
        if (recalled.isEmpty()) return ""
        val lines = recalled.mapNotNull { msg ->
            val text = msg.text.trim().replace(Regex("\\s+"), " ")
            if (text.isBlank()) return@mapNotNull null
            val who = if (msg.role == MessageRole.USER) "ব্যবহারকারী" else "তুমি"
            val when_ = msg.timestamp.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
            "- $who$when_: ${text.take(240)}"
        }
        if (lines.isEmpty()) return ""
        val joined = lines.joinToString("\n")
        return if (joined.length <= maxChars) joined else joined.takeLast(maxChars)
    }
}
