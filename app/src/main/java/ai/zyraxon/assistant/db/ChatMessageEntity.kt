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
    val createdAt: Long = System.currentTimeMillis(),
    /** The conversation this message belongs to; "main" is the original single chat. */
    val conversationId: String = "main",
    /** How long the assistant took to answer, in milliseconds (0 for user lines). */
    val durationMs: Long = 0,
    /** JSON: the tool calls and generated media attached to this message. */
    val meta: String = ""
)
