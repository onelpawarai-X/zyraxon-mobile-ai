package ai.zyraxon.assistant.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ZyraxonDao {
    // Chat Messages
    @Query("SELECT * FROM chat_messages ORDER BY createdAt ASC")
    fun getAllMessages(): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()

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
    @Query("SELECT * FROM tasks ORDER BY timestamp DESC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity)

    @Query("UPDATE tasks SET isCompleted = :completed WHERE id = :id")
    suspend fun updateTaskStatus(id: String, completed: Boolean)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("DELETE FROM tasks")
    suspend fun deleteAllTasks()
}
