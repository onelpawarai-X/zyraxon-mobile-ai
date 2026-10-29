package ai.zyraxon.assistant.model

import java.util.Locale
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val timestamp: String,
    val isStreaming: Boolean = false,
    val modelName: String = "OpenCode Free"
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class Persona(
    val id: String,
    val name: String,
    val title: String,
    val emoji: String,
    val description: String,
    val greeting: String,
    val systemPrompt: String
)

data class AiModel(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val provider: String,
    val isFree: Boolean = true,
    /**
     * True when this model speaks and listens on a real speech-to-speech socket.
     * Chosen by [ProviderCatalog.isRealtimeModel] against the provider's own
     * catalogue, never by a hardcoded list.
     */
    val isRealtime: Boolean = false
)

enum class AppTab(val title: String) {
    CHAT("চ্যাট"),
    TASKS("টাস্ক"),
    TOOLS("টুল"),
    MCP("MCP")
}

enum class BgAnimationMode(val displayName: String) {
    LIVING_PINK_AURA("পিংক লিভিং ওরা"),
    COSMIC_AURA("কসমিক অরা পোর্টাল"),
    CYBER_GRID("সাইবার নিয়ন গ্রিড"),
    DEEP_NEBULA("ডিপ গ্যালাক্সি স্টারফিল্ড"),
    MATRIX_RAIN("ম্যাট্রিক্স ডিজিটাল রেইন")
}

data class SystemStatus(
    val keepAliveRunning: Boolean = true,
    val accessibilityConnected: Boolean = false,
    val micPermissionGranted: Boolean = false,
    val activeLanguage: String = "বাংলা (bn-BD)",
    val latencyMs: Long = 28L,
    val currentApp: String = "ZYRAXON",
    /** The last real failure the user should see, e.g. a missing live-voice key. */
    val lastError: String? = null
)

data class TaskItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isCompleted: Boolean = false,
    val createdAt: String = ""
)

/** One file the assistant wrote, shown in the Files panel. */
data class WorkspaceFile(
    val name: String,
    val path: String,
    val relative: String,
    val sizeBytes: Long
) {
    val sizeLabel: String
        get() = when {
            sizeBytes < 1024 -> "${sizeBytes} B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", sizeBytes / 1048576.0)
        }
}

data class ToolExecutionItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val icon: String,
    val status: String,
    val timestamp: String
)

data class MemoryItem(
    val key: String,
    val value: String,
    val timestamp: String = ""
)
