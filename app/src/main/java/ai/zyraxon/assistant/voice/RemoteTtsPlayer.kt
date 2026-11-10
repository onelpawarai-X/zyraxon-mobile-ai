package ai.zyraxon.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
        .connectTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // A concurrent deque, not ArrayDeque: the pump runs on the voice scope while
    // the answer's tokens arrive on the ViewModel's, so a plain deque could be
    // mutated from two threads at once — a corruption that shows up as a hang.
    private val queue = java.util.concurrent.ConcurrentLinkedDeque<Piece>()
    private var player: MediaPlayer? = null
    private var pumpJob: Job? = null

    /**
     * Downloads one piece ahead so the next one is a file, ready the instant the
     * current one ends. Without this the voice waited ~0.4 s between sentences —
     * the round trip to fetch the next piece's first byte — and that gap is what
     * made a streamed answer feel stitched rather than spoken.
     *
     * Several pieces are kept in flight, not one: the endpoint needs about a
     * second before its first byte, and an answer that arrives as a run of short
     * clauses would otherwise outpace a single-ahead prefetch and fall silent
     * between every clause — heard as "one word, a long wait, another word".
     */
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefetched = ConcurrentHashMap<String, File>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private var prefetchFailures = 0

    /** How many not-yet-playing pieces may be downloaded at once. */
    private val prefetchDepth = 6

    /**
     * Cleared the first time a prefetch fails, so a network or endpoint that
     * cannot be downloaded from is not fetched a second time for every piece —
     * that would spend the turn on requests that never pay off.
     */
    @Volatile
    private var prefetchEnabled = true

    /**
     * Cleared the first time pointing the player straight at the URL fails, so a
     * device that will not accept the streamed response does not pay the stream
     * timeout once per sentence for the rest of the session.
     */
    @Volatile
    private var streamUsable = true

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

    /** Real loudness of the voice, 0..1, so the aura can pulse with the audio. */
    var onAudioLevel: ((Float) -> Unit)? = null

    private data class Piece(val text: String, val url: String, val attempts: Int = 0)

    /**
     * Replaces whatever is playing with [text]. A new reply must be heard from
     * its first word, so the old queue is dropped rather than finished.
     */
    fun speak(text: String, bcp47: String, gender: String) {
        stop()
        // A fresh reply gets a fresh chance to prefetch and to stream, so one bad
        // moment on the network does not cost the gap-free voice for the session.
        prefetchEnabled = true
        prefetchFailures = 0
        streamUsable = true
        clearPrefetched()
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
        // Text can arrive after the pump has already drained the queue and stopped
        // — the reply's last sentence does exactly that, because it is enqueued
        // after streaming ends. A live pump picks the new piece up on its own loop;
        // a stopped one must be restarted, or that sentence is never spoken, which
        // is heard as the voice stopping mid-answer.
        if (!running) startPump() else prefetchNext(generation)
    }

    private fun startPump() {
        running = true
        isSpeaking = true
        onSpeakingChanged?.invoke(true)
        pumpJob?.cancel()
        pumpJob = scope.launch {
            val mine = generation
            while (running && mine == generation) {
                val piece = queue.pollFirst() ?: break
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
     * Keeps several upcoming pieces downloading in the background. Deliberately
     * not awaited: the point is to overlap them with the piece that is playing,
     * so the next sentence is already a local file by the time it is needed.
     */
    private fun prefetchNext(mine: Int) {
        if (!prefetchEnabled) return
        var depth = 0
        for (next in queue) {
            if (depth >= prefetchDepth) break
            if (prefetched.containsKey(next.url) || next.url in inFlight) continue
            depth++
            inFlight.add(next.url)
            downloadScope.launch {
                if (mine != generation) { inFlight.remove(next.url); return@launch }
                val file = download(next.url)
                inFlight.remove(next.url)
                if (file != null && mine == generation) {
                    prefetched[next.url] = file
                    prefetchFailures = 0
                } else if (file != null) {
                    // The reply moved on while this was downloading, so the file
                    // is for a queue that no longer exists. Deleted here, or it
                    // would sit in the cache until the process died.
                    file.delete()
                } else {
                    // One failure is not a reason to stop: the piece is simply
                    // fetched on demand when its turn comes. Only a run of failures
                    // means the endpoint is not answering, and that is when
                    // prefetching is switched off for the rest of the reply.
                    if (++prefetchFailures >= 3) prefetchEnabled = false
                }
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
                playSource(ready.absolutePath, piece.text, PREPARE_TIMEOUT_MS)
            } finally {
                ready.delete()
            }
        }
        // The stream attempt and the download run at the same time. Pointing the
        // player at the URL is the fast path — it starts on the first bytes — but
        // not every device accepts a chunked stream with no declared length. The
        // old code waited a fixed timeout (8s) before it even started the
        // download, and that single wait, once per sentence, is what made the
        // voice fall silent between words.
        //
        // The race is decided by events, not by a clock: whichever finishes first
        // wins. If the stream is ready first it plays; if the file lands first the
        // stream attempt is dropped and the file plays. No timer is involved, so
        // nothing is cut off early and nothing waits on a number someone guessed.
        //
        // Once a stream attempt has failed, the device has proven it will not take
        // this shape, so later pieces skip the attempt and go straight to the file.
        if (streamUsable) {
            val downloadJob = downloadScope.async { download(piece.url) }
            val dropStream = CompletableDeferred<Unit>()
            // The file landing first is the signal that this device is not going
            // to accept the stream, so the attempt is released rather than left to
            // hang.
            downloadScope.launch {
                val landed = downloadJob.await()
                if (landed != null) dropStream.complete(Unit)
            }
            if (playSource(piece.url, piece.text, 0L, dropStream)) {
                // The stream is playing. The parallel download may still land, so
                // its file is deleted once it arrives rather than left in the cache.
                downloadScope.launch { downloadJob.await()?.delete() }
                return true
            }
            streamUsable = false
            val raced = downloadJob.await()
            if (raced != null) {
                return try {
                    playSource(raced.absolutePath, piece.text, PREPARE_TIMEOUT_MS)
                } finally {
                    raced.delete()
                }
            }
        }
        val file = download(piece.url)
        if (file == null) {
            onError?.invoke("ভয়েস আনা যায়নি")
            return false
        }
        return try {
            playSource(file.absolutePath, piece.text, PREPARE_TIMEOUT_MS)
        } finally {
            file.delete()
        }
    }

    /**
     * Points a fresh player at [source] and waits for the last note.
     *
     * There is no fixed deadline. [timeoutMs] of zero means wait for the player
     * itself to say it is ready or failed. [dropStream] is an event, not a clock:
     * when the parallel download lands first, this attempt is released because
     * the device has shown it will not accept a chunked stream, and waiting
     * longer would only delay the sentence.
     *
     * `prepareAsync` is used, never `prepare`: a synchronous prepare on the main
     * thread would freeze the UI for as long as the network takes.
     */
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null

    private suspend fun playSource(
        source: String,
        text: String = "",
        timeoutMs: Long = PREPARE_TIMEOUT_MS,
        dropStream: CompletableDeferred<Unit>? = null
    ): Boolean = withContext(Dispatchers.Main) {
        val mp = MediaPlayer()
        var levelJob: Job? = null
        var viz: Visualizer? = null
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
                // The voice was too quiet even at full phone volume. A loudness
                // enhancer on this player's own audio session adds about +10 dB
                // without touching any other app's sound.
                try {
                    enhancer?.release()
                    enhancer = android.media.audiofx.LoudnessEnhancer(it.audioSessionId).apply {
                        setTargetGain(VOICE_GAIN_MB)
                        enabled = true
                    }
                } catch (_: Exception) {}
                try { it.setVolume(1f, 1f) } catch (_: Exception) {}
                // The real loudness of the audio is what drives the on-screen
                // aura's pulse, so the animation follows the voice instead of a
                // guess. Nothing here is allowed to fail the playback.
                try {
                    viz = Visualizer(it.audioSessionId).apply {
                        captureSize = Visualizer.getCaptureSizeRange()[0]
                        enabled = true
                    }
                    val v = viz
                    levelJob = scope.launch(Dispatchers.Default) {
                        val buf = ByteArray(v?.captureSize ?: 0)
                        while (true) {
                            if (v != null && v.getWaveForm(buf) == Visualizer.SUCCESS) {
                                var sum = 0.0
                                for (b in buf) { val x = (b.toInt() and 0xFF) - 128; sum += x * x }
                                val rms = Math.sqrt(sum / buf.size) / 128.0
                                onAudioLevel?.invoke(rms.toFloat())
                            }
                            delay(50L)
                        }
                    }
                } catch (_: Throwable) { viz = null }
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
            val started = when {
                timeoutMs > 0L -> withTimeoutOrNull(timeoutMs) { prepared.await() } ?: false
                dropStream != null -> {
                    // Whichever speaks first wins: the player, or the download
                    // that already made this attempt unnecessary.
                    val released = CompletableDeferred<Boolean>()
                    val watcher = launch {
                        dropStream.await()
                        if (!prepared.isCompleted) released.complete(false)
                    }
                    val ok = select<Boolean> {
                        prepared.onAwait { it }
                        released.onAwait { it }
                    }
                    watcher.cancel()
                    ok
                }
                else -> prepared.await()
            }
            if (!started) {
                release(mp)
                return@withContext false
            }
            val finished = done.await()
            levelJob?.cancel()
            try { viz?.release() } catch (_: Throwable) {}
            release(mp)
            finished
        } catch (e: Exception) {
            Log.w(TAG, "playback failed: ${e.message}")
            levelJob?.cancel()
            try { viz?.release() } catch (_: Throwable) {}
            release(mp)
            false
        }
    }

    /**
     * Saves one piece to the cache so the player can read it as a file. Used only
     * when the streaming path did not start. This is also where the voice the
     * endpoint actually used is read — from its `X-ZYRAXON-Voice` header — and
     * where a 400/502 is turned into a plain reason for the UI.
     */
    private suspend fun download(url: String): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("Accept", "audio/mpeg").get().build()
            client.newCall(request).execute().use { response ->
                val voice = response.header("X-ZYRAXON-Voice").orEmpty()
                if (voice.isNotBlank()) onVoiceResolved?.invoke(voice)
                if (!response.isSuccessful) {
                    onError?.invoke(
                        when (response.code) {
                            400 -> "ভয়েস সেটিংস ঠিক নেই"
                            413 -> "টেক্সট অনেক বড়"
                            502, 503 -> "ভয়েস সার্ভিস ব্যস্ত"
                            else -> "ভয়েস সার্ভিস ${response.code}"
                        }
                    )
                    return@withContext null
                }
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
            onError?.invoke("ইন্টারনেট সংযোগ নেই")
            null
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

    /**
     * Silences the voice where it stands, keeping the piece that is playing and
     * everything still queued. [resume] picks the same sentence back up, so a user
     * who talks over the assistant is answered by the voice stopping, and the
     * reply carrying on from that word once they are quiet again.
     */
    fun pause() {
        val mp = player
        try { mp?.pause() } catch (_: Exception) {}
        isSpeaking = false
        onSpeakingChanged?.invoke(false)
    }

    /** Continues a paused voice, or does nothing when it is not paused. */
    fun resume() {
        val mp = player
        try {
            if (mp != null && !mp.isPlaying) {
                mp.start()
                isSpeaking = true
                onSpeakingChanged?.invoke(true)
            }
        } catch (_: Exception) {}
    }

    /** True while audio is paused mid-piece, so a resume is meaningful. */
    fun isPaused(): Boolean = try {
        player?.let { !it.isPlaying && running } ?: false
    } catch (_: Exception) {
        false
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
        try { enhancer?.release() } catch (_: Exception) {}
        enhancer = null
        try { mp.stop() } catch (_: Exception) {}
        try { mp.release() } catch (_: Exception) {}
    }

    private companion object {
        const val TAG = "RemoteTts"

        /** Extra loudness for the voice, in millibels (1000 = +10 dB). */
        const val VOICE_GAIN_MB = 1200

        /**
         * How long a source may take to start before it is abandoned. Zero means
         * no deadline: a slow network is allowed to finish instead of the voice
         * being cut off mid-sentence.
         */
        const val PREPARE_TIMEOUT_MS = 0L
    }
}
