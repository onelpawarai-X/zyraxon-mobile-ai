// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One chat. Several run at the same time, each with its own mode, history and
 * to-do board. [kind] is "chat" for the ones the user opens and "sub" for the
 * hidden conversation a sub-agent works in ([parentId] is the chat that launched it).
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mode: String = "AGENT",
    val kind: String = "chat",
    val parentId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
