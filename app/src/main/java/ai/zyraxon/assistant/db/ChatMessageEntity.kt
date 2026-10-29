package ai.zyraxon.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val role: String, // "USER", "ASSISTANT", "SYSTEM"
    val text: String,
    val timestamp: String,
    val modelName: String = "OpenCode Free",
    val createdAt: Long = System.currentTimeMillis()
)
