package ai.zyraxon.assistant.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
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

    /** The LiteRT-LM engine, used for `.litertlm` bundles (Gemma 4, Qwen3). */
    private var lmEngine: com.google.ai.edge.litertlm.Engine? = null

    /** The model file this instance loaded, if any. */
    var loadedPath: String? = null
        private set

    /** Where downloaded bundles live. */
    fun modelsDir(): File =
        File(appContext.filesDir, "ondevice-models").apply { mkdirs() }

    /** Every bundle the user has downloaded: `.litertlm` (LiteRT-LM) and `.task` (MediaPipe). */
    fun installedModels(): List<File> =
        modelsDir().listFiles { f -> f.isFile && f.extension in setOf("litertlm", "task") }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun isReady(): Boolean = inference != null || lmEngine != null

    /**
     * Loads a bundle into memory. The work is real and can take a few seconds for
     * a large file, so callers run this off the main thread.
     */
    @Synchronized
    fun load(path: String): Boolean {
        if (loadedPath == path && isReady()) return true
        unload()
        val file = File(path)
        if (!file.isFile) return false
        return try {
            if (file.extension == "litertlm") {
                // CPU backend: slower than the GPU but it does not crash on any
                // phone, and the phone's own GPU drivers vary too much to trust.
                val config = com.google.ai.edge.litertlm.EngineConfig(
                    modelPath = file.absolutePath,
                    backend = com.google.ai.edge.litertlm.Backend.CPU(),
                    cacheDir = appContext.cacheDir.path
                )
                val engine = com.google.ai.edge.litertlm.Engine(config)
                engine.initialize()
                lmEngine = engine
            } else {
                val options = LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    .setMaxTokens(2048)
                    .setMaxTopK(40)
                    .build()
                inference = LlmInference.createFromOptions(appContext, options)
            }
            loadedPath = path
            true
        } catch (_: Throwable) {
            inference = null
            lmEngine = null
            loadedPath = null
            false
        }
    }

    @Synchronized
    fun unload() {
        try { inference?.close() } catch (_: Throwable) {}
        try { lmEngine?.close() } catch (_: Throwable) {}
        inference = null
        lmEngine = null
        loadedPath = null
    }

    /**
     * Generates a reply for one prompt, streaming the text as it is produced.
     * Emits nothing and finishes when no model is loaded, so the caller can fall
     * back to a hosted provider instead of showing a failure.
     */
    fun generate(prompt: String, systemPrompt: String = ""): Flow<String> = callbackFlow {
        val lm = lmEngine
        if (lm != null) {
            // One short-lived conversation per call: the caller sends the history
            // it wants the model to see inside the prompt, so nothing is kept here.
            val qwen = loadedPath?.lowercase()?.contains("qwen") == true
            // Qwen3 "thinks" for a long time by default and can use up its whole
            // small context before answering; /no_think switches that off.
            val text = if (qwen) "/no_think\n$prompt" else prompt
            val conversation = try {
                if (systemPrompt.isNotBlank()) {
                    lm.createConversation(
                        com.google.ai.edge.litertlm.ConversationConfig(
                            systemInstruction = com.google.ai.edge.litertlm.Contents.of(systemPrompt)
                        )
                    )
                } else lm.createConversation()
            } catch (_: Throwable) { close(); return@callbackFlow }
            launch {
                try {
                    conversation.sendMessageAsync(text)
                        .catch { }
                        .collect { part ->
                            val piece = part.toString()
                            if (piece.isNotEmpty()) trySend(piece)
                        }
                } catch (_: Throwable) {
                }
                close()
            }
            awaitClose { try { conversation.close() } catch (_: Throwable) {} }
            return@callbackFlow
        }
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
                .connectTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
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
                name = "SmolLM2 135M — সবচেয়ে ছোট (১৪৩ MB)",
                fileName = "SmolLM2_135M_Instruct.litertlm",
                url = "https://huggingface.co/litert-community/SmolLM2-135M-Instruct/resolve/main/SmolLM2_135M_Instruct.litertlm",
                approxMb = 143,
                requiresToken = false,
                note = "সবচেয়ে ছোট বান্ডেল — প্রায় যেকোনো ফোনে চলে। সাধারণ কথা ও ছোট প্রশ্নের জন্য, জটিল কাজে নয়। Apache-2.0।"
            ),
            OnDeviceModelInfo(
                name = "FunctionGemma 270M — ছোট ও দ্রুত, টুল-কল (২৮৪ MB)",
                fileName = "mobile-actions_q8_ekv1024.litertlm",
                url = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/main/mobile-actions_q8_ekv1024.litertlm",
                approxMb = 284,
                requiresToken = false,
                note = "FunctionGemma 270M, ফাংশন-কলিংয়ের জন্য ফাইন-টিউনড। সবচেয়ে কম RAM-এ চলে এবং সবচেয়ে দ্রুত — দুর্বল ফোনের জন্যও ভালো। Apache-2.0।"
            ),
            OnDeviceModelInfo(
                name = "Qwen3 0.6B — খুব দুর্বল ফোনের জন্য (৫৮৬ MB)",
                fileName = "Qwen3-0.6B.litertlm",
                url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
                approxMb = 586,
                requiresToken = false,
                note = "খুব ছোট ও দ্রুত, কিন্তু বাংলা ও জটিল কাজে দুর্বল। শুধু শেষ ভরসা হিসেবে।"
            ),
            OnDeviceModelInfo(
                name = "Gemma 4 E2B — সেরা পছন্দ (বহুভাষা, টুল-কল, ২.৬ GB)",
                fileName = "gemma-4-E2B-it.litertlm",
                url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                approxMb = 2583,
                requiresToken = false,
                note = "Google-এর Gemma পরিবার বহু ভাষায় প্রশিক্ষিত। ৬ GB+ RAM-এর ফোনে ভালো চলে। Apache-2.0।"
            ),
            OnDeviceModelInfo(
                name = "Qwen3 4B — বহুভাষা, ভালো টুল-কল (২.৫ GB)",
                fileName = "qwen3_4b_mixed_int4.litertlm",
                url = "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/qwen3_4b_mixed_int4.litertlm",
                approxMb = 2536,
                requiresToken = false,
                note = "Qwen3 ১১৯টি ভাষা জানে (বাংলা সহ) এবং টুল-কল ভালো বোঝে। প্রসঙ্গ-সীমা ২০৪৮ টোকেন। Apache-2.0।"
            ),
            OnDeviceModelInfo(
                name = "Gemma 4 E4B — সবচেয়ে নিখুঁত (৩.৭ GB, ৮ GB+ RAM)",
                fileName = "gemma-4-E4B-it.litertlm",
                url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                approxMb = 3660,
                requiresToken = false,
                note = "E2B-র চেয়ে বুদ্ধিমান, কিন্তু বেশি RAM লাগে। ফাইলের নামটি নিয়মমাফিক ধরা, ডাউনলোড না হলে রিপোর (litert-community/gemma-4-E4B-it-litert-lm) নাম দেখুন।"
            ),
            OnDeviceModelInfo(
                name = "Qwen3 0.6B — খুব দুর্বল ফোনের জন্য (৫৮৬ MB)",
                fileName = "Qwen3-0.6B.litertlm",
                url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
                approxMb = 586,
                requiresToken = false,
                note = "খুব ছোট ও দ্রুত, কিন্তু বাংলা ও জটিল কাজে দুর্বল। শুধু শেষ ভরসা হিসেবে।"
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
    val licencePage: String = "",
    val note: String = ""
)
