package ai.zyraxon.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Calling the assistant by name.
 *
 * The phrases are the user's own — any language, any script — kept in
 * preferences. [Matcher] decides whether what the recogniser heard contains one
 * of them; it is pure so it can be tested without a phone.
 */
object WakeWord {

    private const val PREFS = "zyraxon_wake"
    private const val KEY_ON = "on"
    private const val KEY_PHRASES = "phrases"

    /** What works before the user adds anything. */
    val DEFAULTS = listOf("জিরাক্সন", "জাইরাক্সন", "zyraxon", "hey zyraxon", "zyrexon", "ziraxon")

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    fun phrases(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PHRASES, null)
        if (raw == null) return DEFAULTS
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setPhrases(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHRASES, list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n"))
            .apply()
    }

    object Matcher {
        /** Lower-cased, with spaces, punctuation and zero-width marks removed. */
        fun squash(text: String): String = buildString {
            for (ch in text.lowercase()) {
                if (ch.isLetterOrDigit() || Character.getType(ch) == Character.NON_SPACING_MARK.toInt() ||
                    Character.getType(ch) == Character.COMBINING_SPACING_MARK.toInt()
                ) append(ch)
            }
        }

        /** True when [heard] contains any of [phrases], allowing one slip on a long phrase. */
        fun matches(heard: String, phrases: List<String>): Boolean {
            val h = squash(heard)
            if (h.isEmpty()) return false
            for (p in phrases) {
                val q = squash(p)
                if (q.length < 2) continue
                if (h.contains(q)) return true
                if (q.length >= 6 && nearContains(h, q)) return true
            }
            return false
        }

        /** Whether a window of [q]'s length inside [h] is one edit away from [q]. */
        private fun nearContains(h: String, q: String): Boolean {
            for (len in (q.length - 1)..(q.length + 1)) {
                if (len > h.length) continue
                for (i in 0..(h.length - len)) {
                    if (distance(h.substring(i, i + len), q) <= 1) return true
                }
            }
            return false
        }

        private fun distance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]
                d[0] = i
                for (j in 1..b.length) {
                    val tmp = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return d[b.length]
        }
    }

    /**
     * Listens in the background and calls [onWake] when a phrase is heard. It
     * stands aside whenever [busy] says the app's own microphone or voice is in
     * use, because two recognisers cannot share the microphone.
     */
    class Listener(
        private val context: Context,
        private val busy: () -> Boolean,
        private val onWake: () -> Unit
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var recognizer: SpeechRecognizer? = null
        @Volatile private var running = false
        private var phrases: List<String> = emptyList()

        fun start() {
            if (running) return
            if (!SpeechRecognizer.isRecognitionAvailable(context)) return
            running = true
            phrases = WakeWord.phrases(context)
            handler.post { listen() }
        }

        fun refresh() { phrases = WakeWord.phrases(context) }

        fun stop() {
            running = false
            handler.removeCallbacksAndMessages(null)
            handler.post {
                runCatching { recognizer?.cancel() }
                runCatching { recognizer?.destroy() }
                recognizer = null
            }
        }

        private fun again(delayMs: Long) {
            if (!running) return
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ listen() }, delayMs)
        }

        private fun listen() {
            if (!running) return
            if (busy()) { again(1200); return }
            runCatching { recognizer?.destroy() }
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                private fun check(b: android.os.Bundle?): Boolean {
                    val heard = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    if (heard.any { Matcher.matches(it, phrases) }) {
                        runCatching { r.cancel() }
                        onWake()
                        again(4000)
                        return true
                    }
                    return false
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) { check(partialResults) }
                override fun onResults(results: android.os.Bundle?) { if (!check(results)) again(150) }
                override fun onError(error: Int) {
                    again(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 2000 else 350)
                }
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            runCatching { r.startListening(intent) }.onFailure { again(2000) }
        }
    }
}
