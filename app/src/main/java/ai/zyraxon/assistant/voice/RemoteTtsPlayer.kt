package ai.zyraxon.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Speaks with the ZYRAXON website's own voice instead of the phone's engine.
 *
 * The website streams `audio/mpeg` and starts sending the first sentence while
 * the rest is still being made, so the audio is handed to the player by URL and
 * playback begins on the first bytes. Nothing is buffered here, which is the
 * whole point: the answer is heard while the model is still writing it.
 *
 * One piece is played at a time. The next piece only starts when the previous
 * one finishes, so the sentences are heard in the order they were written. A
 * piece that fails is retried once and then handed to the phone's engine through
 * [fallbackToPhone], so a network wobble is a voice change, never silence.
 */
class RemoteTtsPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val fallbackToPhone: (String) -> Unit
) {

    private val client = OkHttpClient.Builder()
        // The first bytes must arrive quickly or the voice feels late; a hung
        // connection is abandoned so the phone's engine can take over.
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val queue = ArrayDeque<Piece>()
    private var player: MediaPlayer? = null
    private var pumpJob: Job? = null

    /**
     * Downloads one piece ahead so the next one is a file, ready the instant the
     * current one ends. Without this the voice waited ~0.4 s between sentences —
     * the round trip to fetch the next piece's first byte — and that gap is what
     * made a streamed answer feel stitched rather than spoken.
     */
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefetched = ConcurrentHashMap<String, File>()

    /**
     * Cleared the first time a prefetch fails, so a network or endpoint that
     * cannot be downloaded from is not fetched a second time for every piece —
     * that would spend the turn on requests that never pay off.
     */
    @Volatile
    private var prefetchEnabled = true

    @Volatile
    private var generation = 0

    @Volatile
    private var running = false

    /** True while audio is actually coming out of the speaker. */
    @Volatile
    var isSpeaking = false
        private set

    /** True while anything is still playing or waiting to play. */
    fun hasPending(): Boolean = running || queue.isNotEmpty()

    /** Called with the real voice name the website reports, for the status line. */
    var onVoiceResolved: ((String) -> Unit)? = null

    /** Called when the endpoint answers with an error, so the UI can say why. */
    var onError: ((String) -> Unit)? = null

    /** Called as playback starts and stops, so the mic gate can follow the voice. */
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    private data class Piece(val text: String, val url: String, val attempts: Int = 0)

    /**
     * Replaces whatever is playing with [text]. A new reply must be heard from
     * its first word, so the old queue is dropped rather than finished.
     */
    fun speak(text: String, bcp47: String, gender: String) {
        stop()
        // A fresh reply gets a fresh chance to prefetch, so one bad moment on the
        // network does not cost the gap-free voice for the rest of the session.
        prefetchEnabled = true
        enqueue(text, bcp47, gender)
    }

    /**
     * Appends [text] without interrupting what is already playing, so the voice
     * keeps pace with an answer that is still streaming in.
     */
    fun enqueue(text: String, bcp47: String, gender: String) {
        val clean = SpeechTextCleaner.clean(text)
        if (clean.isBlank()) return
        val g = RemoteTtsRequest.genderFor(gender)
        for (chunk in RemoteTtsRequest.splitForUrl(clean)) {
            val tone = RemoteTtsRequest.toneFor(chunk)
            queue.add(Piece(chunk, RemoteTtsRequest.buildUrl(chunk, bcp47, g, tone)))
        }
        if (!running) startPump()
    }

    private fun startPump() {
        running = true
        isSpeaking = true
        onSpeakingChanged?.invoke(true)
        pumpJob?.cancel()
        pumpJob = scope.launch {
            val mine = generation
            while (running && mine == generation) {
                val piece = queue.removeFirstOrNull() ?: break
                // The piece after this one is fetched while this one is playing,
                // so the voice runs from sentence to sentence without a gap.
                prefetchNext(mine)
                val ok = play(piece)
                if (!ok && piece.attempts < 1 && mine == generation) {
                    // One retry: a dropped connection is common on mobile data.
                    queue.addFirst(piece.copy(attempts = piece.attempts + 1))
                } else if (!ok && mine == generation) {
                    // Still failing — the phone's own engine reads this piece so
                    // the user hears the words instead of a gap.
                    withContext(Dispatchers.Main) { fallbackToPhone(piece.text) }
                    // Give the fallback room to finish before the next piece.
                    delay(1_200L)
                }
            }
            running = false
            // Only the pump that still owns the current generation may clear the
            // shared state. A cancelled pump can reach here just after a new one
            // started, and clearing `running` then would strand the new queue.
            if (mine == generation) {
                isSpeaking = false
                onSpeakingChanged?.invoke(false)
            }
        }
    }

    /**
     * Starts downloading the next piece in the background. Deliberately not
     * awaited: the point is to overlap it with the piece that is playing.
     */
    private fun prefetchNext(mine: Int) {
        if (!prefetchEnabled) return
        val next = queue.firstOrNull() ?: return
        if (prefetched.containsKey(next.url)) return
        downloadScope.launch {
            if (mine != generation) return@launch
            val file = download(next.url)
            if (file != null && mine == generation) {
                prefetched[next.url] = file
            } else if (file == null) {
                prefetchEnabled = false
            }
        }
    }

    /**
     * Plays one piece and waits for it to end.
     *
     * Two ways are tried in order. First the URL goes straight to the player,
     * which begins on the first bytes — the real-time path. If it does not start
     * (a stream with no declared length is not accepted by every device's
     * player), the piece is downloaded once and played from a file. The voice is
     * heard either way; only the second way waits for the whole sentence.
     */
    private suspend fun play(piece: Piece): Boolean {
        // A prefetched file plays the instant it is needed — no round trip, so the
        // next sentence starts the moment the last one ends.
        val ready = prefetched.remove(piece.url)
        if (ready != null) {
            return try {
                playSource(ready.absolutePath)
            } finally {
                ready.delete()
            }
        }
        if (!fetch(piece.url)) return false
        if (playSource(piece.url)) return true
        val file = download(piece.url) ?: return false
        return try {
            playSource(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    /**
     * Points a fresh player at [source] and waits for the last note. A source
     * that has not started within the timeout is given up on, so a stream the
     * device will not accept costs a few seconds, not the whole reply.
     *
     * `prepareAsync` is used, never `prepare`: a synchronous prepare on the main
     * thread would freeze the UI for as long as the network takes.
     */
    private suspend fun playSource(source: String): Boolean = withContext(Dispatchers.Main) {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(source)
            val prepared = CompletableDeferred<Boolean>()
            val done = CompletableDeferred<Boolean>()
            mp.setOnPreparedListener {
                prepared.complete(true)
                try { it.start() } catch (_: Exception) { done.complete(false) }
            }
            mp.setOnCompletionListener { done.complete(true) }
            mp.setOnErrorListener { _, _, _ ->
                prepared.complete(false)
                done.complete(false)
                true
            }
            player = mp
            mp.prepareAsync()
            val started = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { prepared.await() } ?: false
            if (!started) {
                release(mp)
                return@withContext false
            }
            val finished = done.await()
            release(mp)
            finished
        } catch (e: Exception) {
            Log.w(TAG, "playback failed: ${e.message}")
            release(mp)
            false
        }
    }

    /**
     * Saves one piece to the cache so the player can read it as a file. Used only
     * when the streaming path did not start.
     */
    private suspend fun download(url: String): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("Accept", "audio/mpeg").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val file = File.createTempFile("zyraxon-tts-", ".mp3", context.cacheDir)
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                if (file.length() == 0L) {
                    file.delete()
                    null
                } else {
                    file
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            null
        }
    }

    /**
     * Confirms the endpoint really answers before the player is pointed at it, so
     * a 400 ("unknown voice") or a 502 is reported instead of sounding like a
     * broken speaker. The body is not read — only the status and the voice header.
     */
    private suspend fun fetch(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("Accept", "audio/mpeg").get().build()
            client.newCall(request).execute().use { response ->
                val voice = response.header("X-ZYRAXON-Voice").orEmpty()
                if (!response.isSuccessful) {
                    val reason = when (response.code) {
                        400 -> "ভয়েস সেটিংস ঠিক নেই"
                        413 -> "টেক্সট অনেক বড়"
                        502, 503 -> "ভয়েস সার্ভিস ব্যস্ত"
                        else -> "ভয়েস সার্ভিস ${response.code}"
                    }
                    onError?.invoke(reason)
                    return@withContext false
                }
                if (voice.isNotBlank()) onVoiceResolved?.invoke(voice)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "tts fetch failed: ${e.message}")
            onError?.invoke("ইন্টারনেট সংযোগ নেই")
            false
        }
    }

    /** Cuts the voice off and drops everything waiting. */
    fun stop() {
        generation++
        queue.clear()
        running = false
        isSpeaking = false
        pumpJob?.cancel()
        pumpJob = null
        release(player)
        player = null
        clearPrefetched()
    }

    /** Releases the player and the queue for good. */
    fun shutdown() {
        stop()
        downloadScope.cancel()
        try { client.dispatcher.executorService.shutdown() } catch (_: Exception) {}
    }

    private fun clearPrefetched() {
        for (file in prefetched.values) {
            try { file.delete() } catch (_: Exception) {}
        }
        prefetched.clear()
    }

    private fun release(mp: MediaPlayer?) {
        if (mp == null) return
        try { mp.stop() } catch (_: Exception) {}
        try { mp.release() } catch (_: Exception) {}
    }

    private companion object {
        const val TAG = "RemoteTts"

        /** How long a source may take to start before it is abandoned. */
        const val PREPARE_TIMEOUT_MS = 8_000L
    }
}
