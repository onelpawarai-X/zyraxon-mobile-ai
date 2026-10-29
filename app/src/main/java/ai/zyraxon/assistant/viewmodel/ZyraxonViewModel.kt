package ai.zyraxon.assistant.viewmodel

import ai.zyraxon.assistant.DeviceBridge
import ai.zyraxon.assistant.ai.ToolRegistry
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
import ai.zyraxon.assistant.model.ToolExecutionItem
import ai.zyraxon.assistant.model.WorkspaceFile
import ai.zyraxon.assistant.voice.NativeVoiceManager
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

    private val _selectedPersona = MutableStateFlow(ZyraxonAiEngine.PERSONAS[0])
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

    // Nothing is selected until the live catalogue arrives — an invented
    // default would be a model the server may not even offer.
    private val _selectedModel = MutableStateFlow(AiModel("", "মডেল লোড হচ্ছে…", "", "", "", true))
    val selectedModel: StateFlow<AiModel> = _selectedModel.asStateFlow()

    private val _activeTab = MutableStateFlow(AppTab.CHAT)
    val activeTab: StateFlow<AppTab> = _activeTab.asStateFlow()

    private val _bgMode = MutableStateFlow(BgAnimationMode.LIVING_PINK_AURA)
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

    // Settings States
    private val _ttsEnabled = MutableStateFlow(prefs.getBoolean("tts_enabled", true))
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    private val _ttsRate = MutableStateFlow(prefs.getFloat("tts_rate", 1.0f))
    val ttsRate: StateFlow<Float> = _ttsRate.asStateFlow()

    private val _ttsPitch = MutableStateFlow(prefs.getFloat("tts_pitch", 1.25f))
    val ttsPitch: StateFlow<Float> = _ttsPitch.asStateFlow()

    private val _alwaysListening = MutableStateFlow(prefs.getBoolean("always_listening", false))
    val alwaysListening: StateFlow<Boolean> = _alwaysListening.asStateFlow()

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
            if (text.isNotBlank()) {
                // The user speaking over the assistant is a normal interruption,
                // not an error: cut the voice and take the new words.
                if (!isFinal) onUserStartedSpeaking()
                _inputText.value = text
                if (isFinal && _autoSend.value) {
                    sendMessage(text)
                }
            }
        }

        // Fetch Live Models on startup
        refreshModels()
    }

    private fun currentTimeString(): String {
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
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
                        if (partial.none { it.id == _selectedModel.value.id }) {
                            _selectedModel.value = partial[0]
                        }
                    }
                }
                if (live.isNotEmpty()) {
                    _availableModels.value = live
                    if (live.none { it.id == _selectedModel.value.id }) {
                        _selectedModel.value = live[0]
                    }
                }
            } catch (_: Exception) {
            } finally {
                _isLoadingModels.value = false
            }
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
        _selectedModel.value = customModel
    }

    fun onInputChange(text: String) {
        _inputText.value = text
    }

    fun setTab(tab: AppTab) {
        _activeTab.value = tab
    }

    fun setPersona(persona: Persona) {
        _selectedPersona.value = persona
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
        if (_ttsEnabled.value) {
            voiceManager.speak(persona.greeting)
        }
    }

    fun setModel(model: AiModel) {
        _selectedModel.value = model
    }

    fun setBgMode(mode: BgAnimationMode) {
        _bgMode.value = mode
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
        _alwaysListening.value = enabled
        prefs.edit().putBoolean("always_listening", enabled).apply()
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
        if (isListening.value) {
            voiceManager.stopListening()
        } else {
            voiceManager.startListening()
        }
    }

    /**
     * Opens the microphone and leaves it open. The user asked for a mic that is
     * never off, so there is no toggle on the aura — speech simply keeps flowing.
     */
    fun startAlwaysOnListening() {
        if (!_systemStatus.value.micPermissionGranted) return
        voiceManager.startListening()
    }

    fun updateMicPermission(granted: Boolean) {
        _systemStatus.value = _systemStatus.value.copy(micPermissionGranted = granted)
    }

    /** True when the user's own voice should cut the assistant off mid-sentence. */
    fun onUserStartedSpeaking() {
        if (isSpeaking.value) voiceManager.stopSpeaking()
    }

    fun speak(text: String) {
        if (_ttsEnabled.value) {
            voiceManager.speak(text)
        }
    }

    fun stopSpeaking() {
        voiceManager.stopSpeaking()
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
            try {
                // Fetch SQL memories to inject into AI prompt so it NEVER forgets!
                val memoriesContext = repository.getMemoriesPromptContext()

                aiEngine.streamAiResponse(
                    prompt = prompt,
                    history = _messages.value.filter { it.id != assistantMsgId },
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
                    if (_ttsEnabled.value) {
                        spokenBuffer.append(chunk)
                        val boundary = spokenBuffer.indexOfLast { it in "\n।!?;." }
                        if (boundary >= 0) {
                            val ready = spokenBuffer.substring(0, boundary + 1)
                            spokenBuffer.delete(0, boundary + 1)
                            if (ready.isNotBlank()) voiceManager.speakQueued(ready)
                        }
                    }
                }
            } catch (e: Exception) {
                val reason = e.message ?: "সংযোগে সমস্যা হয়েছে"
                fullReply = if (fullReply.isBlank()) reason else fullReply
            } finally {
                _isGenerating.value = false
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

            // Speak whatever is left after the final sentence boundary.
            if (_ttsEnabled.value && spokenBuffer.isNotBlank()) {
                voiceManager.speakQueued(spokenBuffer.toString())
            }
        }
    }

    fun onScreenFrame(base64Jpeg: String) {
        if (base64Jpeg.isBlank()) return
        _screenFrameDataUrl.value = "data:image/jpeg;base64,$base64Jpeg"
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
            "floating_bubble" -> {
                val want = args.optString("action", "on") != "off"
                val ctx = getApplication<Application>()
                if (want && !android.provider.Settings.canDrawOverlays(ctx)) {
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
