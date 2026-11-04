package ai.zyraxon.assistant.chat

import ai.zyraxon.assistant.model.AgentMode
import ai.zyraxon.assistant.model.ChatMessage
import ai.zyraxon.assistant.model.MediaJob
import ai.zyraxon.assistant.model.TaskItem
import ai.zyraxon.assistant.model.ToolExecutionItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Everything one chat owns while it runs. Several of these exist at once, so a
 * turn in one chat never touches the screen state of another: each has its own
 * transcript, to-do board, tool rows, queued user words and running job.
 */
class ChatSession(val id: String) {
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val isGenerating = MutableStateFlow(false)

    /** Calls running right now, newest first. */
    val activeToolCalls = MutableStateFlow<List<ToolExecutionItem>>(emptyList())

    /** Finished calls, newest first. */
    val toolExecutions = MutableStateFlow<List<ToolExecutionItem>>(emptyList())

    /** A file being written right now, so its code appears line by line in the chat. */
    val liveCode = MutableStateFlow<LiveCode?>(null)

    /** Pictures, sounds and videos being made, with their progress. */
    val mediaJobs = MutableStateFlow<List<MediaJob>>(emptyList())

    val mode = MutableStateFlow(AgentMode.AGENT)

    /** Words the user typed while a turn was running; they join that same turn. */
    val pendingInjections = ConcurrentLinkedQueue<String>()

    /** The assistant bubble of the turn that is running, so tool cards land in it. */
    @Volatile var currentMsgId: String = ""
    @Volatile var turnStartedAt: Long = 0L
    /** Set for a helper chat started by `sub_agent`. */
    @Volatile var parentId: String = ""

    @Volatile var job: Job? = null
    @Volatile var loaded = false

    val lock = Any()

    inline fun updateActive(block: (List<ToolExecutionItem>) -> List<ToolExecutionItem>) {
        synchronized(lock) { activeToolCalls.value = block(activeToolCalls.value) }
    }

    fun updateJobs(block: (List<MediaJob>) -> List<MediaJob>) {
        synchronized(lock) { mediaJobs.value = block(mediaJobs.value) }
    }
}

/** The file the model is writing at this instant, grown as its arguments stream in. */
data class LiveCode(val path: String, val text: String, val done: Boolean = false)
