package ai.zyraxon.assistant.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The phone's own microphone and voice.
 *
 * Two behaviours matter here and both were wrong before:
 *
 *  - **The microphone stays on.** It used to be cancelled and restarted for
 *    every spoken sentence, and restarted again on every recognizer error, so
 *    the mic flicked off and on dozens of times a minute. Now there is one
 *    continuous loop: the ear closes once while the assistant speaks and opens
 *    once when it finishes, and a restart is debounced so a failing recognizer
 *    cannot spin. [micEnabled] is the user's switch and it is the only thing
 *    that turns the microphone off.
 *
 *  - **The right voice speaks.** An engine holds one language at a time, so a
 *    sentence that mixes Bangla and English is spoken run by run with the voice
 *    switched between runs, and the voice is reset — never left on the previous
 *    language — when the new language has no installed voice. That leftover
 *    voice was why an English word came out letter by letter.
 */
class NativeVoiceManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _voiceGender = MutableStateFlow("female") // "female" or "male"
    val voiceGender: StateFlow<String> = _voiceGender.asStateFlow()

    /** False when the phone has no speech engine, so the UI can say so. */
    private val _ttsAvailable = MutableStateFlow(false)
    val ttsAvailable: StateFlow<Boolean> = _ttsAvailable.asStateFlow()

    /** The voice the engine actually selected, so the UI can prove which one spoke. */
    private val _activeVoiceName = MutableStateFlow("")
    val activeVoiceName: StateFlow<String> = _activeVoiceName.asStateFlow()

    /**
     * True when the language has no offline voice installed. The engine then
     * spells words out letter by letter, which is the defect the user reported,
     * so the UI offers the download instead of pretending it spoke.
     */
    private val _voiceDataMissing = MutableStateFlow(false)
    val voiceDataMissing: StateFlow<Boolean> = _voiceDataMissing.asStateFlow()

    /** BCP-47 tag used for both recognition and speech. */
    private val _voiceLanguage = MutableStateFlow("bn-BD")
    val voiceLanguage: StateFlow<String> = _voiceLanguage.asStateFlow()

    /**
     * The real voice the website used for the last sentence, from its
     * `X-ZYRAXON-Voice` header. Shown in Settings so the voice in use is a fact,
     * not an assumption.
     */
    private val _remoteVoiceName = MutableStateFlow("")
    val remoteVoiceName: StateFlow<String> = _remoteVoiceName.asStateFlow()

    /** The last voice error, so the UI can say why instead of going silent. */
    private val _voiceError = MutableStateFlow("")
    val voiceError: StateFlow<String> = _voiceError.asStateFlow()

    /** "website" while the online voice speaks, "phone" while the engine does. */
    private val _voiceSource = MutableStateFlow("website")
    val voiceSource: StateFlow<String> = _voiceSource.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    /**
     * The ZYRAXON website's own voice. It is the voice the assistant speaks with
     * whenever it is switched on and the phone is online, because the phone's
     * engine has one voice per language and no emotion, while the website has 322
     * voices, both Bangla genders, and four tones. The phone's engine stays as the
     * fallback so the assistant is never silent.
     */
    private val remoteVoice = RemoteTtsPlayer(context, scope) { text -> speakOnPhone(text) }

    /**
     * True when the website's voice should be used. The user owns this switch and
     * it persists; a phone with no network falls back to the engine regardless.
     */
    @Volatile
    private var useRemoteVoice = true

    private var currentRate = 1.0f
    private var currentPitch = 1.28f

    /**
     * The voice actually loaded into the engine, remembered so it is not
     * re-selected for every sentence. Assigning `TextToSpeech.voice` stops the
     * utterance that is playing — doing that once per streamed sentence is why
     * the voice cut out after a word or two and never finished the answer.
     */
    private var appliedVoiceKey: String? = null

    /**
     * The BCP-47 tag the engine is currently set to. `setLanguage` also stops the
     * utterance that is playing, so it is called only when the language really
     * changes — otherwise every streamed sentence would cut the previous one off.
     */
    private var appliedLanguageTag: String? = null

    /**
     * The user's own switch. True means the microphone should be open; the loop
     * below reopens it whenever it closes for any other reason. Nothing else may
     * change this.
     */
    @Volatile
    private var micEnabled = false

    /** True while a recognizer session is actually open. */
    @Volatile
    private var sessionOpen = false

    /** Wall-clock deadline until which a recognized phrase is the speaker's echo. */
    @Volatile
    private var echoGuardUntilMs = 0L

    private var restartJob: Job? = null
    private var restartAttempts = 0

    /**
     * Fires when the engine never calls back for a piece it accepted — a stalled
     * or crashed TTS service, or an utterance that was silently dropped. Without
     * it the queue waits forever and the assistant stops mid-answer, which is the
     * "she speaks and then stops" report.
     */
    private var speechWatchdog: Job? = null

    /** Decides the single close/open pair around a streamed reply. */
    private val micGate = MicGate()

    /**
     * Speech waiting to be spoken, drained one piece at a time. A piece is only
     * handed to the engine when the previous one has finished, because switching
     * the engine's language or voice stops whatever is currently playing — doing
     * that for every run up front cut the answer off after a word or two.
     */
    private val speechQueue = SpeechQueue()

    /** Bumped by [stopSpeaking], so ids from an abandoned queue are never reused. */
    private var speechGeneration = 0

    var onSpeechRecognized: ((text: String, isFinal: Boolean) -> Unit)? = null

    init {
        remoteVoice.onVoiceResolved = { name ->
            _remoteVoiceName.value = name
            _voiceError.value = ""
        }
        remoteVoice.onError = { message -> _voiceError.value = message }
        remoteVoice.onSpeakingChanged = { speaking ->
            _isSpeaking.value = speaking
            if (speaking) {
                _voiceSource.value = "website"
                micGate.onUtteranceStart()
            } else if (micGate.onUtteranceDone(false)) {
                resumeAfterSpeech()
            }
        }
        initTts()
        initStt()
    }

    /**
     * Switches between the website's voice and the phone's engine. Both are real
     * and both are kept working; the choice is the user's and it persists.
     */
    fun setUseRemoteVoice(enabled: Boolean) {
        useRemoteVoice = enabled
        if (!enabled) remoteVoice.stop()
    }

    val isUsingRemoteVoice: Boolean get() = useRemoteVoice

    /**
     * Speaks [text] through the phone's engine only. This is the fallback path
     * for the website's player, so it must never route back to the website — that
     * would be an endless loop of the two voices handing the same line over.
     */
    private fun speakOnPhone(text: String) {
        _voiceSource.value = "phone"
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        stopEngineOnly()
        enqueue(clean)
    }

    // ---- text to speech ----------------------------------------------------

    private fun initTts() {
        // com.google.android.tts carries the Bangla voice; the AOSP engine often
        // does not. Ask for it explicitly and fall back to whatever exists.
        textToSpeech = try {
            TextToSpeech(context, { status -> onTtsReady(status) }, "com.google.android.tts")
        } catch (_: Exception) {
            TextToSpeech(context) { status -> onTtsReady(status) }
        }
    }

    private fun onTtsReady(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _ttsAvailable.value = false
            return
        }
        ttsReady = true
        _ttsAvailable.value = true
        applyVoiceLanguage(_voiceLanguage.value)
        applyVoiceSettings(_voiceGender.value, currentRate, currentPitch, _voiceLanguage.value)
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                // The ear is deliberately NOT closed here. Closing it for the
                // whole reply is what stopped the user interrupting: they could
                // say nothing until the last word. The microphone stays open and
                // the assistant's own voice is filtered out by text instead
                // (`EchoMatcher`), so a genuine interruption still gets through.
                micGate.onUtteranceStart()
            }

            override fun onDone(utteranceId: String?) {
                finishUtterance(utteranceId)
            }

            override fun onError(utteranceId: String?) {
                finishUtterance(utteranceId)
            }
        })
    }

    /**
     * One utterance finished. The internal queue is drained one piece at a time,
     * each with its own voice switch, so a piece still waiting is what keeps the
     * engine "busy": the ear reopens only once the queue is empty, the engine is
     * idle, and the reply has stopped streaming.
     */
    private fun finishUtterance(utteranceId: String?) {
        cancelSpeechWatchdog()
        speechQueue.onFinished(utteranceId)
        // Hand the next piece to the engine before judging whether we are busy,
        // so a queued sentence is never mistaken for the end of the reply.
        pumpNext()
        val busy = engineBusy()
        val reopen = micGate.onUtteranceDone(busy)
        // Keep the "speaking" flag up across the gap between two sentences of the
        // same reply, so the UI does not flicker to "listening" mid-answer.
        if (!busy && !micGate.replyActive) _isSpeaking.value = false
        if (reopen) resumeAfterSpeech()
    }

    /**
     * True while a piece is being spoken or more speech is still waiting. This is
     * judged from our own queues rather than `TextToSpeech.isSpeaking`, whose value
     * at the instant `onDone` fires is racy — trusting it could leave the ear shut
     * after the last word, which reads as the microphone never coming back.
     */
    private fun engineBusy(): Boolean = !speechQueue.isEmpty() || remoteVoice.hasPending()

    /**
     * Opens a spoken reply. Called once when a reply starts streaming, so the ear
     * stays shut across the gaps between sentences instead of flicking on in each
     * one. The pause itself happens when the first word is actually spoken — a
     * reply that spends a while on tools never holds the microphone shut.
     * [endReply] must be called when the reply is finished.
     */
    fun beginReply() {
        // A new answer must be heard from its own first word. Anything still
        // speaking from the previous answer is cut off here, so the user never
        // hears the old reply's tail mixed into the new one — the complaint that
        // the previous speech kept playing after a new message was sent.
        remoteVoice.stop()
        stopEngineOnly()
        micGate.beginReply()
    }

    /**
     * True while the phone's own speaker is talking, plus a short tail after the
     * last word. The recognizer keeps running for a moment once the engine stops
     * and hears the speaker's decay — without this tail that echo is a final
     * result, which is what sent the assistant's own words back into the chat.
     */
    fun isSpeakerActive(): Boolean = _isSpeaking.value || System.currentTimeMillis() < echoGuardUntilMs

    private fun armEchoGuard() {
        echoGuardUntilMs = System.currentTimeMillis() + ECHO_TAIL_MS
    }

    /** Closes the spoken reply and reopens the ear once the last word has played. */
    fun endReply() {
        armEchoGuard()
        if (micGate.endReply(engineBusy())) {
            _isSpeaking.value = false
            resumeAfterSpeech()
        }
    }

    /** Releases the remote voice player. Called when the ViewModel is cleared. */
    fun shutdown() {
        remoteVoice.shutdown()
        try { textToSpeech?.shutdown() } catch (_: Exception) {}
        textToSpeech = null
        ttsReady = false
    }

    /**
     * Points the engine at a language and reports whether its voice data is
     * installed. Bengali is tried under every tag an engine might file it under
     * before the language is called missing, so a phone with `bn-IN` but not
     * `bn-BD` still speaks Bangla instead of dropping to English.
     */
    private fun applyVoiceLanguage(tag: String) {
        if (!ttsReady || textToSpeech == null) return
        // `setLanguage` interrupts whatever is being spoken, so a run that is
        // already in the wanted language is left untouched. Without this every
        // streamed sentence reset the engine and the reply stopped mid-word.
        if (tag == appliedLanguageTag) return
        val wanted = Locale.forLanguageTag(tag)
        val candidates = if (wanted.language == "bn") {
            listOf(wanted, Locale.forLanguageTag("bn-IN"), Locale.forLanguageTag("bn"))
        } else {
            listOf(wanted)
        }

        var resolved = false
        for (candidate in candidates) {
            val res = textToSpeech?.setLanguage(candidate)
            val ok = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            if (ok) {
                resolved = true
                break
            }
        }

        appliedLanguageTag = tag
        // The voice is re-picked for the new language, so the cache must not
        // claim the old one is still loaded.
        appliedVoiceKey = null
        _voiceDataMissing.value = !resolved
        if (!resolved) {
            textToSpeech?.setLanguage(Locale.US)
        }
    }

    /**
     * Switches both recognition and speech to a language. Recognition restarts
     * so the new locale takes effect on the next utterance.
     */
    fun setVoiceLanguage(tag: String) {
        _voiceLanguage.value = tag
        recognizerIntent?.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        recognizerIntent?.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
        applyVoiceLanguage(tag)
        applyVoiceSettings(_voiceGender.value, currentRate, currentPitch, tag)
        if (micEnabled) {
            // One clean restart for the new locale; the loop takes over from here.
            try { speechRecognizer?.cancel() } catch (_: Exception) {}
            sessionOpen = false
            scheduleRestart(immediate = true)
        }
    }

    /**
     * The voice in use right now: "female" or "male". Exposed so a caller can
     * report the real state instead of an assumed one.
     */
    val currentGender: String get() = _voiceGender.value

    fun setVoiceGender(gender: String) {
        val g = if (gender.lowercase() == "male") "male" else "female"
        _voiceGender.value = g
        val targetPitch = if (g == "female") 1.28f else 0.82f
        val targetRate = if (g == "female") 1.0f else 0.95f
        currentPitch = targetPitch
        currentRate = targetRate
        applyVoiceSettings(g, targetRate, targetPitch, _voiceLanguage.value)
    }

    fun setSpeechRate(rate: Float) {
        currentRate = rate
        textToSpeech?.setSpeechRate(rate)
    }

    fun setPitch(pitch: Float) {
        currentPitch = pitch
        textToSpeech?.setPitch(pitch)
    }

    /**
     * Sets the pitch/rate for the requested gender and, for [forLanguage], picks
     * the matching installed voice.
     *
     * When the language has no voice that states the wanted gender, the engine's
     * own default for that language is used — the voice is cleared first, so it
     * can never fall back to the *previous* run's voice, which is what made an
     * English word come out of the Bangla voice letter by letter.
     */
    private fun applyVoiceSettings(gender: String, rate: Float, pitch: Float, forLanguage: String) {
        if (!ttsReady || textToSpeech == null) return
        try {
            val wanted = Locale.forLanguageTag(forLanguage)
            val all = textToSpeech?.voices.orEmpty().toList()
            val candidates = all.map { v ->
                VoiceSelector.Candidate(
                    name = v.name,
                    localeTag = v.locale.toLanguageTag(),
                    isNetwork = v.isNetworkConnectionRequired,
                    requiresNetwork = v.isNetworkConnectionRequired
                )
            }
            // Prefer a voice that states the wanted gender. When this language has
            // no such voice, fall back to any voice *for this language* rather than
            // leaving the engine on the previous language's voice: that leftover
            // voice is what read English words letter by letter and spoke Bangla in
            // the wrong gender. Only when the language has no voice at all does the
            // engine's own default stand.
            val chosen = VoiceSelector.pick(candidates, forLanguage, gender)
                ?: VoiceSelector.pickAny(candidates, forLanguage)
            val match = chosen?.let { c -> all.firstOrNull { it.name == c.name } }

            // The voice is only reassigned when it really changes. Assigning it
            // stops whatever is currently being spoken, so re-selecting the same
            // voice for every sentence of a streamed reply cut the speech off
            // after a word or two — the exact defect the user reported.
            val voiceKey = match?.name ?: ""
            if (voiceKey != appliedVoiceKey) {
                textToSpeech?.voice = match
                _activeVoiceName.value = match?.name ?: ""
                appliedVoiceKey = voiceKey
            }

            // When no installed voice for this language states the wanted gender,
            // the gender is carried by pitch instead. Bangla usually has only one
            // neutral voice while English has a female one, so without this the
            // Bangla words came out male and the English words female.
            val hasGenderedVoice = match != null &&
                VoiceSelector.statesGender(match.name, gender)
            val effectivePitch = VoiceSelector.pitchFor(gender, pitch, hasGenderedVoice)

            // The engine resets pitch/rate when the voice changes, so they are
            // (re)applied after any switch — and always, since tone varies by run.
            textToSpeech?.setPitch(effectivePitch)
            textToSpeech?.setSpeechRate(rate)

            _voiceDataMissing.value = !VoiceSelector.hasOfflineVoice(candidates, forLanguage) &&
                wanted.language.isNotBlank()
        } catch (_: Exception) {}
    }

    // ---- speech recognition ------------------------------------------------

    private fun initStt() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    sessionOpen = true
                }

                override fun onBeginningOfSpeech() {
                    sessionOpen = true
                }

                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    _audioLevel.value = normalized
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _audioLevel.value = 0f
                }

                override fun onError(error: Int) {
                    _audioLevel.value = 0f
                    sessionOpen = false
                    // A pause, a phrase that did not match, a busy engine — the
                    // recognizer ends a session constantly and reports each end
                    // here. Those are the normal end of one phrase, so the
                    // listening state is *not* touched: it belongs to the user's
                    // switch, not to any one recognizer session. Flipping it here
                    // is what made the indicator flicker off and on.
                    // A restart is only ever scheduled by the loop, never here, so
                    // a recognizer that fails instantly cannot spin.
                    if (micEnabled) scheduleRestart()
                }

                override fun onResults(results: Bundle?) {
                    _audioLevel.value = 0f
                    sessionOpen = false
                    // Everything the recognizer hears is passed up, including while
                    // the assistant is talking: the caller filters the assistant's
                    // own voice out by comparing the text (`EchoMatcher`), and that
                    // is what lets a real interruption through instead of muting
                    // the user until the reply ends.
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?.let { onSpeechRecognized?.invoke(it, true) }
                    if (micEnabled) {
                        restartAttempts = 0
                        scheduleRestart()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // Partials are delivered during the assistant's speech too, so
                    // the user can cut in mid-answer; the caller drops the ones
                    // that are really the assistant's own voice coming back.
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?.let { onSpeechRecognized?.invoke(it, false) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, _voiceLanguage.value)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, _voiceLanguage.value)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
        }
    }

    /** Reopens the ear once the assistant has finished the whole reply. */
    private fun resumeAfterSpeech() {
        if (!micEnabled) return
        // A short tail: the speaker keeps ringing for a moment after the last
        // word, and that tail is what the recognizer would otherwise latch onto.
        scheduleRestart(delayMs = SPEAKER_TAIL_MS)
    }

    /**
     * Opens the microphone again, with a debounce and a backoff. The debounce
     * collapses the several "restart" calls that one turn can produce into a
     * single open; the backoff stops a recognizer that fails immediately from
     * being restarted in a tight loop.
     */
    private fun scheduleRestart(immediate: Boolean = false, delayMs: Long = 0L) {
        // The mic is reopened even while the assistant is speaking, so the user
        // can cut in at any moment. The assistant's own words are filtered by
        // text, so an open ear does not feed the reply back into the chat.
        if (!micEnabled) return
        if (sessionOpen) return
        restartJob?.cancel()
        val backoff = if (immediate) 0L else minOf(RESTART_BASE_MS shl restartAttempts, RESTART_MAX_MS)
        restartJob = scope.launch(Dispatchers.Main) {
            delay(maxOf(delayMs, backoff))
            if (!micEnabled || sessionOpen) return@launch
            val intent = recognizerIntent ?: return@launch
            // Every attempt counts, not only a successful one. A recognizer that
            // refuses to start (a busy engine, a denied permission) used to leave
            // the counter at zero, so the backoff never grew and the loop hammered
            // it every 300 ms — the on/off churn the user heard.
            restartAttempts = minOf(restartAttempts + 1, 6)
            try {
                speechRecognizer?.startListening(intent)
                sessionOpen = true
            } catch (_: Exception) {
                sessionOpen = false
                // A failed start is retried after the backoff, and the listening
                // state is left alone: it is the user's switch, not this session.
                scheduleRestart()
            }
        }
    }

    /**
     * The user's switch. `true` keeps the microphone open for as long as the app
     * runs; `false` closes it and leaves it closed. Nothing else turns it on.
     * The listening state is exactly this switch, so the UI shows what the user
     * chose instead of the recognizer's per-phrase churn.
     */
    fun setMicEnabled(enabled: Boolean) {
        if (enabled == micEnabled) return
        micEnabled = enabled
        if (enabled) {
            restartAttempts = 0
            _isListening.value = true
            scheduleRestart(immediate = true)
        } else {
            restartJob?.cancel()
            restartJob = null
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            sessionOpen = false
            _isListening.value = false
            _audioLevel.value = 0f
        }
    }

    val microphoneEnabled: Boolean get() = micEnabled

    fun startListening() = setMicEnabled(true)

    fun stopListening() = setMicEnabled(false)

    /**
     * Opens the engine's own "install voice data" screen. Google's engine shows
     * the language list there and downloads on tap, which is the only way to get
     * a voice for a language the phone has never spoken.
     */
    fun openVoiceDataInstall(tag: String) {
        try {
            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("lang", tag)
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = context.packageManager
                    .getLaunchIntentForPackage("com.google.android.tts")
                fallback?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (fallback != null) context.startActivity(fallback)
            } catch (_: Exception) {}
        }
    }

    // ---- speaking ----------------------------------------------------------

    /**
     * Speaks [text]. The ZYRAXON website's voice is used whenever it is switched
     * on, and the phone's own engine only when it is not — the phone's engine is
     * never the first choice because it has one voice per language and no emotion.
     *
     * The website answers with `audio/mpeg` and starts sending the first sentence
     * while the rest is still being made, so playback begins on the first bytes
     * instead of waiting for the whole file.
     */
    fun speak(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        if (useRemoteVoice) {
            _voiceSource.value = "website"
            speakRemote(clean, replace = true)
            return
        }
        // A fresh reply replaces anything already queued or playing.
        stopEngineOnly()
        enqueue(clean)
    }

    /**
     * Appends one finished sentence so a long answer is heard while it is still
     * arriving, instead of waiting for the last word. Used by the streaming reply.
     */
    fun speakQueued(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        if (useRemoteVoice) {
            speakRemote(clean, replace = false)
            return
        }
        enqueue(clean)
    }

    /**
     * Hands [clean] to the website's voice, one language at a time.
     *
     * The endpoint's voice is chosen by locale, and one voice reads only one
     * language. An English word inside a Bangla sentence would be spelled out
     * letter by letter by the Bangla voice — the exact defect the phone engine had
     * — so the sentence is split into same-language runs first and each run is
     * requested with its own locale. A single-language sentence stays one request,
     * which keeps the prosody whole.
     */
    private fun speakRemote(clean: String, replace: Boolean) {
        val segments = SpeechSegmenter.split(clean)
        if (segments.isEmpty()) return
        segments.forEachIndexed { index, segment ->
            if (index == 0 && replace) {
                remoteVoice.speak(segment.text, segment.bcp47, _voiceGender.value)
            } else {
                remoteVoice.enqueue(segment.text, segment.bcp47, _voiceGender.value)
            }
        }
    }

    /**
     * Cuts the engine off and clears the queue without touching the microphone
     * state. Used when a new reply starts so it is heard from its first word.
     */
    private fun stopEngineOnly() {
        try { textToSpeech?.stop() } catch (_: Exception) {}
        cancelSpeechWatchdog()
        speechGeneration++
        speechQueue.clear()
    }

    /**
     * Splits [clean] into one-language runs, turns each into an utterance with its
     * own tone, and appends them to the speech queue. Nothing is handed to the
     * engine here: [pumpNext] speaks them one at a time, because switching the
     * engine's language or voice stops whatever is playing — doing that for every
     * run up front cut the answer off after a word or two.
     */
    private fun enqueue(clean: String) {
        if (!(ttsReady && textToSpeech != null)) {
            _isSpeaking.value = false
            return
        }
        val generation = speechGeneration
        val segments = SpeechSegmenter.split(clean)
        if (segments.isEmpty()) return
        val base = speechQueue.size
        speechQueue.enqueue(
            segments.mapIndexed { index, segment ->
                val tone = SpeechTone.of(segment.text)
                SpeechUtterance(
                    text = segment.text,
                    bcp47 = segment.bcp47,
                    pitch = (currentPitch * tone.pitchFactor).coerceIn(0.4f, 2.0f),
                    rate = (currentRate * tone.rateFactor).coerceIn(0.4f, 2.0f),
                    id = "zyraxon_${generation}_${base + index}"
                )
            }
        )
        pumpNext()
    }

    /**
     * Hands exactly one queued piece to the engine, with its language and voice
     * set first — safe here because nothing is playing between pieces. The rest
     * wait for the engine's own onDone, which calls back into here.
     */
    private fun pumpNext() {
        if (!(ttsReady && textToSpeech != null)) {
            speechQueue.clear()
            _isSpeaking.value = false
            return
        }
        val utterance = speechQueue.takeNext() ?: return
        // Set the language and voice while the engine is idle, then speak. The
        // switch can no longer cut off a previous piece, which is what stopped the
        // voice after a word or two.
        applyVoiceLanguage(utterance.bcp47)
        applyVoiceSettings(_voiceGender.value, utterance.rate, utterance.pitch, utterance.bcp47)
        val res = try {
            textToSpeech?.speak(utterance.text, TextToSpeech.QUEUE_ADD, utteranceParams(), utterance.id)
        } catch (_: Exception) {
            TextToSpeech.ERROR
        }
        if (res == TextToSpeech.SUCCESS) {
            speechQueue.markSpoken(utterance.id)
            _isSpeaking.value = true
            armSpeechWatchdog(utterance)
        } else {
            // This piece could not be spoken; try the next without spinning.
            pumpNext()
        }
    }

    /**
     * A piece the engine accepted must finish. The engine normally answers with
     * onDone, but a stalled service, a killed engine process or a dropped
     * utterance leaves the queue waiting and the answer stops mid-sentence. The
     * watchdog estimates how long the piece should take and, if no callback has
     * arrived by then, drops it and moves to the next one so speech continues.
     */
    private fun armSpeechWatchdog(utterance: SpeechUtterance) {
        speechWatchdog?.cancel()
        val words = utterance.text.count { it == ' ' } + 1
        // ~2.4 words/second at rate 1, scaled by the rate, with a 4s floor and an
        // 8s cushion on top so a slow but healthy engine is never cut short.
        val spokenMs = (words / (2.4f * utterance.rate) * 1000f).toLong()
        val budget = (spokenMs + 8_000L).coerceAtLeast(6_000L)
        speechWatchdog = scope.launch {
            delay(budget)
            if (speechQueue.isAwaiting(utterance.id)) {
                speechQueue.onFinished(utterance.id)
                finishUtterance(utterance.id)
            }
        }
    }

    private fun cancelSpeechWatchdog() {
        speechWatchdog?.cancel()
        speechWatchdog = null
    }

    private fun utteranceParams(): Bundle = Bundle().apply {
        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "zyraxon_msg")
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
    }

    /**
     * Strips markdown and emoji before speech. The engine reads them literally
     * ("asterisk asterisk", "smiling face") and the result sounds broken.
     */
    private fun cleanForSpeech(text: String): String = SpeechTextCleaner.clean(text)

    fun stopSpeaking() {
        remoteVoice.stop()
        stopEngineOnly()
        micGate.reset()
        _isSpeaking.value = false
        resumeAfterSpeech()
    }

    companion object {
        /** How long the speaker rings after the last word before the ear reopens. */
        private const val SPEAKER_TAIL_MS = 350L

        /**
         * How long after the last word a recognized phrase is treated as the
         * speaker's own echo. It is longer than [SPEAKER_TAIL_MS] because the
         * recognizer can deliver a final result slightly after the ear reopens.
         */
        private const val ECHO_TAIL_MS = 1_200L

        /** Debounce before reopening the microphone, and the backoff ceiling. */
        private const val RESTART_BASE_MS = 300L
        private const val RESTART_MAX_MS = 4000L
    }
}
