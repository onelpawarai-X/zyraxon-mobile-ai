package ai.zyraxon.assistant.db

import ai.zyraxon.assistant.model.ChatMessage
import ai.zyraxon.assistant.model.MemoryItem
import ai.zyraxon.assistant.model.MessageRole
import ai.zyraxon.assistant.model.TaskItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ZyraxonRepository(private val dao: ZyraxonDao) {

    val allMessages: Flow<List<ChatMessage>> = dao.getAllMessages().map { list ->
        list.map { entity ->
            ChatMessage(
                id = entity.id,
                role = when (entity.role) {
                    "USER" -> MessageRole.USER
                    "SYSTEM" -> MessageRole.SYSTEM
                    else -> MessageRole.ASSISTANT
                },
                text = entity.text,
                timestamp = entity.timestamp,
                isStreaming = false,
                modelName = entity.modelName
            )
        }
    }

    val allMemories: Flow<List<MemoryItem>> = dao.getAllMemories().map { list ->
        list.map { MemoryItem(key = it.key, value = it.value) }
    }

    val allTasks: Flow<List<TaskItem>> = dao.getAllTasks().map { list ->
        list.map {
            TaskItem(
                id = it.id,
                text = it.text,
                isCompleted = it.isCompleted,
                createdAt = it.createdAt
            )
        }
    }

    suspend fun saveMessage(msg: ChatMessage) {
        val entity = ChatMessageEntity(
            id = msg.id,
            role = msg.role.name,
            text = msg.text,
            timestamp = msg.timestamp,
            modelName = msg.modelName
        )
        dao.insertMessage(entity)
    }

    suspend fun clearMessages() {
        dao.clearAllMessages()
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

    suspend fun addTask(task: TaskItem) {
        val entity = TaskEntity(
            id = task.id,
            text = task.text,
            isCompleted = task.isCompleted,
            createdAt = task.createdAt
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
    suspend fun replaceTasks(tasks: List<TaskItem>) {
        dao.deleteAllTasks()
        for (t in tasks) addTask(t)
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
}
