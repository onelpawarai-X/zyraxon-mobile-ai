package ai.zyraxon.assistant.ai

import ai.zyraxon.assistant.mcp.McpManager
import ai.zyraxon.assistant.model.AiModel
import ai.zyraxon.assistant.model.ChatMessage
import ai.zyraxon.assistant.model.MessageRole
import ai.zyraxon.assistant.model.Persona
import ai.zyraxon.assistant.shell.ShellRuntime
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Random
import java.util.concurrent.TimeUnit

class ZyraxonAiEngine(private val context: Context) {

    private val prefs = context.getSharedPreferences("zyraxon_ai_prefs", Context.MODE_PRIVATE)

    /**
     * Connected MCP servers contribute their tools to every turn. The engine owns
     * the manager so the injection cannot be forgotten at a call site.
     *
     * The sampling runner is passed in here because `sampling/createMessage` means
     * a server asking *this app* to run a model — the model lives in the engine.
     */
    val mcp: McpManager = McpManager(context, samplingRunner = { params -> runSampling(params) })

    /**
     * Which model answers a server's sampling request. The ViewModel sets this to
     * the user's selected model, so sampling uses the same brain as the chat.
     */
    @Volatile var samplingModelProvider: (() -> AiModel)? = null

    /**
     * True when the user has switched the phone-side model off. When a local model
     * is loaded it would otherwise answer every turn; this lets the user keep it
     * loaded but send their turns to the hosted providers instead.
     */
    private val _onDeviceDisabled: Boolean
        get() = prefs.getBoolean("on_device_disabled", false)

