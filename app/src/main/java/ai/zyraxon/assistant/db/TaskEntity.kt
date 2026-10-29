package ai.zyraxon.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val text: String,
    val isCompleted: Boolean = false,
    val createdAt: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
