package ai.zyraxon.assistant.ai

import ai.zyraxon.assistant.model.ChatMessage

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
     * The recalled turns first, then the newest turns, trimmed so the whole thing
     * still fits the engine's window — which means nothing here is thrown away.
     */
    fun assemble(recalled: List<ChatMessage>, recent: List<ChatMessage>): List<ChatMessage> {
        val head = recalled.takeLast(RECALL_LIMIT)
        val tail = recent.takeLast(RECENT_LIMIT)
        return (head + tail).takeLast(ENGINE_LIMIT)
    }
}
