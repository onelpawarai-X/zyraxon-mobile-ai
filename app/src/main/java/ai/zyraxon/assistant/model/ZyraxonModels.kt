package ai.zyraxon.assistant.model

import java.util.Locale
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val timestamp: String,
    val isStreaming: Boolean = false,
    val modelName: String = "OpenCode Free",
    /**
     * The model's own reasoning for this turn, when the provider streamed it. Shown
     * on screen only — the user can tap to read exactly what it was thinking — and
     * never spoken.
     */
    val thinking: String = ""
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
    MCP("MCP"),
    TERMINAL("ZX")
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

/**
 * One entry in the Terminal scrollback: the prompt and the line the user or the
 * assistant typed, then whatever the shell really printed.
 */
data class TerminalLine(
    val id: String = UUID.randomUUID().toString(),
    val command: String,
    val output: String,
    val exitCode: Int,
    val isError: Boolean,
    val timedOut: Boolean = false,
    val millis: Long = 0,
    /** True while the command is still running, so the UI shows a live cursor. */
    val running: Boolean = false
)

/**
 * Which real shell a terminal session drives.
 *
 * Both are real and both are always available; the user picks by picking a tab.
 *  - [ANDROID] is the phone's own shell: the bundled busybox applets plus the
 *    app's `sh`, run in the app's sandbox. It is the shell the phone itself has.
 *  - [LINUX] is ZYRAXON's own full Unix system shipped inside the APK — bash,
 *    apt/pkg, git, python, node, clang — unpacked from the APK so it needs no
 *    network. It is the same system `linux_run` drives.
 */
enum class TerminalShell { ANDROID, LINUX }

/**
 * One terminal session: its own scrollback, its own working directory and the
 * shell it drives. The sidebar lists these, so a user can keep several shells
 * open at once and come back to an earlier one without losing where they were.
 */
data class TerminalSession(
    val id: String,
    val title: String,
    val lines: List<TerminalLine> = emptyList(),
    val cwd: String = "~",
    val shell: TerminalShell = TerminalShell.ANDROID
)
