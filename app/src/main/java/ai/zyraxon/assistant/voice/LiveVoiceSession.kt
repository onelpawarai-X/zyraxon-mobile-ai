package ai.zyraxon.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speech-to-speech with a live model, as opposed to the usual text turn that we
 * read out with the phone's own text-to-speech.
 *
 * While this session is running the model *is* the voice: raw PCM goes up from
 * the microphone and the model's own audio comes back down and is played as it
 * arrives. The phone's TTS must stay silent for the duration — two voices
 * talking at once is the bug this class exists to avoid — which is why
 * [isActive] is what the ViewModel checks before speaking.
 *
 * Two real wire dialects are supported, and the caller says which one its
 * provider speaks:
 *  - [Protocol.GEMINI] — Google's `BidiGenerateContent`, used by the
 *    `*-native-audio-*` and `*-live-*` models.
 *  - [Protocol.OPENAI] — the OpenAI Realtime event stream, which OpenAI's
 *    `*realtime*` models and xAI's `grok-voice-*` models both speak.
 *
 * No model id lives in this file. The caller passes the id and the socket URL
 * from the provider's own catalogue, so a model is only ever used where its
 * provider actually serves it.
 */
class LiveVoiceSession(
    private val context: Context,
    private val scope: CoroutineScope
) {

    /** What the UI shows: idle, connecting, listening, speaking. */
    enum class State { IDLE, CONNECTING, LISTENING, SPEAKING }

    /** The wire dialect the open socket speaks. */
    enum class Protocol { GEMINI, OPENAI }

    @Volatile
    var isActive: Boolean = false
        private set

    var onState: ((State) -> Unit)? = null
    var onUserTranscript: ((String) -> Unit)? = null
    var onModelTranscript: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var micJob: Job? = null
    private var mic: AudioRecord? = null
    private var track: AudioTrack? = null
    private val running = AtomicBoolean(false)

    /** True once the session is set up, so audio may be sent. */
    private val ready = AtomicBoolean(false)

    @Volatile
    private var protocol: Protocol = Protocol.GEMINI

    fun start(
        apiKey: String,
        wsUrl: String,
        protocol: Protocol = Protocol.GEMINI,
        model: String,
        systemInstruction: String = "",
        voiceName: String = "",
        sampleRateIn: Int = MIC_RATE,
        sampleRateOut: Int = OUT_RATE
    ) {
        if (isActive) return
        if (apiKey.isBlank()) {
            onError?.invoke("লাইভ ভয়েসের জন্য এই প্রোভাইডারের কী দরকার")
            return
        }
        if (model.isBlank()) {
            onError?.invoke("কোনো রিয়েলটাইম মডেল বাছা হয়নি")
            return
        }
        this.protocol = protocol
        isActive = true
        ready.set(false)
        running.set(true)
        onState?.invoke(State.CONNECTING)

        val request = buildRequest(wsUrl, apiKey, protocol, model)
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (protocol == Protocol.GEMINI) {
                    webSocket.send(geminiSetup(model, systemInstruction, voiceName))
                } else {
                    // OpenAI's socket accepts appends as soon as it is open; the
                    // session frame configures voice and transcription.
                    webSocket.send(openAiSessionUpdate(systemInstruction, voiceName))
                    ready.set(true)
                    onState?.invoke(State.LISTENING)
                    startMic()
                    startPlayback()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (running.get()) onError?.invoke(t.message ?: "লাইভ সংযোগ বিচ্ছিন্ন")
                stopInternal()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = stopInternal()
        })
    }

    fun stop() {
        running.set(false)
        socket?.close(1000, "user stopped")
        stopInternal()
    }

    private fun stopInternal() {
        isActive = false
        ready.set(false)
        running.set(false)
        micJob?.cancel()
        micJob = null
        try { mic?.stop() } catch (_: Throwable) {}
        try { mic?.release() } catch (_: Throwable) {}
        mic = null
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
        socket = null
        onState?.invoke(State.IDLE)
    }

    /**
     * Pushes one camera or screen frame so the model can see what the user sees.
     * The app already captures frames as JPEG; both protocols take them inline.
     */
    fun sendScreenFrame(jpegBase64: String, mimeType: String = "image/jpeg") {
        val ws = socket ?: return
        if (!ready.get() || jpegBase64.isBlank()) return
        val payload = if (protocol == Protocol.GEMINI) {
            geminiRealtime(jpegBase64, mimeType)
        } else {
            openAiImage(jpegBase64)
        }
        ws.send(payload)
    }

    // ---- request -----------------------------------------------------------

    private fun buildRequest(
        wsUrl: String,
        apiKey: String,
        protocol: Protocol,
        model: String
    ): Request {
        val builder = Request.Builder()
        return if (protocol == Protocol.GEMINI) {
            val sep = if (wsUrl.contains("?")) "&" else "?"
            builder.url("$wsUrl${sep}key=$apiKey").build()
        } else {
            // OpenAI and xAI both take the model on the URL and the key as a header.
            val sep = if (wsUrl.contains("?")) "&" else "?"
            builder.url("$wsUrl${sep}model=$model")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()
        }
    }

    // ---- wire format: Gemini ----------------------------------------------

    private fun geminiSetup(model: String, systemInstruction: String, voiceName: String): String {
        val voice = voiceName.ifBlank { "Aoede" }
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray(listOf("AUDIO")))
            .put(
                "speechConfig", JSONObject().put(
                    "voiceConfig", JSONObject().put(
                        "prebuiltVoiceConfig", JSONObject().put("voiceName", voice)
                    )
                )
            )
        val setup = JSONObject().put(
            "model", if (model.startsWith("models/")) model else "models/$model"
        ).put("generationConfig", generationConfig)
            // Both transcripts are asked for so the chat timeline shows the same
            // words the user hears; without them the panel would stay empty.
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        if (systemInstruction.isNotBlank()) {
            setup.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
        }
        return JSONObject().put("setup", setup).toString()
    }

    /**
     * One piece of live input. The `mediaChunks` field this used to send is
     * deprecated, so audio goes in `audio` and pictures in `video`, which is what
     * the service documents and what the current live models expect.
     */
    private fun geminiRealtime(base64: String, mimeType: String): String {
        val key = if (mimeType.startsWith("audio/")) "audio" else "video"
        return JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                key,
                JSONObject().put("mimeType", mimeType).put("data", base64)
            )
        ).toString()
    }

    // ---- wire format: OpenAI Realtime -------------------------------------

    private fun openAiSessionUpdate(systemInstruction: String, voiceName: String): String {
        val session = JSONObject()
            .put("modalities", JSONArray(listOf("audio", "text")))
            .put("input_audio_format", "pcm16")
            .put("output_audio_format", "pcm16")
            // Server VAD lets the user interrupt naturally, exactly like the
            // Gemini side, instead of holding a push-to-talk button.
            .put("turn_detection", JSONObject().put("type", "server_vad"))
            .put(
                "input_audio_transcription",
                JSONObject().put("model", "whisper-1")
            )
        if (systemInstruction.isNotBlank()) session.put("instructions", systemInstruction)
        if (voiceName.isNotBlank()) session.put("voice", voiceName)
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    private fun openAiAudio(base64: String): String =
        JSONObject().put("type", "input_audio_buffer.append").put("audio", base64).toString()

    /**
     * A picture in the OpenAI dialect is a conversation item, so the model sees
     * it rather than having it silently queued.
     */
    private fun openAiImage(jpegBase64: String): String =
        JSONObject()
            .put("type", "conversation.item.create")
            .put(
                "item",
                JSONObject()
                    .put("type", "message")
                    .put("role", "user")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "input_image")
                                .put("image_url", "data:image/jpeg;base64,$jpegBase64")
                        )
                    )
            ).toString()

    // ---- receive -----------------------------------------------------------

    private fun handleMessage(text: String) {
        val json = try { JSONObject(text) } catch (_: Throwable) { return }
        if (protocol == Protocol.GEMINI) handleGemini(json) else handleOpenAi(json)
    }

    private fun handleGemini(json: JSONObject) {
        if (json.has("setupComplete")) {
            ready.set(true)
            onState?.invoke(State.LISTENING)
            startMic()
            startPlayback()
            return
        }

        val serverContent = json.optJSONObject("serverContent") ?: return

        serverContent.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }
            ?.let { onUserTranscript?.invoke(it) }
        serverContent.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }
            ?.let { onModelTranscript?.invoke(it) }

        if (serverContent.optBoolean("interrupted")) {
            try { track?.pause(); track?.flush() } catch (_: Throwable) {}
            onState?.invoke(State.LISTENING)
        }

        val turn = serverContent.optJSONObject("modelTurn")
        if (turn != null) {
            val parts = turn.optJSONArray("parts") ?: JSONArray()
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                val data = inline.optString("data")
                if (data.isNotBlank()) playPcm(Base64.decode(data, Base64.DEFAULT))
            }
            onState?.invoke(State.SPEAKING)
        }
        if (serverContent.optBoolean("turnComplete")) onState?.invoke(State.LISTENING)
    }

    /**
     * The OpenAI event stream. Two generations of names exist — the older beta
     * sends `response.audio.delta`, the newer one `response.output_audio.delta`
     * — so both are read rather than guessing which the provider speaks.
     */
    private fun handleOpenAi(json: JSONObject) {
        when (json.optString("type")) {
            "session.updated", "session.created" -> {
                ready.set(true)
                onState?.invoke(State.LISTENING)
            }
            "input_audio_buffer.speech_started" -> onState?.invoke(State.LISTENING)
            "conversation.item.input_audio_transcription.completed" ->
                json.optString("transcript").takeIf { it.isNotBlank() }
                    ?.let { onUserTranscript?.invoke(it) }
            "response.audio.delta", "response.output_audio.delta" -> {
                val delta = json.optString("delta")
                if (delta.isNotBlank()) {
                    playPcm(Base64.decode(delta, Base64.DEFAULT))
                    onState?.invoke(State.SPEAKING)
                }
            }
            "response.audio_transcript.delta", "response.output_audio_transcript.delta" ->
                json.optString("delta").takeIf { it.isNotBlank() }
                    ?.let { onModelTranscript?.invoke(it) }
            "response.audio_transcript.done", "response.output_audio_transcript.done",
            "response.audio.done", "response.output_audio.done", "response.done" ->
                onState?.invoke(State.LISTENING)
            "error" -> {
                val message = json.optJSONObject("error")?.optString("message").orEmpty()
                if (message.isNotBlank()) onError?.invoke(message)
            }
        }
    }

    // ---- audio -------------------------------------------------------------

    private fun startMic() {
        if (micJob != null) return
        micJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                onError?.invoke("মাইক্রোফোন ব্যবহার করা যাচ্ছে না")
                return@launch
            }
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MIC_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } catch (e: Throwable) {
                onError?.invoke(e.message ?: "মাইক খোলা যায়নি")
                return@launch
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                onError?.invoke("মাইক চালু হয়নি — অনুমতি দিন")
                return@launch
            }
            mic = record
            val buffer = ByteArray(CHUNK_BYTES)
            try {
                record.startRecording()
                while (running.get() && ready.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val ws = socket ?: break
                        val b64 = Base64.encodeToString(buffer.copyOf(read), Base64.NO_WRAP)
                        ws.send(
                            if (protocol == Protocol.GEMINI) {
                                geminiRealtime(b64, "audio/pcm;rate=$MIC_RATE")
                            } else {
                                openAiAudio(b64)
                            }
                        )
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try { record.stop() } catch (_: Throwable) {}
                try { record.release() } catch (_: Throwable) {}
                if (mic === record) mic = null
            }
        }
    }

    private fun startPlayback() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 4, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Throwable) {
            onError?.invoke(e.message ?: "স্পিকার খোলা যায়নি")
            return
        }
        track = audioTrack
        try { audioTrack.play() } catch (_: Throwable) {}
    }

    private fun playPcm(pcm: ByteArray) {
        val audioTrack = track ?: return
        try {
            audioTrack.write(pcm, 0, pcm.size)
        } catch (_: Throwable) {}
    }

    companion object {
        /**
         * What both protocols expect from us and what they send back. Gemini's
         * live models take 16 kHz mono PCM in and answer with 24 kHz; the OpenAI
         * Realtime format is also 16 kHz in / 24 kHz out, so one pair covers both.
         */
        const val MIC_RATE = 16000
        const val OUT_RATE = 24000

        /** ~64 ms of 16 kHz mono PCM: small enough to feel live, large enough to be cheap. */
        const val CHUNK_BYTES = 2048

        /**
         * The persona's voice, chosen by gender, in each provider's own vocabulary.
         * Gemini names its prebuilt voices (`Aoede`, `Puck`); the OpenAI dialect
         * names its own (`coral`, `ash`). One list cannot serve both, so the
         * protocol picks the table.
         */
        private val GEMINI_FEMALE = listOf("Aoede", "Kore", "Leda", "Zephyr", "Autonoe")
        private val GEMINI_MALE = listOf("Puck", "Charon", "Fenrir", "Orus", "Enceladus")
        private val OPENAI_FEMALE = listOf("coral", "sage", "shimmer", "ballad", "marin")
        private val OPENAI_MALE = listOf("ash", "echo", "verse", "cedar", "onyx")

        /** The voice name for a gender, spoken by this protocol. */
        fun voiceFor(protocol: Protocol, gender: String): String {
            val female = gender.lowercase() != "male"
            return if (protocol == Protocol.GEMINI) {
                (if (female) GEMINI_FEMALE else GEMINI_MALE).first()
            } else {
                (if (female) OPENAI_FEMALE else OPENAI_MALE).first()
            }
        }
    }
}
