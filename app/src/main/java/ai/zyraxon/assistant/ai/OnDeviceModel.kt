package ai.zyraxon.assistant.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * A language model that runs on the phone itself, with no network at all.
 *
 * MediaPipe's LLM Inference task loads a `.task` bundle (a quantised Gemma, for
 * example) and generates text locally. The bundle is not shipped inside the APK —
 * it is hundreds of megabytes — so the user downloads one once and it stays in
 * app storage.
 *
 * This is deliberately a second, optional engine. When no model file is present
 * the app behaves exactly as before and every turn goes to a hosted provider.
 */
class OnDeviceModel(context: Context) {

    private val appContext = context.applicationContext
    private var inference: LlmInference? = null

    /** The model file this instance loaded, if any. */
    var loadedPath: String? = null
        private set

    /** Where downloaded bundles live. */
    fun modelsDir(): File =
        File(appContext.filesDir, "ondevice-models").apply { mkdirs() }

    /** Every `.task` bundle the user has downloaded. */
    fun installedModels(): List<File> =
        modelsDir().listFiles { f -> f.isFile && f.extension == "task" }?.sortedBy { it.name }
            ?: emptyList()

    fun isReady(): Boolean = inference != null

    /**
     * Loads a bundle into memory. The work is real and can take a few seconds for
     * a large file, so callers run this off the main thread.
     */
    @Synchronized
    fun load(path: String): Boolean {
        if (loadedPath == path && inference != null) return true
        unload()
        val file = File(path)
        if (!file.isFile) return false
        return try {
            val options = LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(2048)
                .setMaxTopK(40)
                .build()
            inference = LlmInference.createFromOptions(appContext, options)
            loadedPath = path
            true
        } catch (_: Throwable) {
            inference = null
            loadedPath = null
            false
        }
    }

    @Synchronized
    fun unload() {
        try { inference?.close() } catch (_: Throwable) {}
        inference = null
        loadedPath = null
    }

    /**
     * Generates a reply for one prompt, streaming the text as it is produced.
     * Emits nothing and finishes when no model is loaded, so the caller can fall
     * back to a hosted provider instead of showing a failure.
     */
    fun generate(prompt: String, systemPrompt: String = ""): Flow<String> = callbackFlow {
        val engine = inference ?: run { close(); return@callbackFlow }
        val full = buildString {
            if (systemPrompt.isNotBlank()) {
                append(systemPrompt).append("\n\n")
            }
            append(prompt)
        }
        // The callback reports the whole text so far rather than a delta, so only
        // the newly added tail is emitted downstream.
        var emitted = 0
        try {
            engine.generateResponseAsync(full) { partial, done ->
                if (!partial.isNullOrEmpty() && partial.length > emitted) {
                    trySend(partial.substring(emitted))
                    emitted = partial.length
                }
                if (done) close()
            }
        } catch (_: Throwable) {
            // A closed or unsupported engine simply produces no text.
            close()
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    /**
     * Downloads a bundle to app storage. `token` is needed for the gated Gemma
     * repositories: the user accepts Google's licence on Hugging Face once, makes
     * a read token, and pastes it here. The file is streamed to a `.part` name and
     * only renamed when the whole body arrived, so an interrupted download never
     * leaves a half file that looks usable.
     */
    fun download(
        url: String,
        fileName: String,
        token: String = "",
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): File? {
        val target = File(modelsDir(), fileName)
        val part = File(modelsDir(), "$fileName.part")
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url)
                .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val total = response.body?.contentLength() ?: -1L
                response.body?.byteStream()?.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var read: Int
                        var done = 0L
                        while (input.read(buffer).also { read = it } > 0) {
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (part.renameTo(target)) target else null
        } catch (_: Throwable) {
            part.delete()
            null
        }
    }

    companion object {
        /**
         * One engine per process. The tool layer and the ViewModel must see the
         * same loaded model, and a tool call has no Activity to reach it through,
         * so the instance lives here.
         */
        @Volatile
        private var shared: OnDeviceModel? = null

        fun get(context: Context): OnDeviceModel =
            shared ?: synchronized(this) {
                shared ?: OnDeviceModel(context.applicationContext).also { shared = it }
            }

        /**
         * The bundles offered for download. Every one is verified token-free:
         * a ranged GET returns 206 without any Hugging Face token, unlike the
         * Gemma repositories which answer 401. Sizes are the real file sizes.
         * Ordered smallest first, so a modest phone can still try the smallest.
         */
        val SUGGESTED = listOf(
            OnDeviceModelInfo(
                name = "SmolLM 135M (int8)",
                fileName = "SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
                url = "https://huggingface.co/litert-community/SmolLM-135M-Instruct/resolve/main/SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
                approxMb = 159,
                requiresToken = false
            ),
            OnDeviceModelInfo(
                name = "TinyLlama 1.1B (int8)",
                fileName = "TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task",
                url = "https://huggingface.co/litert-community/TinyLlama-1.1B-Chat-v1.0/resolve/main/TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task",
                approxMb = 1095,
                requiresToken = false
            ),
            OnDeviceModelInfo(
                name = "Qwen2.5 1.5B Instruct (int8)",
                fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                approxMb = 1524,
                requiresToken = false
            ),
            OnDeviceModelInfo(
                name = "DeepSeek-R1 Distill Qwen 1.5B (int8)",
                fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
                url = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv1280.task",
                approxMb = 1775,
                requiresToken = false
            ),
            OnDeviceModelInfo(
                name = "Qwen3 4B (int4)",
                fileName = "qwen3_4b_mixed_int4.litertlm",
                url = "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/qwen3_4b_mixed_int4.litertlm",
                approxMb = 2536,
                requiresToken = false
            ),
            OnDeviceModelInfo(
                name = "Phi-4 mini Instruct (int8)",
                fileName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task",
                url = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task",
                approxMb = 3762,
                requiresToken = false
            )
        )
    }
}

/**
 * One on-device bundle. [requiresToken] is false for every suggested bundle,
 * because each is public; it stays for a gated repository a user adds by hand,
 * where the licence is accepted on [licencePage] with a Hugging Face read token.
 */
data class OnDeviceModelInfo(
    val name: String,
    val fileName: String,
    val url: String,
    val approxMb: Int,
    val requiresToken: Boolean = false,
    val licencePage: String = ""
)
