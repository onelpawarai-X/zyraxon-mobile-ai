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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

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

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    private var currentRate = 1.0f
    private var currentPitch = 1.28f

    // Master continuous listening flag: when user clicks Aura, mic stays active
    private var continuousListening = false

    var onSpeechRecognized: ((text: String, isFinal: Boolean) -> Unit)? = null

    init {
        initTts()
        initStt()
    }

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
                // Close the ear while the mouth is open. A recognizer that stays
                // live during playback hears the assistant's own voice, feeds it
                // back as a user message, and the pair talk to each other forever.
                pauseForSpeech()
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                resumeAfterSpeech()
            }

            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                resumeAfterSpeech()
            }
        })
    }

    /**
     * Points the engine at a language and reports whether its voice data is
     * installed. When it is not, the engine falls back to another language and
     * reads the words letter by letter — the exact defect reported — so
     * [voiceDataMissing] is raised and the UI offers the download.
     *
     * Bengali is tried under every tag an engine might file it under before the
     * language is called missing. A phone that has `bn-IN` but not `bn-BD` used
     * to be judged "no Bengali" and dropped to English, which read Bengali words
     * as noise; now the working tag is used instead.
     */
    private fun applyVoiceLanguage(tag: String) {
        if (!ttsReady || textToSpeech == null) return
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

        _voiceDataMissing.value = !resolved
        if (!resolved) {
            // English is the safest fallback for an unsupported run, but the flag
            // above already tells the UI that a download is what is really needed.
            textToSpeech?.setLanguage(Locale.US)
        }
    }

    /**
     * Switches both recognition and speech to a language. Recognition restarts so
     * the new locale takes effect on the next utterance.
     */
    fun setVoiceLanguage(tag: String) {
        _voiceLanguage.value = tag
        recognizerIntent?.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        recognizerIntent?.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
        applyVoiceLanguage(tag)
        applyVoiceSettings(_voiceGender.value, currentRate, currentPitch, tag)
        if (continuousListening) {
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
            restartListeningDelay()
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
        val targetPitch = if (g == "female") 1.28f else 0.86f
        val targetRate = if (g == "female") 1.0f else 0.96f
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
     * The language matters: the engine picks its voice from the language it is
     * set to, so a voice chosen while Bangla was loaded was silently replaced when
     * the engine switched to English — which is why the wrong gender spoke. This
     * takes the language explicitly so the voice is chosen for the run actually
     * being spoken.
     */
    private fun applyVoiceSettings(gender: String, rate: Float, pitch: Float, forLanguage: String) {
        if (!ttsReady || textToSpeech == null) return
        try {
            textToSpeech?.setSpeechRate(rate)
            textToSpeech?.setPitch(pitch)

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
            val chosen = VoiceSelector.pick(candidates, forLanguage, gender)
                ?: candidates.firstOrNull { it.localeTag.equals(forLanguage, ignoreCase = true) }
                ?: return
            val match = all.firstOrNull { it.name == chosen.name }
            if (match != null) {
                // The engine resets pitch when the voice changes, so set it after.
                textToSpeech?.voice = match
                textToSpeech?.setPitch(pitch)
                textToSpeech?.setSpeechRate(rate)
                _activeVoiceName.value = match.name
            }
            // A voice for a language with no data installed cannot be spoken; say
            // so instead of letting the engine spell the words out letter by letter.
            _voiceDataMissing.value = !VoiceSelector.hasOfflineVoice(candidates, forLanguage) &&
                wanted.language.isNotBlank()
        } catch (_: Exception) {}
    }

    private fun initStt() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }

                override fun onBeginningOfSpeech() {
                    _isListening.value = true
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
                    // If in continuous mode and not cancelled by user, smoothly restart listening
                    if (continuousListening) {
                        restartListeningDelay()
                    } else {
                        _isListening.value = false
                    }
                }

                override fun onResults(results: Bundle?) {
                    _audioLevel.value = 0f
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        onSpeechRecognized?.invoke(text, true)
                    }

                    // Keep listening if in continuous listening mode
                    if (continuousListening) {
                        restartListeningDelay()
                    } else {
                        _isListening.value = false
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        onSpeechRecognized?.invoke(text, false)
                    }
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

    /**
     * Silences recognition while the assistant speaks. The microphone is not
     * "turned off" from the user's point of view — it reopens on the last syllable
     * — but it cannot hear the phone's own speaker, which is what caused the
     * assistant to answer itself in a loop.
     */
    private fun pauseForSpeech() {
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        _audioLevel.value = 0f
    }

    /** Reopens the ear once the assistant has finished the whole utterance. */
    private fun resumeAfterSpeech() {
        if (!continuousListening) return
        // A short tail: the speaker keeps ringing for a moment after onDone, and
        // that tail is exactly the kind of noise the recognizer would latch onto.
        scope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(450)
            if (continuousListening && !_isSpeaking.value) {
                recognizerIntent?.let {
                    try {
                        speechRecognizer?.startListening(it)
                        _isListening.value = true
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun restartListeningDelay() {
        if (!continuousListening) return
        scope.launch(Dispatchers.Main) {
            try {
                kotlinx.coroutines.delay(200)
                if (continuousListening) {
                    recognizerIntent?.let {
                        speechRecognizer?.startListening(it)
                        _isListening.value = true
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun startListening() {
        continuousListening = true
        try {
            recognizerIntent?.let {
                speechRecognizer?.startListening(it)
                _isListening.value = true
            }
        } catch (_: Exception) {
            _isListening.value = false
        }
    }

    fun stopListening() {
        continuousListening = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        _isListening.value = false
        _audioLevel.value = 0f
    }

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
                // Some engines only answer to an explicit package.
                val fallback = context.packageManager
                    .getLaunchIntentForPackage("com.google.android.tts")
                fallback?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (fallback != null) context.startActivity(fallback)
            } catch (_: Exception) {}
        }
    }

    /**
     * Speaks through the phone's own engine, never a remote voice service. If the
     * device has no usable engine the call is a no-op and [ttsAvailable] is false,
     * so the UI can say so instead of pretending it spoke.
     */
    fun speak(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        speakRuns(clean, TextToSpeech.QUEUE_FLUSH)
    }

    /**
     * Speaks [clean] run by run, switching the engine's language at each boundary.
     * Without this an English word inside a Bangla sentence is spelled out letter
     * by letter, which is the single most noticeable voice defect there is.
     */
    private fun speakRuns(clean: String, queueMode: Int) {
        if (!(ttsReady && textToSpeech != null)) {
            _isSpeaking.value = false
            return
        }
        val segments = SpeechSegmenter.split(clean)
        if (segments.isEmpty()) return

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "zyraxon_msg")
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }

        // A single-language sentence is the common case and must stay a single
        // utterance, so the engine's own prosody is not interrupted.
        if (segments.size == 1) {
            applyVoiceLanguage(segments[0].bcp47)
            applyVoiceSettings(_voiceGender.value, currentRate, currentPitch, segments[0].bcp47)
            val res = textToSpeech?.speak(segments[0].text, queueMode, params, "zyraxon_msg")
            _isSpeaking.value = res == TextToSpeech.SUCCESS
            return
        }

        var anyAccepted = false
        segments.forEachIndexed { index, segment ->
            // The engine picks the voice from the language it is set to, so the
            // switch has to happen before each run, not once at the start.
            applyVoiceLanguage(segment.bcp47)
            applyVoiceSettings(_voiceGender.value, currentRate, currentPitch, segment.bcp47)
            val mode = if (index == 0) queueMode else TextToSpeech.QUEUE_ADD
            val id = "zyraxon_msg_$index"
            val res = textToSpeech?.speak(segment.text, mode, params, id)
            if (res == TextToSpeech.SUCCESS) anyAccepted = true
        }
        _isSpeaking.value = anyAccepted
    }

    /**
     * Strips markdown and emoji before speech. The engine reads them literally
     * ("asterisk asterisk", "smiling face") and the result sounds broken.
     */
    private fun cleanForSpeech(text: String): String = SpeechTextCleaner.clean(text)

    /**
     * Appends one finished sentence to the speech queue instead of cutting off
     * what is already playing, so a long answer is heard while it is still arriving.
     */
    fun speakQueued(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isEmpty()) return
        speakRuns(clean, TextToSpeech.QUEUE_ADD)
    }

    fun stopSpeaking() {
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {}
        _isSpeaking.value = false
    }
}
