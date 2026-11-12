// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

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
    val thinking: String = "",
    /** When the line was written, in epoch millis — shown as a clock time. */
    val createdAt: Long = System.currentTimeMillis(),
    /** How long the assistant took to answer (0 for lines the user wrote). */
    val durationMs: Long = 0,
    /** The tool calls this turn made, kept so the chat shows them after the fact. */
    val toolCalls: List<ToolCallRecord> = emptyList(),
    /**
     * Everything the turn did, in the exact order it happened: reasoning, spoken
     * text, tool calls, more text. The chat used to draw all the prose first and
     * all the tool cards after it, which showed a different story from the one
     * that actually ran. This is that story.
     */
    val events: List<TurnEvent> = emptyList(),
    /** Pictures, audio and videos the assistant produced in this turn. */
    val media: List<MediaItem> = emptyList()
)

/** One step of an assistant turn. */
data class TurnEvent(
    /** "thinking", "text" or "tool". */
    val kind: String,
    val text: String = "",
    /** For a "tool" event: the id of the matching entry in [ChatMessage.toolCalls]. */
    val toolId: String = ""
)

/** One tool call as it appears under an assistant message. */
data class ToolCallRecord(
    val id: String = "",
    val name: String,
    /** The command, path or url the call acted on. */
    val detail: String = "",
    val icon: String = "",
    val summary: String = "",
    /** "running", "ok" or "error". */
    val status: String = "ok",
    val output: String = "",
    val ms: Long = 0
)

/** A file the assistant made for the user: an image, a sound, a video or any file. */
data class MediaItem(
    /** "image", "audio", "video" or "file". */
    val kind: String,
    val path: String,
    val title: String = ""
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
    val isRealtime: Boolean = false,
    /**
     * What this model can be sent as a content part: an image, spoken audio, a
     * clip. A part sent to a model that does not take it is a hard 400, so a
     * kind is only ever sent when the provider declares it or the model's own
     * name says so. Empty means text and links only, which never fails.
     */
    val media: String = ""
) {
    /** The kinds this model takes as a content part. */
    val mediaCaps: ai.zyraxon.assistant.ai.MediaSupport.Caps
        get() = if (media.isBlank()) ai.zyraxon.assistant.ai.MediaSupport.NONE
        else ai.zyraxon.assistant.ai.MediaSupport.decode(media)
}

enum class AppTab(val title: String) {
    /** The home: the 3D person fills the middle. No panel is open. */
    HOME("হোম"),
    CHAT("চ্যাট"),
    TERMINAL("টার্মিনাল"),
    CODE("কোড"),
    PREVIEW("প্রিভিউ"),
    MCP("MCP"),

    // Kept only so an old saved value or a tool that still names them does not
    // crash; both are folded into the chat panel's own sections (see setTab).
    TASKS("টাস্ক"),
    TOOLS("টুল")
}

/** The three ways a chat can work; each has its own system prompt and its own tools. */
enum class AgentMode(private val titleBn: String, private val subtitleBn: String, /** Icon key resolved through [AppIcons.of]. */ val icon: String) {
    CHAT("চ্যাট", "শুধু কথা ও পরামর্শ", "chat"),
    BUILD("বিল্ড", "ফুল-স্ট্যাক অ্যাপ ও ওয়েবসাইট", "build"),
    AGENT("এজেন্ট", "ফোন ও সিস্টেমের পূর্ণ অটোমেশন", "smartToy");

    val title: String get() = ai.zyraxon.assistant.i18n.t(titleBn)
    val subtitle: String get() = ai.zyraxon.assistant.i18n.t(subtitleBn)

    companion object {
        fun parse(v: String?): AgentMode = entries.firstOrNull { it.name == v } ?: AGENT
    }
}

/** One chat in the sidebar. */
data class Conversation(
    val id: String,
    val title: String,
    val mode: AgentMode = AgentMode.AGENT,
    val updatedAt: Long = 0L,
    /** True while this chat has a turn running — it keeps working in the background. */
    val busy: Boolean = false
)

/** What the panel shows for a sub-agent the main agent launched. */
data class SubAgentInfo(
    val id: String,
    val parentId: String,
    val title: String,
    val task: String,
    val model: String,
    /** "running", "done", "error" or "stopped". */
    val state: String,
    /** 0..100, from the sub-agent's own to-do list and tool calls. */
    val percent: Int,
    val lastAction: String,
    val result: String = ""
)

/** A picture, sound or video being made, so the chat can show a live progress bar. */
data class MediaJob(
    val id: String,
    val conversationId: String,
    val kind: String,
    val label: String,
    val percent: Int,
    val done: Boolean = false,
    val path: String = "",
    val error: String = ""
)

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
    val timestamp: String,
    /** The call's own output so far, streamed while it runs. */
    val output: String = "",
    /** True from the instant the call is made until its result arrives. */
    val running: Boolean = false
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
    val shell: TerminalShell = TerminalShell.ANDROID,
    /**
     * True while a command is running in *this* session.
     *
     * Busy is a property of one shell, not of the Terminal. A single shared flag
     * meant a long command in the Linux tab marked the Android tab busy too, so
     * every line typed there was written into that command's standard input and
     * silently lost — the screen showed the line echoed and nothing else.
     */
    val busy: Boolean = false
)
