package ai.zyraxon.assistant.auto

import ai.zyraxon.assistant.DeviceBridge
import ai.zyraxon.assistant.ai.ToolRegistry
import ai.zyraxon.assistant.callagent.CallSession
import ai.zyraxon.assistant.callagent.Reply
import ai.zyraxon.assistant.voice.NativeVoiceManager
import android.app.Application
import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Answers a call for the owner. The caller's words come in through speech recognition as text, the
 * reply goes out through the app's own voice (with the phone on speaker so the caller hears it):
 * caller speech → text → rules or model → text → TTS. When the call ends the summary is saved,
 * shown as a notification, and handed to the agent to tell the owner.
 */
class CallAgentRuntime(
    private val app: Application,
    private val scope: CoroutineScope,
    private val voice: NativeVoiceManager,
    private val askModel: suspend (String) -> String,
    private val report: (title: String, prompt: String) -> Unit
) {
    private var session: CallSession? = null
    private var screening = false
    private var number = ""
    private var name = ""
    private var prevHandler: ((String, Boolean) -> Unit)? = null
    private var prevMic = false
    private var prevSpeaker = false
    private var watcher: Job? = null
    @Volatile private var lastActivity = 0L
    @Volatile private var lastCaller = ""

    private fun prefs() = AutoTools.prefs(app)
    fun enabled() = prefs().getBoolean("call_agent", false)

    /** The ViewModel calls this at start so the switch survives a restart. */
    fun applySetting() { if (enabled()) DeviceBridge.setAutoAnswer(true, 6000) }

    /** Returns true when the call agent takes this call (so the normal "incoming call" message is skipped). */
    fun onRinging(num: String): Boolean {
        if (!enabled() || session != null) return false
        screening = true; number = num; name = DeviceBridge.callName()
        return true
    }

    fun onActive() {
        if (!screening || session != null) return
        val owner = prefs().getString("owner", "")?.ifBlank { null } ?: "মালিক"
        val s = CallSession(owner, number, name); session = s
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prevSpeaker = audio.isSpeakerphoneOn
        runCatching { audio.isSpeakerphoneOn = true; audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0) }
        prevHandler = voice.onSpeechRecognized; prevMic = voice.isMicEnabled()
        voice.onSpeechRecognized = { text, isFinal -> if (isFinal) onCaller(text) }
        voice.setMicEnabled(true)
        lastActivity = System.currentTimeMillis()
        scope.launch { delay(1000); say(s.greeting()) }
        watcher = scope.launch {
            while (session === s) {
                delay(1000)
                if (voice.isSpeakerActive()) { lastActivity = System.currentTimeMillis(); continue }
                if (System.currentTimeMillis() - lastActivity > 9000) { lastActivity = System.currentTimeMillis(); handle(s, s.onSilence()) }
            }
        }
    }

    private fun say(text: String) { lastActivity = System.currentTimeMillis(); voice.speak(text) }

    private fun onCaller(text: String) {
        val s = session ?: return
        if (voice.isSpeakerActive() || text.trim().length < 2) return   // our own voice leaking back is not the caller
        lastActivity = System.currentTimeMillis(); lastCaller = text
        handle(s, s.onCaller(text))
    }

    private fun handle(s: CallSession, r: Reply) {
        if (session !== s) return
        if (r.model) {
            scope.launch {
                val answer = runCatching { askModel(s.modelPrompt(lastCaller)) }.getOrDefault("")
                handle(s, s.modelSaid(answer.ifBlank { "আমি বার্তাটা জানিয়ে দেব।" }))
            }
            return
        }
        if (r.say.isNotBlank()) say(r.say)
        if (r.hangUp) scope.launch {
            delay(700); var w = 0
            while (voice.isSpeakerActive() && w < 20) { delay(500); w++ }
            delay(500); DeviceBridge.hangUpCall(app)
        }
    }

    fun onEnded() {
        val s = session
        screening = false
        if (s == null) return
        session = null; watcher?.cancel()
        voice.onSpeechRecognized = prevHandler; voice.setMicEnabled(prevMic)
        runCatching { (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = prevSpeaker }
        val sum = s.summary()
        val owner = prefs().getString("owner", "")?.ifBlank { null } ?: "মালিক"
        val text = sum.text(owner)
        runCatching {
            val d = File(ToolRegistry.workspace(app), "calls").apply { mkdirs() }
            File(d, SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()) + ".md").writeText(text)
        }
        DeviceBridge.notify(app, if (sum.urgent) "জরুরি কল ধরা হয়েছে" else "কল ধরা হয়েছে", sum.smsToOwner())
        if (s.transcript().any { it.who == "caller" })
            report("কল সারাংশ", "[কল-এজেন্ট] আমার অনুপস্থিতিতে একটা কল ধরা হয়েছিল। নিচের সারাংশ থেকে আমাকে বাংলায় ২-৩ লাইনে জানাও কে কী চেয়েছে, জরুরি কি না, আর আমার কী করা উচিত (যেমন কল ব্যাক বা উত্তর)। দরকার না হলে কিছু করবে না।\n\n$text")
    }
}
