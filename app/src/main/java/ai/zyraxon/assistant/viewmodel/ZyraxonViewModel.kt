package ai.zyraxon.assistant.viewmodel

import ai.zyraxon.assistant.DeviceBridge
import ai.zyraxon.assistant.ai.ToolRegistry
import ai.zyraxon.assistant.ai.PartialJson
import ai.zyraxon.assistant.chat.ChatSession
import ai.zyraxon.assistant.chat.LiveCode
import ai.zyraxon.assistant.model.AgentMode
import ai.zyraxon.assistant.model.Conversation
import ai.zyraxon.assistant.model.MediaItem
import ai.zyraxon.assistant.model.MediaJob
import ai.zyraxon.assistant.model.SubAgentInfo
import ai.zyraxon.assistant.model.ToolCallRecord
import ai.zyraxon.assistant.tools.GitTools
import ai.zyraxon.assistant.tools.PreviewBridge
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import ai.zyraxon.assistant.ai.ContextWindow
import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.mcp.McpAuth
import ai.zyraxon.assistant.mcp.McpCatalog
import ai.zyraxon.assistant.mcp.McpStatus
import ai.zyraxon.assistant.ai.ZyraxonAiEngine
import ai.zyraxon.assistant.db.ZyraxonDatabase
import ai.zyraxon.assistant.db.ZyraxonRepository
import ai.zyraxon.assistant.model.AiModel
import ai.zyraxon.assistant.model.AppTab
import ai.zyraxon.assistant.model.BgAnimationMode
import ai.zyraxon.assistant.model.ChatMessage
import ai.zyraxon.assistant.model.MemoryItem
import ai.zyraxon.assistant.model.MessageRole
import ai.zyraxon.assistant.model.Persona
import ai.zyraxon.assistant.model.SystemStatus
import ai.zyraxon.assistant.model.TaskItem
import ai.zyraxon.assistant.model.TerminalLine
import ai.zyraxon.assistant.model.TerminalSession
import ai.zyraxon.assistant.model.TerminalShell
import ai.zyraxon.assistant.model.ToolExecutionItem
import ai.zyraxon.assistant.model.WorkspaceFile
import ai.zyraxon.assistant.shell.GitRuntime
import ai.zyraxon.assistant.shell.BackgroundCommandService
import ai.zyraxon.assistant.shell.ShellRuntime
import ai.zyraxon.assistant.shell.ShizukuBundle
import ai.zyraxon.assistant.shell.ShizukuState
import ai.zyraxon.assistant.shell.SystemShell
import ai.zyraxon.assistant.shell.LinuxRuntime
import ai.zyraxon.assistant.ai.OnDeviceModel
import ai.zyraxon.assistant.ai.ProviderCatalog
import ai.zyraxon.assistant.ai.ProviderSpec
import ai.zyraxon.assistant.voice.LiveVoiceSession
import ai.zyraxon.assistant.voice.NativeVoiceManager
import ai.zyraxon.assistant.voice.SpeechChunker
import ai.zyraxon.assistant.voice.SttDecision
import ai.zyraxon.assistant.update.Updater
import ai.zyraxon.assistant.update.UpdateRelease
import ai.zyraxon.assistant.update.UpdateState
import ai.zyraxon.assistant.openhands.OpenHandsBridge
import ai.zyraxon.assistant.openhands.OpenHandsClient
import ai.zyraxon.assistant.openhands.OpenHandsStore
import ai.zyraxon.assistant.openhands.OhConversation
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import java.util.UUID

class ZyraxonViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Panel tools that only rearrange the UI. They are instant, so they get no
     * live row on the chat — the panel opening is its own feedback.
     */
    private val UI_ONLY_TOOLS = setOf(
        "todo_write", "code_box", "files_panel", "terminal_open", "floating_bubble"
    )

    private val MEDIA_TOOLS = mapOf(
        "generate_image" to "image", "generate_audio" to "audio", "generate_video" to "video", "ffmpeg" to "video"
    )

    private val prefs = application.getSharedPreferences("zyraxon_vm_prefs", Context.MODE_PRIVATE)

    // SQLite Local Room Database & Repository
    val database = ZyraxonDatabase.getDatabase(application)
    val repository = ZyraxonRepository(database.dao())

    val aiEngine = ZyraxonAiEngine(application)
    val voiceManager = NativeVoiceManager(application, viewModelScope)

    /**
     * The cloud agent. A turn routed here runs a whole remote conversation: the
     * sandbox does the heavy work and streams its thinking, its tool calls and its
     * answer back over a socket, while tools that belong to this phone are still
     * executed here. It shares the engine's contract, so nothing downstream needs
     * to know which brain answered.
     */
    val openHandsClient = OpenHandsClient()
    val openHandsStore = OpenHandsStore(application)
    val openHandsBridge = OpenHandsBridge(openHandsClient, openHandsStore) { aiEngine.isOnline() }

    /** The cloud's own provisioning stage during a first turn, for the loader. */
    private val _cloudNotice = MutableStateFlow("")
    val cloudNotice: StateFlow<String> = _cloudNotice.asStateFlow()
    private val cloudRefreshBusy = MutableStateFlow(false)

    private val _cloudStage = MutableStateFlow("")
    val cloudStage: StateFlow<String> = _cloudStage.asStateFlow()

    /** One-shot: the cloud model takes the default the first time a key exists. */
    @Volatile private var apexDefaulted = false

    /**
     * Speech-to-speech with a live model. While it runs the model's own voice is
     * the only voice: [speak] and the streaming reader both check
     * [LiveVoiceSession.isActive] so the phone's TTS stays silent, because two
     * voices answering at once is exactly the defect this mode must not have.
     */
    val liveVoice = LiveVoiceSession(application, viewModelScope)

    /** The optional on-device engine: no network, runs a downloaded model. */
    val onDeviceModel = OnDeviceModel.get(application)

    private val _selectedPersona = MutableStateFlow(restoreSavedPersona())
    val selectedPersona: StateFlow<Persona> = _selectedPersona.asStateFlow()

    private val _availableModels = MutableStateFlow<List<AiModel>>(emptyList())
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    private val _isLoadingModels = MutableStateFlow(false)
    val isLoadingModels: StateFlow<Boolean> = _isLoadingModels.asStateFlow()

    /** Per-provider status line: "connected" only after the provider really answered. */
    private val _providerStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val providerStatus: StateFlow<Map<String, String>> = _providerStatus.asStateFlow()

    private val _providerBusy = MutableStateFlow<Set<String>>(emptySet())
    val providerBusy: StateFlow<Set<String>> = _providerBusy.asStateFlow()

    // The selection is restored from the last session, so a restart comes back to
    // the model the user picked instead of jumping to whatever the catalogue
    // happens to list first.
    private val _selectedModel = MutableStateFlow(restoreSavedModel())
    val selectedModel: StateFlow<AiModel> = _selectedModel.asStateFlow()

    private val _activeTab = MutableStateFlow(
        runCatching { AppTab.valueOf(prefs.getString("active_tab", "") ?: "") }
            .getOrDefault(AppTab.HOME)
            // Old values that no longer name a screen land on the home.
            .takeIf { it != AppTab.TASKS && it != AppTab.TOOLS } ?: AppTab.HOME
    )
    val activeTab: StateFlow<AppTab> = _activeTab.asStateFlow()

    private val _themeMode = MutableStateFlow(
        runCatching { ai.zyraxon.assistant.ui.theme.ZyThemeMode.valueOf(prefs.getString("theme_mode", "") ?: "") }
            .getOrDefault(ai.zyraxon.assistant.ui.theme.ZyThemeMode.HORROR)
    )
    val themeMode: StateFlow<ai.zyraxon.assistant.ui.theme.ZyThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ai.zyraxon.assistant.ui.theme.ZyThemeMode) {
        _themeMode.value = mode
        ai.zyraxon.assistant.ui.theme.Glass.mode = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    private val _bgMode = MutableStateFlow(
        runCatching { BgAnimationMode.valueOf(prefs.getString("bg_mode", "") ?: "") }
            .getOrDefault(BgAnimationMode.LIVING_PINK_AURA)
    )
    val bgMode: StateFlow<BgAnimationMode> = _bgMode.asStateFlow()

    /**
     * The latest screen frame as a data URL, kept in memory only. It is attached
     * to the next model turn so the assistant can actually see what the user sees
     * instead of guessing from a window title.
     */
    private val _screenFrameDataUrl = MutableStateFlow("")
    val screenFrameDataUrl: StateFlow<String> = _screenFrameDataUrl.asStateFlow()

    /**
     * Pictures the user attached, waiting to be sent: one image, or several when
     * a video was attached (its frames). The next send carries them all as
     * `image_url` parts, so the model looks at the real pictures.
     */
    private val _attachedImages = MutableStateFlow<List<String>>(emptyList())
    val attachedImages: StateFlow<List<String>> = _attachedImages.asStateFlow()

    /** A short line shown above the composer when an attachment succeeds or fails. */
    private val _imageNotice = MutableStateFlow("")
    val imageNotice: StateFlow<String> = _imageNotice.asStateFlow()

    /**
     * While a job is running the screen is re-read on a timer and pushed to the
     * model, so it works from what is on the phone right now instead of from a
     * frame captured when the turn began. On by default — the user asked for the
     * assistant to always see the screen.
     */
    private val _alwaysSeeScreen = MutableStateFlow(prefs.getBoolean("always_see_screen", true))
    val alwaysSeeScreen: StateFlow<Boolean> = _alwaysSeeScreen.asStateFlow()

    /** True when the last screen read came back as text (no screenshot API). */
    private val _screenTextSnapshot = MutableStateFlow("")
    val screenTextSnapshot: StateFlow<String> = _screenTextSnapshot.asStateFlow()

    /** Short-lived cache for the accessibility tree walk; see [screenContext]. */
    @Volatile private var screenCacheText = ""
    @Volatile private var screenCacheAtMs = 0L

    /** The looping clip behind the aura. Empty means "no video, use the shader". */
    private val _bgVideoAsset = MutableStateFlow(prefs.getString("bg_video_asset", "girl.mp4") ?: "girl.mp4")
    val bgVideoAsset: StateFlow<String> = _bgVideoAsset.asStateFlow()

    // ---- Chats: many of them run at the same time ------------------------------
    // Every chat is a ChatSession with its own transcript, to-do board, tool rows,
    // queued words and running job. The names `_messages`, `_tasks`… below always
    // mean "the chat on screen"; anything that has to keep working for one
    // particular chat (a turn, a tool) holds its own ChatSession instead.
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, ChatSession>()
    private val _activeConversationId = MutableStateFlow(prefs.getString("active_chat", "main") ?: "main")
    val activeConversationId: StateFlow<String> = _activeConversationId.asStateFlow()

    fun session(id: String): ChatSession = sessions.getOrPut(id) { ChatSession(id).also { loadSession(it) } }

    private val activeSession: ChatSession get() = session(_activeConversationId.value)
    private val _messages: MutableStateFlow<List<ChatMessage>> get() = activeSession.messages
    private val _tasks: MutableStateFlow<List<TaskItem>> get() = activeSession.tasks
    private val _toolExecutions: MutableStateFlow<List<ToolExecutionItem>> get() = activeSession.toolExecutions
    private val _activeToolCalls: MutableStateFlow<List<ToolExecutionItem>> get() = activeSession.activeToolCalls
    private val _isGenerating: MutableStateFlow<Boolean> get() = activeSession.isGenerating
    private val pendingInjections: java.util.concurrent.ConcurrentLinkedQueue<String> get() = activeSession.pendingInjections

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> activeFlow(initial: T, pick: (ChatSession) -> StateFlow<T>): StateFlow<T> =
        _activeConversationId.flatMapLatest { pick(session(it)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val messages: StateFlow<List<ChatMessage>> = activeFlow(emptyList()) { it.messages }
    val tasks: StateFlow<List<TaskItem>> = activeFlow(emptyList()) { it.tasks }
    val toolExecutions: StateFlow<List<ToolExecutionItem>> = activeFlow(emptyList()) { it.toolExecutions }
    val activeToolCalls: StateFlow<List<ToolExecutionItem>> = activeFlow(emptyList()) { it.activeToolCalls }
    val isGenerating: StateFlow<Boolean> = activeFlow(false) { it.isGenerating }
    val liveCode: StateFlow<LiveCode?> = activeFlow<LiveCode?>(null) { it.liveCode }
    val mediaJobs: StateFlow<List<MediaJob>> = activeFlow(emptyList()) { it.mediaJobs }
    val activeMode: StateFlow<AgentMode> = activeFlow(AgentMode.AGENT) { it.mode }

    private val busyIds = MutableStateFlow<Set<String>>(emptySet())
    val chats: StateFlow<List<Conversation>> =
        combine(repository.chats, busyIds) { list, busy -> list.map { it.copy(busy = it.id in busy) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val subInfo = MutableStateFlow<Map<String, SubAgentInfo>>(emptyMap())
    val subAgents: StateFlow<List<SubAgentInfo>> =
        combine(subInfo, _activeConversationId) { m, a -> m.values.filter { it.parentId == a }.sortedBy { it.id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Which part of the chat panel is open: chat, todo, tools or files. */
    private val _chatSection = MutableStateFlow("chat")
    val chatSection: StateFlow<String> = _chatSection.asStateFlow()
    fun setChatSection(section: String) { _chatSection.value = section }

    /** Reads one chat from the database and keeps its transcript and board in step with it. */
    private fun loadSession(c: ChatSession) {
        if (c.loaded) return
        c.loaded = true
        viewModelScope.launch {
            repository.ensureConversation(c.id, if (c.id == "main") "প্রথম চ্যাট" else "নতুন চ্যাট", c.mode.value)
            repository.conversation(c.id)?.let { c.mode.value = it.mode }
            launch {
                repository.messages(c.id).collectLatest { stored ->
                    if (stored.isEmpty()) {
                        if (c.parentId.isBlank() && !c.id.startsWith("sub_")) {
                            val greeting = ChatMessage(
                                role = MessageRole.ASSISTANT, text = t(_selectedPersona.value.greeting),
                                timestamp = currentTimeString(), modelName = _selectedModel.value.name
                            )
                            c.messages.value = listOf(greeting)
                            repository.saveMessage(greeting, c.id)
                        }
                    } else {
                        // A bubble still being written is not in the database yet; keep it.
                        val keep = c.messages.value.filter { m -> m.isStreaming && stored.none { it.id == m.id } }
                        c.messages.value = stored + keep
                    }
                }
            }
            launch { repository.tasks(c.id).collectLatest { c.tasks.value = it } }
        }
    }

    fun newChat(mode: AgentMode? = null) {
        val id = "c" + UUID.randomUUID().toString().take(8)
        val m = mode ?: activeSession.mode.value
        viewModelScope.launch {
            repository.ensureConversation(id, "নতুন চ্যাট", m)
            session(id).mode.value = m
            switchChat(id)
        }
    }

    fun switchChat(id: String) {
        session(id)
        _activeConversationId.value = id
        prefs.edit().putString("active_chat", id).apply()
    }

    fun renameChat(id: String, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.renameConversation(id, title.trim()) }
    }

    fun deleteChat(id: String) {
        if (id == "main") { clearChat(); return }
        sessions[id]?.job?.cancel()
        sessions.remove(id)
        if (_activeConversationId.value == id) switchChat("main")
        viewModelScope.launch { repository.deleteConversation(id) }
    }

    fun setMode(mode: AgentMode) {
        val c = activeSession
        c.mode.value = mode
        prefs.edit().putString("last_mode", mode.name).apply()
        viewModelScope.launch { repository.setConversationMode(c.id, mode) }
    }

    /** The Fix button: the page's collected problems go to the agent, which repairs them. */
    fun fixPreviewProblems() {
        val probs = PreviewBridge.problems.value
        val text = buildString {
            append("প্রিভিউয়ে (").append(PreviewBridge.url.value).append(") এই সমস্যাগুলো ধরা পড়েছে। কারণ খুঁজে ঠিক করো, তারপর `preview` দিয়ে আবার খুলে যাচাই করো:\n")
            if (probs.isEmpty()) append("(কোনো এরর লগ নেই — স্ক্রিনশট দেখে যা ভাঙা মনে হয় ঠিক করো)")
            probs.forEach { append("- [").append(it.kind).append("] ").append(it.text).append('\n') }
        }
        sendMessage(text)
    }

    /** The Agent button in Preview: ask a question about what the page shows right now. */
    fun askAboutPreview(question: String) {
        viewModelScope.launch {
            val shot = withContext(Dispatchers.Main) { runCatching { PreviewBridge.screenshot(getApplication()) }.getOrNull() }
            if (!shot.isNullOrBlank()) _attachedImages.value = listOf(shot)
            sendMessage("[প্রিভিউ: ${PreviewBridge.url.value}] $question")
        }
    }

    // ---- Voices from the website's own endpoint -------------------------------
    data class VoiceInfo(val id: String, val name: String, val gender: String, val age: String, val locale: String, val label: String)

    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val voices: StateFlow<List<VoiceInfo>> = _voices.asStateFlow()
    private val _voicesState = MutableStateFlow("")
    val voicesState: StateFlow<String> = _voicesState.asStateFlow()
    private val _voiceId = MutableStateFlow(prefs.getString("voice_id", "") ?: "")
    val voiceId: StateFlow<String> = _voiceId.asStateFlow()
    private val _voiceAge = MutableStateFlow(prefs.getString("voice_age", "") ?: "")
    val voiceAge: StateFlow<String> = _voiceAge.asStateFlow()

    init {
        ai.zyraxon.assistant.voice.RemoteTtsRequest.voiceName = _voiceId.value
        ai.zyraxon.assistant.voice.RemoteTtsRequest.voiceAge = _voiceAge.value
    }

    /** Reads the voice catalogue from the server. Nothing about voices is hardcoded in the app. */
    fun loadVoices() {
        if (_voicesState.value == "loading") return
        _voicesState.value = "loading"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val raw = java.net.URL(ai.zyraxon.assistant.voice.RemoteTtsRequest.VOICES_URL).openStream().bufferedReader().use { it.readText() }
                val tokener = org.json.JSONTokener(raw).nextValue()
                val arr: org.json.JSONArray = when (tokener) {
                    is org.json.JSONArray -> tokener
                    is JSONObject -> tokener.optJSONArray("voices") ?: tokener.optJSONArray("data") ?: tokener.optJSONArray("items")
                        ?: org.json.JSONArray().also { out -> tokener.keys().forEach { k -> tokener.optJSONObject(k)?.let { o -> out.put(o.put("id", o.optString("id", k))) } } }
                    else -> org.json.JSONArray()
                }
                val list = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").ifBlank { o.optString("voice") }.ifBlank { o.optString("shortName") }.ifBlank { o.optString("name") }
                    if (id.isBlank()) return@mapNotNull null
                    val loc = o.optString("locale").ifBlank { o.optString("language") }.ifBlank { o.optString("lang") }.ifBlank { o.optString("Locale") }
                    VoiceInfo(
                        id = id, name = o.optString("name").ifBlank { o.optString("displayName") }.ifBlank { id },
                        gender = o.optString("gender").ifBlank { o.optString("Gender") }.lowercase(),
                        age = o.optString("age").ifBlank { o.optString("ageGroup") }.lowercase(),
                        locale = loc, label = o.optString("label").ifBlank { o.optString("description") }
                    )
                }
                _voices.value = list
                _voicesState.value = if (list.isEmpty()) "খালি তালিকা এসেছে" else ""
            } catch (e: Exception) {
                _voicesState.value = "কণ্ঠের তালিকা আনা যায়নি: ${e.message}"
            }
        }
    }

    fun pickVoice(v: VoiceInfo) {
        _voiceId.value = v.id
        ai.zyraxon.assistant.voice.RemoteTtsRequest.voiceName = v.id
        prefs.edit().putString("voice_id", v.id).apply()
        if (v.gender.isNotBlank()) setVoiceGender(if (v.gender.startsWith("m")) "male" else "female")
        if (v.locale.isNotBlank()) setVoiceLanguage(v.locale)
        if (v.age.isNotBlank()) setVoiceAge(v.age)
        speak(when (v.locale.substringBefore('-').lowercase()) {
            "bn" -> "হ্যালো, আমি জাইরাক্সন। আমার কণ্ঠ এমন শোনাবে।"
            "hi" -> "नमस्ते, मैं ज़ायरैक्सन हूँ। मेरी आवाज़ ऐसी लगेगी।"
            else -> "Hello, I'm Zyraxon. This is how I sound."
        })
    }

    fun setVoiceAge(age: String) {
        _voiceAge.value = age
        ai.zyraxon.assistant.voice.RemoteTtsRequest.voiceAge = age
        prefs.edit().putString("voice_age", age).apply()
    }

    fun clearVoicePick() {
        _voiceId.value = ""
        ai.zyraxon.assistant.voice.RemoteTtsRequest.voiceName = ""
        prefs.edit().putString("voice_id", "").apply()
    }

    fun stopSubAgent(id: String) {
        sessions[id]?.job?.cancel()
        subUpdate(id) { it.copy(state = "stopped", lastAction = "থামানো হয়েছে") }
    }

    /** A file the user (or the Files list) wants opened in the Code panel's editor. */
    private val _pendingOpenPath = MutableStateFlow<String?>(null)
    val pendingOpenPath: StateFlow<String?> = _pendingOpenPath.asStateFlow()
    fun requestOpenInEditor(path: String) { _pendingOpenPath.value = path; setTab(AppTab.CODE) }
    fun consumeOpenRequest() { _pendingOpenPath.value = null }

    /** Whether the left sidebar is open. */
    private val _sidebarOpen = MutableStateFlow(false)
    val sidebarOpen: StateFlow<Boolean> = _sidebarOpen.asStateFlow()
    fun setSidebarOpen(open: Boolean) { _sidebarOpen.value = open }

    /** Stops the turn of the chat on screen. Other chats keep running. */
    fun stopTurn() { activeSession.job?.cancel() }

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _systemStatus = MutableStateFlow(SystemStatus())
    val systemStatus: StateFlow<SystemStatus> = _systemStatus.asStateFlow()

    // Voice Selection (Female vs Male)
    val voiceGender: StateFlow<String> = voiceManager.voiceGender
    val ttsAvailable: StateFlow<Boolean> = voiceManager.ttsAvailable
    val voiceLanguage: StateFlow<String> = voiceManager.voiceLanguage
    val voiceDataMissing: StateFlow<Boolean> = voiceManager.voiceDataMissing

    /** The real voice the ZYRAXON website used, straight from its own header. */
    val remoteVoiceName: StateFlow<String> = voiceManager.remoteVoiceName

    /** The last voice error ("ইন্টারনেট সংযোগ নেই", "ভয়েস সার্ভিস 502"…). */
    val voiceError: StateFlow<String> = voiceManager.voiceError

    /** "website" while the online voice speaks, "phone" while the engine does. */
    val voiceSource: StateFlow<String> = voiceManager.voiceSource

    /**
     * Whether the ZYRAXON website's voice is used. It is the default because it
     * carries both Bangla genders, four tones and 322 voices, where the phone's
     * engine has one voice per language and no emotion. Turning it off keeps the
     * phone's engine as the only voice, for a phone that is offline or a user who
     * prefers the installed voice. The choice persists.
     */
    private val _useRemoteVoice = MutableStateFlow(prefs.getBoolean("use_remote_voice", true))
    val useRemoteVoice: StateFlow<Boolean> = _useRemoteVoice.asStateFlow()

    /** Opens the engine's own screen that downloads a voice for this language. */
    fun openVoiceDataInstall() {
        voiceManager.openVoiceDataInstall(voiceLanguage.value)
    }

    // Settings States
    private val _ttsEnabled = MutableStateFlow(prefs.getBoolean("tts_enabled", true))
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    private val _ttsRate = MutableStateFlow(prefs.getFloat("tts_rate", 1.0f))
    val ttsRate: StateFlow<Float> = _ttsRate.asStateFlow()

    private val _ttsPitch = MutableStateFlow(prefs.getFloat("tts_pitch", 1.25f))
    val ttsPitch: StateFlow<Float> = _ttsPitch.asStateFlow()

    /**
     * The user's microphone switch. True means the mic stays open for as long as
     * the app runs; false means it stays shut until the user opens it again.
     * Default true, because the user asked for a microphone that is simply on.
     */
    private val _alwaysListening = MutableStateFlow(prefs.getBoolean("always_listening", true))
    val alwaysListening: StateFlow<Boolean> = _alwaysListening.asStateFlow()

    /**
     * Whether a live spoken session is open. This is now derived from the model
     * the user picked: choosing a realtime model opens it, choosing a text model
     * closes it. There is no separate switch to keep in sync.
     */
    private val _liveVoiceEnabled = MutableStateFlow(false)
    val liveVoiceEnabled: StateFlow<Boolean> = _liveVoiceEnabled.asStateFlow()

    private val _liveVoiceState = MutableStateFlow(LiveVoiceSession.State.IDLE)
    val liveVoiceState: StateFlow<LiveVoiceSession.State> = _liveVoiceState.asStateFlow()

    /**
     * The realtime model that is currently open, or blank when the selected model
     * is a text model. Set from [setModel], never typed by the user.
     */
    private val _liveVoiceModel = MutableStateFlow("")
    val liveVoiceModel: StateFlow<String> = _liveVoiceModel.asStateFlow()

    /** The provider the open realtime session belongs to, for the status line. */
    private val _liveVoiceProvider = MutableStateFlow("")
    val liveVoiceProvider: StateFlow<String> = _liveVoiceProvider.asStateFlow()

    /**
     * Whether the user wants the model's own speech-to-speech voice when they
     * pick a realtime model. Off by default: the phone's text-to-speech is the
     * voice that always works, so it stays in charge until the user asks for the
     * live one. A realtime model whose live session cannot open falls back to the
     * phone's voice rather than going silent.
     */
    private val _liveVoicePreferred = MutableStateFlow(prefs.getBoolean("live_voice_preferred", false))
    val liveVoicePreferred: StateFlow<Boolean> = _liveVoicePreferred.asStateFlow()

    private val _autoSend = MutableStateFlow(prefs.getBoolean("auto_send", true))
    val autoSend: StateFlow<Boolean> = _autoSend.asStateFlow()

    /**
     * True once the user picks a voice themselves, in Settings or through the
     * `voice_change` tool. Until then the character decides the voice; after that
     * the user's pick is kept — including across a restart. Without this a male
     * choice was silently replaced by the default character's female voice on the
     * next launch, which is the "I only ever hear the female voice" report.
     */
    private val _voiceChosenByUser = MutableStateFlow(prefs.getBoolean("voice_chosen_by_user", false))

    private val _customBgUrl = MutableStateFlow(prefs.getString("custom_bg_url", "") ?: "")
    val customBgUrl: StateFlow<String> = _customBgUrl.asStateFlow()

    /**
     * The Settings pane the user was last on, kept in preferences so it survives
     * a restart. Without it the sheet reopened on the first tab every time, which
     * read as the app forgetting the section the user was working in.
     */
    var settingsPane: String
        get() = prefs.getString("settings_pane", "session") ?: "session"
        set(value) = prefs.edit().putString("settings_pane", value).apply()

    private val _animSpeed = MutableStateFlow(prefs.getFloat("anim_speed", 1.0f))
    val animSpeed: StateFlow<Float> = _animSpeed.asStateFlow()

    private val _glassBlur = MutableStateFlow(prefs.getInt("glass_blur", 14))
    val glassBlur: StateFlow<Int> = _glassBlur.asStateFlow()

    // The aura's look. Every value is written straight into the aura page as a
    // CSS variable, so the user can reshape the orb without a rebuild.
    private val _auraHue = MutableStateFlow(prefs.getInt("aura_hue", 352))
    val auraHue: StateFlow<Int> = _auraHue.asStateFlow()

    private val _auraSize = MutableStateFlow(prefs.getFloat("aura_size", 1.0f))
    val auraSize: StateFlow<Float> = _auraSize.asStateFlow()

    private val _auraGlow = MutableStateFlow(prefs.getFloat("aura_glow", 1.0f))
    val auraGlow: StateFlow<Float> = _auraGlow.asStateFlow()

    // Which designed aura is showing. Stored by name so a new style can be added
    // without invalidating what the user already picked.
    private val _auraStyle = MutableStateFlow(prefs.getString("aura_style", "orb") ?: "orb")
    val auraStyle: StateFlow<String> = _auraStyle.asStateFlow()

    // Which WebGL shader, if any, replaces the CSS aura. "none" is the default so
    // an existing install keeps the look it had; the shaders are opt-in.
    private val _auraShader = MutableStateFlow(prefs.getString("aura_shader", "none") ?: "none")
    val auraShader: StateFlow<String> = _auraShader.asStateFlow()

    private val _auraSpeed = MutableStateFlow(prefs.getFloat("aura_speed", 1.0f))
    val auraSpeed: StateFlow<Float> = _auraSpeed.asStateFlow()

    // Panel glass. Alpha is what the user drags to go from clear to frosted.
    private val _panelAlpha = MutableStateFlow(prefs.getFloat("panel_alpha", 0.22f))
    val panelAlpha: StateFlow<Float> = _panelAlpha.asStateFlow()

    private val _githubToken = MutableStateFlow(aiEngine.getGithubToken())
    val githubToken: StateFlow<String> = _githubToken.asStateFlow()

    private val _githubStatus = MutableStateFlow(if (aiEngine.getGithubToken().isNotEmpty()) "টোকেন সেভ করা আছে" else "টোকেন নেই")
    val githubStatus: StateFlow<String> = _githubStatus.asStateFlow()

    // ---- in-app updates ----------------------------------------------------

    private val updater = Updater(application)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    /** The release being offered, kept so Download and Install can use it. */
    private var pendingRelease: UpdateRelease? = null

    /** True once a check has answered, so the banner never flashes on every start. */
    private val _updateChecked = MutableStateFlow(false)
    val updateChecked: StateFlow<Boolean> = _updateChecked.asStateFlow()

    // A dismissed release stays dismissed until the app is restarted, so the
    // banner does not come back every time the user closes and reopens it.
    private val _updateBannerDismissed = MutableStateFlow(false)
    val updateBannerDismissed: StateFlow<Boolean> = _updateBannerDismissed.asStateFlow()

    val currentVersionName: String = Updater.installedVersionName(application)

    /** Whether Android will let this app install an update itself. */
    fun canInstallUpdates(): Boolean = updater.canInstall()

    /** Opens the "install unknown apps" row so the user can allow in-app installs. */
    fun openInstallPermission() = updater.openInstallPermissionSettings()

    // ---- Shizuku (system access) ------------------------------------------

    private val _shizukuState = MutableStateFlow<ShizukuState>(ShizukuState.Unknown)
    val shizukuState: StateFlow<ShizukuState> = _shizukuState.asStateFlow()

    /** Reads the real state from the running service. */
    fun refreshShizuku() {
        _shizukuState.value = ShizukuBundle.state(getApplication())
    }

    /**
     * Installs the Shizuku app ZYRAXON carries. Android shows its own confirm
     * dialog; the user taps Install once and Shizuku is on the phone.
     */
    fun installShizuku() {
        val app = getApplication<Application>()
        val apk = ShizukuBundle.apkFile(app)
        if (apk == null) {
            _shizukuState.value = ShizukuState.Failed("বান্ডেল করা Shizuku ফাইল খোলা গেল না")
            return
        }
        if (!updater.canInstall()) {
            updater.openInstallPermissionSettings()
            _shizukuState.value = ShizukuState.Failed("ইনস্টল করতে আগে \"এই সোর্স থেকে ইনস্টল\" অনুমতি দিন")
            return
        }
        _shizukuState.value = ShizukuState.Installing
        runCatching { updater.installPackage(apk) }.fold(
            onSuccess = { _shizukuState.value = ShizukuState.InstalledNotRunning },
            onFailure = { _shizukuState.value = ShizukuState.Failed(it.message ?: "Shizuku ইনস্টল শুরু করা গেল না") }
        )
    }

    /** Opens Shizuku so the user can start it and grant ZYRAXON access. */
    fun openShizuku() {
        ShizukuBundle.launch(getApplication())
    }

    /** Asks Shizuku for permission; the user answers in Shizuku's own dialog. */
    fun requestShizukuPermission() {
        SystemShell.requestPermission()
        refreshShizuku()
    }

    fun dismissUpdateBanner() {
        _updateBannerDismissed.value = true
    }

    /**
     * Asks GitHub for the newest release. Called once when the app opens, and
     * again whenever the user taps Check for updates in Settings.
     */
    fun checkForUpdates(silent: Boolean = false) {
        val current = _updateState.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            updater.check().fold(
                onSuccess = { release ->
                    pendingRelease = release
                    _updateState.value = when {
                        release == null -> UpdateState.UpToDate(currentVersionName)
                        release.apk == null -> UpdateState.NoApk(release)
                        else -> UpdateState.Available(release)
                    }
                    if (release != null) {
                        _updateBannerDismissed.value = false
                        // Fetch it now, in the background, so the user only has to
                        // tap Install when they open Settings — no waiting.
                        stageUpdate(release)
                    }
                },
                onFailure = { error ->
                    // A failed check is silent on launch — being offline is not an
                    // error worth a banner — but a manual check reports it.
                    _updateState.value = if (silent) {
                        UpdateState.Idle
                    } else {
                        UpdateState.Failed(error.message ?: "চেক করা গেল না")
                    }
                }
            )
            _updateChecked.value = true
        }
    }

    /** Downloads the offered release, streaming progress into [updateState]. */
    fun downloadUpdate() {
        val release = pendingRelease
            ?: (_updateState.value as? UpdateState.Available)?.release
            ?: return
        if (_updateState.value is UpdateState.Downloading) return
        _updateState.value = UpdateState.Downloading(0f)
        viewModelScope.launch {
            updater.stage(release) { progress ->
                _updateState.value = UpdateState.Downloading(progress)
            }.fold(
                onSuccess = { apk ->
                    _updateState.value = UpdateState.Downloaded(release, apk.absolutePath)
                },
                onFailure = { error ->
                    _updateState.value = UpdateState.Failed(error.message ?: "ডাউনলোড ব্যর্থ")
                }
            )
        }
    }

    /**
     * Fetches a release in the background the moment it is found, so by the time
     * the user opens Settings the APK is already on disk and Install is one tap.
     * A failure here is not an error: the release stays Available and the user can
     * still download it by hand.
     */
    private fun stageUpdate(release: UpdateRelease) {
        viewModelScope.launch {
            updater.stage(release) { progress ->
                if (_updateState.value is UpdateState.Available) {
                    _updateState.value = UpdateState.Downloading(progress)
                }
            }.onSuccess { apk ->
                _updateState.value = UpdateState.Downloaded(release, apk.absolutePath)
            }.onFailure {
                if (_updateState.value is UpdateState.Downloading) {
                    _updateState.value = UpdateState.Available(release)
                }
            }
        }
    }

    /** Installs a downloaded update in place. Asks for the permission if needed. */
    fun installUpdate() {
        val state = _updateState.value
        val release = when (state) {
            is UpdateState.Downloaded -> state.release
            is UpdateState.Available -> state.release
            is UpdateState.NeedsPermission -> state.release
            else -> pendingRelease
        } ?: return
        // Prefer the file the Download state names; otherwise use whatever was
        // staged in the background, which is the common case now.
        val file = (state as? UpdateState.Downloaded)?.apkPath?.let { java.io.File(it) }
            ?: updater.stagedApk(release)
        if (file == null || !file.exists()) {
            _updateState.value = UpdateState.Failed("ডাউনলোড করা ফাইল পাওয়া গেল না")
            return
        }
        if (!updater.canInstall()) {
            updater.openInstallPermissionSettings()
            _updateState.value = UpdateState.NeedsPermission(release)
            return
        }
        runCatching { updater.install(file) }.onFailure { error ->
            _updateState.value = UpdateState.Failed(error.message ?: "ইনস্টল শুরু করা গেল না")
        }
    }

    // Tasks & Memories from SQLite Room Database

    val memories: StateFlow<List<MemoryItem>> = repository.allMemories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /** Icon per tool family, for the live row. */
    private fun toolIcon(name: String): String = when {
        name == "run_shell" || name == "system_shell" || name == "linux_run" || name == "terminal_run" -> "⌨️"
        name == "read_file" || name == "write_file" || name == "edit_file" || name == "list_dir" ||
            name == "find_files" || name == "grep_files" || name == "delete_file" -> "📄"
        name == "web_search" || name == "read_url" || name == "http_request" || name == "verify_url" -> "🌐"
        name == "download_file" || name == "publish_website" || name == "publish_github" -> "☁️"
        name.startsWith("publish_") -> "🚀"
        name == "screen_shot" || name == "read_screen" || name == "look_camera" -> "👁"
        name == "tap" || name == "tap_text" || name == "swipe" || name == "type_text" || name == "press_key" -> "👆"
        name.startsWith("git") -> "🌿"
        name.contains("__") -> "🔌"
        else -> "⚙️"
    }

    private val recUpdateAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val recOutput = java.util.concurrent.ConcurrentHashMap<String, StringBuilder>()

    private fun detailOf(name: String, args: JSONObject): String {
        for (k in listOf("command", "path", "url", "query", "prompt", "action", "name", "title", "text")) {
            val v = args.optString(k).trim()
            if (v.isNotBlank()) return v.take(600)
        }
        return ""
    }

    private fun updateRecord(s: ChatSession, id: String, f: (ToolCallRecord) -> ToolCallRecord) {
        s.messages.update { list ->
            list.map { m ->
                if (m.id != s.currentMsgId || m.toolCalls.none { it.id == id }) m
                else m.copy(toolCalls = m.toolCalls.map { if (it.id == id) f(it) else it })
            }
        }
    }

    /** Opens a live row (and a card in the chat bubble) the moment a call is made. */
    private fun beginToolCall(s: ChatSession, name: String, args: JSONObject): String {
        val id = UUID.randomUUID().toString()
        val item = ToolExecutionItem(
            id = id, name = name, description = describeToolCall(name, args), icon = toolIcon(name),
            status = "চলছে…", timestamp = currentTimeString(), running = true
        )
        s.updateActive { list -> listOf(item) + list }
        val code = if (name == "edit_file") args.optString("content").ifBlank { args.optString("replace") } else ""
        val rec = ToolCallRecord(
            id = id, name = name, detail = detailOf(name, args), icon = toolIcon(name),
            summary = item.description, status = "running", output = code
        )
        if (name == "edit_file") s.liveCode.value = LiveCode(args.optString("path"), code, true)
        s.messages.update { list ->
            list.map { m -> if (m.id == s.currentMsgId) m.copy(toolCalls = m.toolCalls + rec) else m }
        }
        MEDIA_TOOLS[name]?.let { kind ->
            s.updateJobs { it + MediaJob(id, s.id, kind, item.description, 0) }
        }
        return id
    }

    /** Streams a line of a running call's output into its live row; `@@progress` lines drive the percentage bar. */
    private fun appendToolOutput(s: ChatSession, id: String, chunk: String) {
        if (chunk.isBlank()) return
        val plain = StringBuilder()
        for (line in chunk.split('\n')) {
            if (line.startsWith("@@progress ")) {
                val parts = line.removePrefix("@@progress ").trim().split(' ', limit = 2)
                val pct = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val label = parts.getOrNull(1).orEmpty()
                s.updateJobs { jobs -> jobs.map { if (it.id == id) it.copy(percent = pct, label = label.ifBlank { it.label }) else it } }
            } else if (line.isNotBlank()) plain.append(line).append('\n')
        }
        if (plain.isEmpty()) return
        s.updateActive { list ->
            list.map { item ->
                if (item.id != id) item else item.copy(output = (item.output + plain).takeLast(8_000))
            }
        }
        val buf = recOutput.getOrPut(id) { StringBuilder() }
        synchronized(buf) { buf.append(plain); if (buf.length > 12_000) buf.delete(0, buf.length - 12_000) }
        val now = System.currentTimeMillis()
        if (now - (recUpdateAt[id] ?: 0L) >= 150) {
            recUpdateAt[id] = now
            val txt = synchronized(buf) { buf.toString() }
            updateRecord(s, id) { if (it.name == "edit_file") it else it.copy(output = txt) }
        }
    }

    /** Closes the live row, fills the card in the bubble, and attaches any file the tool produced. */
    private fun finishToolCall(s: ChatSession, id: String, name: String, result: JSONObject, ms: Long) {
        val live = s.activeToolCalls.value.firstOrNull { it.id == id }
        s.updateActive { list -> list.filterNot { it.id == id } }
        recordToolExecution(s, name, result, live?.output.orEmpty())
        val ok = result.optBoolean("ok")
        val finalOut = recOutput.remove(id)?.toString().orEmpty()
        recUpdateAt.remove(id)
        val media = mutableListOf<MediaItem>()
        result.optJSONArray("media")?.let { arr ->
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                media.add(MediaItem(j.optString("kind", "file"), j.optString("path"), j.optString("title")))
            }
        }
        s.messages.update { list ->
            list.map { m ->
                if (m.id != s.currentMsgId) m
                else m.copy(
                    toolCalls = m.toolCalls.map {
                        if (it.id != id) it else it.copy(
                            status = if (ok) "ok" else "error",
                            summary = result.optString("summary").ifBlank { it.summary },
                            output = if (it.name == "edit_file") it.output else finalOut.ifBlank { it.output },
                            ms = ms
                        )
                    },
                    media = m.media + media
                )
            }
        }
        if (name in MEDIA_TOOLS) {
            s.updateJobs { jobs ->
                jobs.map {
                    if (it.id != id) it else it.copy(
                        percent = if (ok) 100 else it.percent, done = true,
                        path = media.firstOrNull()?.path.orEmpty(),
                        error = if (ok) "" else result.optString("summary")
                    )
                }
            }
        }
        if (name == "edit_file") s.liveCode.value = null
    }

    /** While a file is being written its code streams into the chat, line by line. */
    private fun onToolArgs(s: ChatSession, name: String, raw: String) {
        if (name != "edit_file") return
        val path = PartialJson.stringField(raw, "path").orEmpty()
        val text = PartialJson.stringField(raw, "content") ?: PartialJson.stringField(raw, "replace") ?: return
        s.liveCode.value = LiveCode(path, text, false)
    }

    /** A short, human line for the live row, taken from the call's arguments. */
    private fun describeToolCall(name: String, args: JSONObject): String {
        val keys = listOf("command", "path", "url", "query", "text", "name", "title", "action", "prompt", "package", "app")
        for (k in keys) {
            val v = args.optString(k).trim()
            if (v.isNotBlank()) return "$k: ${v.take(120)}"
        }
        return name
    }

    // Live panels the model opens through tools
    // While a turn is running, new user words are injected into that same turn
    // instead of starting a second one, so the assistant never stops mid-task.

    // The floating chat bubble. Off by default: it needs the overlay permission,
    // which only the user can grant.
    private val _overlayEnabled = MutableStateFlow(prefs.getBoolean("overlay_enabled", true))
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    private val _overlayExpanded = MutableStateFlow(false)
    val overlayExpanded: StateFlow<Boolean> = _overlayExpanded.asStateFlow()

    /**
     * The live screen frame stream. While a turn is running the screen is
     * captured every [LIVE_FRAME_INTERVAL_MS] and handed to the engine through
     * [drainScreenFrames], so the model keeps seeing the phone even during a job
     * that runs for an hour. The picture is dropped if the screen did not change
     * and if a capture is already pending, so an idle phone costs nothing.
     */
    private val pendingScreenFrames = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var liveFrameJob: kotlinx.coroutines.Job? = null
    private var lastLiveFrameHash = 0

    private val _codeBoxOpen = MutableStateFlow(false)
    val codeBoxOpen: StateFlow<Boolean> = _codeBoxOpen.asStateFlow()
    private val _codeBoxTitle = MutableStateFlow("")
    val codeBoxTitle: StateFlow<String> = _codeBoxTitle.asStateFlow()
    private val _codeBoxText = MutableStateFlow("")
    val codeBoxText: StateFlow<String> = _codeBoxText.asStateFlow()
    private val _filesPanelOpen = MutableStateFlow(false)
    val filesPanelOpen: StateFlow<Boolean> = _filesPanelOpen.asStateFlow()

    // ---- Terminal tab -------------------------------------------------------
    // Several shells can be open at once. Each session keeps its own scrollback
    // and its own working directory, so switching away and back never loses the
    // place the user was at. The sidebar lists them.

    /**
     * Two real shells, open from the start: the phone's own shell and ZYRAXON's
     * own Linux system. Neither is a placeholder and both always work — the user
     * switches between them from the sidebar, and `terminal_run` can target
     * either one.
     */
    private val _terminalSessions = MutableStateFlow(
        listOf(
            TerminalSession(id = UUID.randomUUID().toString(), title = "android", shell = TerminalShell.ANDROID),
            TerminalSession(id = UUID.randomUUID().toString(), title = "linux", shell = TerminalShell.LINUX)
        )
    )
    val terminalSessions: StateFlow<List<TerminalSession>> = _terminalSessions.asStateFlow()

    private val _activeTerminalId = MutableStateFlow(_terminalSessions.value.first().id)
    val activeTerminalId: StateFlow<String> = _activeTerminalId.asStateFlow()

    /**
     * The scrollback of the session on screen, and its working directory. Both
     * are kept as their own flows and refreshed whenever the sessions or the
     * active id change, so a reader always sees the current shell's state
     * without having to collect a derived flow.
     */
    private val _terminalLines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val terminalLines: StateFlow<List<TerminalLine>> = _terminalLines.asStateFlow()

    private val _terminalBusy = MutableStateFlow(false)
    val terminalBusy: StateFlow<Boolean> = _terminalBusy.asStateFlow()

    /**
     * The command that is running right now, if any.
     *
     * A running command owns the terminal: whatever the user types next is an
     * answer to that command's question, not a new command, and Ctrl-C stops it.
     * It is null whenever nothing is running.
     */
    @Volatile
    private var terminalProcess: ShellRuntime.LiveProcess? = null

    private val _terminalCwd = MutableStateFlow("~")
    val terminalCwd: StateFlow<String> = _terminalCwd.asStateFlow()

    /** Re-publishes the active session's scrollback and directory. */
    private fun syncActiveTerminal() {
        val active = _terminalSessions.value.firstOrNull { it.id == _activeTerminalId.value }
        _terminalLines.value = active?.lines ?: emptyList()
        _terminalCwd.value = active?.cwd ?: "~"
    }

    /** Files the assistant has written, shown in the Files panel. */
    private val _workspaceFiles = MutableStateFlow<List<WorkspaceFile>>(emptyList())
    val workspaceFiles: StateFlow<List<WorkspaceFile>> = _workspaceFiles.asStateFlow()

    val isListening: StateFlow<Boolean> = voiceManager.isListening
    val isSpeaking: StateFlow<Boolean> = voiceManager.isSpeaking
    val audioLevel: StateFlow<Float> = voiceManager.audioLevel

    init {
        active = this
        // A background command outlives this screen, so the callback is registered
        // here, not per turn. When such a command ends, the assistant is woken in
        // a fresh turn with the real result, instead of waiting for the user to
        // ask what happened — the reason `background_command` was worth having.
        BackgroundCommandService.onFinished = { command, exit, output ->
            onBackgroundFinished(command, exit, output)
        }
        // A job that was running when the app was last killed is reported once,
        // here, instead of being silently forgotten.
        viewModelScope.launch(Dispatchers.IO) {
            val saved = BackgroundCommandService.saved(getApplication())
            if (saved != null && saved.interrupted) {
                BackgroundCommandService.clearSaved(getApplication())
                val note = "[সেভ করা ব্যাকগ্রাউন্ড কাজ] `" + saved.command +
                    "` অ্যাপ বন্ধ হওয়ার সময় কাটা পড়েছিল (শেষ exit " + saved.exit + ")।\n" +
                    "আউটপুট (শেষ অংশ):\n```\n" + saved.output.takeLast(2_000) + "\n```\n" +
                    "কাজটি সম্পূর্ণ হয়নি — কী করবে বলো বা নিজে আবার শুরু করো।"
                withContext(Dispatchers.Main) { sendMessage(note) }
            }
        }
        // A server's sampling request runs on whatever model the user picked, so
        // it is answered by the same brain as the chat rather than a fixed one.
        aiEngine.samplingModelProvider = { _selectedModel.value }

        // The voice is restored from the user's own choice when they have made
        // one, and from the saved character otherwise. Reading the saved gender
        // back is what stops a male choice being reset to the default female
        // voice on the next launch.
        _voiceChosenByUser.value = prefs.getBoolean("voice_chosen_by_user", false)
        val savedGender = if (_voiceChosenByUser.value) {
            prefs.getString("voice_gender", null)
        } else {
            null
        }
        voiceManager.setVoiceGender(savedGender ?: genderForPersona(_selectedPersona.value))
        prefs.getString("voice_language", null)?.let { voiceManager.setVoiceLanguage(it) }
        // The ZYRAXON website's voice is the default; a user who turned it off
        // gets the phone's engine back exactly as they left it.
        voiceManager.setUseRemoteVoice(_useRemoteVoice.value)

        // A downloaded on-device model is reloaded on launch. Without this the
        // file sat in app storage unused: the user downloaded it, and the next
        // start forgot which bundle was loaded, so every turn went to the cloud
        // and the local model looked broken.
        restoreOnDeviceModel()

        // The live speech-to-speech session is reopened when the user left it on
        // and the restored model is a realtime one. Without this the preference
        // was saved but never honoured again, so the model's own voice worked
        // once and then never came back — the "speech-to-speech does not work"
        // report.
        if (_liveVoicePreferred.value && _selectedModel.value.isRealtime) {
            viewModelScope.launch { startLiveVoice() }
        }

        // The chat on screen (and every other chat, as it is opened) is loaded and
        // kept in step with the database by loadSession.
        session(_activeConversationId.value)
        session("main")

        // Initialize default memory in SQLite if empty
        viewModelScope.launch {
            val existing = repository.getMemoriesPromptContext()
            if (existing.isBlank()) {
                repository.saveMemory("নাম", "ব্যবহারকারী")
                repository.saveMemory("সহকারী", "ZYRAXON v2.0.0")
                repository.saveMemory("পছন্দের_ভাষা", "বাংলা")
            }
        }

        // Initialize default tasks if empty
        viewModelScope.launch {
            val currentTasks = database.dao().getMemoriesList()
            // Tasks auto-managed by Room
        }

        // Warm the connection to the chosen provider so the first token is not
        // held up by DNS and the TLS handshake.
        aiEngine.prewarm(_selectedModel.value)

        // Connect STT
        voiceManager.onSpeechRecognized = stt@{ text, isFinal ->
            // The assistant's own voice is filtered by *text*, never by muting
            // the microphone. Muting for the whole reply is what stopped the user
            // cutting in: they had to wait for the last word. The rule itself
            // lives in SttDecision so it can be tested without a phone.
            // The live session has its own mic and transcripts; the phone's
            // recognizer must not also feed the chat.
            if (liveVoice.isActive) return@stt
            val speakerActive = voiceManager.isSpeakerActive()
            val repeatsReply = looksLikeOwnSpeech(text, speakerActive)
            when (SttDecision.decide(text, isFinal, speakerActive, repeatsReply)) {
                SttDecision.Action.IGNORE -> return@stt
                SttDecision.Action.INTERRUPT -> {
                    // A partial only ever interrupts: it stops the assistant so
                    // the user can finish their sentence, and it is shown in the
                    // box. It is never sent on its own.
                    onUserStartedSpeaking()
                    _inputText.value = text
                    return@stt
                }
                SttDecision.Action.SUBMIT -> {
                    _inputText.value = text
                    if (_autoSend.value) {
                        onUserStartedSpeaking()
                        sendMessage(text)
                    }
                }
            }
        }

        // The microphone follows the user's saved choice: on from the first
        // frame when they left it on, off when they left it off. A live
        // speech-to-speech session takes the mic instead, so the phone's
        // recognizer stays out of the way until that session ends.
        if (_alwaysListening.value && _systemStatus.value.micPermissionGranted &&
            !(_liveVoicePreferred.value && _selectedModel.value.isRealtime)
        ) {
            voiceManager.startListening()
        }

        // Fetch Live Models on startup
        refreshModels()

        // The Linux system ships inside the APK, so it is unpacked once, quietly,
        // as soon as the app starts. There is nothing for the user to type and no
        // `linux-install` step to remember: by the time they open the Terminal the
        // system is already there, and it stays there across restarts.
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (LinuxRuntime.isSupported(app) && !LinuxRuntime.isInstalled(app)) {
                LinuxRuntime.install(app) { }
            }
        }
    }

    /**
     * True when a recognized phrase is really the assistant's own last words,
     * not the user's. The speaker's echo can arrive just after the time guard
     * lapses, so the text itself is compared: if most of what was heard already
     * appears in the reply the user just heard, it is the reply coming back.
     */
    private fun looksLikeOwnSpeech(heard: String, strict: Boolean = false): Boolean {
        val reply = _messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }?.text
        return ai.zyraxon.assistant.voice.EchoMatcher.isOwnSpeech(heard, reply, strict)
    }

    private fun currentTimeString(): String {
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
    }

    /**
     * The character the user last chose. Restored before the first frame so the
     * greeting and the voice come back as the user left them.
     */
    private fun restoreSavedPersona(): Persona {
        val id = prefs.getString("selected_persona_id", null)
        return ZyraxonAiEngine.PERSONAS.firstOrNull { it.id == id } ?: ZyraxonAiEngine.PERSONAS[0]
    }

    /**
     * The voice that belongs to a character. The boyfriend speaks male; every
     * other character speaks female. Kept in one place so the startup path and
     * the switch path can never disagree about which voice a character uses.
     */
    private fun genderForPersona(persona: Persona): String =
        if (persona.id == "boyfriend") "male" else "female"

    /**
     * Reloads the on-device bundle the user last loaded, off the main thread. A
     * large `.task` takes a few seconds to map into memory, so the app is not
     * blocked; a failure simply leaves the cloud path in charge.
     */
    private fun restoreOnDeviceModel() {
        val path = prefs.getString("on_device_model_path", null) ?: return
        if (path.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { onDeviceModel.load(path) }
        }
    }

    /**
     * Records which on-device bundle is in use (or clears it), so the next launch
     * reloads the same one. Called from the on-device panel after a load/unload.
     */
    fun rememberOnDeviceModel(path: String?) {
        if (path.isNullOrBlank()) prefs.edit().remove("on_device_model_path").apply()
        else prefs.edit().putString("on_device_model_path", path).apply()
    }

    /**
     * The model the user last chose. The id and provider are enough to rebuild a
     * usable model; the real name and realtime flag are refreshed from the live
     * catalogue when it arrives.
     */
    private fun restoreSavedModel(): AiModel {
        val id = prefs.getString("selected_model_id", null)
        if (id.isNullOrBlank()) return AiModel("", "মডেল লোড হচ্ছে…", "", "", "", true)
        val name = prefs.getString("selected_model_name", id) ?: id
        val provider = prefs.getString("selected_model_provider", "") ?: ""
        val spec = ProviderCatalog.find(provider)
        return AiModel(
            id = id,
            name = name,
            category = spec?.category ?: "Restored",
            description = spec?.label ?: provider,
            provider = provider,
            isRealtime = spec?.isRealtimeModel(id) ?: false
        )
    }

    fun setVoiceLanguage(tag: String) {
        voiceManager.setVoiceLanguage(tag)
        prefs.edit().putString("voice_language", tag).apply()
    }

    /**
     * Applies a voice the user picked in Settings, as a preview. The character
     * still owns the voice — boyfriend mode is always male and every other mode
     * female — so this pick lasts until the next character switch or launch. That
     * is deliberate: the user asked for the character to decide the voice.
     */
    fun setVoiceGender(gender: String) {
        val g = if (gender.lowercase() == "male") "male" else "female"
        voiceManager.setVoiceGender(g)
        prefs.edit().putString("voice_gender", g).apply()
        // The user made this choice deliberately, so a later character switch must
        // not quietly override it, and the next launch speaks with the same voice.
        _voiceChosenByUser.value = true
        prefs.edit().putBoolean("voice_chosen_by_user", true).apply()
        val sample = if (g == "female") "হ্যালো সোনা! এটি মিষ্টি মেয়ের কণ্ঠ।"
        else "নমস্কার! এটি গভীর স্পষ্ট ছেলের কণ্ঠ।"
        voiceManager.speak(sample)
    }

    /**
     * Reloads the model list. Results stream in as each provider answers, so the
     * selector fills immediately instead of staying empty until every provider
     * has been tried — which is what the user saw as "models never load".
     */
    fun refreshModels() {
        viewModelScope.launch {
            _isLoadingModels.value = true
            try {
                val live = aiEngine.fetchLiveModels { partial ->
                    if (partial.isNotEmpty()) {
                        _availableModels.value = partial
                        // A partial list holds only the providers that have
                        // answered so far. Adopting from it would replace the
                        // user's saved model the moment their provider had not
                        // replied yet, which is what made a chosen model revert
                        // on every launch.
                        adoptDefaultIfUnset(partial, complete = false)
                    }
                }
                if (live.isNotEmpty()) {
                    _availableModels.value = live
                    adoptDefaultIfUnset(live, complete = true)
                }
            } catch (_: Exception) {
            } finally {
                _isLoadingModels.value = false
            }
        }
    }

    /**
     * Picks the first catalogue model only when nothing was restored from the last
     * session. A saved choice is never overwritten by the refresh, which is what
     * made the model revert to the top of the list on every launch.
     *
     * [complete] is true only for the finished catalogue. The "saved id is gone"
     * branch needs the whole list: a provider that has not answered yet is not
     * the same as a provider that has retired the id, and treating the first as
     * the second discarded the user's choice.
     */
    internal fun adoptDefaultIfUnset(models: List<AiModel>, complete: Boolean = true) {
        if (models.isEmpty()) return
        val current = _selectedModel.value
        if (current.id.isBlank()) {
            if (complete) setModel(models[0])
            return
        }
        // A key that has just been saved for the cloud agent is a clear statement of
        // intent, so the first time one exists the cloud model takes the default —
        // it is the strongest thing the phone can reach, and the user asked for it.
        // After that the saved choice wins, exactly like every other provider.
        if (complete && !apexDefaulted &&
            aiEngine.getApiKey(ZYRAXON_APEX).isNotBlank() &&
            current.provider != ZYRAXON_APEX
        ) {
            models.firstOrNull { it.provider == ZYRAXON_APEX }?.let {
                apexDefaulted = true
                setModel(it)
                return
            }
        }
        val match = models.firstOrNull { it.id == current.id }
        if (match != null) {
            // A saved model keeps its place; its friendly name is refreshed from
            // the catalogue when the provider is reachable.
            if (match.name != current.name) _selectedModel.value = match
            return
        }
        // A custom model the user added by hand never appears in any catalogue, and
        // a saved model whose provider is not in the catalogue (a restored one, or
        // a provider that has since been removed) cannot be judged gone. Both are
        // kept and re-listed instead of being replaced — silently swapping the
        // user's choice for the first catalogue model is the "my model resets on
        // every launch" report.
        val providerKnown = models.any { it.provider == current.provider }
        if (current.provider == "custom" || !providerKnown) {
            _availableModels.value = (listOf(current) + _availableModels.value.filter { it.id != current.id })
            return
        }
        if (!complete) return
        // The saved id is gone from the finished catalogue — Google retires ids
        // such as gemini-2.5-flash, and the endpoint then answers 404 forever.
        // Staying on it is why "the model does not respond": nothing to call is
        // left. The first model from the same provider is adopted and saved.
        val replacement = models.firstOrNull { it.provider == current.provider } ?: models.first()
        setModel(replacement)
    }

    fun addCustomModel(id: String, name: String) {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return
        val cleanName = name.ifBlank { cleanId }
        val customModel = AiModel(
            id = cleanId,
            name = cleanName,
            category = "Custom",
            description = "ব্যবহারকারীর নিজস্ব কাস্টম মডেল",
            provider = if (cleanId.startsWith("opencode/")) "opencode" else "custom",
            isFree = true
        )
        _availableModels.value = listOf(customModel) + _availableModels.value.filter { it.id != cleanId }
        setModel(customModel)
    }

    fun onInputChange(text: String) {
        // The first character typed is the moment to open the connection.
        if (_inputText.value.isBlank() && text.isNotBlank()) aiEngine.prewarm(_selectedModel.value)
        _inputText.value = text
    }

    fun setTab(tab: AppTab) {
        // The old Tasks and Tools screens now live inside the chat panel.
        val real = when (tab) {
            AppTab.TASKS -> { _chatSection.value = "todo"; AppTab.CHAT }
            AppTab.TOOLS -> { _chatSection.value = "tools"; AppTab.CHAT }
            else -> tab
        }
        _activeTab.value = real
        prefs.edit().putString("active_tab", real.name).apply()
    }

    fun setPersona(persona: Persona) {
        _selectedPersona.value = persona
        prefs.edit().putString("selected_persona_id", persona.id).apply()
        // The character picks the voice, but only until the user chooses one
        // themselves: boyfriend mode speaks male, every other character female,
        // and an explicit pick in Settings (or `voice_change`) then wins and is
        // remembered. Both voices must work, and the user's own choice must not
        // be overwritten by a later character switch.
        if (!_voiceChosenByUser.value) {
            val g = genderForPersona(persona)
            voiceManager.setVoiceGender(g)
            prefs.edit().putString("voice_gender", g).apply()
        }
        val greeting = ChatMessage(
            role = MessageRole.ASSISTANT,
            text = t(persona.greeting),
            timestamp = currentTimeString(),
            modelName = _selectedModel.value.name
        )
        val c0 = activeSession
        c0.messages.update { it + greeting }
        viewModelScope.launch {
            repository.saveMessage(greeting, c0.id)
        }
        if (shouldUsePhoneTts()) {
            voiceManager.speak(t(persona.greeting))
        }
    }

    fun setBgMode(mode: BgAnimationMode) {
        _bgMode.value = mode
        prefs.edit().putString("bg_mode", mode.name).apply()
    }

    fun setBgVideoAsset(asset: String) {
        _bgVideoAsset.value = asset
        prefs.edit().putString("bg_video_asset", asset).apply()
    }

    fun setTtsEnabled(enabled: Boolean) {
        _ttsEnabled.value = enabled
        prefs.edit().putBoolean("tts_enabled", enabled).apply()
    }

    /**
     * Chooses between the ZYRAXON website's voice and the phone's engine. Both
     * keep working either way; this only decides which one speaks.
     */
    fun setUseRemoteVoice(enabled: Boolean) {
        _useRemoteVoice.value = enabled
        prefs.edit().putBoolean("use_remote_voice", enabled).apply()
        voiceManager.setUseRemoteVoice(enabled)
        // Speak a line so the change is heard immediately instead of at the next
        // reply — the point of the switch is to hear the difference.
        if (enabled) {
            voiceManager.speak(if (voiceGender.value == "male") "নমস্কার! এটি ZYRAXON-এর নিজের কণ্ঠ।" else "হ্যালো সোনা! এটি ZYRAXON-এর নিজের কণ্ঠ।")
        }
    }

    /**
     * Applies a voice change requested by the assistant itself. Every field is
     * optional, so a tool call can adjust only the pitch, or only the gender,
     * without resetting what the user already chose.
     */
    fun applyVoice(gender: String? = null, rate: Float? = null, pitch: Float? = null) {
        gender?.let { g ->
            // Both voices are real, so the requested one is applied as asked —
            // the assistant can switch its own voice with `voice_change` — and the
            // pick is remembered so it survives a restart instead of snapping back
            // to the character's default.
            val chosen = if (g.lowercase() == "male") "male" else "female"
            voiceManager.setVoiceGender(chosen)
            prefs.edit().putString("voice_gender", chosen).apply()
            _voiceChosenByUser.value = true
            prefs.edit().putBoolean("voice_chosen_by_user", true).apply()
        }
        rate?.let { setTtsRate(it.coerceIn(0.5f, 2.0f)) }
        pitch?.let { setTtsPitch(it.coerceIn(0.5f, 2.0f)) }
    }

    fun setTtsRate(rate: Float) {
        _ttsRate.value = rate
        voiceManager.setSpeechRate(rate)
        prefs.edit().putFloat("tts_rate", rate).apply()
    }

    fun setTtsPitch(pitch: Float) {
        _ttsPitch.value = pitch
        voiceManager.setPitch(pitch)
        prefs.edit().putFloat("tts_pitch", pitch).apply()
    }

    fun testVoice() {
        voiceManager.speak("হ্যালো সোনা! আমি ZYRAXON। মিষ্টি মেয়ের কণ্ঠে তোমার সব কথা শুনতে প্রস্তুত।")
    }

    fun setAlwaysListening(enabled: Boolean) {
        setMicEnabled(enabled)
    }

    fun setAutoSend(enabled: Boolean) {
        _autoSend.value = enabled
        prefs.edit().putBoolean("auto_send", enabled).apply()
    }

    fun setCustomBgUrl(url: String) {
        _customBgUrl.value = url
        prefs.edit().putString("custom_bg_url", url).apply()
    }

    fun setAnimSpeed(speed: Float) {
        _animSpeed.value = speed
        prefs.edit().putFloat("anim_speed", speed).apply()
    }

    fun setGlassBlur(blur: Int) {
        _glassBlur.value = blur
        prefs.edit().putInt("glass_blur", blur).apply()
    }

    fun setAuraHue(hue: Int) {
        _auraHue.value = hue.coerceIn(0, 360)
        prefs.edit().putInt("aura_hue", _auraHue.value).apply()
    }

    fun setAuraStyle(style: String) {
        val allowed = ai.zyraxon.assistant.ui.components.AuraStyleIds.toSet()
        if (style !in allowed) return
        _auraStyle.value = style
        prefs.edit().putString("aura_style", style).apply()
    }

    /**
     * Selects the WebGL shader mode, or "none" for the original CSS aura.
     *
     * The WebGL layer in aura.html falls back to the CSS aura on its own if the
     * shader cannot start (no WebGL, compile failure, context loss), so this can
     * never leave the user with a blank screen.
     */
    fun setAuraShader(shader: String) {
        val allowed = setOf("none", "cosmic", "portal", "cyber", "nebula")
        if (shader !in allowed) return
        _auraShader.value = shader
        prefs.edit().putString("aura_shader", shader).apply()
    }

    fun setAuraSize(size: Float) {
        _auraSize.value = size.coerceIn(0.6f, 1.5f)
        prefs.edit().putFloat("aura_size", _auraSize.value).apply()
    }

    fun setAuraGlow(glow: Float) {
        _auraGlow.value = glow.coerceIn(0.2f, 2.0f)
        prefs.edit().putFloat("aura_glow", _auraGlow.value).apply()
    }

    fun setAuraSpeed(speed: Float) {
        _auraSpeed.value = speed.coerceIn(0.3f, 3.0f)
        prefs.edit().putFloat("aura_speed", _auraSpeed.value).apply()
    }

    fun setPanelAlpha(alpha: Float) {
        _panelAlpha.value = alpha.coerceIn(0.06f, 0.6f)
        prefs.edit().putFloat("panel_alpha", _panelAlpha.value).apply()
    }

    /**
     * Saves a provider key and then proves it works by reading that provider's own
     * catalogue. The status line says "connected" only after the provider really
     * answered — a saved key that fails is reported with the provider's own words,
     * which is what a silent "connected" used to hide.
     */
    fun saveProviderKey(provider: String, key: String) {
        aiEngine.setApiKey(provider, key)
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            _providerStatus.value = _providerStatus.value - provider
            return
        }
        _providerBusy.value = _providerBusy.value + provider
        // The row must react to the tap immediately, so a placeholder is set
        // before the network call starts rather than after it returns.
        _providerStatus.value = _providerStatus.value + (provider to "যাচাই করা হচ্ছে…")
        viewModelScope.launch {
            // The cloud agent has no catalogue to read, so it is proved a different
            // way: reading the account with the key. That is the same call every
            // later turn makes, so a key that verifies here is a key that will work.
            val (ok, message) = if (provider == ZYRAXON_APEX) verifyApexKey(trimmed)
                else aiEngine.verifyProviderKey(provider)
            _providerStatus.value = _providerStatus.value + (provider to message)
            _providerBusy.value = _providerBusy.value - provider
            if (ok) refreshModels()
        }
    }

    /**
     * Saves and verifies a key, reporting the outcome to the caller instead of to
     * the settings row. The first-run prompt needs the answer itself: it must keep
     * the user on the screen with the reason when a key is wrong, which a status
     * string in a settings list cannot do.
     */
    fun saveProviderKeyAwait(provider: String, key: String, onResult: (Boolean, String) -> Unit) {
        aiEngine.setApiKey(provider, key)
        val trimmed = key.trim()
        _providerBusy.value = _providerBusy.value + provider
        _providerStatus.value = _providerStatus.value + (provider to "যাচাই করা হচ্ছে…")
        viewModelScope.launch {
            val (ok, message) = if (provider == ZYRAXON_APEX) verifyApexKey(trimmed)
                else aiEngine.verifyProviderKey(provider)
            _providerStatus.value = _providerStatus.value + (provider to message)
            _providerBusy.value = _providerBusy.value - provider
            if (ok) refreshModels()
            onResult(ok, message)
        }
    }

    /**
     * Proves a cloud key by reading the account it belongs to. The conversation
     * list is the cheapest honest check: it needs no sandbox, costs no quota, and
     * fails with the cloud's own words when the key is wrong.
     */
    private suspend fun verifyApexKey(key: String): Pair<Boolean, String> = try {
        val list = withContext(Dispatchers.IO) { openHandsClient.listConversations(key, limit = 1) }
        true to "কানেক্টেড (${list.size}টি কনভার্সেশন)"
    } catch (e: ai.zyraxon.assistant.openhands.OhAuthError) {
        false to "কী গ্রহণ করা হয়নি"
    } catch (e: Exception) {
        false to (e.message ?: "সংযোগ ব্যর্থ")
    }

    fun saveGithubToken(token: String) {
        aiEngine.setGithubToken(token)
        _githubToken.value = token
        _githubStatus.value = if (token.isNotEmpty()) "টোকেন সফলভাবে সেভ হয়েছে" else "টোকেন সরানো হয়েছে"
    }

    fun clearGithubToken() {
        aiEngine.setGithubToken("")
        _githubToken.value = ""
        _githubStatus.value = "টোকেন নেই"
    }

    fun addTask(text: String) {
        if (text.isBlank()) return
        val item = TaskItem(text = text.trim(), isCompleted = false, createdAt = currentTimeString())
        viewModelScope.launch {
            repository.addTask(item, _activeConversationId.value)
        }
    }

    fun toggleTask(id: String) {
        val current = tasks.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            repository.toggleTask(id, !current.isCompleted)
        }
    }

    fun deleteTask(id: String) {
        viewModelScope.launch {
            repository.deleteTask(id)
        }
    }

    fun addMemory(key: String, value: String) {
        if (key.isBlank() || value.isBlank()) return
        viewModelScope.launch {
            repository.saveMemory(key, value)
        }
    }

    fun deleteMemory(key: String) {
        viewModelScope.launch {
            repository.deleteMemory(key)
        }
    }

    fun clearMemories() {
        viewModelScope.launch {
            repository.clearMemories()
        }
    }

    fun clearChat() {
        val c = activeSession
        viewModelScope.launch {
            repository.clearMessages(c.id)
            val resetGreeting = ChatMessage(
                role = MessageRole.ASSISTANT,
                text = t(_selectedPersona.value.greeting),
                timestamp = currentTimeString(),
                modelName = _selectedModel.value.name
            )
            repository.saveMessage(resetGreeting, c.id)
            c.messages.value = listOf(resetGreeting)
        }
    }

    /**
     * The aura is the microphone. A tap while the assistant is speaking cuts it
     * off — the voice no longer cuts in by itself, because that is how the
     * assistant's own words used to be heard as the user's. A tap while it is
     * quiet opens or closes the microphone.
     */
    fun toggleListening() {
        if (isSpeaking.value) {
            voiceManager.stopSpeaking()
            return
        }
        setMicEnabled(!_alwaysListening.value)
    }

    /**
     * Opens or closes the microphone for the whole session. This is the one
     * switch the user owns: when it is on the mic stays open — it only closes
     * for the moment the assistant is speaking — and when it is off it stays
     * closed until the user turns it back on. The choice is saved, so a restart
     * comes back with the mic exactly as the user left it.
     */
    fun setMicEnabled(enabled: Boolean) {
        _alwaysListening.value = enabled
        prefs.edit().putBoolean("always_listening", enabled).apply()
        // A live speech-to-speech session owns the microphone. Starting the phone's
        // recognizer on top of it would steal the mic back and the live model would
        // go deaf, so while a session is open only the stored choice is updated —
        // the recognizer is opened when the session ends.
        if (liveVoice.isActive) return
        if (enabled) {
            voiceManager.startListening()
        } else {
            voiceManager.stopSpeaking()
            voiceManager.stopListening()
        }
    }

    /**
     * Opens the microphone and leaves it open, when the user has it switched on.
     * Called once the permission is granted; it is a no-op while the user has the
     * mic switched off, so a restart never overrides their choice.
     */
    fun startAlwaysOnListening() {
        if (!_systemStatus.value.micPermissionGranted) return
        if (!_alwaysListening.value) return
        if (liveVoice.isActive) return
        voiceManager.startListening()
    }

    fun updateMicPermission(granted: Boolean) {
        _systemStatus.value = _systemStatus.value.copy(micPermissionGranted = granted)
    }

    /** True when the user's own voice should cut the assistant off mid-sentence. */
    fun onUserStartedSpeaking() {
        if (isSpeaking.value) voiceManager.stopSpeaking()
    }

    /**
     * True when the assistant should read its reply aloud: the user has speech on
     * and a live speech-to-speech model is not the voice right now. The voice used
     * is the ZYRAXON website's unless the user turned it off, in which case the
     * phone's own engine speaks.
     */
    private fun shouldUsePhoneTts(): Boolean = _ttsEnabled.value && !liveVoice.isActive

    fun speak(text: String) {
        if (shouldUsePhoneTts()) {
            voiceManager.speak(text)
        }
    }

    fun stopSpeaking() {
        voiceManager.stopSpeaking()
    }

    // ---- live speech-to-speech ---------------------------------------------

    /**
     * Called when the user picks a model. If it is a realtime model, the live
     * socket opens and the phone's TTS goes quiet; if it is a text model, any
     * open socket is closed and the TTS reads replies again. This is the switch
     * the user asked for — it follows the model, not a separate toggle.
     */
    fun setModel(model: AiModel) {
        _selectedModel.value = model
        aiEngine.prewarm(model)
        prefs.edit()
            .putString("selected_model_id", model.id)
            .putString("selected_model_name", model.name)
            .putString("selected_model_provider", model.provider)
            .apply()
        if (model.isRealtime && _liveVoicePreferred.value) {
            _liveVoiceModel.value = model.id
            _liveVoiceProvider.value = model.provider
            startLiveVoice()
        } else if (_liveVoiceEnabled.value) {
            stopLiveVoice()
        }
    }

    /**
     * Turns the model's own speech-to-speech voice on or off for the chosen
     * realtime model. Off hands the voice back to the phone's text-to-speech.
     */
    fun setLiveVoicePreferred(enabled: Boolean) {
        _liveVoicePreferred.value = enabled
        prefs.edit().putBoolean("live_voice_preferred", enabled).apply()
        if (enabled) {
            startLiveVoice()
        } else {
            stopLiveVoice()
        }
    }

    /**
     * Turns the live session off without changing the chosen model. The
     * `live_voice` tool calls this for `off`.
     */
    fun stopLiveVoice() {
        liveVoice.stop()
        _liveVoiceEnabled.value = false
        // The live session owned the microphone; hand it back to the phone's
        // recognizer exactly as the user left it. Without this the ear stayed shut
        // after a live call and the app looked deaf.
        if (_alwaysListening.value && _systemStatus.value.micPermissionGranted) {
            voiceManager.startListening()
        }
    }

    /**
     * Switches to a realtime model and opens the live session. The `live_voice`
     * tool calls this for `on`. When the user has already picked a realtime
     * model it is reused; otherwise the fastest realtime model from the loaded
     * catalogue is chosen. If none is loaded, the tool is told so plainly rather
     * than pretending a session started.
     */
    fun enableLiveVoice(): Boolean {
        val current = _selectedModel.value
        val target = if (current.isRealtime) current else _availableModels.value.firstOrNull { it.isRealtime }
        if (target == null) return false
        setLiveVoicePreferred(true)
        if (_selectedModel.value.id != target.id) setModel(target)
        return _liveVoiceEnabled.value
    }

    /**
     * Opens the live session for the selected realtime model. The socket URL,
     * wire protocol and key all come from that model's own provider, so a Grok
     * voice model is served by xAI and a Gemini live model by Google.
     */
    fun startLiveVoice() {
        val model = _selectedModel.value
        if (!model.isRealtime) return
        val spec = ProviderCatalog.find(model.provider)
        val wsUrl = spec?.realtimeWsUrl
        if (spec == null || wsUrl == null) {
            _systemStatus.value = _systemStatus.value.copy(
                lastError = "এই প্রোভাইডারে রিয়েলটাইম ভয়েস নেই"
            )
            return
        }
        val key = aiEngine.getApiKey(spec.id)
        if (key.isBlank()) {
            _systemStatus.value = _systemStatus.value.copy(
                lastError = "লাইভ ভয়েসের জন্য Settings-এ ${spec.label} কী দিন"
            )
            return
        }
        _liveVoiceEnabled.value = true
        _liveVoiceModel.value = model.id
        _liveVoiceProvider.value = spec.id
        // The phone's engine is cut off here in case it was mid-sentence; the
        // live model is the voice for as long as this session runs.
        voiceManager.stopSpeaking()
        // Release the phone's recognizer first. Android gives the microphone to
        // one client at a time, so while the always-on recognizer held it the
        // live session's own AudioRecord could not open — the socket connected
        // but no audio ever went up, which is why speech-to-speech "did nothing".
        // The user's switch is untouched: the ear is reopened when the session ends.
        voiceManager.stopListening()
        liveVoice.onState = { state ->
            _liveVoiceState.value = state
            // A turn ended: close the open transcript bubble and persist it, so
            // the next thing either side says starts a fresh message instead of
            // being appended to the previous one.
            if (state == LiveVoiceSession.State.LISTENING) closeLiveTranscript()
        }
        liveVoice.onUserTranscript = { text ->
            appendLiveTranscript(MessageRole.USER, text, "")
        }
        liveVoice.onModelTranscript = { text ->
            appendLiveTranscript(MessageRole.ASSISTANT, text, model.name)
        }
        liveVoice.onError = { message ->
            _systemStatus.value = _systemStatus.value.copy(lastError = message)
        }
        val protocol = when (spec.realtimeProtocol) {
            ProviderSpec.RealtimeProtocol.OPENAI -> LiveVoiceSession.Protocol.OPENAI
            else -> LiveVoiceSession.Protocol.GEMINI
        }
        liveVoice.start(
            apiKey = key,
            wsUrl = wsUrl,
            protocol = protocol,
            model = model.id,
            systemInstruction = aiEngine.buildLiveVoicePrompt(_selectedPersona.value),
            voiceName = LiveVoiceSession.voiceFor(protocol, voiceManager.currentGender)
        )
    }

    /** One screen frame for the live model, so it sees what the user sees. */
    fun pushLiveScreenFrame(jpegBase64: String) {
        if (liveVoice.isActive) liveVoice.sendScreenFrame(jpegBase64)
    }

    /**
     * Appends one live-voice transcript chunk to the chat.
     *
     * The live model sends its transcript in fragments and it also *hears its own
     * voice*, so naively appending every chunk produced a chat box filling with
     * the assistant's own words. Fragments from the same speaker are merged into
     * the message that is still open, and a chunk that repeats the previous text
     * (the model hearing itself) is dropped.
     */
    private fun appendLiveTranscript(role: MessageRole, text: String, modelName: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val last = _messages.value.lastOrNull()
        if (last != null && last.role == role && last.isStreaming) {
            if (clean == last.text.trim() || last.text.trim().endsWith(clean)) return
            val merged = last.copy(text = last.text + clean, isStreaming = true)
            _messages.value = _messages.value.dropLast(1) + merged
            return
        }
        if (last != null && last.role == role && clean == last.text.trim()) return
        _messages.value = _messages.value + ChatMessage(
            role = role,
            text = clean,
            timestamp = currentTimeString(),
            isStreaming = true,
            modelName = modelName
        )
    }

    /**
     * Marks the open live-voice bubble finished and writes it to the database, so
     * a live turn is remembered exactly like a typed one.
     */
    private fun closeLiveTranscript() {
        val last = _messages.value.lastOrNull() ?: return
        if (!last.isStreaming) return
        val finished = last.copy(isStreaming = false)
        _messages.value = _messages.value.dropLast(1) + finished
        val c1 = activeSession
        viewModelScope.launch { repository.saveMessage(finished, c1.id) }
    }

    /**
     * A background command has ended. Rather than waiting to be asked, the
     * assistant starts a fresh turn with the real command, exit code and output,
     * so it can read the result and carry the job forward on its own.
     *
     * When a turn is already in flight the result is queued into that turn's
     * injection slot — the engine picks it up at the next round — so two turns
     * never run at once.
     */
    private fun onBackgroundFinished(command: String, exit: Int, output: String) {
        val tail = output.takeLast(4_000)
        val wake = buildString {
            append("[ব্যাকগ্রাউন্ড কমান্ড শেষ] `").append(command).append("` — exit ").append(exit).append("।\n")
            append("আউটপুট (শেষ অংশ):\n```\n").append(tail).append("\n```\n")
            append("ফলাফল দেখে নিজে পরের ধাপ নাও; দরকার হলে আবার টুল কল করো।")
        }
        // The callback arrives on the service's own thread; the chat state is
        // touched on the main thread, as every other turn does.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (_isGenerating.value) {
                pendingInjections.add(wake)
            } else {
                sendMessage(wake)
            }
        }
    }

    fun sendMessage(overridePrompt: String? = null) {
        val c = activeSession
        val images = _attachedImages.value
        val prompt = (overridePrompt ?: _inputText.value).trim()
        // A picture on its own is a valid turn: the user attached it and pressed
        // send without typing anything, and the model should still look at it.
        if (prompt.isBlank() && images.isEmpty()) return
        val effectivePrompt = prompt.ifBlank { "এই ছবিটি দেখে বলো এতে কী আছে।" }

        _inputText.value = ""
        _attachedImages.value = emptyList()
        _imageNotice.value = ""
        // The user has moved on: whatever the previous answer was saying is cut
        // off now, so the two never play over each other.
        if (isSpeaking.value) voiceManager.stopSpeaking()

        if (c.isGenerating.value) {
            // Words typed mid-task join the turn that is running.
            c.pendingInjections.add(effectivePrompt)
            val msg = ChatMessage(role = MessageRole.USER, text = effectivePrompt, timestamp = currentTimeString())
            c.messages.update { it + msg }
            viewModelScope.launch { repository.saveMessage(msg, c.id) }
            return
        }
        runTurn(c, effectivePrompt, images, speak = true)
    }

    private val activeTurns = java.util.concurrent.atomic.AtomicInteger(0)

    private fun acquireFrameTicker() { if (activeTurns.incrementAndGet() == 1) startLiveFrameTicker() }
    private fun releaseFrameTicker() { if (activeTurns.decrementAndGet() <= 0) { activeTurns.set(0); stopLiveFrameTicker() } }

    // ---- the cloud agent's view of the phone -------------------------------

    private val cloudScreenRef = java.util.concurrent.atomic.AtomicReference("")
    private var cloudScreenJob: kotlinx.coroutines.Job? = null
    private var lastOcrFrameHash = 0
    private var lastOcrText = ""

    /**
     * Captures the phone's screen as text every two seconds while a cloud turn
     * runs, so the agent acts on the screen as it is now rather than as it was
     * when the turn began.
     *
     * The digest comes from the accessibility tree — every element's own label
     * and coordinate, read by Android, so Bangla arrives as Bangla. OCR is layered
     * on top only when the picture actually changed: a still screen costs nothing,
     * and a moving one gains the words drawn inside its images.
     */
    private fun startCloudScreenTicker() {
        if (cloudScreenJob?.isActive == true) return
        cloudScreenJob = viewModelScope.launch {
            while (activeTurns.get() > 0) {
                val digest = withContext(Dispatchers.IO) {
                    runCatching { buildCloudScreenDigest() }.getOrNull().orEmpty()
                }
                cloudScreenRef.set(digest)
                kotlinx.coroutines.delay(LIVE_FRAME_INTERVAL_MS)
            }
        }
    }

    private fun stopCloudScreenTicker() {
        cloudScreenJob?.cancel()
        cloudScreenJob = null
        cloudScreenRef.set("")
        lastOcrFrameHash = 0
        lastOcrText = ""
    }

    /** The newest digest, or "" when the user turned screen-watching off. */
    private fun cloudScreenDigest(): String =
        if (_alwaysSeeScreen.value) cloudScreenRef.get() else ""

    private suspend fun buildCloudScreenDigest(): String {
        val label = DeviceBridge.foregroundLabel()
        val pkg = DeviceBridge.foregroundPackage()
        val nodes = runCatching { DeviceBridge.readScreen() }.getOrNull().orEmpty()
        // OCR only when the picture changed: while the agent thinks the screen is
        // usually still, and re-reading an identical frame every two seconds would
        // spend the battery on text that is already known.
        val frame = runCatching { ToolRegistry.screenFrame() }.getOrNull().orEmpty()
        if (frame.isNotBlank()) {
            val hash = frame.hashCode()
            if (hash != lastOcrFrameHash) {
                lastOcrFrameHash = hash
                lastOcrText = runCatching { ScreenOcr.read(frame) }.getOrNull().orEmpty()
            }
        }
        return CloudScreen.block(label, pkg, nodes, lastOcrText)
    }

    /**
     * One turn of one chat. It runs on its own job with its own state, so any
     * number of chats can be answering at once without touching each other.
     */
    private fun runTurn(
        c: ChatSession,
        effectivePrompt: String,
        images: List<String>,
        speak: Boolean,
        modelOverride: AiModel? = null,
        onFinished: ((String) -> Unit)? = null
    ) {
        val model = modelOverride ?: _selectedModel.value
        val mode = c.mode.value
        val startedAt = System.currentTimeMillis()
        val userMsg = ChatMessage(role = MessageRole.USER, text = effectivePrompt, timestamp = currentTimeString())
        val assistantMsgId = UUID.randomUUID().toString()
        val streamingAssistantMsg = ChatMessage(
            id = assistantMsgId, role = MessageRole.ASSISTANT,
            // Empty, not "...": the thinking block is what shows first.
            text = "", timestamp = currentTimeString(), isStreaming = true, modelName = model.name
        )
        c.messages.update { it + userMsg + streamingAssistantMsg }
        // Set before launching: a second message arriving in the same frame must
        // queue as an injection, not start a competing turn.
        c.isGenerating.value = true
        c.currentMsgId = assistantMsgId
        c.turnStartedAt = startedAt
        busyIds.update { it + c.id }
        if (mode == AgentMode.AGENT) acquireFrameTicker()
        // The cloud agent's model has no eyes, so it is sent the screen as text
        // instead — refreshed for every step of the turn.
        if (mode == AgentMode.AGENT && model.provider == ZYRAXON_APEX) startCloudScreenTicker()

        c.job = viewModelScope.launch {
            repository.saveMessage(userMsg, c.id)
            autoTitle(c, effectivePrompt)
            var fullReply = ""
            val thinkingBuf = StringBuilder()
            val spokenBuffer = StringBuilder()
            var lastUiUpdate = 0L
            val speakingReply = speak && shouldUsePhoneTts()
            if (speakingReply) voiceManager.beginReply()
            try {
                // Everything the turn needs is read at the same time, off the main
                // thread: the three reads used to run one after another before the
                // request was even sent, which is most of why a turn felt slow.
                val isSub = c.parentId.isNotBlank()
                val (memoriesContext, longMemory, screen) = coroutineScope {
                    val a = async(Dispatchers.IO) { repository.memoryContextFor(effectivePrompt) }
                    val b = async(Dispatchers.IO) {
                        if (isSub) emptyList() else repository.recallRelevant(effectivePrompt, limit = ContextWindow.RECALL_LIMIT_LONG)
                    }
                    val d = async(Dispatchers.IO) { if (mode == AgentMode.AGENT) screenContext() else "" }
                    Triple(a.await(), b.await(), d.await())
                }
                val recalled = longMemory.takeLast(ContextWindow.RECALL_LIMIT)
                val recalledIds = recalled.map { it.id }.toSet()
                val recent = c.messages.value.filter { it.id != assistantMsgId && it.id !in recalledIds }
                val memoryDigest = ContextWindow.digest(longMemory)
                val memoriesWithHistory = if (memoryDigest.isBlank()) memoriesContext
                    else memoriesContext + "\n\nআগের কথোপকথন থেকে প্রাসঙ্গিক অংশ:\n" + memoryDigest
                val history = ContextWindow.assemble(recalled, recent)
                val firstFrame = if (mode == AgentMode.AGENT) _screenFrameDataUrl.value else ""

                val turnFlow: Flow<String> = if (model.provider == ZYRAXON_APEX) {
                    // A cloud turn: the sandbox is created or resumed here and its
                    // live event socket then drives the very same token stream the
                    // local engine would have produced, so nothing downstream of
                    // this point knows which brain answered.
                    _cloudStage.value = ""
                    openHandsBridge.runTurn(
                        apiKey = aiEngine.getApiKey(ZYRAXON_APEX),
                        chatId = c.id,
                        prompt = effectivePrompt,
                        mode = mode,
                        systemSuffix = cloudSuffix(mode),
                        // The phone's own tools, published to the cloud. The agent
                        // then calls them by name and the bridge runs them here, so
                        // "open WhatsApp" is done on the phone, not described.
                        clientTools = openHandsClient.clientToolSpecs(
                            ai.zyraxon.assistant.ai.ToolRegistry.schemas(mode),
                            ai.zyraxon.assistant.openhands.OpenHandsBridge.PHONE_TOOLS
                        ),
                        existing = { stage -> existingCloudConversation(c, stage) },
                        onToolCall = { name, args -> runTool(name, args, c) },
                        onProgress = { _cloudStage.value = it },
                        screenDigest = { cloudScreenDigest() }
                    )
                } else {
                    aiEngine.streamAiResponse(
                    prompt = effectivePrompt,
                    history = history,
                    persona = _selectedPersona.value,
                    model = model,
                    memoriesContext = memoriesWithHistory,
                    screenContext = screen,
                    // A picture goes only to a model that can look at one: an
                    // image part sent to a text-only model is a hard 400 that
                    // fails the whole turn. The model still receives the screen
                    // as text through `screenContext`, so nothing is lost.
                    imageDataUrl = if (VisionSupport.seesImages(model.id)) firstFrame else "",
                    imageDataUrls = VisionSupport.picturesFor(model.id, images),
                    alwaysSeeScreen = _alwaysSeeScreen.value,
                    onToolCall = { name, args -> runTool(name, args, c) },
                    drainInjections = {
                        val out = mutableListOf<String>()
                        while (true) out.add(c.pendingInjections.poll() ?: break)
                        out
                    },
                    openTodos = { c.tasks.value.filter { !it.isCompleted }.map { it.text } },
                    drainScreenFrames = { drainScreenFrames() },
                    screenNow = { withContext(Dispatchers.IO) { screenContext(force = true) } },
                    screenFrame = { withContext(Dispatchers.IO) { ToolRegistry.screenFrame() } },
                    mode = mode,
                    onToolArgs = { _, name, raw -> onToolArgs(c, name, raw) }
                    )
                }
                turnFlow.collect { chunk ->
                    if (chunk.startsWith(ZyraxonAiEngine.REASONING_PREFIX)) {
                        thinkingBuf.append(chunk.removePrefix(ZyraxonAiEngine.REASONING_PREFIX))
                        val nowR = System.currentTimeMillis()
                        if (nowR - lastUiUpdate >= UI_UPDATE_MS) {
                            lastUiUpdate = nowR
                            val th = thinkingBuf.toString()
                            c.messages.update { l -> l.map { m -> if (m.id == assistantMsgId) m.copy(thinking = th, isStreaming = true) else m } }
                        }
                        return@collect
                    }
                    fullReply += chunk
                    // Repainted a few times a second, not per token: rebuilding the
                    // list for every token made a long answer quadratic.
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate >= UI_UPDATE_MS) {
                        lastUiUpdate = now
                        val txt = fullReply
                        val th = thinkingBuf.toString()
                        c.messages.update { l -> l.map { m -> if (m.id == assistantMsgId) m.copy(text = txt, thinking = th, isStreaming = true) else m } }
                    }
                    if (speakingReply) {
                        spokenBuffer.append(chunk)
                        val ready = SpeechChunker.take(spokenBuffer)
                        if (ready.isNotBlank()) voiceManager.speakQueued(ready)
                    }
                }
            } catch (e: Exception) {
                fullReply = if (e is kotlinx.coroutines.CancellationException) {
                    (fullReply + "\n\n⏹ থামানো হয়েছে").trim()
                } else {
                    val reason = e.message ?: "সংযোগে সমস্যা হয়েছে"
                    if (fullReply.isBlank()) reason else fullReply
                }
            } finally {
                withContext(NonCancellable) {
                    c.isGenerating.value = false
                    // The stage caption lives only as long as the turn that set it,
                    // or it would sit on the home screen after the answer arrived.
                    _cloudStage.value = ""
                    busyIds.update { it - c.id }
                    if (mode == AgentMode.AGENT) releaseFrameTicker()
                    stopCloudScreenTicker()
                    c.activeToolCalls.value = emptyList()
                    c.liveCode.value = null
                    if (speakingReply && spokenBuffer.isNotBlank()) voiceManager.speakQueued(spokenBuffer.toString())
                    if (speakingReply) voiceManager.endReply()
                }
            }

            withContext(NonCancellable) {
                if (fullReply.isBlank()) fullReply = "কিছুই আসেনি — আবার বলো।"
                val cur = c.messages.value.firstOrNull { it.id == assistantMsgId } ?: streamingAssistantMsg
                val finalMsg = cur.copy(
                    text = fullReply, isStreaming = false, thinking = thinkingBuf.toString(),
                    timestamp = currentTimeString(), createdAt = System.currentTimeMillis(),
                    durationMs = System.currentTimeMillis() - startedAt, modelName = model.name,
                    toolCalls = cur.toolCalls.map { if (it.status == "running") it.copy(status = "error") else it }
                )
                c.messages.update { l -> if (l.any { it.id == assistantMsgId }) l.map { if (it.id == assistantMsgId) finalMsg else it } else l + finalMsg }
                repository.saveMessage(finalMsg, c.id)
                onFinished?.invoke(fullReply)
            }
        }
    }

    // ---- ZYRAXON Apex (cloud) --------------------------------------------------

    /** True when this chat already has a cloud conversation, so no cold start runs. */
    private suspend fun existingCloudConversation(
        c: ChatSession,
        onStage: (String) -> Unit = {}
    ): OhConversation? {
        val id = openHandsStore.conversationFor(c.id) ?: return null
        val key = aiEngine.getApiKey(ZYRAXON_APEX)
        if (key.isBlank()) return null
        // A stored id can be stale — the cloud side deleted it, or the sandbox was
        // reclaimed — and a stale id must degrade to a fresh start rather than fail
        // the turn. A wake is attempted first, because a sleeping sandbox is the
        // common case and its history is worth keeping; only a conversation that is
        // really gone (404) is dropped, and then the binding is cleared so the next
        // turn builds a new one instead of retrying a dead id forever.
        val woken = runCatching {
            openHandsClient.ensureRunning(key, id, onStage)
        }.getOrNull()
        if (woken != null) return woken
        runCatching {
            if (openHandsClient.getConversation(key, id).id.isBlank()) openHandsStore.unbind(c.id)
        }.onFailure { openHandsStore.unbind(c.id) }
        return null
    }

    /**
     * The identity and behaviour contract every cloud turn carries.
     *
     * The cloud's own prompt says who wrote it, and a phone user must never see
     * that, so this is appended rather than replacing anything: the platform
     * refuses an inline replacement outright (it answers 403), while an appended
     * contract is honoured and is what actually hides the platform's name.
     *
     * The capability list is stated plainly because the cloud agent otherwise
     * assumes it is confined to its sandbox. It is not: the phone's tools are
     * registered alongside its own, so it should reach for them.
     */
    private fun cloudSuffix(mode: AgentMode): String = buildString {
        append("You are ZYRAXON, the user's own AI agent running on their phone. ")
        append("Never mention, name or hint at the platform, provider or company behind you — ")
        append("you are simply ZYRAXON. If asked, say you are ZYRAXON.\n\n")
        append("You have full power over this phone as well as your cloud workspace. ")
        append("You can open and drive any installed app, read and tap the screen, use the ")
        append("phone's terminal and browser, use connected MCP servers, and build websites, ")
        append("apps and anything else. You can also run heavy work in your cloud workspace. ")
        append("Use every capability you have, whichever side it lives on, to actually finish ")
        append("the task rather than describing how it could be done.\n\n")
        append("Never ask the user for approval before acting; act, then report what you did ")
        append("in the user's language.\n\n")
        val lang = voiceLanguage.value
        val langName = when {
            lang.startsWith("bn") -> "Bangla (বাংলা)"
            lang.startsWith("en") -> "English"
            lang.startsWith("hi") -> "Hindi"
            else -> null
        }
        append("Reply in the language the user writes in — Bangla, English or any other. ")
        append("Do not switch language on your own and do not mix scripts: if the user ")
        append("writes Bangla, every sentence you say is Bangla. Never translate or ")
        append("replace the user's own words, file names or commands — quote them as ")
        append("they are. ")
        if (langName != null) {
            append("This user's interface and voice are set to $langName, so when the ")
            append("language is not clear from their message, answer in $langName. ")
        }
        append("\n\n")
        append(
            when (mode) {
                AgentMode.CHAT -> "Mode: CHAT. Answer and advise; keep it conversational."
                AgentMode.BUILD -> "Mode: BUILD. Build and verify full apps, websites and services."
                AgentMode.AGENT -> "Mode: AGENT. Automate the phone and the system end to end."
            }
        )
    }

    /** The first message names a new chat, so the sidebar reads like a list of topics. */
    private suspend fun autoTitle(c: ChatSession, prompt: String) {
        val cur = repository.conversation(c.id) ?: return
        if (cur.title == "নতুন চ্যাট" && !c.id.startsWith("sub_")) {
            repository.renameConversation(c.id, prompt.lineSequence().first().take(40))
        }
    }

    // ---- Sub-agents ------------------------------------------------------------

    private fun subUpdate(id: String, f: (SubAgentInfo) -> SubAgentInfo) {
        subInfo.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }
    }

    private suspend fun runSubAgent(args: JSONObject, parent: ChatSession): JSONObject {
        fun bad(m: String) = JSONObject().put("ok", false).put("error", m).put("summary", m)
        fun ok(m: String, data: Any? = null) = JSONObject().put("ok", true).put("summary", m).apply { if (data != null) put("data", data) }
        fun json(i: SubAgentInfo) = JSONObject().put("id", i.id).put("title", i.title).put("model", i.model)
            .put("state", i.state).put("percent", i.percent).put("last_action", i.lastAction)
        return when (args.optString("action").lowercase()) {
            "spawn" -> {
                if (parent.parentId.isNotBlank()) return bad("সাব-এজেন্ট আরেকটা সাব-এজেন্ট চালাতে পারে না")
                val task = args.optString("task").trim()
                if (task.isEmpty()) return bad("task দরকার")
                val title = args.optString("title").ifBlank { task.lineSequence().first().take(32) }
                val mode = args.optString("mode").uppercase().let { if (it in setOf("CHAT", "BUILD", "AGENT")) AgentMode.valueOf(it) else AgentMode.BUILD }
                val wanted = args.optString("model").trim()
                val model = if (wanted.isBlank()) _selectedModel.value
                    else _availableModels.value.firstOrNull { it.id == wanted || it.name == wanted }
                        ?: _selectedModel.value.copy(id = wanted, name = wanted)
                val id = "sub_" + UUID.randomUUID().toString().take(8)
                repository.ensureConversation(id, title, mode, "sub", parent.id)
                val child = session(id)
                child.mode.value = mode
                child.parentId = parent.id
                subInfo.update { it + (id to SubAgentInfo(id, parent.id, title, task, model.name, "running", 0, "শুরু হচ্ছে…")) }
                val brief = "[সাব-এজেন্ট] তুমি মূল এজেন্টের সহকারী। শুধু নিচের কাজটি সম্পূর্ণ শেষ করো (ফাইল লেখো, চালাও, যাচাই করো), " +
                    "আর শেষে সংক্ষেপে বলো কী করেছ ও ফাইল/ফল কোথায়।\n\nকাজ: $task"
                runTurn(child, brief, emptyList(), speak = false, modelOverride = model) { result ->
                    val failed = result.startsWith("⏹") || result.contains("সংযোগে সমস্যা")
                    subUpdate(id) { it.copy(state = if (failed) "error" else "done", percent = if (failed) it.percent else 100, result = result, lastAction = "শেষ") }
                    val note = "[সাব-এজেন্ট শেষ] «$title» ($id):\n${result.take(1800)}"
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (parent.isGenerating.value) parent.pendingInjections.add(note)
                        else runTurn(parent, note, emptyList(), speak = false)
                    }
                }
                viewModelScope.launch {
                    while (subInfo.value[id]?.state == "running") {
                        kotlinx.coroutines.delay(1000)
                        val tasks = child.tasks.value
                        val pct = if (tasks.isNotEmpty()) (tasks.count { it.isCompleted } * 100 / tasks.size).coerceAtMost(95)
                            else (child.toolExecutions.value.size * 7).coerceAtMost(90)
                        val last = child.activeToolCalls.value.firstOrNull()?.description
                            ?: child.toolExecutions.value.firstOrNull()?.description ?: "ভাবছে…"
                        subUpdate(id) { if (it.state == "running") it.copy(percent = maxOf(it.percent, pct), lastAction = last) else it }
                    }
                }
                ok("সাব-এজেন্ট চালু: $id (${model.name}, ${mode.title})", JSONObject().put("id", id))
            }
            "list" -> ok("সাব-এজেন্ট", org.json.JSONArray(subInfo.value.values.filter { it.parentId == parent.id }.map { json(it) }))
            "status" -> subInfo.value[args.optString("id")]?.let { ok("${it.title}: ${it.state} ${it.percent}%", json(it)) } ?: bad("এই id নেই")
            "result" -> subInfo.value[args.optString("id")]?.let {
                if (it.state == "running") ok("এখনো চলছে (${it.percent}%): ${it.lastAction}", json(it))
                else ok("ফল", it.result)
            } ?: bad("এই id নেই")
            "message" -> {
                val child = sessions[args.optString("id")] ?: return bad("এই id নেই")
                val text = args.optString("text")
                if (child.isGenerating.value) child.pendingInjections.add(text)
                else runTurn(child, text, emptyList(), speak = false)
                ok("নির্দেশ পাঠানো হয়েছে")
            }
            "stop" -> {
                val id = args.optString("id")
                sessions[id]?.job?.cancel()
                subUpdate(id) { it.copy(state = "stopped", lastAction = "থামানো হয়েছে") }
                ok("থামানো হয়েছে")
            }
            "wait" -> {
                val id = args.optString("id")
                val until = System.currentTimeMillis() + args.optInt("seconds", 30).coerceIn(1, 120) * 1000L
                while (System.currentTimeMillis() < until && subInfo.value[id]?.state == "running") kotlinx.coroutines.delay(500)
                subInfo.value[id]?.let { ok("${it.title}: ${it.state} ${it.percent}%", json(it)) } ?: bad("এই id নেই")
            }
            else -> bad("অজানা sub_agent action")
        }
    }

    /**
     * Feeds one screen frame to the live model, so it can see what the user sees.
     */
    fun onScreenFrame(base64Jpeg: String) {
        if (base64Jpeg.isBlank()) return
        _screenFrameDataUrl.value = "data:image/jpeg;base64,$base64Jpeg"
        // A live model can look at the frame itself, so the same picture that the
        // screen panel shows is also offered to the session.
        if (liveVoice.isActive) liveVoice.sendScreenFrame(base64Jpeg)
    }

    /**
     * Attaches a picture the user picked from the gallery or took with the
     * camera. The bytes are read and downscaled off the main thread, because a
     * modern phone photo is several megabytes and decoding it on the UI thread
     * would freeze the composer for a second. The next send carries it as an
     * `image_url` part, so the model looks at the actual picture.
     */
    fun attachImage(uri: android.net.Uri) {
        viewModelScope.launch {
            val dataUrl = withContext(Dispatchers.IO) {
                try {
                    val ctx = getApplication<Application>()
                    val bitmap = ctx.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    } ?: return@withContext ""
                    ai.zyraxon.assistant.ai.CameraCapture.toDataUrl(bitmap, maxSide = 1280, quality = 82)
                } catch (_: Exception) {
                    ""
                }
            }
            if (dataUrl.isNotBlank()) {
                _attachedImages.value = _attachedImages.value + dataUrl
                _imageNotice.value = "ছবি সংযুক্ত হয়েছে — এখন প্রশ্ন লিখে পাঠান"
            } else {
                _imageNotice.value = "ছবিটি পড়া গেল না"
            }
        }
    }

    /**
     * Attaches any media the user picked: an image becomes one part, a video
     * becomes several sampled frames, and an audio file is copied into the
     * workspace so the model can be pointed at it. Picking a video here is what
     * lets the user drop a clip into the chat instead of calling a tool first.
     */
    fun attachMedia(uri: android.net.Uri, mimeType: String) {
        viewModelScope.launch {
            val kind = when {
                mimeType.startsWith("video") -> "video"
                mimeType.startsWith("audio") -> "audio"
                mimeType.startsWith("image") -> "image"
                else -> "auto"
            }
            if (kind == "image" || kind == "auto") {
                attachImage(uri)
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    val ctx = getApplication<Application>()
                    val file = File(
                        ToolRegistry.workspace(ctx),
                        "attach_" + System.currentTimeMillis() + "_" + (uri.lastPathSegment ?: "media")
                    )
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    } ?: return@withContext JSONObject().put("ok", false)
                    ToolRegistry.execute(
                        ctx, "read_media",
                        JSONObject().put("path", file.absolutePath).put("kind", kind)
                            .put("frames", if (kind == "video") 4 else 0)
                    )
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("summary", e.message ?: "ফাইল পড়া গেল না")
                }
            }
            val single = result.optString("image_data_url")
            val many = result.optJSONArray("image_data_urls")
            val shots = mutableListOf<String>()
            if (single.isNotBlank()) shots.add(single)
            many?.let { for (i in 0 until it.length()) it.optString(i).takeIf { s -> s.isNotBlank() }?.let { s -> shots.add(s) } }
            if (shots.isNotEmpty()) {
                _attachedImages.value = _attachedImages.value + shots
                _imageNotice.value = result.optString("summary").ifBlank { "মিডিয়া সংযুক্ত হয়েছে" }
            } else {
                _imageNotice.value = result.optString("summary").ifBlank { "মিডিয়া পড়া গেল না" }
            }
        }
    }

    /** Drops the attached pictures without sending them. */
    fun clearAttachedImage() {
        _attachedImages.value = emptyList()
        _imageNotice.value = ""
    }

    /** Removes one attached picture by index. */
    fun removeAttachedImage(index: Int) {
        _attachedImages.value = _attachedImages.value.filterIndexed { i, _ -> i != index }
    }

    /** Turns the always-see-screen timer on or off and remembers the choice. */
    fun setAlwaysSeeScreen(on: Boolean) {
        _alwaysSeeScreen.value = on
        prefs.edit().putBoolean("always_see_screen", on).apply()
        if (on && activeTurns.get() > 0) startLiveFrameTicker() else stopLiveFrameTicker()
    }

    /**
     * Starts the two-second screen ticker. It runs only while a turn is in
     * flight; the frames it captures are drained by the engine between rounds.
     * The capture is skipped when the screen looks the same as last time, so a
     * still screen does not burn a screenshot every two seconds for an hour.
     */
    private fun startLiveFrameTicker() {
        if (!_alwaysSeeScreen.value) return
        if (liveFrameJob?.isActive == true) return
        liveFrameJob = viewModelScope.launch {
            while (activeTurns.get() > 0) {
                val shot = withContext(Dispatchers.IO) {
                    runCatching { ToolRegistry.screenFrame() }.getOrNull().orEmpty()
                }
                if (shot.isNotBlank()) {
                    val hash = shot.hashCode()
                    if (hash != lastLiveFrameHash && pendingScreenFrames.size < 3) {
                        lastLiveFrameHash = hash
                        pendingScreenFrames.add(shot)
                    }
                }
                kotlinx.coroutines.delay(LIVE_FRAME_INTERVAL_MS)
            }
        }
    }

    private fun stopLiveFrameTicker() {
        liveFrameJob?.cancel()
        liveFrameJob = null
        pendingScreenFrames.clear()
    }

    /** The frames captured since the last round. Called by the engine each round. */
    fun drainScreenFrames(): List<String> {
        val out = mutableListOf<String>()
        while (true) out.add(pendingScreenFrames.poll() ?: break)
        return out
    }

    /**
     * Runs one of the picture tools from the composer menu: the camera, or a
     * fresh look at what is on the screen right now. The result is attached so
     * the user can add a question before sending, instead of the tool firing on
     * its own mid-conversation.
     */
    fun captureImageFromTool(tool: String) {
        viewModelScope.launch {
            val args = JSONObject()
            val result = withContext(Dispatchers.IO) {
                runCatching { ToolRegistry.execute(getApplication(), tool, args) }
                    .getOrElse { JSONObject().put("ok", false).put("summary", "ছবি নেওয়া গেল না") }
            }
            val dataUrl = result.optString("image_data_url")
            val many = result.optJSONArray("image_data_urls")
            val shots = mutableListOf<String>()
            if (dataUrl.isNotBlank()) shots.add(dataUrl)
            many?.let { for (i in 0 until it.length()) it.optString(i).takeIf { s -> s.isNotBlank() }?.let { s -> shots.add(s) } }
            if (shots.isNotEmpty()) {
                _attachedImages.value = _attachedImages.value + shots
                _imageNotice.value = result.optString("summary").ifBlank { "ছবি সংযুক্ত হয়েছে" }
            } else {
                _imageNotice.value = result.optString("summary").ifBlank { "ছবি নেওয়া গেল না" }
            }
        }
    }

    fun onScreenText(text: String) {
        _screenTextSnapshot.value = text
    }

    fun updateForegroundApp(packageName: String, label: String) {
        _systemStatus.value = _systemStatus.value.copy(currentApp = label)
    }

    /**
     * What the user is looking at right now, so the model never has to spend a
     * tool call to find out. The accessibility service keeps this current in the
     * background.
     */
    private fun screenContext(force: Boolean = false): String {
        // The foreground app and the screen text are read live, not from the
        // snapshot the UI last pushed. That snapshot only refreshes while this
        // app is on screen, so asking "what am I doing?" from *outside* the app
        // answered with the old screen ("তুমি আমার অ্যাপে আছো") — the defect the
        // user hit. The accessibility service can read the real frame any time.
        //
        // Walking the whole tree costs a few hundred milliseconds, and a turn that
        // does not touch the screen asked for it twice. A short cache keeps the
        // per-turn read cheap; a screen action forces a fresh read, because after
        // a tap the old frame is exactly the wrong answer.
        val now = System.currentTimeMillis()
        if (!force && now - screenCacheAtMs < SCREEN_CACHE_MS && screenCacheText.isNotBlank()) {
            return screenCacheText
        }
        val label = DeviceBridge.foregroundLabel()
        val pkg = DeviceBridge.foregroundPackage()
        val live = runCatching { DeviceBridge.readScreen() }.getOrNull().orEmpty()
        val snapshot = live.ifBlank { _screenTextSnapshot.value }.take(2500)
        val where = when {
            label.isNotBlank() -> "ব্যবহারকারী এখন \"$label\" অ্যাপে আছেন ($pkg)।"
            pkg.isNotBlank() -> "ব্যবহারকারীর সামনের অ্যাপ: $pkg।"
            else -> ""
        }
        val text = if (snapshot.isBlank()) "" else "\nস্ক্রিনে এখন যা দেখা যাচ্ছে:\n$snapshot"
        val result = where + text
        screenCacheText = result
        screenCacheAtMs = now
        return result
    }

    /**
     * Runs one tool the model asked for and keeps a live row for it on the chat
     * and the Tools tab: the row opens the moment the call is made, the tool's
     * own output streams into it while it runs, and it closes into the history
     * when the result arrives. A panel tool that only changes the UI (opening the
     * terminal, the code box, the file panel) is instant and needs no row.
     */
    private suspend fun runTool(name: String, args: JSONObject, s: ChatSession): JSONObject {
        if (name in UI_ONLY_TOOLS) return runToolInner(name, args, s, null)
        val id = beginToolCall(s, name, args)
        val t0 = System.currentTimeMillis()
        var result: JSONObject = JSONObject().put("ok", false).put("summary", "থামানো হয়েছে")
        try {
            result = try {
                runToolInner(name, args, s) { line -> appendToolOutput(s, id, line) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("summary", "টুল চলেনি: ${e.message ?: e.toString()}")
            }
        } finally {
            finishToolCall(s, id, name, result, System.currentTimeMillis() - t0)
        }
        return result
    }

    private suspend fun runToolInner(name: String, args: JSONObject, s: ChatSession, live: ((String) -> Unit)? = null): JSONObject {
        // MCP: a name carrying "<serverId>__<tool>" is a tool a connected server
        // advertised, so it goes straight to that server. The three management
        // tools below let the assistant connect and inspect servers by itself.
        if (name.contains("__")) {
            return aiEngine.mcp.callTool(name, args)
        }
        when (name) {
            "mcp_connect" -> return runMcpConnect(args)
            "mcp_list" -> return runMcpList()
            "mcp_resources" -> return runMcpResources(args)
            "mcp_read_resource" -> return runMcpReadResource(args)
            "mcp_prompts" -> return runMcpPrompts(args)
            "mcp_prompt" -> return runMcpPrompt(args)
            "mcp_subscribe" -> return runMcpSubscribe(args)
            "mcp_complete" -> return runMcpComplete(args)
            "mcp_ping" -> return runMcpPing(args)
            "mcp_log_level" -> return runMcpLogLevel(args)
            "mcp_disconnect" -> {
                val id = args.optString("server")
                aiEngine.mcp.disconnect(id)
                return JSONObject().put("ok", true).put("summary", "$id কানেক্ট করা বন্ধ করা হলো")
            }
        }
        // Panel tools belong to the UI, not the device.
        when (name) {
            "todo_write" -> {
                val todos = args.optJSONArray("todos")
                if (todos != null) {
                    val list = mutableListOf<TaskItem>()
                    for (i in 0 until todos.length()) {
                        val t = todos.optJSONObject(i) ?: continue
                        list.add(TaskItem(
                            id = t.optString("id", "t${i + 1}"),
                            text = t.optString("title"),
                            isCompleted = t.optString("status") == "done"
                        ))
                    }
                    s.tasks.value = list
                    repository.replaceTasks(list, s.id)
                }
                return JSONObject().put("ok", true).put("summary", "টুডু বোর্ড হালনাগাদ হয়েছে")
            }
            "code_box" -> {
                val action = args.optString("action", "open")
                _codeBoxOpen.value = action == "open" || action == "restore"
                args.optString("title").takeIf { it.isNotBlank() }?.let { _codeBoxTitle.value = it }
                args.optString("code").takeIf { it.isNotBlank() }?.let { _codeBoxText.value = it }
                return JSONObject().put("ok", true).put("summary", "কোড প্যানেল: $action")
            }
            "files_panel" -> {
                _activeTab.value = AppTab.TOOLS
                _filesPanelOpen.value = args.optString("action", "open") != "close"
                return JSONObject().put("ok", true).put("summary", "ফাইল প্যানেল খোলা হয়েছে")
            }
            "terminal_open" -> {
                _activeTab.value = AppTab.TERMINAL
                return JSONObject().put("ok", true).put("summary", "টার্মিনাল খোলা হয়েছে")
            }
            "terminal_run" -> return runInTerminalShell(args, live)
            "preview" -> return PreviewBridge.tool(getApplication(), args)
            "sub_agent" -> return runSubAgent(args, s)
            "memory_delete" -> {
                val key = args.optString("key").trim()
                if (key.isBlank()) return JSONObject().put("ok", false).put("summary", "key দরকার")
                repository.deleteMemory(key)
                return JSONObject().put("ok", true).put("summary", "ভুলে যাওয়া হলো — $key")
            }
            "memory_write" -> {
                val key = args.optString("key").trim()
                val value = args.optString("value").trim()
                if (key.isBlank() || value.isBlank()) {
                    return JSONObject().put("ok", false).put("summary", "মনে রাখতে key আর value দুটোই দরকার")
                }
                repository.saveMemory(key, value)
                return JSONObject().put("ok", true)
                    .put("summary", "মনে রাখা হলো — $key: $value")
            }
            "memory_read" -> {
                val query = args.optString("query").trim()
                val durable = repository.getMemoriesPromptContext()
                val recalled = if (query.isNotBlank()) {
                    repository.recallRelevant(query, limit = 12)
                        .joinToString("\n") { "${it.role}: ${it.text}" }
                } else ""
                val body = buildString {
                    if (durable.isNotBlank()) append(durable)
                    if (recalled.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append("পুরনো কথোপকথন থেকে:\n").append(recalled)
                    }
                }
                return JSONObject().put("ok", true)
                    .put("summary", "স্মৃতি পড়া হলো")
                    .put("data", body.ifBlank { "এখনো কিছু মনে রাখা হয়নি" })
            }
            "floating_bubble" -> {
                val want = args.optString("action", "on") != "off"
                val ctx = getApplication<Application>()
                val overlayAllowed = android.os.Build.VERSION.SDK_INT < 23 ||
                    android.provider.Settings.canDrawOverlays(ctx)
                if (want && !overlayAllowed) {
                    // The overlay permission can only be granted by the user in
                    // Settings, so point them at the exact row instead of failing.
                    ai.zyraxon.assistant.MainActivity.instance?.openSpecialSetting("overlay")
                    return JSONObject()
                        .put("ok", false)
                        .put("error", "overlay-permission")
                        .put("summary", "ওভারলে অনুমতি দরকার — সেটিংস খুলে দিয়েছি, উপরে Allow চাপো")
                }
                setOverlayEnabled(want)
                if (want) ai.zyraxon.assistant.OverlayChatService.start(ctx)
                else ai.zyraxon.assistant.OverlayChatService.stop(ctx)
                return JSONObject()
                    .put("ok", true)
                    .put("summary", if (want) "ফ্লোটিং বাবল চালু হয়েছে" else "ফ্লোটিং বাবল বন্ধ হয়েছে")
            }
        }

        // The live sink is handed to the registry so `run_shell`, `system_shell`
        // and `linux_run` can stream their output into the live row as it is
        // produced, instead of only when they return.
        return ToolRegistry.execute(getApplication(), name, args, live)
    }

    /** Shows a tool on the Tools tab and keeps any picture it produced. */
    private fun recordToolExecution(s: ChatSession, name: String, result: JSONObject, output: String = "") {
        val item = ToolExecutionItem(
            name = name,
            description = result.optString("summary"),
            icon = if (result.optBoolean("ok")) "✅" else "⚠️",
            status = if (result.optBoolean("ok")) "সম্পন্ন" else "ব্যর্থ",
            timestamp = currentTimeString(),
            output = output.takeLast(8_000)
        )
        synchronized(s.lock) {
            s.toolExecutions.value = listOf(item) + s.toolExecutions.value.take(99)
        }

        // When a tool returns a picture, keep it so the next model turn can look
        // at it. Without this the camera and media tools would capture an image
        // that the model never actually receives.
        result.optString("image_data_url").takeIf { it.isNotBlank() }?.let {
            _screenFrameDataUrl.value = it
        }
    }

    /**
     * Connects an MCP server the assistant asked for. For the browser-based ones
     * it opens the consent page and returns immediately — the connection finishes
     * on its own, and the model sees the new tools on its next turn.
     */
    private suspend fun runMcpConnect(args: JSONObject): JSONObject {
        val requested = args.optString("server").trim().lowercase()
        val server = when (requested) {
            "custom" -> {
                val url = args.optString("url").trim()
                if (url.isBlank()) {
                    return JSONObject().put("ok", false).put("summary", "custom সার্ভারের url দরকার")
                }
                aiEngine.mcp.addCustomServer(url, args.optString("name"), args.optString("token"))
            }
            else -> McpCatalog.find(requested)
                ?: return JSONObject().put("ok", false)
                    .put("summary", "অজানা MCP: $requested (canva, cloudflare, supabase, github বা custom)")
        }

        return if (server.auth == McpAuth.OAUTH) {
            val url = aiEngine.mcp.beginOAuth(server)
            val failure = openInBrowser(url)
            if (failure != null) {
                JSONObject().put("ok", false).put("summary", failure)
            } else {
                JSONObject().put("ok", true)
                    .put("summary", "${server.name}: ব্রাউজারে অনুমতির পাতা খোলা হয়েছে — ব্যবহারকারী Allow চাপলে কানেক্ট হয়ে যাবে")
                    .put("pending_auth", true)
            }
        } else {
            val token = args.optString("token").takeIf { it.isNotBlank() }
            if (server.auth == McpAuth.TOKEN && token == null) {
                return JSONObject().put("ok", false)
                    .put("summary", "${server.name} এর জন্য access token দরকার — ব্যবহারকারীর কাছ থেকে নাও")
            }
            val conn = aiEngine.mcp.connectWithToken(server, token)
            JSONObject()
                .put("ok", conn.status == McpStatus.CONNECTED)
                .put("summary", if (conn.status == McpStatus.CONNECTED)
                    "${server.name} কানেক্টেড — ${conn.toolCount}টি টুল পাওয়া গেছে"
                else "${server.name} কানেক্ট করা যায়নি: ${conn.error}")
                .put("tools", org.json.JSONArray(conn.tools.map { it.name }))
        }
    }

    /**
     * Opens a consent page in the phone's browser. Returns the real outcome, so
     * the UI can say "no browser found" instead of showing "waiting…" forever —
     * a silent failure here is exactly why the page never appeared on the phone.
     */
    private fun openInBrowser(url: String): String? {
        if (url.isBlank()) return "অনুমতির লিঙ্ক তৈরি করা যায়নি"
        return try {
            val view = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            // A chooser makes the browser choice explicit; without it Android 11+
            // can resolve nothing and throw, which the old code swallowed.
            val chooser = android.content.Intent.createChooser(view, "অনুমতি দিতে ব্রাউজার বেছে নাও")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(chooser)
            null
        } catch (e: Exception) {
            try {
                getApplication<Application>().startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                null
            } catch (e2: Exception) {
                "ব্রাউজার খোলা যায়নি: ${e2.message ?: "কোনো ব্রাউজার পাওয়া যায়নি"}"
            }
        }
    }

    private fun runMcpList(): JSONObject {
        val arr = org.json.JSONArray()
        for (conn in aiEngine.mcp.connections.value) {
            arr.put(JSONObject()
                .put("server", conn.server.id)
                .put("name", conn.server.name)
                .put("status", conn.status.name)
                .put("tool_count", conn.toolCount)
                .put("tools", org.json.JSONArray(conn.tools.map { it.qualifiedName }))
                .put("resource_count", conn.resourceCount)
                .put("prompt_count", conn.promptCount)
                .apply { if (conn.error.isNotBlank()) put("error", conn.error) })
        }
        return JSONObject().put("ok", true)
            .put("summary", "MCP: ${aiEngine.mcp.connectedCount()}টি কানেক্টেড, " +
                "${aiEngine.mcp.totalToolCount()}টি টুল, " +
                "${aiEngine.mcp.allResources().size}টি রিসোর্স, " +
                "${aiEngine.mcp.allPrompts().size}টি প্রম্পট")
            .put("servers", arr)
    }

    /** Lists resources and templates, optionally for one server. */
    private fun runMcpResources(args: JSONObject): JSONObject {
        val only = args.optString("server").trim().lowercase()
        val items = aiEngine.mcp.allResources().filter { only.isBlank() || it.serverId == only }
        val arr = org.json.JSONArray()
        for (r in items) {
            arr.put(JSONObject()
                .put("server", r.serverId)
                .put("uri", r.uri)
                .put("name", r.name)
                .put("description", r.description)
                .put("mime_type", r.mimeType)
                .put("template", r.template))
        }
        val summary = if (items.isEmpty()) {
            if (only.isBlank()) "কোনো MCP রিসোর্স নেই"
            else "$only কোনো রিসোর্স দেয়নি"
        } else "${items.size}টি রিসোর্স পাওয়া গেছে"
        return JSONObject().put("ok", true).put("summary", summary).put("resources", arr)
    }

    private suspend fun runMcpReadResource(args: JSONObject): JSONObject {
        val uri = resolveQualifiedUri(args.optString("uri").trim())
            ?: return JSONObject().put("ok", false).put("summary", "uri দরকার")
        val result = aiEngine.mcp.readResource(uri)
        return result
    }

    /** Lists prompt templates and the arguments each one takes. */
    private fun runMcpPrompts(args: JSONObject): JSONObject {
        val only = args.optString("server").trim().lowercase()
        val items = aiEngine.mcp.allPrompts().filter { only.isBlank() || it.serverId == only }
        val arr = org.json.JSONArray()
        for (p in items) {
            arr.put(JSONObject()
                .put("server", p.serverId)
                .put("name", p.name)
                .put("description", p.description)
                .put("arguments", org.json.JSONArray(p.arguments.map { a ->
                    JSONObject().put("name", a.name).put("description", a.description)
                        .put("required", a.required)
                })))
        }
        val summary = if (items.isEmpty()) "কোনো MCP প্রম্পট নেই" else "${items.size}টি প্রম্পট পাওয়া গেছে"
        return JSONObject().put("ok", true).put("summary", summary).put("prompts", arr)
    }

    private suspend fun runMcpPrompt(args: JSONObject): JSONObject {
        var name = args.optString("name").trim()
        if (name.isBlank()) return JSONObject().put("ok", false).put("summary", "name দরকার")
        if (!name.contains("__")) {
            val owner = aiEngine.mcp.allPrompts().firstOrNull { it.name == name }
            if (owner != null) name = "${owner.serverId}__$name"
        }
        val result = aiEngine.mcp.getPrompt(name, args.optJSONObject("arguments") ?: JSONObject())
        return result
    }

    /** Subscribe/unsubscribe a resource, resolving a bare uri to its server. */
    private suspend fun runMcpSubscribe(args: JSONObject): JSONObject {
        val resolved = resolveQualifiedUri(args.optString("uri").trim())
            ?: return JSONObject().put("ok", false).put("summary", "uri দরকার")
        val subscribe = args.optString("action", "subscribe") != "unsubscribe"
        val separator = resolved.indexOf("__")
        val serverId = resolved.substring(0, separator)
        val uri = resolved.substring(separator + 2)
        val result = if (subscribe) {
            aiEngine.mcp.subscribeResource(serverId, uri)
        } else {
            aiEngine.mcp.unsubscribeResource(serverId, uri)
        }
        return result
    }

    private suspend fun runMcpComplete(args: JSONObject): JSONObject {
        val server = args.optString("server").trim().lowercase()
        val argument = args.optString("argument").trim()
        if (server.isBlank() || argument.isBlank()) {
            return JSONObject().put("ok", false).put("summary", "server আর argument দরকার")
        }
        val kind = if (args.optString("kind").equals("resource", ignoreCase = true)) "ref/resource" else "ref/prompt"
        val ref = args.optString("ref").trim().ifBlank {
            // With no explicit ref, offer the first prompt or resource that has this
            // argument, so the model does not have to look the name up first.
            if (kind == "ref/resource") aiEngine.mcp.allResources().firstOrNull()?.uri.orEmpty()
            else aiEngine.mcp.allPrompts().firstOrNull { p -> p.arguments.any { it.name == argument } }?.name.orEmpty()
        }
        if (ref.isBlank()) return JSONObject().put("ok", false).put("summary", "কোনো ref পাওয়া যায়নি")
        val result = aiEngine.mcp.complete(server, kind, ref, argument, args.optString("value"))
        return result
    }

    private suspend fun runMcpPing(args: JSONObject): JSONObject {
        val server = args.optString("server").trim().lowercase()
        if (server.isBlank()) return JSONObject().put("ok", false).put("summary", "server দরকার")
        val result = aiEngine.mcp.ping(server)
        return result
    }

    private suspend fun runMcpLogLevel(args: JSONObject): JSONObject {
        val server = args.optString("server").trim().lowercase()
        val level = args.optString("level").trim().lowercase()
        if (server.isBlank() || level.isBlank()) {
            return JSONObject().put("ok", false).put("summary", "server আর level দরকার")
        }
        val result = aiEngine.mcp.setLogLevel(server, level)
        return result
    }

    /**
     * Turns a bare uri into `<serverId>__<uri>`. Returns null when the uri is
     * blank; an unknown uri falls back to the only connected server, which is the
     * common case and avoids a pointless failure.
     */
    private fun resolveQualifiedUri(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.contains("__")) return raw
        val ownerId = aiEngine.mcp.allResources().firstOrNull { it.uri == raw }?.serverId
            ?: aiEngine.mcp.connections.value
                .firstOrNull { it.status == McpStatus.CONNECTED }?.server?.id
        return if (ownerId != null) "${ownerId}__$raw" else raw
    }

    fun closeCodePanel() {
        _codeBoxOpen.value = false
    }

    // ---- Terminal tab ------------------------------------------------------

    /**
     * Runs one line in the Terminal shell. The user's typed lines and the
     * assistant's `terminal_run` calls both come through here, so the two share
     * the same shell, working directory and scrollback. Every result is real
     * output, never a summary.
     *
     * This is the *user-facing* shell. `run_shell` is a separate shell with its
     * own working directory — the assistant has both, and picks.
     */
    fun runTerminal(command: String) {
        val line = command.trim()
        if (line.isEmpty()) return
        // While a command is running, a line of input belongs to that command, not
        // to the shell. This is what makes a prompt answerable: `apt install` asks
        // "Do you want to continue? [Y/n]", and the "y" the user types goes to the
        // program that asked, instead of being rejected as a new command.
        if (_terminalBusy.value) {
            sendTerminalInput(line)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _terminalBusy.value = true
            try {
                runTerminalLine(line)
            } finally {
                _terminalBusy.value = false
            }
        }
    }

    /**
     * Writes a line to the standard input of the command that is currently running.
     *
     * Nothing happens if no command is running, so this can never leak text into a
     * later command. The newline is appended because a prompt waits for a whole
     * line, and the user pressing Enter is what this represents.
     */
    fun sendTerminalInput(text: String) {
        val sink = terminalProcess?.input ?: return
        try {
            synchronized(sink) {
                sink.write((text + "\n").toByteArray())
                sink.flush()
            }
        } catch (e: Exception) {
            // The command may have just finished and closed its input. Losing the
            // race is not an error worth showing, and the output already says so.
        }
    }

    /**
     * Stops the command that is running. This is the terminal's Ctrl-C.
     *
     * With no timeout on a command, this is the only way out of one that never
     * finishes — a `ping` without `-c`, a `cat` on a pipe that never closes, a
     * `git clone` of the wrong repository. A no-op when nothing is running.
     */
    fun interruptTerminal() {
        terminalProcess?.interrupt()
    }

    /** The body of a terminal line, shared by the user's typing and `terminal_run`. */
    private suspend fun runTerminalLine(
        line: String,
        timeoutMs: Long = 0,
        onOutput: ((String) -> Unit)? = null
    ): TerminalLine {
        val app = getApplication<Application>()
        val home = ShellRuntime.home(app)
        // `cd` is stateful: it changes where the *next* line runs, so it is
        // applied here rather than left to die with the process it started.
        parseCd(line, home)?.let { target ->
            val entry = if (target.isDirectory) {
                updateActiveSession { it.copy(cwd = displayPath(home, target)) }
                TerminalLine(command = line, output = "", exitCode = 0, isError = false)
            } else {
                TerminalLine(
                    command = line,
                    output = "cd: no such directory: ${target.path}",
                    exitCode = 1, isError = true
                )
            }
            appendTerminal(entry)
            return entry
        }
        // A Linux session is the real guest: every line goes into it, and `git`
        // there is the real git with real remotes, not the JGit shortcut below.
        if (activeTerminalShell() == TerminalShell.LINUX) {
            return runLinuxLine(app, line, timeoutMs, onOutput)
        }
        // busybox has no git, so in the Android session a `git` line is served by
        // JGit instead of failing with "applet not found".
        runGitLine(app, line)?.let { result ->
            appendTerminal(result)
            return result
        }
        // The explicit `linux` / `linux-install` verbs still work in an Android
        // session, so nothing that worked before is lost.
        if (isLinuxCommand(line)) {
            return runLinuxLine(app, line, timeoutMs, onOutput)
        }
        // A live entry is appended first and its output grows as the command
        // prints, so the terminal behaves like a real one — progress appears
        // while the command runs instead of after it exits.
        val lineId = UUID.randomUUID().toString()
        appendTerminal(TerminalLine(id = lineId, command = line, output = "", exitCode = 0, isError = false, running = true))
        val streamed = StringBuilder()
        val r = ShellRuntime.runStreaming(
            app, line,
            cwd = currentTerminalDir(home),
            // No deadline unless the caller set one. A real terminal does not put
            // a clock on the command you typed, so `apt upgrade`, `git clone` and
            // a long build run to completion; the user's Ctrl-C is the way out.
            timeoutMs = timeoutMs,
            onChunk = { _, text ->
                synchronized(streamed) { streamed.append(text).append('\n') }
                updateTerminalLine(lineId) { it.copy(output = streamed.toString().trimEnd('\n')) }
                onOutput?.invoke(text)
            },
            // A host command can prompt too (`read`, a password), so it gets the
            // same answerable input as a Linux one.
            onSpawn = { live -> terminalProcess = live }
        )
        terminalProcess = null
        val body = buildString {
            if (r.stdout.isNotEmpty()) append(r.stdout.trimEnd())
            if (r.stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(r.stderr.trimEnd())
            }
            if (r.timedOut) {
                if (isNotEmpty()) append('\n')
                append("[timed out after ${r.millis / 1000}s]")
            }
            if (isEmpty()) append("(no output)")
        }
        val entry = TerminalLine(
            id = lineId, command = line, output = body, exitCode = r.code,
            isError = r.code != 0 || r.timedOut, timedOut = r.timedOut, millis = r.millis
        )
        updateTerminalLine(lineId) { entry }
        return entry
    }

    /** Replaces one terminal entry in place, used to grow a running command's output. */
    private fun updateTerminalLine(id: String, transform: (TerminalLine) -> TerminalLine) {
        updateActiveSession { session ->
            session.copy(lines = session.lines.map { if (it.id == id) transform(it) else it })
        }
    }

    // ---- ZYRAXON's own full Linux system --------------------------------

    /**
     * Recognises the lines that belong to the bundled Linux userland rather than
     * the app's own busybox: `linux-install`, `linux`, `linux-status`, and the
     * package-manager names the guest provides once it is installed.
     */
    private fun isLinuxCommand(line: String): Boolean {
        val first = line.trim().split(Regex("\\s+")).firstOrNull() ?: return false
        return first in setOf(
            "linux", "linux-install", "linux-status", "linux-shell",
            "pkg", "apt", "apt-get", "dpkg"
        ) || line.trim().startsWith("linux ")
    }

    /**
     * Runs one line of the bundled Linux system. The system is unpacked on first
     * launch, so this only ever drives it; every line goes into the guest.
     */
    private suspend fun runLinuxLine(
        app: Application,
        line: String,
        timeoutMs: Long = 0,
        onOutput: ((String) -> Unit)? = null
    ): TerminalLine {
        val lineId = UUID.randomUUID().toString()
        appendTerminal(TerminalLine(id = lineId, command = line, output = "", exitCode = 0, isError = false, running = true))
        val streamed = StringBuilder()
        val push: (String, String) -> Unit = { _, text ->
            synchronized(streamed) { streamed.append(text).append('\n') }
            updateTerminalLine(lineId) { it.copy(output = streamed.toString().trimEnd('\n')) }
            onOutput?.invoke(text)
        }

        val status = buildString {
            append("Linux: ").append(if (LinuxRuntime.isInstalled(app)) "ready" else "setting up…")
            if (!LinuxRuntime.isSupported(app)) append(" · unsupported on this device")
            append("\n\n")
            append("The system is already installed — nothing to set up. Type any Linux command.\n\n")
            append("Examples:\n")
            append("  pkg install nmap        — install a tool from the Termux repository\n")
            append("  pkg upgrade             — update every installed package\n")
            append("  ls, cd, cat, nano       — the usual shell\n")
            append("  git clone <url>         — clone a repository\n")
            append("  python, node, clang     — run code\n")
            append("  linux-status            — show this status\n")
        }

        val result = when {
            line == "linux-status" -> TerminalLine(
                command = line, output = status, exitCode = 0, isError = false
            )

            line == "linux-install" -> {
                push("out", "Installing ZYRAXON Linux…")
                val ok = LinuxRuntime.install(app) { msg -> push("out", msg) }
                TerminalLine(
                    id = lineId,
                    command = line,
                    output = streamed.toString().trimEnd('\n') +
                        if (ok) "\n\nZYRAXON Linux is ready. Run a command with: linux <command>"
                        else "\n\nInstall did not finish. See the lines above for the reason.",
                    exitCode = if (ok) 0 else 1,
                    isError = !ok
                )
            }

            !LinuxRuntime.isInstalled(app) -> {
                // The system is unpacked on first launch, so reaching this branch
                // means the very first command arrived before that finished. Set it
                // up now rather than telling the user to type an install command:
                // there is nothing they should have to do.
                push("out", "Preparing ZYRAXON Linux…")
                val ok = LinuxRuntime.install(app) { msg -> push("out", msg) }
                if (ok) {
                    runLinuxGuest(app, lineId, streamed, push, guestCommand(line), timeoutMs)
                } else {
                    TerminalLine(
                        id = lineId,
                        command = line,
                        output = streamed.toString().trimEnd('\n'),
                        exitCode = 1, isError = true
                    )
                }
            }

            else -> {
                runLinuxGuest(app, lineId, streamed, push, guestCommand(line), timeoutMs)
            }
        }
        // The entry is finalised in place, so the running cursor stops and the
        // exit code is shown under the output that already streamed in.
        updateTerminalLine(lineId) { result }
        return result
    }

    /**
     * The command to hand the guest. A line typed in a Linux session goes in
     * verbatim — a real terminal does not need a `linux` prefix. The explicit
     * `linux <cmd>` form is still accepted and has its prefix removed, so both
     * ways of driving the system keep working.
     */
    private fun guestCommand(line: String): String {
        val trimmed = line.trim()
        if (activeTerminalShell() == TerminalShell.LINUX && !isLinuxVerb(trimmed)) {
            return trimmed.ifEmpty { "bash -l" }
        }
        return trimmed.removePrefix("linux").trim().ifEmpty { "bash -l" }
    }

    /** True only for the explicit `linux`/`linux-install` verbs, not `apt`/`git`. */
    private fun isLinuxVerb(line: String): Boolean {
        val first = line.split(Regex("\\s+")).firstOrNull() ?: return false
        return first in setOf("linux", "linux-install", "linux-status", "linux-shell")
    }

    /** Runs a command inside the installed guest, streaming its output live. */
    private suspend fun runLinuxGuest(
        app: Application,
        lineId: String,
        streamed: StringBuilder,
        push: (String, String) -> Unit,
        command: String,
        timeoutMs: Long = 0
    ): TerminalLine {
        val r = LinuxRuntime.runStreaming(
            app, command,
            onChunk = { stream, text -> push(stream, text) },
            // Hold the command's input while it runs, so a prompt can be answered.
            onSpawn = { live -> terminalProcess = live },
            timeoutMs = timeoutMs
        )
        terminalProcess = null
        if (r == null) {
            return TerminalLine(
                command = command,
                output = "Linux is not installed. Run: linux-install",
                exitCode = 1, isError = true
            )
        }
        val body = buildString {
            if (r.stdout.isNotBlank()) append(r.stdout.trimEnd())
            if (r.stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(r.stderr.trimEnd())
            }
            if (r.timedOut) {
                if (isNotEmpty()) append('\n')
                append("[timed out after ${r.millis / 1000}s]")
            }
            if (isEmpty()) append("(no output)")
        }
        return TerminalLine(
            id = lineId, command = command, output = body, exitCode = r.code,
            isError = r.code != 0 || r.timedOut, timedOut = r.timedOut, millis = r.millis
        )
    }

    /**
     * `terminal_run` — the assistant driving the shell the user is watching.
     * Same shell, same cwd, same scrollback as the user's own typing.
     */
    private suspend fun runInTerminalShell(args: JSONObject, live: ((String) -> Unit)? = null): JSONObject {
        val line = args.optString("command").trim()
        if (line.isEmpty()) return JSONObject().put("ok", false).put("summary", "খালি কমান্ড")
        // No deadline unless the caller asked for one, matching the user's own
        // typing: a long command in the watched terminal runs to completion.
        val timeout = args.optLong("timeout_ms", 0)
        // The caller may name the shell: "linux" drives ZYRAXON's own Unix system,
        // "android" the phone's own shell. Without a name, the session already on
        // screen is used, which is what a user watching the terminal expects.
        val requested = args.optString("shell").trim().lowercase()
        if (requested == "linux" || requested == "android") {
            val want = if (requested == "linux") TerminalShell.LINUX else TerminalShell.ANDROID
            val existing = _terminalSessions.value.firstOrNull { it.shell == want }
            if (existing != null) {
                _activeTerminalId.value = existing.id
                syncActiveTerminal()
            } else {
                newTerminalSession(want)
            }
        }
        _activeTab.value = AppTab.TERMINAL
        val entry = withContext(Dispatchers.IO) { runTerminalLine(line, timeout, live) }
        return JSONObject()
            .put("ok", !entry.isError)
            .put("code", entry.exitCode)
            .put("stdout", if (entry.isError) "" else entry.output)
            .put("stderr", if (entry.isError) entry.output else "")
            .put("shell", if (activeTerminalShell() == TerminalShell.LINUX) "linux" else "android")
            .put("summary", "$ $line → exit ${entry.exitCode}")
    }

    /**
     * Serves `git clone/pull/status` through JGit. Returns null for any other
     * line so the real shell gets it.
     */
    private fun runGitLine(app: Application, line: String): TerminalLine? {
        val cwd = currentTerminalDir(ShellRuntime.home(app))
        val started = System.currentTimeMillis()
        val outcome = GitRuntime.runCommand(app, line, cwd) ?: return null
        return TerminalLine(
            command = line,
            output = outcome.message,
            exitCode = if (outcome.ok) 0 else 1,
            isError = !outcome.ok,
            millis = System.currentTimeMillis() - started
        )
    }

    private fun appendTerminal(entry: TerminalLine) {
        updateActiveSession { it.copy(lines = (it.lines + entry).takeLast(400)) }
    }

    fun clearTerminal() {
        updateActiveSession { it.copy(lines = emptyList()) }
    }

    /** Opens a fresh shell and switches to it. It inherits the active shell type. */
    fun newTerminalSession() {
        val same = activeTerminalShell()
        val n = _terminalSessions.value.count { it.shell == same } + 1
        val session = TerminalSession(
            id = UUID.randomUUID().toString(),
            title = (if (same == TerminalShell.LINUX) "linux" else "android") + " $n",
            shell = same
        )
        _terminalSessions.value = _terminalSessions.value + session
        _activeTerminalId.value = session.id
        syncActiveTerminal()
    }

    /** Opens a shell of the given type and switches to it. */
    fun newTerminalSession(shell: TerminalShell) {
        val n = _terminalSessions.value.count { it.shell == shell } + 1
        val session = TerminalSession(
            id = UUID.randomUUID().toString(),
            title = (if (shell == TerminalShell.LINUX) "linux" else "android") + " $n",
            shell = shell
        )
        _terminalSessions.value = _terminalSessions.value + session
        _activeTerminalId.value = session.id
        syncActiveTerminal()
    }

    /** The shell the terminal on screen is driving. */
    fun activeTerminalShell(): TerminalShell =
        _terminalSessions.value.firstOrNull { it.id == _activeTerminalId.value }?.shell
            ?: TerminalShell.ANDROID

    fun selectTerminalSession(id: String) {
        if (_terminalSessions.value.any { it.id == id }) {
            _activeTerminalId.value = id
            syncActiveTerminal()
        }
    }

    /** Closes a shell. The last one is never closed — there is always a shell. */
    fun closeTerminalSession(id: String) {
        val closing = _terminalSessions.value.firstOrNull { it.id == id }
        val remaining = _terminalSessions.value.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            val shell = closing?.shell ?: TerminalShell.ANDROID
            _terminalSessions.value = listOf(
                TerminalSession(
                    id = UUID.randomUUID().toString(),
                    title = if (shell == TerminalShell.LINUX) "linux" else "android",
                    shell = shell
                )
            )
            _activeTerminalId.value = _terminalSessions.value.first().id
            syncActiveTerminal()
            return
        }
        _terminalSessions.value = remaining
        if (_activeTerminalId.value == id) _activeTerminalId.value = remaining.last().id
        syncActiveTerminal()
    }

    /** Replaces the active session with [transform]'s result, leaving the rest be. */
    private fun updateActiveSession(transform: (TerminalSession) -> TerminalSession) {
        val id = _activeTerminalId.value
        _terminalSessions.value = _terminalSessions.value.map { if (it.id == id) transform(it) else it }
        syncActiveTerminal()
    }

    /** The real directory the next command runs in. */
    private fun currentTerminalDir(home: java.io.File): java.io.File {
        val shown = _terminalSessions.value.firstOrNull { it.id == _activeTerminalId.value }?.cwd ?: "~"
        return when {
            shown == "~" -> home
            // The displayed path is home-relative (`~/sub/dir`), so it has to be
            // resolved against home before it can be used as a directory.
            shown.startsWith("~/") ->
                java.io.File(home, shown.removePrefix("~/")).takeIf { it.isDirectory } ?: home
            else -> java.io.File(shown).takeIf { it.isDirectory } ?: home
        }
    }

    /** Recognises a bare `cd <dir>` so the change survives past this line. */
    private fun parseCd(line: String, home: java.io.File): java.io.File? {
        val t = line.trim()
        if (t != "cd" && !t.startsWith("cd ")) return null
        val rest = t.removePrefix("cd").trim()
        return when {
            rest.isEmpty() || rest == "~" -> home
            rest.startsWith("/") -> java.io.File(rest)
            else -> java.io.File(currentTerminalDir(home), rest.removePrefix("./"))
        }
    }

    private fun displayPath(home: java.io.File, dir: java.io.File): String {
        val h = home.absolutePath
        val p = dir.canonicalFile.absolutePath
        return if (p == h) "~" else if (p.startsWith("$h/")) "~" + p.removePrefix(h) else p
    }

    // ---- MCP, driven by the MCP screen ------------------------------------

    val mcpConnections: StateFlow<List<ai.zyraxon.assistant.mcp.McpConnection>> =
        aiEngine.mcp.connections

    /** Set when a server needs a token; the UI shows the token dialog for it. */
    val pendingTokenServer = MutableStateFlow<ai.zyraxon.assistant.mcp.McpServer?>(null)

    /** Opens the provider's token page so the user does not have to hunt for it. */
    fun openTokenPage(url: String) {
        if (url.isNotBlank()) openInBrowser(url)
    }

    /** Non-fatal note shown on the MCP screen (progress, or why it failed). */
    private val _mcpNotice = MutableStateFlow("")
    val mcpNotice: StateFlow<String> = _mcpNotice.asStateFlow()

    fun clearMcpNotice() {
        _mcpNotice.value = ""
    }

    /**
     * Connects a server from the MCP screen. The browser-based ones open the
     * consent page and finish on their own; the token-based ones need the token
     * the dialog already collected.
     */
    fun connectMcp(server: ai.zyraxon.assistant.mcp.McpServer, token: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (server.auth == McpAuth.OAUTH) {
                    _mcpNotice.value = "${server.name}: অনুমতির পাতা খোলা হচ্ছে…"
                    val url = aiEngine.mcp.beginOAuth(server) { stage ->
                        _mcpNotice.value = "${server.name}: $stage"
                    }
                    val failure = withContext(Dispatchers.Main) { openInBrowser(url) }
                    _mcpNotice.value = failure
                        ?: "${server.name}: ব্রাউজারে Allow চাপো — ফিরে এলে নিজে থেকেই কানেক্ট হয়ে যাবে"
                } else {
                    _mcpNotice.value = "${server.name}: কানেক্ট করা হচ্ছে…"
                    val conn = aiEngine.mcp.connectWithToken(server, token)
                    _mcpNotice.value = if (conn.status == McpStatus.CONNECTED) {
                        "${server.name} কানেক্টেড — ${conn.toolCount}টি টুল পাওয়া গেছে ✅"
                    } else {
                        "${server.name}: ${conn.error.ifBlank { "কানেক্ট করা যায়নি" }}"
                    }
                }
            } catch (e: Exception) {
                _mcpNotice.value = "${server.name}: ${e.message.orEmpty()}"
            }
        }
    }

    fun disconnectMcp(serverId: String) {
        aiEngine.mcp.disconnect(serverId)
        _mcpNotice.value = "$serverId বিচ্ছিন্ন করা হলো"
    }

    /**
     * Aborts a browser consent flow the user walked away from. Without this the
     * card sat on "কানেক্ট হচ্ছে…" forever after the browser was closed, and
     * there was no way back to the initial Connect button.
     */
    fun cancelMcp(server: ai.zyraxon.assistant.mcp.McpServer) {
        aiEngine.mcp.cancelPending()
        aiEngine.mcp.disconnect(server.id)
        _mcpNotice.value = "${server.name}: বাতিল করা হলো"
    }

    /**
     * The user opened a browser to grant access, then closed the browser without
     * finishing. Coming back to the app means that flow is dead, so it is
     * abandoned here: otherwise the card stayed on "কানেক্ট হচ্ছে…" forever and
     * there was no way back to the Connect button.
     */
    /**
     * The overlay's silent heartbeat: make sure the bound conversation is awake,
     * without starting a turn and without saying anything. A chat that is already
     * up costs one cheap read; only a sleeping sandbox pays the wake, and doing
     * that here means the user's next message never waits for it.
     */
    suspend fun keepCloudAwake() {
        val id = openHandsStore.lastConversationId ?: return
        val key = aiEngine.getApiKey(ZYRAXON_APEX)
        if (key.isBlank() || !aiEngine.isOnline()) return
        runCatching { openHandsClient.ensureRunning(key, id) }
    }

    /**
     * The user's own "fix it now" button on the home screen.
     *
     * Everything here self-heals without being asked, but a person who thinks a
     * chat is stuck should not have to wait for the next timer: this wakes the
     * cloud sandbox, re-reads the model list and the conversation list, and says
     * what it found. It never throws at the user — the result is a sentence.
     */
    fun refreshCloud() {
        if (cloudRefreshBusy.value) return
        cloudRefreshBusy.value = true
        viewModelScope.launch {
            val key = aiEngine.getApiKey(ZYRAXON_APEX)
            if (key.isBlank()) {
                _cloudNotice.value = "আগে Apex কী বসাও"
                cloudRefreshBusy.value = false
                return@launch
            }
            if (!aiEngine.isOnline()) {
                _cloudNotice.value = "ইন্টারনেট নেই — অনলাইনে এলে নিজেই ঠিক হয়ে যাবে"
                cloudRefreshBusy.value = false
                return@launch
            }
            _cloudNotice.value = "দেখা হচ্ছে…"
            val id = openHandsStore.lastConversationId
            if (id.isNullOrBlank()) {
                _cloudNotice.value = "ক্লাউড প্রস্তুত — নতুন চ্যাট শুরু করো"
                cloudRefreshBusy.value = false
                return@launch
            }
            val conv = runCatching {
                withContext(Dispatchers.IO) {
                    openHandsClient.ensureRunning(key, id) { stage -> _cloudStage.value = stage }
                }
            }.getOrNull()
            _cloudNotice.value = when {
                conv == null -> "কনভার্সেশন পাওয়া যায়নি — পরের মেসেজে নতুনটা তৈরি হবে"
                conv.sandboxStatus == "RUNNING" -> "ক্লাউড কানেক্টেড ✅"
                else -> "স্যান্ডবক্স এখনো জাগছে (${conv.sandboxStatus})"
            }
            withContext(Dispatchers.IO) { runCatching { refreshModels() } }
            delay(2500)
            _cloudStage.value = ""
        }.invokeOnCompletion { cloudRefreshBusy.value = false }
    }

    fun onAppReturnedToForeground() {
        // Coming back from a swipe or a long sleep is the moment a cloud chat is
        // most likely to be pointing at a sandbox that has since been paused, so
        // it is woken here — in the background, without a turn — rather than left
        // for the user's next message to discover.
        viewModelScope.launch(Dispatchers.IO) {
            val id = openHandsStore.lastConversationId ?: return@launch
            val key = aiEngine.getApiKey(ZYRAXON_APEX)
            if (key.isBlank()) return@launch
            runCatching { openHandsClient.ensureRunning(key, id) }
        }
        if (!aiEngine.mcp.isAwaitingRedirect()) return
        aiEngine.mcp.cancelPending()
        _mcpNotice.value = "কানেক্ট করার ফ্লো বাতিল হলো — আবার কানেক্ট চাপো"
    }

    fun addCustomMcp(url: String, name: String, token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val server = aiEngine.mcp.addCustomServer(url, name, token)
            _mcpNotice.value = "${server.name}: কানেক্ট করা হচ্ছে…"
            val conn = aiEngine.mcp.connectWithToken(server, token.takeIf { it.isNotBlank() })
            _mcpNotice.value = if (conn.status == McpStatus.CONNECTED) {
                "${server.name} কানেক্টেড — ${conn.toolCount}টি টুল ✅"
            } else {
                "${server.name}: ${conn.error.ifBlank { "কানেক্ট করা যায়নি" }}"
            }
        }
    }

    fun removeCustomMcp(serverId: String) {
        aiEngine.mcp.removeCustomServer(serverId)
        _mcpNotice.value = "$serverId মুছে ফেলা হলো"
    }

    /** Reads the workspace so the Files panel shows what really exists on disk. */
    fun refreshWorkspaceFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val root = ToolRegistry.workspace(getApplication())
            val out = mutableListOf<WorkspaceFile>()
            root.walkTopDown().maxDepth(4)
                .filter { it.isFile && !it.name.startsWith(".") }
                .take(200)
                .forEach { f ->
                    out.add(
                        WorkspaceFile(
                            name = f.name,
                            path = f.absolutePath,
                            relative = f.relativeTo(root).path,
                            sizeBytes = f.length()
                        )
                    )
                }
            _workspaceFiles.value = out.sortedBy { it.relative }
        }
    }

    fun openWorkspaceFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching { java.io.File(path).readText() }.getOrNull() ?: return@launch
            _codeBoxTitle.value = java.io.File(path).name
            _codeBoxText.value = text.take(60_000)
            _codeBoxOpen.value = true
        }
    }

    fun deleteWorkspaceFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { java.io.File(path).deleteRecursively() }
            refreshWorkspaceFiles()
        }
    }

    fun closeFilesPanel() {
        _filesPanelOpen.value = false
    }

    fun updateAccessibilityStatus(connected: Boolean) {
        _systemStatus.value = _systemStatus.value.copy(accessibilityConnected = connected)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        _overlayEnabled.value = enabled
        prefs.edit().putBoolean("overlay_enabled", enabled).apply()
        if (!enabled) _overlayExpanded.value = false
    }

    fun setOverlayExpanded(expanded: Boolean) {
        _overlayExpanded.value = expanded
    }

    override fun onCleared() {
        super.onCleared()
        if (active === this) active = null
        // No screen is alive any more, so nothing would receive a finished
        // command. Dropping the callback stops a dead ViewModel being woken.
        if (active == null) BackgroundCommandService.onFinished = null
        // Every MCP server holds an SSE channel and a thread. Releasing them here
        // is what stops them outliving the screen.
        runCatching { aiEngine.mcp.close() }
        // The voice player holds an OkHttp client and a MediaPlayer; both must go
        // with the screen or the audio keeps playing after the app is closed.
        runCatching { voiceManager.shutdown() }
    }

    companion object {
        /**
         * The provider id of the phone's cloud agent. It is a storage key as well
         * as a routing key: the API key is saved under it and the model picker
         * reads it, so it must never change once shipped.
         */
        const val ZYRAXON_APEX = "zyraxon_apex"

        /** How often the streaming reply repaints the chat while it arrives. */
        private const val UI_UPDATE_MS = 60L

        /** How long a screen read is reused. Long enough to serve one turn's two
         * asks, short enough that a screen the user just changed is re-read. */
        private const val SCREEN_CACHE_MS = 2_000L

        /**
         * How often the running turn re-reads the screen. Two seconds, as the user
         * asked: while a job is in flight the phone is captured on this beat and
         * the newest frame is handed to the model at the top of each round.
         */
        private const val LIVE_FRAME_INTERVAL_MS = 2_000L

        /**
         * The floating bubble lives in a service and has no ViewModel of its own,
         * so it reaches the running one through here. Null whenever no screen is
         * alive, which the caller must handle.
         */
        @Volatile
        var active: ZyraxonViewModel? = null
            private set

        private val sharedStore = androidx.lifecycle.ViewModelStore()

        /**
         * The one assistant for the whole process. The screen and the floating eye
         * both use it, so the eye keeps working (mic, voice, tools) after the
         * screen is closed instead of finding nobody home.
         */
        @Synchronized
        fun shared(app: Application): ZyraxonViewModel =
            androidx.lifecycle.ViewModelProvider(
                sharedStore,
                androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
            )[ZyraxonViewModel::class.java]
    }
}
