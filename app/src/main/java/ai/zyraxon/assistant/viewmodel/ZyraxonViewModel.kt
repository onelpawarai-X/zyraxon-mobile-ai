package ai.zyraxon.assistant.viewmodel

import ai.zyraxon.assistant.DeviceBridge
import ai.zyraxon.assistant.ai.ToolRegistry
import ai.zyraxon.assistant.ai.ContextWindow
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
import ai.zyraxon.assistant.model.ToolExecutionItem
import ai.zyraxon.assistant.model.WorkspaceFile
import ai.zyraxon.assistant.shell.GitRuntime
import ai.zyraxon.assistant.shell.ShellRuntime
import ai.zyraxon.assistant.ai.OnDeviceModel
import ai.zyraxon.assistant.ai.ProviderCatalog
import ai.zyraxon.assistant.ai.ProviderSpec
import ai.zyraxon.assistant.voice.LiveVoiceSession
import ai.zyraxon.assistant.voice.NativeVoiceManager
import ai.zyraxon.assistant.voice.SpeechChunker
import ai.zyraxon.assistant.update.Updater
import ai.zyraxon.assistant.update.UpdateRelease
import ai.zyraxon.assistant.update.UpdateState
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import java.util.UUID

class ZyraxonViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("zyraxon_vm_prefs", Context.MODE_PRIVATE)

    // SQLite Local Room Database & Repository
    val database = ZyraxonDatabase.getDatabase(application)
    val repository = ZyraxonRepository(database.dao())

    val aiEngine = ZyraxonAiEngine(application)
    val voiceManager = NativeVoiceManager(application, viewModelScope)

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
            // The terminal is a full-screen takeover, so a restart always lands on
            // the chat rather than dropping the user into a shell they did not ask
            // for. The scrollback itself is still there when they open it again.
            .getOrDefault(AppTab.CHAT)
            .takeIf { it != AppTab.TERMINAL } ?: AppTab.CHAT
    )
    val activeTab: StateFlow<AppTab> = _activeTab.asStateFlow()

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

    /** True when the last screen read came back as text (no screenshot API). */
    private val _screenTextSnapshot = MutableStateFlow("")
    val screenTextSnapshot: StateFlow<String> = _screenTextSnapshot.asStateFlow()

    /** The looping clip behind the aura. Empty means "no video, use the shader". */
    private val _bgVideoAsset = MutableStateFlow(prefs.getString("bg_video_asset", "girl.mp4") ?: "girl.mp4")
    val bgVideoAsset: StateFlow<String> = _bgVideoAsset.asStateFlow()

    // Persistent Chat Messages from Room SQLite
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _systemStatus = MutableStateFlow(SystemStatus())
    val systemStatus: StateFlow<SystemStatus> = _systemStatus.asStateFlow()

    // Voice Selection (Female vs Male)
    val voiceGender: StateFlow<String> = voiceManager.voiceGender
    val ttsAvailable: StateFlow<Boolean> = voiceManager.ttsAvailable
    val voiceLanguage: StateFlow<String> = voiceManager.voiceLanguage
    val voiceDataMissing: StateFlow<Boolean> = voiceManager.voiceDataMissing

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

    private val _customBgUrl = MutableStateFlow(prefs.getString("custom_bg_url", "") ?: "")
    val customBgUrl: StateFlow<String> = _customBgUrl.asStateFlow()

    private val _animSpeed = MutableStateFlow(prefs.getFloat("anim_speed", 1.0f))
    val animSpeed: StateFlow<Float> = _animSpeed.asStateFlow()

    private val _glassBlur = MutableStateFlow(prefs.getInt("glass_blur", 14))
    val glassBlur: StateFlow<Int> = _glassBlur.asStateFlow()

    // The aura's look. Every value is written straight into the aura page as a
    // CSS variable, so the user can reshape the orb without a rebuild.
    private val _auraHue = MutableStateFlow(prefs.getInt("aura_hue", 325))
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
                    if (release != null) _updateBannerDismissed.value = false
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
            updater.download(release) { progress ->
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

    /** Installs a downloaded update in place. Asks for the permission if needed. */
    fun installUpdate() {
        val state = _updateState.value
        val path = (state as? UpdateState.Downloaded)?.apkPath ?: return
        val file = java.io.File(path)
        if (!file.exists()) {
            _updateState.value = UpdateState.Failed("ডাউনলোড করা ফাইল পাওয়া গেল না")
            return
        }
        if (!updater.canInstall()) {
            updater.openInstallPermissionSettings()
            _updateState.value = UpdateState.NeedsPermission(state.release)
            return
        }
        runCatching { updater.install(file) }.onFailure { error ->
            _updateState.value = UpdateState.Failed(error.message ?: "ইনস্টল শুরু করা গেল না")
        }
    }

    // Tasks & Memories from SQLite Room Database
    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()

    val memories: StateFlow<List<MemoryItem>> = repository.allMemories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Tools Execution History
    private val _toolExecutions = MutableStateFlow<List<ToolExecutionItem>>(
        listOf(
            ToolExecutionItem(
                name = "সিস্টেম ইনিশিয়ালাইজেশন",
                description = "ভয়েস ইঞ্জিন ও লোকাল এসকিউএল ডাটাবেজ সক্রিয়",
                icon = "⚡",
                status = "সফল",
                timestamp = currentTimeString()
            )
        )
    )
    val toolExecutions: StateFlow<List<ToolExecutionItem>> = _toolExecutions.asStateFlow()

    // Live panels the model opens through tools
    // While a turn is running, new user words are injected into that same turn
    // instead of starting a second one, so the assistant never stops mid-task.
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // The floating chat bubble. Off by default: it needs the overlay permission,
    // which only the user can grant.
    private val _overlayEnabled = MutableStateFlow(prefs.getBoolean("overlay_enabled", false))
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    private val _overlayExpanded = MutableStateFlow(false)
    val overlayExpanded: StateFlow<Boolean> = _overlayExpanded.asStateFlow()

    private val pendingInjections = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private val _codeBoxOpen = MutableStateFlow(false)
    val codeBoxOpen: StateFlow<Boolean> = _codeBoxOpen.asStateFlow()
    private val _codeBoxTitle = MutableStateFlow("")
    val codeBoxTitle: StateFlow<String> = _codeBoxTitle.asStateFlow()
    private val _codeBoxText = MutableStateFlow("")
    val codeBoxText: StateFlow<String> = _codeBoxText.asStateFlow()
    private val _filesPanelOpen = MutableStateFlow(false)
    val filesPanelOpen: StateFlow<Boolean> = _filesPanelOpen.asStateFlow()

    // ---- Terminal tab -------------------------------------------------------
    // One scrollback of real command lines and their real output, shared by the
    // user typing and by the assistant calling `terminal_run`. The assistant and
    // the user are literally looking at the same shell.

    private val _terminalLines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val terminalLines: StateFlow<List<TerminalLine>> = _terminalLines.asStateFlow()

    private val _terminalBusy = MutableStateFlow(false)
    val terminalBusy: StateFlow<Boolean> = _terminalBusy.asStateFlow()

    /** The directory the next command runs in, shown in the terminal prompt. */
    private val _terminalCwd = MutableStateFlow("~")
    val terminalCwd: StateFlow<String> = _terminalCwd.asStateFlow()

    /** Files the assistant has written, shown in the Files panel. */
    private val _workspaceFiles = MutableStateFlow<List<WorkspaceFile>>(emptyList())
    val workspaceFiles: StateFlow<List<WorkspaceFile>> = _workspaceFiles.asStateFlow()

    val isListening: StateFlow<Boolean> = voiceManager.isListening
    val isSpeaking: StateFlow<Boolean> = voiceManager.isSpeaking
    val audioLevel: StateFlow<Float> = voiceManager.audioLevel

    init {
        active = this
        // A server's sampling request runs on whatever model the user picked, so
        // it is answered by the same brain as the chat rather than a fixed one.
        aiEngine.samplingModelProvider = { _selectedModel.value }

        // Restore voice gender preference
        val savedGender = prefs.getString("voice_gender", "female") ?: "female"
        voiceManager.setVoiceGender(savedGender)
        prefs.getString("voice_language", null)?.let { voiceManager.setVoiceLanguage(it) }

        // Observe messages from SQLite Room Database
        viewModelScope.launch {
            repository.allMessages.collectLatest { stored ->
                if (stored.isEmpty()) {
                    val initialGreeting = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        text = _selectedPersona.value.greeting,
                        timestamp = currentTimeString(),
                        modelName = _selectedModel.value.name
                    )
                    _messages.value = listOf(initialGreeting)
                    repository.saveMessage(initialGreeting)
                } else {
                    _messages.value = stored
                }
            }
        }

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

        viewModelScope.launch {
            repository.allTasks.collectLatest { stored -> _tasks.value = stored }
        }

        // Connect STT
        voiceManager.onSpeechRecognized = { text, isFinal ->
            // The microphone can hear the phone's own speaker. Anything that
            // arrives while the assistant is speaking — or in the short tail
            // after the last word — is the assistant's own voice coming back, so
            // it is dropped rather than sent as a user message. That echo is what
            // made the app answer itself and post its own words into the chat.
            val echo = isSpeaking.value || voiceManager.isSpeakerActive()
            if (text.isNotBlank() && !echo && !liveVoice.isActive) {
                // The user speaking over the assistant is a normal interruption,
                // not an error: cut the voice and take the new words.
                if (!isFinal) onUserStartedSpeaking()
                _inputText.value = text
                if (isFinal && _autoSend.value) {
                    sendMessage(text)
                }
            }
        }

        // The microphone follows the user's saved choice: on from the first
        // frame when they left it on, off when they left it off.
        if (_alwaysListening.value && _systemStatus.value.micPermissionGranted) {
            voiceManager.startListening()
        }

        // Fetch Live Models on startup
        refreshModels()
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

    fun setVoiceGender(gender: String) {
        val g = if (gender.lowercase() == "male") "male" else "female"
        voiceManager.setVoiceGender(g)
        prefs.edit().putString("voice_gender", g).apply()
        // Provide audio preview of selected voice
        val sample = if (g == "female") "হ্যালো সোনা! এটি মিষ্টি মেয়ের কণ্ঠ।" else "নমস্কার! এটি গভীর স্পষ্ট ছেলের কণ্ঠ।"
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
                        adoptDefaultIfUnset(partial)
                    }
                }
                if (live.isNotEmpty()) {
                    _availableModels.value = live
                    adoptDefaultIfUnset(live)
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
     */
    private fun adoptDefaultIfUnset(models: List<AiModel>) {
        val current = _selectedModel.value
        if (current.id.isBlank()) {
            _selectedModel.value = models[0]
            return
        }
        // A saved model keeps its place; its friendly name is refreshed from the
        // catalogue when the provider is reachable.
        val match = models.firstOrNull { it.id == current.id }
        if (match != null && match.name != current.name) {
            _selectedModel.value = match
        }
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
        _inputText.value = text
    }

    fun setTab(tab: AppTab) {
        _activeTab.value = tab
        prefs.edit().putString("active_tab", tab.name).apply()
    }

    fun setPersona(persona: Persona) {
        _selectedPersona.value = persona
        prefs.edit().putString("selected_persona_id", persona.id).apply()
        // A male character speaking with the sweet female voice would break the
        // illusion instantly, so the voice follows the character.
        applyVoice(gender = if (persona.id == "boyfriend") "male" else "female")
        val greeting = ChatMessage(
            role = MessageRole.ASSISTANT,
            text = persona.greeting,
            timestamp = currentTimeString(),
            modelName = _selectedModel.value.name
        )
        _messages.value = _messages.value + greeting
        viewModelScope.launch {
            repository.saveMessage(greeting)
        }
        if (shouldUsePhoneTts()) {
            voiceManager.speak(persona.greeting)
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
     * Applies a voice change requested by the assistant itself. Every field is
     * optional, so a tool call can adjust only the pitch, or only the gender,
     * without resetting what the user already chose.
     */
    fun applyVoice(gender: String? = null, rate: Float? = null, pitch: Float? = null) {
        gender?.let { g ->
            // voiceManager owns the voice state and publishes it; there is no
            // second copy to keep in sync.
            voiceManager.setVoiceGender(g)
            prefs.edit().putString("voice_gender", g).apply()
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
        val sample = if (voiceGender.value == "female") {
            "হ্যালো সোনা! আমি ZYRAXON। মিষ্টি মেয়ের কণ্ঠে তোমার সব কথা শুনতে প্রস্তুত।"
        } else {
            "নমস্কার! আমি ZYRAXON। গম্ভীর স্পষ্ট ছেলের কণ্ঠে আপনাকে তথ্য দিতে প্রস্তুত।"
        }
        voiceManager.speak(sample)
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
        val allowed = setOf("orb", "twin", "vortex", "bloom", "helix", "core")
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
            val (ok, message) = aiEngine.verifyProviderKey(provider)
            _providerStatus.value = _providerStatus.value + (provider to message)
            _providerBusy.value = _providerBusy.value - provider
            if (ok) refreshModels()
        }
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
            repository.addTask(item)
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
        viewModelScope.launch {
            repository.clearMessages()
            val resetGreeting = ChatMessage(
                role = MessageRole.ASSISTANT,
                text = _selectedPersona.value.greeting,
                timestamp = currentTimeString(),
                modelName = _selectedModel.value.name
            )
            repository.saveMessage(resetGreeting)
            _messages.value = listOf(resetGreeting)
        }
    }

    fun toggleListening() {
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
     * True when the phone's own text-to-speech must stay silent: the user turned
     * it off, or a live speech-to-speech model is the voice right now.
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
     * realtime model. Off hands the voice back to the phone's text-to-speech, so
     * a realtime model the user cannot connect still speaks in the normal way.
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
        viewModelScope.launch { repository.saveMessage(finished) }
    }

    fun sendMessage(overridePrompt: String? = null) {
        val prompt = (overridePrompt ?: _inputText.value).trim()
        if (prompt.isBlank()) return

        _inputText.value = ""

        if (_isGenerating.value) {
            pendingInjections.add(prompt)
            _messages.value = _messages.value + ChatMessage(
                role = MessageRole.USER,
                text = prompt,
                timestamp = currentTimeString()
            )
            viewModelScope.launch {
                repository.saveMessage(_messages.value.last())
            }
            return
        }

        val userMsg = ChatMessage(
            role = MessageRole.USER,
            text = prompt,
            timestamp = currentTimeString()
        )

        val assistantMsgId = UUID.randomUUID().toString()
        val streamingAssistantMsg = ChatMessage(
            id = assistantMsgId,
            role = MessageRole.ASSISTANT,
            text = "...",
            timestamp = currentTimeString(),
            isStreaming = true,
            modelName = _selectedModel.value.name
        )

        _messages.value = _messages.value + userMsg + streamingAssistantMsg
        // Set before launching: a second message arriving in the same frame must
        // queue as an injection, not start a competing turn.
        _isGenerating.value = true

        viewModelScope.launch {
            repository.saveMessage(userMsg)
            var fullReply = ""
            val spokenBuffer = StringBuilder()
            // One spoken reply: the ear closes here and reopens once, in the
            // finally block. Without this pair the mic reopened in every gap
            // between streamed sentences, which is the on/off churn the user heard.
            val speakingReply = shouldUsePhoneTts()
            if (speakingReply) voiceManager.beginReply()
            try {
                // Fetch SQL memories to inject into AI prompt so it NEVER forgets!
                val memoriesContext = repository.getMemoriesPromptContext()
                // Long-term recall: turns from weeks ago that match what the user
                // just said come back with this turn, so an old conversation is
                // remembered without resending the whole transcript every time.
                val recalled = repository.recallRelevant(prompt, limit = ContextWindow.RECALL_LIMIT)
                val recalledIds = recalled.map { it.id }.toSet()
                val recent = _messages.value
                    .filter { it.id != assistantMsgId && it.id !in recalledIds }
                // Recalled old turns lead, then the newest turns, both trimmed to
                // fit the engine's twelve-turn window so nothing is dropped.
                val history = ContextWindow.assemble(recalled, recent)

                aiEngine.streamAiResponse(
                    prompt = prompt,
                    history = history,
                    persona = _selectedPersona.value,
                    model = _selectedModel.value,
                    memoriesContext = memoriesContext,
                    screenContext = screenContext(),
                    imageDataUrl = _screenFrameDataUrl.value,
                    onToolCall = { name, args -> runTool(name, args) },
                    drainInjections = {
                        val out = mutableListOf<String>()
                        while (true) out.add(pendingInjections.poll() ?: break)
                        out
                    }
                ).collect { chunk ->
                    fullReply += chunk
                    _messages.value = _messages.value.map { msg ->
                        if (msg.id == assistantMsgId) {
                            msg.copy(
                                text = fullReply,
                                isStreaming = true
                            )
                        } else {
                            msg
                        }
                    }
                    // Speak each finished sentence the moment it lands, so the voice
                    // keeps pace with the text instead of waiting for the whole answer.
                    if (shouldUsePhoneTts()) {
                        spokenBuffer.append(chunk)
                        val ready = SpeechChunker.take(spokenBuffer)
                        if (ready.isNotBlank()) voiceManager.speakQueued(ready)
                    }
                }
            } catch (e: Exception) {
                val reason = e.message ?: "সংযোগে সমস্যা হয়েছে"
                fullReply = if (fullReply.isBlank()) reason else fullReply
            } finally {
                _isGenerating.value = false
                // Speak the leftover sentence, then hand the microphone back
                // exactly once, after the engine has finished the last word.
                if (speakingReply && spokenBuffer.isNotBlank()) {
                    voiceManager.speakQueued(spokenBuffer.toString())
                }
                if (speakingReply) voiceManager.endReply()
            }

            if (fullReply.isBlank()) {
                fullReply = "কিছুই আসেনি — আবার বলো।"
            }

            val finalMsg = ChatMessage(
                id = assistantMsgId,
                role = MessageRole.ASSISTANT,
                text = fullReply,
                timestamp = currentTimeString(),
                isStreaming = false,
                modelName = _selectedModel.value.name
            )

            // Mark stream complete in memory
            _messages.value = _messages.value.map { msg ->
                if (msg.id == assistantMsgId) finalMsg else msg
            }

            // Persist Complete Assistant Response to SQLite Room
            repository.saveMessage(finalMsg)
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
    private fun screenContext(): String {
        val label = DeviceBridge.foregroundLabel()
        val where = if (label.isBlank()) "" else "ব্যবহারকারী এখন \"$label\" অ্যাপে আছেন (${DeviceBridge.foregroundPackage()})।"
        val snapshot = _screenTextSnapshot.value.take(2000)
        val text = if (snapshot.isBlank()) "" else "\nস্ক্রিনে এখন যা দেখা যাচ্ছে:\n$snapshot"
        return where + text
    }

    /**
     * Run one tool the model asked for and show it on the Tools tab. The result
     * is what actually happened — the model reads it back on the next round.
     */
    private suspend fun runTool(name: String, args: JSONObject): JSONObject {
        // MCP: a name carrying "<serverId>__<tool>" is a tool a connected server
        // advertised, so it goes straight to that server. The three management
        // tools below let the assistant connect and inspect servers by itself.
        if (name.contains("__")) {
            val result = aiEngine.mcp.callTool(name, args)
            recordToolExecution(name, result)
            return result
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
                    _tasks.value = list
                    repository.replaceTasks(list)
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
            "terminal_run" -> return runInTerminalShell(args)
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

        val result = ToolRegistry.execute(getApplication(), name, args)
        recordToolExecution(name, result)
        return result
    }

    /** Shows a tool on the Tools tab and keeps any picture it produced. */
    private fun recordToolExecution(name: String, result: JSONObject) {
        val item = ToolExecutionItem(
            name = name,
            description = result.optString("summary"),
            icon = if (result.optBoolean("ok")) "✅" else "⚠️",
            status = if (result.optBoolean("ok")) "সম্পন্ন" else "ব্যর্থ",
            timestamp = currentTimeString()
        )
        _toolExecutions.value = listOf(item) + _toolExecutions.value.take(49)

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
        recordToolExecution("mcp_read_resource", result)
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
        recordToolExecution("mcp_prompt", result)
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
        recordToolExecution("mcp_subscribe", result)
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
        recordToolExecution("mcp_complete", result)
        return result
    }

    private suspend fun runMcpPing(args: JSONObject): JSONObject {
        val server = args.optString("server").trim().lowercase()
        if (server.isBlank()) return JSONObject().put("ok", false).put("summary", "server দরকার")
        val result = aiEngine.mcp.ping(server)
        recordToolExecution("mcp_ping", result)
        return result
    }

    private suspend fun runMcpLogLevel(args: JSONObject): JSONObject {
        val server = args.optString("server").trim().lowercase()
        val level = args.optString("level").trim().lowercase()
        if (server.isBlank() || level.isBlank()) {
            return JSONObject().put("ok", false).put("summary", "server আর level দরকার")
        }
        val result = aiEngine.mcp.setLogLevel(server, level)
        recordToolExecution("mcp_log_level", result)
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
        if (line.isEmpty() || _terminalBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _terminalBusy.value = true
            try {
                runTerminalLine(line)
            } finally {
                _terminalBusy.value = false
            }
        }
    }

    /** The body of a terminal line, shared by the user's typing and `terminal_run`. */
    private suspend fun runTerminalLine(line: String): TerminalLine {
        val app = getApplication<Application>()
        val home = ShellRuntime.home(app)
        // `cd` is stateful: it changes where the *next* line runs, so it is
        // applied here rather than left to die with the process it started.
        parseCd(line, home)?.let { target ->
            val entry = if (target.isDirectory) {
                _terminalCwd.value = displayPath(home, target)
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
        // busybox has no git, so a `git` line is served by JGit instead of
        // failing with "applet not found".
        runGitLine(app, line)?.let { result ->
            appendTerminal(result)
            return result
        }
        val r = ShellRuntime.run(app, line, cwd = currentTerminalDir(home), timeoutMs = 120_000)
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
            command = line, output = body, exitCode = r.code,
            isError = r.code != 0 || r.timedOut, timedOut = r.timedOut, millis = r.millis
        )
        appendTerminal(entry)
        return entry
    }

    /**
     * `terminal_run` — the assistant driving the shell the user is watching.
     * Same shell, same cwd, same scrollback as the user's own typing.
     */
    private suspend fun runInTerminalShell(args: JSONObject): JSONObject {
        val line = args.optString("command").trim()
        if (line.isEmpty()) return JSONObject().put("ok", false).put("summary", "খালি কমান্ড")
        _activeTab.value = AppTab.TERMINAL
        val entry = withContext(Dispatchers.IO) { runTerminalLine(line) }
        return JSONObject()
            .put("ok", !entry.isError)
            .put("code", entry.exitCode)
            .put("stdout", if (entry.isError) "" else entry.output)
            .put("stderr", if (entry.isError) entry.output else "")
            .put("shell", "terminal")
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
        _terminalLines.value = (_terminalLines.value + entry).takeLast(400)
    }

    fun clearTerminal() {
        _terminalLines.value = emptyList()
    }

    /** The real directory the next command runs in. */
    private fun currentTerminalDir(home: java.io.File): java.io.File {
        val shown = _terminalCwd.value
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
        // Every MCP server holds an SSE channel and a thread. Releasing them here
        // is what stops them outliving the screen.
        runCatching { aiEngine.mcp.close() }
    }

    companion object {
        /**
         * The floating bubble lives in a service and has no ViewModel of its own,
         * so it reaches the running one through here. Null whenever no screen is
         * alive, which the caller must handle.
         */
        @Volatile
        var active: ZyraxonViewModel? = null
            private set
    }
}
