package ai.zyraxon.assistant.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ZyraxonDao {
    // Chat Messages
    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun getMessages(conversationId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun clearMessages(conversationId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()

    // Conversations
    @Query("SELECT * FROM conversations WHERE kind = 'chat' ORDER BY updatedAt DESC")
    fun getChats(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE kind = 'sub' ORDER BY createdAt DESC")
    fun getSubAgents(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(c: ConversationEntity)

    /**
     * Adds a chat only when it is not already known. Used when the account's own
     * conversations are folded into the sidebar: an existing row keeps its local
     * title and its place in the list, so a sync never reorders what the user
     * already has.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversationIfAbsent(c: ConversationEntity)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversationId = :id")
    suspend fun messageCount(id: String): Int

    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun renameConversation(id: String, title: String, now: Long)

    @Query("UPDATE conversations SET mode = :mode, updatedAt = :now WHERE id = :id")
    suspend fun setConversationMode(id: String, mode: String, now: Long)

    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
    suspend fun touchConversation(id: String, now: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("DELETE FROM tasks WHERE conversationId = :id")
    suspend fun deleteTasksOf(id: String)

    /**
     * Past turns that mention any of the given words, newest first. Used to
     * recall a conversation from weeks ago: the whole transcript stays in the
     * database, but only the matching parts are worth sending to the model.
     */
    @Query(
        "SELECT * FROM chat_messages WHERE text LIKE '%' || :a || '%' " +
            "OR text LIKE '%' || :b || '%' OR text LIKE '%' || :c || '%' " +
            "OR text LIKE '%' || :d || '%' " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun searchMessages(a: String, b: String, c: String, d: String, limit: Int): List<ChatMessageEntity>

    // Memories (Persistent SQLite Memory Store)
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun getAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun getMemoriesList(): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE `key` = :key")
    suspend fun deleteMemory(key: String)

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()

    // Tasks
    @Query("SELECT * FROM tasks WHERE conversationId = :conversationId ORDER BY timestamp DESC")
    fun getTasks(conversationId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE conversationId = :conversationId AND isCompleted = 0 ORDER BY timestamp DESC")
    suspend fun getOpenTasks(conversationId: String): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity)

    @Query("UPDATE tasks SET isCompleted = :completed WHERE id = :id")
    suspend fun updateTaskStatus(id: String, completed: Boolean)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("DELETE FROM tasks WHERE conversationId = :conversationId")
    suspend fun deleteTasksFor(conversationId: String)
}