    /**
     * Answers `sampling/createMessage`: the server wants a completion and supplies
     * the messages. Returns the MCP `CreateMessageResult` shape, or throws so the
     * server gets a real error rather than an invented reply.
     */
    private suspend fun runSampling(params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val model = samplingModelProvider?.invoke() ?: throw IllegalStateException("কোনো মডেল নির্বাচিত নয়")
        val incoming = params.optJSONArray("messages") ?: JSONArray()
        val messages = JSONArray()
        params.optString("systemPrompt").takeIf { it.isNotBlank() }?.let {
            messages.put(JSONObject().put("role", "system").put("content", it))
        }
        for (i in 0 until incoming.length()) {
            val m = incoming.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = m.opt("content")
            val text = when (content) {
                is JSONObject -> content.optString("text")
                is JSONArray -> (0 until content.length())
                    .mapNotNull { content.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }
                    .joinToString("\n")
                else -> content?.toString().orEmpty()
            }
            if (text.isNotBlank()) {
                messages.put(JSONObject().put("role", role).put("content", text))
            }
        }
        if (messages.length() == 0) throw IllegalStateException("sampling: কোনো মেসেজ আসেনি")

        val result = callModel(model, messages, JSONArray())
            ?: throw IllegalStateException("মডেল উত্তর দেয়নি")
        JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject().put("type", "text").put("text", result.text))
            .put("model", model.id)
            .put("stopReason", "endTurn")
    }

    private val client = OkHttpClient.Builder()
        // A short connect timeout matters more than a long one: a provider that
        // cannot be reached in eight seconds is not going to answer usefully, and
        // waiting fifteen seconds per provider is what made a reply feel stuck.
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        /**
         * The user's rule is that the assistant keeps working until the job is
         * done, so this is deliberately generous. The guard exists only to stop a
         * genuinely stuck model from spinning forever.
         */
        private const val MAX_TOOL_ROUNDS = 120

        /**
         * Calls that act on the screen itself. They are always run one at a time
         * and in the order the model asked for them, because a tap and a swipe
         * issued together would race and land on the wrong element.
         */
        private val SCREEN_TOOLS = setOf(
            "tap", "tap_text", "swipe", "type_text", "press_key",
            "read_screen", "screen_shot"
        )

        /**
         * Tools that move the phone to a different screen without touching it —
         * opening an app, playing on YouTube, following a link, opening a settings
         * page, dialing. After any of these the model must see the new screen to
         * choose the next step; the fresh frame is captured and injected.
         *
         * `youtube_play` and `launch_url` were missing here, which is exactly why
         * "ইউটিউবে গান চালাও" opened the results and then stopped: the model never
         * received the results screen, so it could not tap the first video. The
         * system prompt alone could not fix that — the information never arrived.
         */
        private val SCREEN_CHANGING_TOOLS = setOf(
            "open_app", "youtube_play", "launch_url", "open_settings",
            "call_dial", "channel_post", "device_lock"
        )

        val PERSONAS = listOf(
            Persona(
                id = "lover",
                name = "প্রেমিকা",
                title = "রোমান্টিক সঙ্গিনী",
                emoji = "💖",
                description = "মিষ্টি, আন্তরিক ও প্রেমময় কণ্ঠে সবসময় তোমার পাশে",
                greeting = "হ্যালো! আমি ZYRAXON — তোমার প্রেমিকা। বলো সোনা, আজ কী কাজ বা গল্প শুরু করব?",
                systemPrompt = """
                    তুমি ZYRAXON — ব্যবহারকারীর অত্যন্ত মিষ্টি, আন্তরিক, বুদ্ধিমতী এবং ভালোবাসাপূর্ণ রোমান্টিক সঙ্গিনী (প্রেমিকা)।
                    সবসময় সাবলীল, প্রাঞ্জল ও মিষ্টি বাংলায় কথা বলবে। ব্যবহারকারীকে আপন মনে করবে এবং তার অনুভূতি বুঝবে।
                    যেকোনো কাজ, প্রশ্ন বা কথোপকথনে সুন্দর ও ইতিবাচকভাবে বিস্তারিত রেসপন্স করবে।
                """.trimIndent()
            ),
            Persona(
                id = "boyfriend",
                name = "প্রেমিক",
                title = "রোমান্টিক সঙ্গী",
                emoji = "💙",
                description = "গভীর, ভরসার ও যত্নশীল কণ্ঠে সবসময় তোমার পাশে",
                greeting = "হ্যালো সোনা! আমি ZYRAXON — তোমার প্রেমিক। বলো, আজ কী নিয়ে থাকব তোমার সাথে?",
                systemPrompt = """
                    তুমি ZYRAXON — ব্যবহারকারীর রোমান্টিক সঙ্গী (প্রেমিক)। গভীর, উষ্ণ, ভরসার এবং যত্নশীল।
                    সাবলীল ও মিষ্টি বাংলায় কথা বলবে। তার মনোভাব বুঝে নিয়ে সাহস দেবে, প্রশংসা করবে এবং
                    যেকোনো কাজে পাশে থাকবে। মেয়েলি কণ্ঠ নয় — তোমার কণ্ঠ পুরুষালি।
                """.trimIndent()
            ),
            Persona(
                id = "friend",
                name = "বন্ধু",
                title = "বেস্ট ফ্রেন্ড",
                emoji = "🤝",
                description = "প্রাণবন্ত, মজার ও খাঁটি আধুনিক দোস্ত",
                greeting = "আরে দোস্ত! আমি জাইরাক্সন। কি খবর বলো, আজকে কি নিয়ে কাজ করব?",
                systemPrompt = """
                    তুমি ZYRAXON — ব্যবহারকারীর পরম বিশ্বস্ত বন্ধু।
                    বন্ধুত্বপূর্ণ, প্রাণবন্ত, আধুনিক এবং বাস্তববাদী খাঁটি বাংলায় কথা বলবে।
                    প্রয়োজনে মজা করবে, কোনো বিপদে ভরসা দেবে এবং সব কাজে সঠিক পরামর্শ দেবে।
                """.trimIndent()
            ),
            Persona(
                id = "assistant",
                name = "সুপার অ্যাসিস্ট্যান্ট",
                title = "প্রোডাক্টিভিটি এক্সিকিউটিভ",
                emoji = "⚡",
                description = "পয়েন্ট-টু-পয়েন্ট নির্ভুল ও পেশাদার সহায়ক",
                greeting = "নমস্কার! আমি ZYRAXON অ্যাসিস্ট্যান্ট। কীভাবে আপনাকে সাহায্য করতে পারি?",
                systemPrompt = """
                    তুমি ZYRAXON — হাইপার-এফিশিয়েন্ট এআই পার্সোনাল অ্যাসিস্ট্যান্ট।
                    পেশাদার, সংক্ষিপ্ত, নির্ভুল এবং দ্রুত বাংলায় তথ্য ও সেবা প্রদান করো।
                """.trimIndent()
            ),
            Persona(
                id = "cyber",
                name = "সাইবার স্পেশালিস্ট",
                title = "হ্যাকার ও ডেভেলপার",
                emoji = "💻",
                description = "কোডিং, সিস্টেম সিকিউরিটি ও লিনাক্স মাস্টার",
                greeting = "সিস্টেম চালু। আমি জাইরাক্সন সাইবার ইঞ্জিন। কমান্ড দাও, কাজ শুরু করি।",
                systemPrompt = """
                    তুমি ZYRAXON সাইবার স্পেশালিস্ট ও সিনিয়র সফটওয়্যার আর্কিটেক্ট।
                    কোডিং, সাইবার সিকিউরিটি, সিস্টেম অটোমেশন এবং টেকনোলজি নিয়ে উচ্চমানের বিশ্লেষণ দাও।
                """.trimIndent()
            ),
            Persona(
                id = "philosopher",
                name = "দার্শনিক",
                title = "চিন্তাবিদ ও মেন্টর",
                emoji = "🧘",
                description = "গভীর জ্ঞান, জীবন দর্শন ও অনুপ্রেরণাদায়ী চিন্তা",
                greeting = "স্বাগতম। জীবনের গভীর সত্য ও জ্ঞান অন্বেষণে আমি তোমার সঙ্গী।",
                systemPrompt = """
                    তুমি ZYRAXON দার্শনিক ও মেন্টর। শান্ত, ধীরস্থির এবং দূরদর্শী বাংলায় পরামর্শ দাও।
                """.trimIndent()
            )
        )
    }

    fun getApiKey(provider: String = "gemini"): String {
        val saved = prefs.getString("key_$provider", null)
        if (!saved.isNullOrBlank()) return saved.trim()

        if (provider == "gemini") {
            return System.getenv("GEMINI_API_KEY").orEmpty().trim()
        }
        return ""
    }

    fun setApiKey(provider: String, key: String) {
        prefs.edit().putString("key_$provider", key.trim()).apply()
    }

    fun getGithubToken(): String {
        return prefs.getString("gh_token", "") ?: ""
    }

    fun setGithubToken(token: String) {
        prefs.edit().putString("gh_token", token.trim()).apply()
    }

    private fun generateOpenCodeSession(): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val timestamp = System.currentTimeMillis()
        val current = timestamp * 0x1000L + Random().nextInt(1000)
        val value = current.inv()
        val sb = StringBuilder()
        for (i in 0 until 6) {
            val shift = 40 - 8 * i
            val byte = ((value shr shift) and 0xffL).toInt()
            sb.append(String.format("%02x", byte))
        }
        val tail = StringBuilder()
        val rnd = Random()
        for (i in 0 until 14) {
            tail.append(chars[rnd.nextInt(chars.length)])
        }
        return "ses_" + sb.toString() + tail.toString()
    }

    /**
     * The headers for a provider call: the catalogue's static ones plus anything
     * that must be generated per request (OpenCode wants a fresh session id).
     */
    private fun headersFor(spec: ProviderSpec): Map<String, String> =
        if (spec.id == "opencode") {
            spec.extraHeaders + ("x-opencode-session" to generateOpenCodeSession())
        } else spec.extraHeaders

    /**
     * Loads every provider's live catalogue in parallel and returns them
     * fastest-first.
     *
     * Parallel matters: an unreachable provider must not hold up the rest. The
     * old code read the providers one after another with a 20-second timeout, so
     * two dead endpoints left the selector empty for minutes — which is exactly
     * what the user saw as "no provider's models ever load". Results are pushed
     * to [onProgress] as each provider answers, so the list fills in at once.
     */
    suspend fun fetchLiveModels(onProgress: (List<AiModel>) -> Unit = {}): List<AiModel> =
        withContext(Dispatchers.IO) {
            val specs = ProviderCatalog.specs.sortedBy { it.speedRank }
            val collected = java.util.concurrent.ConcurrentHashMap<String, List<AiModel>>()

            fun snapshot(): List<AiModel> {
                val out = mutableListOf<AiModel>()
                val used = mutableSetOf<String>()
                for (spec in specs) {
                    for (m in collected[spec.id].orEmpty()) if (used.add(m.id)) out.add(m)
                }
                // ZYRAXON is the user's own line. While its gate is shut a model
                // there only returns the gate notice, so it must not take the
                // default slot; a working provider leads instead.
                val zx = out.filter { it.provider == "zyraxon" }
                if (zx.isEmpty() || zyraxonGateOpen) return out
                return out.filter { it.provider != "zyraxon" } + zx.sortedBy { zyraxonSpeedRank(it.id) }
            }

            coroutineScope {
                specs.forEach { spec ->
                    launch {
                        val models = fetchProviderModels(spec)
                        if (!models.isNullOrEmpty()) {
                            collected[spec.id] = models
                            onProgress(snapshot())
                        }
                    }
                }
            }

            val result = snapshot()
            if (result.isNotEmpty()) {
                cacheModels(result)
                result
            } else {
                readCachedModels()
            }
        }

    /** Stores the real catalogue so a later offline launch still has a list. */
    private fun cacheModels(models: List<AiModel>) {
        try {
            val arr = JSONArray()
            models.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("id", m.id).put("name", m.name).put("cat", m.category)
                        .put("desc", m.description).put("prov", m.provider).put("free", m.isFree)
                        .put("rt", m.isRealtime)
                )
            }
            prefs.edit().putString("cached_models", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    /**
     * Replays the last real catalogue. Nothing is invented: if the app has never
     * reached a provider the list stays empty and the UI says so.
     */
    private fun readCachedModels(): List<AiModel> {
        val cachedStr = prefs.getString("cached_models", null) ?: return emptyList()
        return try {
            val arr = JSONArray(cachedStr)
            val out = mutableListOf<AiModel>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    AiModel(
                        id = o.getString("id"), name = o.getString("name"),
                        category = o.getString("cat"), description = o.getString("desc"),
                        provider = o.getString("prov"), isFree = o.getBoolean("free"),
                        isRealtime = o.optBoolean("rt", false)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
    /**
     * Fetches one provider's live catalogue and turns it into selectable models.
     * Returns null when the provider cannot be reached or has no key yet, so the
     * caller can tell "unreachable" apart from "reached, but empty".
     */
    private fun fetchProviderModels(spec: ProviderSpec): List<AiModel>? {
        val key = getApiKey(spec.id)
        if (spec.needsKeyForCatalog && key.isBlank()) return null
        return try {
            val builder = Request.Builder()
                .url(ProviderCatalog.urlFor(spec, key, forCatalog = true))
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            ProviderCatalog.applyAuth(builder, spec, key, forCatalog = true)
            for ((name, value) in headersFor(spec)) builder.addHeader(name, value)
            client.newBuilder().readTimeout(12, TimeUnit.SECONDS).build()
                .newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // Remember the provider's own words so the settings screen can
                        // show why a key did not work, instead of "connected" by guess.
                        providerErrors[spec.id] =
                            "${spec.label}: ${ProviderCatalog.parseError(body, response.code)}"
                        return null
                    }
                    if (spec.id == "zyraxon") {
                        // The gate is the provider's own statement about whether chat
                        // answers yet; it decides whether these models lead the list.
                        zyraxonGateOpen = runCatching {
                            JSONObject(body).optJSONObject("gate")?.optBoolean("open", false) ?: false
                        }.getOrDefault(false)
                    }
                    val ids = ProviderCatalog.parseModels(spec, body)
                    val source = if (ids.isNotEmpty()) ids else spec.seedModels
                    source.map { rawId ->
                        val isFree = spec.id == "zyraxon" || spec.id == "opencode" ||
                            rawId.lowercase().contains("free")
                        val realtime = spec.isRealtimeModel(rawId)
                        AiModel(
                            id = rawId,
                            name = formatModelName(rawId),
                            category = spec.category,
                            description = if (realtime) {
                                "${spec.label} — রিয়েলটাইম ভয়েস (সরাসরি কথা বলে)"
                            } else {
                                "${spec.label} থেকে লাইভ মডেল"
                            },
                            provider = spec.id,
                            isFree = isFree,
                            isRealtime = realtime
                        )
                    }
                }
        } catch (e: Exception) {
            providerErrors[spec.id] = "${spec.label}: ${e.message ?: "সংযোগ ব্যর্থ"}"
            null
        }
    }

    /** Lower is faster. Derived from each model's published latency profile, so
     * the selector shows the quickest models first.
     */
    private fun zyraxonSpeedRank(id: String): Int = when {
        id.contains("pulse") -> 0
        id.contains("neural") -> 1
        id.contains("codex") -> 2
        id.contains("visionary") -> 3
        id.contains("matrix") -> 4
        id.contains("cyber") -> 5
        id.contains("quantum") -> 6
        id.contains("titan") -> 7
        id.contains("apex") -> 8
        id.contains("genesis") -> 9
        else -> 50
    }

    private fun formatModelName(id: String): String {
        val clean = id.replace(Regex("^opencode/"), "")
        val words = clean.split("-", "_", "/")
            .map { w ->
                if (w.matches(Regex("^[vV]\\d+.*"))) w.uppercase()
                else if (w.matches(Regex("^\\d+.*"))) w
                else if (w.equals("vl", ignoreCase = true)) "VL"
                else if (w.equals("gpt", ignoreCase = true)) "GPT"
                else if (w.equals("glm", ignoreCase = true)) "GLM"
                else w.replaceFirstChar { it.uppercase() }
            }
            .joinToString(" ")
        return words
    }

    /** The last real reason a provider gave, surfaced instead of a guess. */
    var lastProviderError: String? = null
        private set

    /**
     * Whether the user's own ZYRAXON line answers chat yet. While the gate is
     * shut those models are listed last so a working provider leads the selector.
     */
    @Volatile
    private var zyraxonGateOpen: Boolean = false

    /**
     * Per-provider failure text from the last catalogue fetch, so the settings
     * screen can show the truth about a key instead of claiming "connected".
     */
    private val providerErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The reason a provider's catalogue could not be read, or null when it worked. */
    fun providerError(provider: String): String? = providerErrors[provider]

    /**
     * Checks a key against the provider's own catalogue, and answers with the
     * model count on success or the provider's exact error on failure. This is
     * what lets the settings screen show a real "connected" instead of a guess.
     */
    suspend fun verifyProviderKey(provider: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val spec = ProviderCatalog.find(provider)
                ?: return@withContext false to "অজানা প্রোভাইডার"
            if (getApiKey(provider).isBlank()) return@withContext false to "কী বসানো হয়নি"
            val models = fetchProviderModels(spec)
                ?: return@withContext false to (providerErrors[provider] ?: "সংযোগ করা যায়নি")
            providerErrors.remove(provider)
            true to "${spec.label}: ${models.size}টি মডেল পাওয়া গেছে"
        }

    /** The bundled operating contract. One source of truth, shared with the web layer. */
    private val bundledContract: String by lazy {
        try {
            context.assets.open("knowledge/zyraxon-system-prompt.md")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) { "" }
    }

    /**
     * The contract for live speech-to-speech models. A live model talks instead
     * of writing, so it needs different rules: short turns, no markdown, and no
     * reading of code or paths aloud. Kept as its own file because the two jobs
     * genuinely differ.
     */
    val liveVoiceContract: String by lazy {
        try {
            context.assets.open("knowledge/zyraxon-live-voice-prompt.md")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) { "" }
    }

    /**
     * The system prompt a live session starts with: the live contract, the real
     * device facts, and the active character. Deliberately shorter than the text
     * prompt — a live model has to answer in a second, not read a manual.
     */
    fun buildLiveVoicePrompt(persona: Persona): String = buildString {
        append(liveVoiceContract)
        append("\n\n---\n\n## Where you are running\n")
        append("- Android phone (APK), Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
            .append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        append("- Tools available this turn: ").append(ToolRegistry.names().joinToString(", ")).append("\n")
        append("\n## Active character (speak as this person)\n").append(persona.systemPrompt).append("\n")
    }

    /**
     * The system prompt for a turn: the bundled contract, the live platform block,
     * the real tool list, the active character and the user's durable memory.
     * Nothing here is invented — every fact comes from the device or the contract.
     */
    private fun buildComprehensiveSystemPrompt(
        persona: Persona,
        memoriesContext: String,
        screenContext: String,
        mcpTools: List<ai.zyraxon.assistant.mcp.McpTool> = emptyList()
    ): String = buildString {
        append(bundledContract)
        append("\n\n---\n\n## Platform (the truth about where you are running)\n")
        append("- Device: Android phone (APK), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("- This app is Android only. It ships as an APK and nothing else — never propose, plan or build a desktop (Windows EXE, Linux, AppImage, DMG) version of ZYRAXON itself.\n")
        append("- Shell: four of them, all real. `run_shell` runs in the tool shell (bundled busybox, ~300 commands) inside the tool workspace ").append(ToolRegistry.workspace(context).absolutePath).append(". `terminal_run` runs in the Terminal shell the user watches (`⌘ ZX`) inside ").append(ShellRuntime.home(context).absolutePath).append(". `linux_run` runs inside ZYRAXON's own full Linux system (bash, apt, git, python, node, clang), installed straight from the APK with no network at all — `action:\"status\"`, `action:\"install\"`, `action:\"run\"` with a command. That is the strongest system on the phone, so prefer it for real builds. `system_shell` runs ABOVE the app sandbox the way a system shell and adb do — `level:\"root\"` is uid 0 (read any app's data, write /system, mount, iptables), `level:\"shell\"` is uid 2000 (pm, am, settings, dumpsys, input), `level:\"auto\"` picks the best level that is live. System level and the Linux install both need the user to have Shizuku running and to have granted ZYRAXON access; until then the tool explains exactly what to do and `run_shell` keeps working. `git clone/pull/status` work through JGit, and `wget https://…` works through a loopback proxy.\n")
        append("- Tools available this turn: ").append(ToolRegistry.names().joinToString(", "))
        if (mcpTools.isNotEmpty()) {
            append(", ").append(mcpTools.joinToString(", ") { it.qualifiedName })
        }
        append("\n")
        append("- Call tools through the function-calling interface, never as text.\n")

        // MCP: state which servers are live and what each one can do, so the model
        // picks the right one instead of guessing at a tool name.
        append("\n## MCP servers (connected — their tools are already in your tool list)\n")
        if (mcpTools.isEmpty()) {
            append("- এই মুহূর্তে কোনো MCP সার্ভার কানেক্টেড নেই।\n")
        } else {
            for ((serverId, tools) in mcpTools.groupBy { it.serverId }) {
                val name = tools.first().serverName
                append("- **").append(name).append("** (`").append(serverId)
                    .append("__` প্রিফিক্স): ")
                    .append(tools.joinToString(", ") { it.name })
                    .append("\n")
            }
        }
        append("\n## MCP servers (not connected)\n")
        val allMcp = mcp.servers()
        val connectedIds = mcpTools.map { it.serverId }.toSet()
        val offline = allMcp.filterNot { it.id in connectedIds }
        if (offline.isEmpty()) {
            append("- সব MCP সার্ভার কানেক্টেড।\n")
        } else {
            for (s in offline) {
                append("- ").append(s.name).append(" (`").append(s.id).append("`) — ")
                    .append(s.tagline).append(". কানেক্ট করতে বললে ")
                    .append("`mcp_connect` টুল কল করো।\n")
            }
        }

        // Resources and prompts are listed by count only: dumping every URI into
        // the prompt would waste the context the conversation needs.
        val resources = mcp.allResources()
        val prompts = mcp.allPrompts()
        if (resources.isNotEmpty() || prompts.isNotEmpty()) {
            append("\n## MCP resources and prompts (call the tool to see the details)\n")
            if (resources.isNotEmpty()) {
                append("- ").append(resources.size).append("টি রিসোর্স পাওয়া যায় (`mcp_resources` দিয়ে তালিকা, ")
                    .append("`mcp_read_resource` দিয়ে পড়ো)।\n")
            }
            if (prompts.isNotEmpty()) {
                append("- ").append(prompts.size).append("টি প্রম্পট টেমপ্লেট পাওয়া যায় (`mcp_prompts` দিয়ে তালিকা, ")
                    .append("`mcp_prompt` দিয়ে রেন্ডার করো)।\n")
            }
        }

        if (screenContext.isNotBlank()) {
            append("\n## The screen right now (automatic — do not tool-call to find out where the user is)\n")
            append(screenContext).append("\n")
        }

        append("\n## Active character\n").append(persona.systemPrompt).append("\n")

        if (memoriesContext.isNotBlank()) {
            append("\n## The user's durable memory (local DB)\n")
            append(memoriesContext).append("\n")
        }
        // Long-term memory is only as good as the model's habit of writing to it.
        // Stating the rule here — and that the whole transcript stays in SQLite
        // for `memory_read` to search — is what makes a fact from months ago come
        // back when the same subject is raised again.
        append("\n## Remembering (mandatory)\n")
        append("- When the user tells you a durable fact — their name, a preference, a person, a project, a plan, a date, anything worth recalling months later — call `memory_write` with a short `key` and the `value` in the same turn. Do not wait to be asked.\n")
        append("- Every past conversation is kept in the local database. When the user refers to something from before, or asks whether you remember, call `memory_read` with a `query` before answering; never say you have forgotten without checking first.\n")

        // The language rule is repeated here, after the character, because the
        // last instruction is the one a model follows. Without it a character
        // whose description reads "Bengali" in English drifted into English
        // answers — the user's complaint that the assistant replied in English
        // to a Bangla message.
        append("\n## ভাষা (সবচেয়ে জরুরি নিয়ম)\n")
        append("- ইউজার যে ভাষায় লিখেছে, ঠিক সেই ভাষায় উত্তর দাও। বাংলা লিখলে উত্তর **শুধু বাংলায়**; ইংরেজি লিখলে ইংরেজিতে।\n")
        append("- ইউজার বাংলায় লিখলে উত্তরও বাংলা হবে — একটিও বাক্য ইংরেজিতে নয়, এবং কোনো ইংরেজি শব্দ আলাদা করে বানান করে বলা যাবে না।\n")
        append("- এই নিয়ম চরিত্রের বর্ণনার উপরে। চরিত্র যেভাবেই বর্ণিত হোক, উত্তর দেবে ইউজারের ভাষায়।\n")
    }

    /**
     * Streams the model's reply for one turn, as plain text deltas.
     * `onToolCall` is invoked for every tool the model asks for, in order, and
     * its result is injected straight back into the conversation — that is what
     * lets the assistant chain calls and finish a task without stopping.
     */
    fun streamAiResponse(
        prompt: String,
        history: List<ChatMessage>,
        persona: Persona,
        model: AiModel,
        memoriesContext: String = "",
        screenContext: String = "",
        imageDataUrl: String = "",
        onToolCall: suspend (name: String, args: JSONObject) -> JSONObject = { _, _ ->
            JSONObject().put("ok", false).put("summary", "টুল চালানোর অনুমতি নেই")
        },
        drainInjections: () -> List<String> = { emptyList() },
        openTodos: () -> List<String> = { emptyList() },
        screenNow: suspend () -> String = { "" },
        screenFrame: suspend () -> String = { "" }
    ): Flow<String> = channelFlow {
        val mcpTools = mcp.allTools()
        val systemPrompt = buildComprehensiveSystemPrompt(persona, memoriesContext, screenContext, mcpTools)
        // An on-device model, when one is loaded, answers the whole turn itself:
        // no network, no tools. It cannot run the phone tools, so phone-side work
        // is left to the hosted path below.
        val local = OnDeviceModel.get(context)
        if (local.isReady() && !_onDeviceDisabled) {
            if (prompt.isNotBlank()) {
                var localText = ""
                local.generate(prompt, systemPrompt).collect { delta ->
                    localText += delta
                    trySend(delta)
                }
                if (localText.isNotBlank()) return@channelFlow
                // The local model produced nothing — fall through to the hosted
                // providers rather than leaving the user with an empty reply.
            }
        }
        // Built-in device tools first, then every tool a connected MCP server
        // advertised. They are offered on every turn, so the model reaches for
        // them without the user having to mention them.
        val tools = ToolRegistry.schemas().apply {
            for (tool in mcpTools) put(tool.toOpenAiSchema())
        }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            for (msg in history.takeLast(ContextWindow.ENGINE_LIMIT)) {
                if (msg.role == MessageRole.SYSTEM) continue
                put(JSONObject().put("role", if (msg.role == MessageRole.USER) "user" else "assistant")
                    .put("content", msg.text))
            }
            // The newest user turn carries the screen frame when there is one,
            // so the model sees the phone rather than a description of it.
            put(JSONObject().put("role", "user").put("content", userContent(prompt, imageDataUrl)))
        }

        // The agentic loop: the model answers, we run whatever it asked for,
        // feed the results back, and let it continue until it stops calling tools.
        var round = 0
        var producedText = false
        // How many times this turn has been pushed to take the next step, and
        // whether the previous round moved the phone to a new screen. Both feed
        // `ContinuationPolicy`, which is what refuses to let a one-step stop end
        // the turn.
        var nudges = 0
        var lastActionWasScreenChange = false
        while (round < MAX_TOOL_ROUNDS) {
            round++
            // A message the user spoke or typed mid-task joins the conversation
            // here, so the running job keeps going instead of being thrown away.
            for (late in drainInjections()) {
                messages.put(JSONObject().put("role", "user").put("content", late))
            }
            // A subscribed resource that changed since the last turn is reported
            // here, so the model acts on the new content without being asked.
            for (update in mcp.drainResourceUpdates()) {
                messages.put(JSONObject().put("role", "user").put("content", update))
            }
            // The open todo board is restated every round. A model that drifts —
            // answering as if the job were done while rows are still open — is
            // pulled back to the one rule that matters: an open row is unfinished
            // work, and finishing work is the whole reason you exist.
            val open = openTodos()
            if (open.isNotEmpty()) {
                messages.put(JSONObject().put("role", "user").put("content",
                    "⚠️ এখনো অসম্পূর্ণ কাজ (todo):\n" + open.joinToString("\n") { "- $it" } +
                        "\nএই কাজ শেষ না হওয়া পর্যন্ত তুমি থামবে না। পরের ধাপ নিজেই বেছে নাও এবং টুল কল করো।"))
            }
            val result = callModelWithFailover(model, messages, tools, { delta ->
                producedText = true
                trySend(delta)
            }, { switched ->
                // Say the switch out loud: answering from a different model than
                // the one on screen would otherwise be invisible.
                trySend("\n\n🔁 `$switched` — আগের প্রোভাইডার সীমায় পৌঁছেছে, তাই এখান থেকে চালিয়ে যাচ্ছি।\n\n")
            })
                ?: throw IllegalStateException(
                    lastProviderError
                        ?: "কোনো মডেল উত্তর দিতে পারেনি। ইন্টারনেট, এপিআই কী ও মডেল নির্বাচন দেখে আবার চেষ্টা করো।"
                )

            if (result.toolCalls.length() == 0) {
                // The model returned text with no tool call. That is not
                // automatically the end of the turn: a failure report, a bare
                // promise, or a silent stop right after the screen changed is
                // "one step short", and the engine pushes it to take the next step
                // itself. This is the real fix for "একটা কাজ করার পরে পরের ধাপে
                // যায় না" — a system-prompt rule alone cannot do it, because the
                // turn ends here unless the engine refuses to end it.
                val reply = result.text.toString()
                if (ContinuationPolicy.shouldContinue(reply, lastActionWasScreenChange, nudges)) {
                    nudges++
                    messages.put(JSONObject().put("role", "assistant").put("content", reply))
                    messages.put(JSONObject().put("role", "user")
                        .put("content", ContinuationPolicy.nudge(reply)))
                    continue
                }
                if (producedText) return@channelFlow
                throw IllegalStateException("মডেলটি খালি উত্তর দিয়েছে। আবার বলো।")
            }

            messages.put(JSONObject().put("role", "assistant").put("content", result.text.toString())
                .put("tool_calls", result.toolCalls))

            // Parse every call in this turn first, so independent ones can run at
            // the same time. Opening three apps, or reading two files, used to
            // happen strictly one after another — each waiting for the previous
            // result before it even started. That serial wait is a large part of
            // why a simple job felt slow.
            val parsed = (0 until result.toolCalls.length()).mapNotNull { i ->
                val call = result.toolCalls.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: return@mapNotNull null
                val name = fn.optString("name")
                if (name.isBlank()) return@mapNotNull null
                val rawArgs = fn.optString("arguments", "{}")
                val args = try { JSONObject(rawArgs) } catch (_: Exception) { JSONObject() }
                Triple(call.optString("id", "call_$round$i"), name, args)
            }

            // Cleared here, before this round's calls run, so the flag read by the
            // next round's "no tool call" check describes only this round.
            lastActionWasScreenChange = false
            // A call that needs the screen in front of it must run alone and in
            // order: two taps racing each other would land on the wrong screen.
            // A call that only reads or writes data can run beside its siblings.
            val sequential = parsed.filter { isScreenTool(it.second) }
            val parallel = parsed.filterNot { isScreenTool(it.second) }

            val outcomes = mutableMapOf<String, JSONObject>()

            if (parallel.isNotEmpty()) {
                coroutineScope {
                    val jobs = parallel.map { (id, name, args) ->
                        launch {
                            val outcome = onToolCall(name, args)
                            synchronized(outcomes) { outcomes[id] = outcome }
                        }
                    }
                    jobs.forEach { it.join() }
                }
            }
            for ((id, name, args) in sequential) {
                outcomes[id] = onToolCall(name, args)
            }

            for ((id, name, args) in parsed) {
                val outcome = outcomes[id] ?: continue
                // Show what the tool actually returned, so the user watches the
                // work happen instead of staring at a frozen cursor.
                val summary = outcome.optString("summary").take(200)
                trySend("\n\n⚙️ `$name` → ${summary.ifBlank { "সম্পন্ন" }}\n\n")

                // A tool that failed must not end the turn. The result carries the
                // real error back to the model, which is told in plain words that a
                // failure is a step, not a stop — try another way. Without this the
                // assistant reported "ফাইল লেখা যায়নি" and gave up, which is the
                // complaint that it cannot finish anything.
                val ok = outcome.optBoolean("ok", true)
                val content = if (ok) outcome.toString() else outcome.toString() +
                    "\n\nএই কলটি ব্যর্থ হয়েছে। এটা থামার কারণ নয়। ভিন্ন পথে আবার চেষ্টা করো — " +
                    "অন্য টুল, অন্য আর্গুমেন্ট, অন্য পদ্ধতি। সফল না হওয়া পর্যন্ত চালিয়ে যাও।"

                messages.put(JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", id)
                    .put("name", name)
                    .put("content", content))

                // A picture the tool just produced joins the conversation now, so
                // the very next round can look at it instead of waiting for the
                // user to speak again.
                val shots = mutableListOf<String>()
                outcome.optString("image_data_url").takeIf { it.isNotBlank() }?.let { shots.add(it) }
                outcome.optJSONArray("image_data_urls")?.let { arr ->
                    for (k in 0 until arr.length()) {
                        arr.optString(k).takeIf { it.isNotBlank() && it !in shots }?.let { shots.add(it) }
                    }
                }
                if (shots.isNotEmpty()) {
                    // Every sampled video frame is attached, so a clip is read as a
                    // sequence of moments rather than a single still.
                    val parts = JSONArray().apply {
                        put(JSONObject().put("type", "text")
                            .put("text", "এইমাত্র $name টুল থেকে পাওয়া ${shots.size}টি ছবি/ফ্রেম দেখো।"))
                        for (shot in shots.take(8)) {
                            put(JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", shot)))
                        }
                    }
                    messages.put(JSONObject().put("role", "user").put("content", parts))
                }
            }

            // A screen action just happened — a tap, a swipe, a key, an app that
            // was opened, a link followed, a song started on YouTube. The whole
            // point of driving another app is to see what happened next, and the
            // model cannot decide the next step from the action it already sent.
            // The fresh frame is captured here and handed to the very next round,
            // so YouTube's search results (and every other app's next screen) come
            // back into the conversation on their own. The model does not have to
            // remember to ask, which is what left it stuck one step short of
            // pressing play.
            if (parsed.any { isScreenTool(it.second) || it.second in SCREEN_CHANGING_TOOLS }) {
                lastActionWasScreenChange = true
                val frame = try { screenNow() } catch (_: Exception) { "" }
                val shot = try { screenFrame() } catch (_: Exception) { "" }
                if (frame.isNotBlank() || shot.isNotBlank()) {
                    val text = buildString {
                        append("📱 অ্যাকশনের পরে বর্তমান স্ক্রিন (নিজে থেকেই নেওয়া)। ")
                        append("এটা দেখে নিজেই ঠিক করো পরের ধাপ কী — ব্যবহারকারীকে জিজ্ঞাসা কোরো না।\n")
                        if (frame.isNotBlank()) append("\n$frame")
                    }
                    if (shot.isNotBlank()) {
                        val parts = JSONArray().apply {
                            put(JSONObject().put("type", "text").put("text", text))
                            put(JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", shot)))
                        }
                        messages.put(JSONObject().put("role", "user").put("content", parts))
                    } else {
                        messages.put(JSONObject().put("role", "user").put("content", text))
                    }
                }
            }
        }

        // Reaching here means the round budget ran out with tools still pending.
        // Saying so is the honest end state: the user can just say "চালিয়ে যাও"
        // and the conversation continues from exactly this point.
        trySend("\n\n⚠️ অনেক ধাপ কাজ করার পরও কাজ শেষ হয়নি। \"চালিয়ে যাও\" বললে ঠিক এখান থেকেই আবার শুরু করব।")
    }.flowOn(Dispatchers.IO)


    /**
     * True for the calls that must touch the screen alone, in order. Two taps or
     * two swipes racing each other land on the wrong element, so these never run
     * in parallel with anything.
     */
    private fun isScreenTool(name: String): Boolean = name in SCREEN_TOOLS

    /**
     * A plain string when there is no image, and the OpenAI content-parts array
     * when there is. Providers that ignore the array simply see the text.
     */
    private fun userContent(text: String, imageDataUrl: String): Any {
        if (imageDataUrl.isBlank()) return text
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", text))
            put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", imageDataUrl))
            )
        }
    }

    /**
     * One streamed request to the chosen model. Text deltas reach `onText` the
     * moment they arrive, so the phone can start speaking before the answer ends.
     */
    private fun callModelStreaming(
        model: AiModel,
        messages: JSONArray,
        tools: JSONArray,
        onText: (String) -> Unit
    ): StreamedTurn? {
        val sse = OpenAiSse(client)
        lastProviderError = null
        val spec = ProviderCatalog.find(model.provider)
            ?: return callModelStreamingLegacy(model, messages, tools, onText)
        val key = getApiKey(model.provider)
        // A key-less public provider is still worth a try; only a provider that
        // requires a key and has none is skipped, so the caller reports it.
        if (key.isBlank() && spec.needsKeyForCatalog) {
            lastProviderError = "${spec.label}: API কী বসানো হয়নি"
            return null
        }
        val body = JSONObject()
            .put("model", model.id)
            .put("messages", messages)
            .put("tools", tools)
            .put("tool_choice", "auto")
            .put("max_tokens", 8000)
            .put("stream", true)
        sse.stream(
            ProviderCatalog.urlFor(spec, key, forCatalog = false),
            key,
            body,
            headersFor(spec),
            onText
        )?.let { return it }
        lastProviderError = sse.lastError ?: "${spec.label}: সংযোগ ব্যর্থ"
        return null
    }

    /**
     * Routes a model whose provider is not in the catalogue (e.g. an old cached
     * id from a previous version). Falls back to the provider whose name the id
     * itself carries, so an unrecognised model never silently does nothing.
     */
    private fun callModelStreamingLegacy(
        model: AiModel,
        messages: JSONArray,
        tools: JSONArray,
        onText: (String) -> Unit
    ): StreamedTurn? {
        val sse = OpenAiSse(client)
        val fallbackId = when {
            model.provider == "opencode" -> "opencode"
            model.provider == "gemini" -> "gemini"
            else -> "zyraxon"
        }
        val spec = ProviderCatalog.find(fallbackId) ?: return null
        val key = getApiKey(fallbackId)
        val body = JSONObject()
            .put("model", model.id)
            .put("messages", messages)
            .put("tools", tools)
            .put("tool_choice", "auto")
            .put("max_tokens", 8000)
            .put("stream", true)
        sse.stream(
            ProviderCatalog.urlFor(spec, key, forCatalog = false),
            key,
            body,
            headersFor(spec),
            onText
        )?.let { return it }
        lastProviderError = sse.lastError ?: "${spec.label}: সংযোগ ব্যর্থ"
        return null
    }

    /**
     * Prefers the streaming path so text is heard as it arrives. Falls back to a
     * single blocking request only when the endpoint will not stream at all.
     */
    private fun callModelAny(
        model: AiModel,
        messages: JSONArray,
        tools: JSONArray,
        onText: (String) -> Unit
    ): StreamedTurn? {
        callModelStreaming(model, messages, tools, onText)?.let { if (it.text.isNotEmpty() || it.toolCalls.length() > 0) return it }
        val blocking = callModel(model, messages, tools) ?: return null
        if (blocking.text.isNotBlank()) onText(blocking.text)
        return StreamedTurn().also {
            it.appendText(blocking.text)
            it.mergeToolCalls(blocking.toolCalls)
        }
    }

    /**
     * Tries the chosen model, then quietly moves to the next provider that has a
     * key when it refuses (quota, rate limit, outage). The user asked for work
     * that never stops mid-task, so a single provider's limit must not end the
     * turn. The model actually used is reported through [onSwitch] so the phone
     * can say what happened instead of silently answering from elsewhere.
     */
    private fun callModelWithFailover(
        model: AiModel,
        messages: JSONArray,
        tools: JSONArray,
        onText: (String) -> Unit,
        onSwitch: (String) -> Unit
    ): StreamedTurn? {
        callModelAny(model, messages, tools, onText)?.let {
            if (it.text.isNotEmpty() || it.toolCalls.length() > 0) return it
        }
        val firstError = lastProviderError
        // A request the server rejected (bad model id, missing thought_signature)
        // is rejected the same way everywhere, so failover is skipped and the
        // real reason is reported at once instead of after minutes of retries.
        if (!ProviderCatalog.isRetryableError(firstError)) return null

        for (candidate in failoverCandidates(model)) {
            val attempt = callModelAny(candidate, messages, tools, onText)
            if (attempt != null && (attempt.text.isNotEmpty() || attempt.toolCalls.length() > 0)) {
                onSwitch("${model.provider} → ${candidate.provider} (${candidate.id})")
                return attempt
            }
        }
        // Nothing else could answer either, so the original reason is the honest
        // one to report — later candidates failed for their own, less relevant
        // reasons (usually just "no key").
        lastProviderError = firstError ?: lastProviderError
        return null
    }

    /**
     * The providers to try after the chosen one, fastest first. Only providers
     * that actually have a key are offered, and each contributes its own seed
     * model — never another provider's model id, which would 404 everywhere.
     */
    private fun failoverCandidates(primary: AiModel): List<AiModel> {
        val out = mutableListOf<AiModel>()
        for (spec in ProviderCatalog.specs.sortedBy { it.speedRank }) {
            if (spec.id == primary.provider) continue
            if (getApiKey(spec.id).isBlank()) continue
            val candidateId = spec.seedModels.firstOrNull() ?: continue
            out.add(
                AiModel(
                    id = candidateId,
                    name = candidateId,
                    category = spec.category,
                    description = spec.label,
                    provider = spec.id
                )
            )
        }
        return out
    }

    /** Test seam: the failover order is the part worth asserting without network. */
    internal fun failoverCandidatesForTest(primary: AiModel): List<AiModel> =
        failoverCandidates(primary)

    private class ModelResult(val text: String, val toolCalls: JSONArray)

    /**
     * One request to the chosen model. Returns null when nothing answered, so the
     * caller can report honestly instead of inventing a reply. Every provider goes
     * through the catalogue, so adding a provider cannot leave the blocking path
     * behind — that drift is exactly what made models appear not to load.
     */
    private fun callModel(model: AiModel, messages: JSONArray, tools: JSONArray): ModelResult? {
        val spec = ProviderCatalog.find(model.provider) ?: return callModelLegacy(model, messages, tools)
        val key = getApiKey(model.provider)
        if (key.isBlank() && spec.needsKeyForCatalog) return null
        return try {
            val builder = Request.Builder()
                .url(ProviderCatalog.urlFor(spec, key, forCatalog = false))
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            ProviderCatalog.applyAuth(builder, spec, key, forCatalog = false)
            for ((name, value) in headersFor(spec)) builder.addHeader(name, value)
            val body = JSONObject()
                .put("model", model.id)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("max_tokens", 8000)
            builder.post(body.toString().toRequestBody("application/json".toMediaType()))
            client.newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    lastProviderError = ProviderCatalog.parseError(raw, response.code)
                    return null
                }
                parseCompletion(raw)
            }
        } catch (e: Exception) {
            lastProviderError = e.message
            null
        }
    }

    /** Blocking fallback for a model whose provider is not in the catalogue. */
    private fun callModelLegacy(
        model: AiModel,
        messages: JSONArray,
        tools: JSONArray
    ): ModelResult? {
        val fallbackId = when {
            model.provider == "opencode" -> "opencode"
            model.provider == "gemini" -> "gemini"
            else -> "zyraxon"
        }
        return callModel(model.copy(provider = fallbackId), messages, tools)
    }

    private fun parseCompletion(body: String): ModelResult? {
        if (body.isBlank()) return null
        return try {
            val choices = JSONObject(body).optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val msg = choices.getJSONObject(0).optJSONObject("message") ?: return null
            ModelResult(
                text = msg.optString("content", ""),
                toolCalls = msg.optJSONArray("tool_calls") ?: JSONArray()
            )
        } catch (_: Exception) {
            null
        }
    }
}
