// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.Visualizer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
 * Speaks with the ZYRAXON website's own voice with continuous prefetching.
 *
 * Upcoming sentences are prefetched concurrently in the background while the
 * current sentence is being spoken. This eliminates network round-trip pauses
 * and keeps speech completely real-time from beginning to end.
 */
class RemoteTtsPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val fallbackToPhone: (String) -> Unit
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    private val queue = java.util.concurrent.ConcurrentLinkedDeque<Piece>()
    private var player: MediaPlayer? = null
    private var pumpJob: Job? = null

    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefetched = ConcurrentHashMap<String, File>()
    private val inFlight = ConcurrentHashMap<String, Deferred<File?>>()
    private var prefetchFailures = 0

    private val prefetchDepth = 4

    @Volatile
    private var prefetchEnabled = true

    @Volatile
    private var generation = 0

    @Volatile
    private var running = false

    @Volatile
    var isSpeaking = false
        private set

    fun hasPending(): Boolean = running || queue.isNotEmpty()

    var onVoiceResolved: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onSpeakingChanged: ((Boolean) -> Unit)? = null
    var onAudioLevel: ((Float) -> Unit)? = null

    private data class Piece(val text: String, val url: String, val attempts: Int = 0)

    fun speak(text: String, bcp47: String, gender: String) {
        stop()
        prefetchEnabled = true
        prefetchFailures = 0
        clearPrefetched()
        enqueue(text, bcp47, gender)
    }

    fun enqueue(text: String, bcp47: String, gender: String) {
        val clean = SpeechTextCleaner.clean(text)
        if (clean.isBlank()) return
        val g = RemoteTtsRequest.genderFor(gender)
        val pieces = RemoteTtsRequest.splitForUrl(clean)
        for (chunk in pieces) {
            val tone = RemoteTtsRequest.toneFor(chunk)
            val url = RemoteTtsRequest.buildUrl(chunk, bcp47, g, tone)
            queue.add(Piece(chunk, url))
        }
        if (!running) {
            startPump()
        } else {
            prefetchNext(generation)
        }
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
                // Kick off prefetching for the next pieces in queue immediately
                prefetchNext(mine)
                // Prepare the piece after this one while this one speaks. The
                // prepare is asynchronous, so it costs this piece no time and the
                // next one is already decoded when its turn comes.
                val upcoming = queue.firstOrNull()
                if (upcoming != null && upcoming.url != primedUrl) {
                    schedulePrime(upcoming, mine)
                }
                val ok = play(piece)
                if (!ok && piece.attempts < 1 && mine == generation) {
                    queue.addFirst(piece.copy(attempts = piece.attempts + 1))
                } else if (!ok && mine == generation) {
                    withContext(Dispatchers.Main) { fallbackToPhone(piece.text) }
                    delay(800L)
                }
            }
            running = false
            if (mine == generation) {
                isSpeaking = false
                onSpeakingChanged?.invoke(false)
            }
        }
    }

    private fun prefetchUrl(url: String, mine: Int) {
        if (!prefetchEnabled || prefetched.containsKey(url) || inFlight.containsKey(url)) return
        val deferred = downloadScope.async {
            if (mine != generation) return@async null
            val file = download(url)
            if (file != null && mine == generation) {
                prefetched[url] = file
                prefetchFailures = 0
            } else if (file != null) {
                file.delete()
            } else {
                if (++prefetchFailures >= 3) prefetchEnabled = false
            }
            file
        }
        inFlight[url] = deferred
    }

    private fun prefetchNext(mine: Int) {
        if (!prefetchEnabled) return
        var depth = 0
        for (next in queue) {
            if (depth >= prefetchDepth) break
            depth++
            prefetchUrl(next.url, mine)
        }
    }

    private suspend fun play(piece: Piece): Boolean {
        // 1. Ready in local cache
        var file = prefetched.remove(piece.url)

        // 2. Currently downloading in background
        if (file == null) {
            val pending = inFlight.remove(piece.url)
            if (pending != null) {
                file = pending.await()
            }
        }

        // 3. Fallback: download directly now
        if (file == null) {
            file = download(piece.url)
        }

        if (file == null) {
            onError?.invoke("ভয়েস আনা যায়নি")
            return false
        }

        // The player prepared while the previous piece was still speaking, if one
        // was — and only when it was prepared for *this* piece. A primed player
        // holds one URL; handing it to a different piece would play the wrong
        // sentence.
        val usePrimed = primedUrl != null && primedUrl == piece.url
        val primed = if (usePrimed) nextPlayer else null
        val prepared = if (usePrimed) nextPrepared else null
        if (usePrimed) {
            nextPlayer = null
            nextPrepared = null
            primedUrl = null
        }

        return try {
            if (primed != null && prepared != null) {
                playPrepared(primed, prepared)
            } else {
                playSource(file.absolutePath, piece.text, PREPARE_TIMEOUT_MS)
            }
        } finally {
            file.delete()
            prefetched.remove(piece.url)
            inFlight.remove(piece.url)
        }
    }

    /**
     * Waits for [piece] to be downloaded, then primes it.
     *
     * Priming has to happen after the file is on disk, and it must not block the
     * piece that is speaking now. So it waits on the download in the background
     * and prepares the player as soon as the bytes are there — which, with the
     * prefetch already running, is well before the current piece finishes.
     */
    private fun schedulePrime(piece: Piece, mine: Int) {
        if (primedUrl == piece.url) return
        val pending = inFlight[piece.url]
        if (pending == null && prefetched[piece.url] == null) return
        primedUrl = piece.url
        downloadScope.launch {
            if (pending != null) pending.await()
            if (mine != generation || primedUrl != piece.url) return@launch
            withContext(Dispatchers.Main) {
                if (mine == generation && primedUrl == piece.url) primeNext(piece)
            }
        }
    }

    /**
     * Prepares the player for [piece] ahead of time, so [play] can hand over to it
     * with no decode pause. Called once the current piece has started speaking, so
     * the prepare overlaps the speech instead of delaying it.
     */
    private fun primeNext(piece: Piece) {
        val file = prefetched[piece.url] ?: return
        // A player primed for a piece that never played is still holding a
        // decoder. Releasing it here is what keeps priming from leaking one per
        // piece on a long answer.
        release(nextPlayer)
        val mp = MediaPlayer()
        val ready = java.util.concurrent.CompletableFuture<MediaPlayer?>()
        nextPlayer = mp
        nextPrepared = ready
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { player ->
                ready.complete(player)
            }
            mp.setOnErrorListener { _, _, _ ->
                ready.complete(null)
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            ready.complete(null)
        }
    }

    /** Plays a player that [primeNext] already prepared, and releases it. */
    private suspend fun playPrepared(
        mp: MediaPlayer,
        ready: java.util.concurrent.CompletableFuture<MediaPlayer?>
    ): Boolean = withContext(Dispatchers.Main) {
        var levelJob: Job? = null
        var viz: Visualizer? = null
        try {
            val prepared = withTimeoutOrNull(PREPARE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { ready.get() }
            }
            if (prepared == null) {
                release(mp)
                return@withContext false
            }
            attachEnhancer(prepared)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val params = prepared.playbackParams
                    params.speed = 1.05f
                    prepared.playbackParams = params
                }
            } catch (_: Exception) {}
            val done = CompletableDeferred<Boolean>()
            prepared.setOnCompletionListener { done.complete(true) }
            prepared.setOnErrorListener { _, _, _ ->
                done.complete(false)
                true
            }
            try {
                viz = Visualizer(prepared.audioSessionId).apply {
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
                            onAudioLevel?.invoke((Math.sqrt(sum / buf.size) / 128.0).toFloat())
                        }
                        delay(50L)
                    }
                }
            } catch (_: Throwable) { viz = null }
            player = prepared
            try {
                prepared.start()
            } catch (_: Exception) {
                levelJob?.cancel()
                try { viz?.release() } catch (_: Throwable) {}
                release(prepared)
                return@withContext false
            }
            val finished = done.await()
            levelJob?.cancel()
            try { viz?.release() } catch (_: Throwable) {}
            release(prepared)
            finished
        } catch (e: Exception) {
            Log.w(TAG, "prepared playback failed: ${e.message}")
            levelJob?.cancel()
            try { viz?.release() } catch (_: Throwable) {}
            release(mp)
            false
        }
    }

    /** Applies the speech gain to a player's audio session. */
    private fun attachEnhancer(mp: MediaPlayer) {
        try {
            enhancer?.release()
            enhancer = android.media.audiofx.LoudnessEnhancer(mp.audioSessionId).apply {
                setTargetGain(VOICE_GAIN_MB)
                enabled = true
            }
        } catch (_: Exception) {}
        try { mp.setVolume(1f, 1f) } catch (_: Exception) {}
    }

    /**
     * The player for the piece that is about to play.
     *
     * Every piece used to build, prepare and release a MediaPlayer of its own,
     * which put a real pause between pieces: the gap between two sentences was
     * the time `prepareAsync` takes to decode a freshly fetched MP3. Preparing
     * the next player while the current one is still speaking removes that pause
     * — the hand-over is a `start()` on a player that is already primed.
     */
    private var nextPlayer: MediaPlayer? = null
    private var nextPrepared: java.util.concurrent.CompletableFuture<MediaPlayer?>? = null

    /** The URL the primed player was prepared for, so it is never primed twice. */
    @Volatile
    private var primedUrl: String? = null

    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null

    private suspend fun playSource(
        source: String,
        text: String = "",
        timeoutMs: Long = PREPARE_TIMEOUT_MS
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
                try {
                    enhancer?.release()
                    enhancer = android.media.audiofx.LoudnessEnhancer(it.audioSessionId).apply {
                        setTargetGain(VOICE_GAIN_MB)
                        enabled = true
                    }
                } catch (_: Exception) {}
                try { it.setVolume(1f, 1f) } catch (_: Exception) {}
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val params = it.playbackParams
                        params.speed = 1.05f
                        it.playbackParams = params
                    }
                } catch (_: Exception) {}
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
            val started = if (timeoutMs > 0L) {
                withTimeoutOrNull(timeoutMs) { prepared.await() } ?: false
            } else {
                prepared.await()
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

    private suspend fun download(url: String): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "audio/mpeg")
                .header("User-Agent", "ZYRAXON/${com.zyraxon.assistant.BuildConfig.VERSION_NAME} (Android; VoiceEngine)")
                .get()
                .build()
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

    fun stop() {
        generation++
        queue.clear()
        running = false
        isSpeaking = false
        pumpJob?.cancel()
        pumpJob = null
        release(player)
        player = null
        // The primed player is not the one playing, so releasing `player` above
        // does not free it. Left behind it would hold a decoder for the rest of
        // the session.
        release(nextPlayer)
        nextPlayer = null
        nextPrepared = null
        primedUrl = null
        clearPrefetched()
    }

    fun pause() {
        val mp = player
        try { mp?.pause() } catch (_: Exception) {}
        isSpeaking = false
        onSpeakingChanged?.invoke(false)
    }

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

    fun isPaused(): Boolean = try {
        player?.let { !it.isPlaying && running } ?: false
    } catch (_: Exception) {
        false
    }

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
        for (job in inFlight.values) {
            job.cancel()
        }
        inFlight.clear()
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
        const val VOICE_GAIN_MB = 1200
        const val PREPARE_TIMEOUT_MS = 10_000L
    }
}
