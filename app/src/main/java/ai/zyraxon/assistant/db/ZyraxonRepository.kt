package ai.zyraxon.assistant.db

import ai.zyraxon.assistant.model.AgentMode
import ai.zyraxon.assistant.model.ChatMessage
import ai.zyraxon.assistant.model.Conversation
import ai.zyraxon.assistant.model.MemoryItem
import ai.zyraxon.assistant.model.MessageRole
import ai.zyraxon.assistant.model.TaskItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ZyraxonRepository(private val dao: ZyraxonDao) {

    fun messages(conversationId: String): Flow<List<ChatMessage>> =
        dao.getMessages(conversationId).map { list -> list.map { it.toModel() } }

    private fun ChatMessageEntity.toModel() = ChatMessage(
        id = id,
        role = when (role) {
            "USER" -> MessageRole.USER
            "SYSTEM" -> MessageRole.SYSTEM
            else -> MessageRole.ASSISTANT
        },
        text = text,
        timestamp = timestamp,
        isStreaming = false,
        modelName = modelName,
        createdAt = createdAt,
        durationMs = durationMs,
        toolCalls = MessageMeta.toolCalls(meta),
        media = MessageMeta.media(meta)
    )

    // ---- conversations -------------------------------------------------
    val chats: Flow<List<Conversation>> = dao.getChats().map { list ->
        list.map { Conversation(it.id, it.title, AgentMode.parse(it.mode), it.updatedAt) }
    }

    suspend fun conversation(id: String): Conversation? = dao.getConversation(id)?.let {
        Conversation(it.id, it.title, AgentMode.parse(it.mode), it.updatedAt)
    }

    suspend fun ensureConversation(id: String, title: String, mode: AgentMode, kind: String = "chat", parentId: String = "") {
        if (dao.getConversation(id) == null) {
            dao.insertConversation(ConversationEntity(id = id, title = title, mode = mode.name, kind = kind, parentId = parentId))
        }
    }

    suspend fun renameConversation(id: String, title: String) = dao.renameConversation(id, title.take(60), System.currentTimeMillis())
    suspend fun setConversationMode(id: String, mode: AgentMode) = dao.setConversationMode(id, mode.name, System.currentTimeMillis())
    suspend fun touchConversation(id: String) = dao.touchConversation(id, System.currentTimeMillis())

    suspend fun deleteConversation(id: String) {
        dao.clearMessages(id)
        dao.deleteTasksOf(id)
        dao.deleteConversation(id)
    }

    val allMemories: Flow<List<MemoryItem>> = dao.getAllMemories().map { list ->
        list.map { MemoryItem(key = it.key, value = it.value) }
    }

    fun tasks(conversationId: String): Flow<List<TaskItem>> = dao.getTasks(conversationId).map { list ->
        list.map {
            TaskItem(
                id = it.id,
                text = it.text,
                isCompleted = it.isCompleted,
                createdAt = it.createdAt
            )
        }
    }

    suspend fun openTasks(conversationId: String): List<String> =
        dao.getOpenTasks(conversationId).map { it.text }

    suspend fun saveMessage(msg: ChatMessage, conversationId: String = "main") {
        val entity = ChatMessageEntity(
            id = msg.id,
            role = msg.role.name,
            text = msg.text,
            timestamp = msg.timestamp,
            modelName = msg.modelName,
            createdAt = msg.createdAt,
            conversationId = conversationId,
            durationMs = msg.durationMs,
            meta = MessageMeta.encode(msg.toolCalls, msg.media)
        )
        dao.insertMessage(entity)
        dao.touchConversation(conversationId, System.currentTimeMillis())
    }

    suspend fun clearMessages(conversationId: String = "main") {
        dao.clearMessages(conversationId)
    }

    suspend fun saveMemory(key: String, value: String) {
        val entity = MemoryEntity(
            key = key.trim(),
            value = value.trim(),
            updatedAt = System.currentTimeMillis()
        )
        dao.insertMemory(entity)
    }

    suspend fun deleteMemory(key: String) {
        dao.deleteMemory(key.trim())
    }

    suspend fun clearMemories() {
        dao.clearAllMemories()
    }

    suspend fun addTask(task: TaskItem, conversationId: String = "main") {
        val entity = TaskEntity(
            id = task.id,
            text = task.text,
            isCompleted = task.isCompleted,
            createdAt = task.createdAt,
            conversationId = conversationId
        )
        dao.insertTask(entity)
    }

    suspend fun toggleTask(id: String, completed: Boolean) {
        dao.updateTaskStatus(id, completed)
    }

    suspend fun deleteTask(id: String) {
        dao.deleteTask(id)
    }

    /** Replace the whole board — what `todo_write` does every round. */
    suspend fun replaceTasks(tasks: List<TaskItem>, conversationId: String = "main") {
        dao.deleteTasksFor(conversationId)
        for (t in tasks) addTask(t, conversationId)
    }

    suspend fun getMemoriesPromptContext(): String {
        val list = dao.getMemoriesList()
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        for (m in list) {
            sb.append("• ").append(m.key).append(": ").append(m.value).append("\n")
        }
        return sb.toString().trim()
    }

    /**
     * The whole memory block for one turn, in a single database read.
     *
     * The durable facts are always included; the ones whose key or value shares a
     * subject with what the user just said are folded in on top. Both used to be
     * fetched with their own `getMemoriesList()` call, so every turn paid for two
     * reads of the same table — part of why a simple reply felt slow.
     */
    suspend fun memoryContextFor(topic: String): String {
        val list = dao.getMemoriesList()
        if (list.isEmpty()) return ""
        val base = list.joinToString("\n") { "• ${it.key}: ${it.value}" }
        val words = topic.lowercase()
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .distinct()
        if (words.isEmpty()) return base
        val recalled = list.filter { m ->
            val hay = (m.key + " " + m.value).lowercase()
            words.any { hay.contains(it) }
        }.take(8).joinToString("\n") { "• ${it.key}: ${it.value}" }
        return if (recalled.isBlank()) base else base + "\n" + recalled
    }

    /**
     * Everything worth remembering from a long time ago, pulled out of the full
     * transcript for the words the user just used.
     *
     * The short-term window is the last few turns; this is the other half — a
     * conversation from weeks ago comes back when the same subject comes up,
     * which is what "মাসের পর মাস আগের কথা মনে থাকতে হবে" asks for. The whole
     * history stays in SQLite; only the matching turns are offered to the model.
     */
    suspend fun recallRelevant(topic: String, limit: Int = 12): List<ChatMessage> {
        val words = topic.lowercase()
            // \p{M} keeps the Bengali combining marks, so "ভালোবাসি" stays one
            // word instead of breaking into letters and matching nothing.
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            // Three characters, not four: "গান", "নাম", "কোড", "ঘর" are real
            // subjects and were being dropped, which is why an old conversation
            // about them never came back. Short function words are excluded by a
            // stop list instead of by length alone.
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .distinct()
            .take(4)
        if (words.isEmpty()) return emptyList()
        val a = words.getOrElse(0) { "" }
        val b = words.getOrElse(1) { a }
        val c = words.getOrElse(2) { a }
        val d = words.getOrElse(3) { a }
        return dao.searchMessages(a, b, c, d, limit).asReversed().map { entity ->
            ChatMessage(
                id = entity.id,
                role = when (entity.role) {
                    "USER" -> MessageRole.USER
                    "SYSTEM" -> MessageRole.SYSTEM
                    else -> MessageRole.ASSISTANT
                },
                text = entity.text,
                timestamp = entity.timestamp,
                modelName = entity.modelName
            )
        }
    }

    /**
     * Facts written with `memory_write`, matched against the words the user just
     * used. The memories list is small, so it is filtered here rather than in SQL:
     * a durable fact that shares a subject with the current turn is pulled in even
     * when its key does not literally contain the word.
     */
    suspend fun recallMemories(topic: String, limit: Int = 8): String {
        val list = dao.getMemoriesList()
        if (list.isEmpty()) return ""
        val words = topic.lowercase()
            .split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .distinct()
        if (words.isEmpty()) return ""
        val hits = list.filter { m ->
            val hay = (m.key + " " + m.value).lowercase()
            words.any { hay.contains(it) }
        }.take(limit)
        if (hits.isEmpty()) return ""
        return hits.joinToString("\n") { "• ${it.key}: ${it.value}" }
    }

    companion object {
        /**
         * Words that appear in almost every sentence and would make recall match
         * the whole transcript. Kept short: this is a precision filter, not NLP.
         */
        private val STOP_WORDS = setOf(
            "আমার", "আমি", "তুমি", "তোমার", "করে", "করো", "করো", "করা", "হবে", "হলো",
            "একটা", "একটি", "কিছু", "কেন", "কেমন", "কোথায়", "কখন", "কত", "এই", "ওই",
            "আর", "না", "নেই", "হ্যাঁ", "ঠিক", "আছে", "ছিল", "the", "and", "you", "for",
            "with", "that", "this", "what", "how", "can", "please", "make", "give"
        )
    }
}
