package ai.zyraxon.assistant.ai

import ai.zyraxon.assistant.mcp.McpManager
import ai.zyraxon.assistant.model.AiModel
import ai.zyraxon.assistant.model.AgentMode
import ai.zyraxon.assistant.model.ChatMessage
import ai.zyraxon.assistant.model.MessageRole
import ai.zyraxon.assistant.model.Persona
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
        // No hardcoded limits anywhere. A fixed 35-second read cap used to cut
        // off a model that was still thinking, and an 8-second connect cap gave
        // up on a slow network before the request was even sent. Both are zero
        // now, which means "wait for the socket's own result" — the OS still
        // fails a genuinely unreachable host on its own, so nothing hangs
        // forever, and no artificial timer decides when real work is over.
        .connectTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        // A warm pool: the next turn reuses the TLS connection the last one
        // opened instead of paying the handshake again (the first-token delay).
        .connectionPool(okhttp3.ConnectionPool(8, 30, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Opens (and keeps) the TLS connection to the chosen provider before the
     * user sends anything, so the first request of a turn does not wait for DNS
     * and the handshake. Fire-and-forget; failure is silent.
     */
    fun prewarm(model: AiModel) {
        // The cloud agent is warmed by opening its conversation, not by a TLS
        // handshake against a chat endpoint it does not use.
        if (model.provider == "zyraxon_apex") return
        Thread {
            try {
                val spec = ProviderCatalog.find(model.provider) ?: return@Thread
                val req = okhttp3.Request.Builder().url(spec.chatUrl).head().build()
                client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build().newCall(req).execute().close()
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    companion object {
        /**
         * Reasoning deltas travel through the same Flow as the answer, wrapped in
         * this marker so the UI can show them as "thinking" and never speak them.
         * The marker cannot occur in real prose, so a plain answer is unaffected.
         */
        const val REASONING_PREFIX = "\u0001zyraxon-reasoning\u0001"

        /** The id of the phone's cloud agent model, always first in the picker. */
        const val APEX_MODEL_ID = "zyraxon/apex"

        /** Lookups and launches that gain nothing from running twice with the same arguments. */
        private val REPEAT_GUARDED = setOf(
            "youtube_play", "web_search", "open_app", "launch_url", "read_url",
            "list_apps", "verify_url", "open_settings", "publish_website", "publish_cloudflare"
        )


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
     * Asks a thinking model to keep its thinking short, which is the single
     * biggest lever on time-to-first-token. Only providers whose endpoint really
     * accepts the parameter get it — an unknown field is a 400, not a hint — and
     * Google takes its own shape instead of `reasoning_effort` (sending both is
     * rejected). Thinking still happens and is still shown; only its length is
     * capped, so the answer starts sooner without the user losing the reasoning.
     */
    private fun applyLatencyTuning(body: JSONObject, spec: ProviderSpec) {
        if (spec.usesGeminiThinkingConfig) {
            body.put("extra_body", JSONObject().put("google", JSONObject().put(
                "thinking_config", JSONObject()
                    .put("thinking_level", "low")
                    .put("include_thoughts", true)
            )))
        } else if (spec.supportsReasoningEffort) {
            body.put("reasoning_effort", "low")
        }
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
            // The phone's cloud agent is always present. It has no catalogue to
            // fetch, so it is added after the network pass rather than raced with
            // it; this is what keeps it in the picker even offline.
            val withApex = ensureApex(result)
            if (withApex.isNotEmpty()) {
                cacheModels(withApex)
                withApex
            } else {
                ensureApex(readCachedModels())
            }
        }

    /**
     * Puts ZYRAXON Apex at the head of the list.
     *
     * The head position is deliberate: it is the default, and the default is the
     * strongest thing the phone can reach. It is also always "connected", because
     * the moment a key is saved the cloud answers — so unlike every other
     * provider there is no state in which it should disappear from the list.
     */
    private fun ensureApex(models: List<AiModel>): List<AiModel> {
        val apex = AiModel(
            id = APEX_MODEL_ID,
            name = "ZYRAXON Apex",
            category = "ZYRAXON Apex",
            description = "ক্লাউড এজেন্ট — ফোন ও ক্লাউড মিলিয়ে পূর্ণ শক্তি",
            provider = "zyraxon_apex",
            isFree = false
        )
        return listOf(apex) + models.filter { it.provider != "zyraxon_apex" }
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
        // A provider whose model list is public (OpenRouter's, for one) used to fill
        // the picker even though the user never connected it. Only providers that
        // are free to use without a key may appear without one.
        if (key.isBlank() && spec.id != "zyraxon" && spec.id != "opencode" && spec.auth != ProviderSpec.AuthStyle.NONE) return null
        // A provider with no catalogue URL has nothing to fetch: ZYRAXON Apex is
        // reached through the conversation API, not an OpenAI-compatible list, and
        // its models are supplied by the app rather than read from the network.
        if (spec.modelsUrl.isBlank()) return null
        return try {
            val builder = Request.Builder()
                .url(ProviderCatalog.urlFor(spec, key, forCatalog = true))
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            ProviderCatalog.applyAuth(builder, spec, key, forCatalog = true)
            for ((name, value) in headersFor(spec)) builder.addHeader(name, value)
            // Catalogue reads have no artificial cap either: a slow but working
            // provider must still fill the model list instead of being dropped.
            client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
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
                                "রিয়েলটাইম ভয়েস (সরাসরি কথা বলে)"
                            } else {
                                "লাইভ মডেল"
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
    private val providerErrorTl = ThreadLocal<String?>()

    // Several chats run at once, each on its own thread; a shared field would
    // let one chat's failure be reported as another's.
    var lastProviderError: String?
        get() = providerErrorTl.get()
        private set(v) { providerErrorTl.set(v) }

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

    private fun asset(path: String): String = try {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: Exception) { "" }

    /**
     * The operating contract of each mode, a plain .txt file of its own:
     * `prompt-chat.txt`, `prompt-build.txt`, `prompt-agent.txt`. Chat is a
     * conversation only, Build makes whole apps, Agent runs the whole device.
     */
    private val modeContracts: Map<AgentMode, String> by lazy {
        mapOf(
            AgentMode.CHAT to asset("knowledge/prompt-chat.txt"),
            AgentMode.BUILD to asset("knowledge/prompt-build.txt"),
            AgentMode.AGENT to asset("knowledge/prompt-agent.txt").ifBlank { asset("knowledge/zyraxon-system-prompt.md") }
        )
    }

    private fun contractFor(mode: AgentMode): String =
        modeContracts[mode].orEmpty().ifBlank { asset("knowledge/prompt-agent.txt") }

    /**
     * The live speech-to-speech section of the one contract file, taken from the
     * `LIVE VOICE` marker to the end. Both jobs live in a single file so no prompt
     * is ever injected from a second place.
     */
    val liveVoiceContract: String by lazy {
        asset("knowledge/prompt-live.txt").trim().ifBlank {
            val md = asset("knowledge/zyraxon-system-prompt.md")
            val marker = "<!-- ==================== LIVE VOICE ==================== -->"
            val at = md.indexOf(marker)
            if (at < 0) "" else md.substring(at + marker.length).trim()
        }
    }

    /**
     * The system prompt a live session starts with: the live contract, the real
     * device facts, and the active character. Deliberately shorter than the text
     * prompt — a live model has to answer in a second, not read a manual. The tool
     * list is not repeated here: the model already receives the tools themselves.
     */
    fun buildLiveVoicePrompt(persona: Persona): String = buildString {
        append(liveVoiceContract)
        append("\n\n---\n\n## Where you are running\n")
        append("- Android phone (APK), Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
            .append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
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
        mcpTools: List<ai.zyraxon.assistant.mcp.McpTool> = emptyList(),
        mode: AgentMode = AgentMode.AGENT
    ): String = buildString {
        append(contractFor(mode))
        if (mode == AgentMode.CHAT) {
            append("\n\n---\n\n## Platform\n- Android phone (APK), Android ${Build.VERSION.RELEASE}. Mode: CHAT (conversation only).\n")
            append("\n## Active character\n").append(persona.systemPrompt).append("\n")
            if (memoriesContext.isNotBlank()) {
                append("\n## The user's durable memory (local DB)\n").append(memoriesContext).append("\n")
            }
            append("\n## ভাষা\n- ইউজার যে ভাষায় কথা বলে, তুমি সেই ভাষায় উত্তর দাও।\n")
            return@buildString
        }
        append("\n\n---\n\n## Platform (the truth about where you are running)\n")
        append("- Mode: ").append(mode.name).append("\n")
        append("- Device: Android phone (APK), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("- This app is Android only. It ships as an APK and nothing else — never propose, plan or build a desktop (Windows EXE, Linux, AppImage, DMG) version of ZYRAXON itself.\n")
        append("- শেল/টার্মিনাল: তোমার কাছে আসল শেল আর টার্মিনাল আছে — কমান্ড দিয়েই যেকোনো কাজ করা যায়। ")
        append("কমান্ড-নির্ভর সবচেয়ে শক্তিশালী কাজগুলোর জন্য টার্মিনাল ব্যবহার করবে এবং কাজ শেষ না হওয়া পর্যন্ত থামবে না। ")
        append("কোথায় চালাচ্ছ, কী আছে, কীভাবে — এসব তুমি নিজেই টুল কল করে জেনে নেবে।\n")
        append("- Call tools through the function-calling interface, never as text.\n")
        append("- সময় ধরে কাজ: কমান্ড চালু করে সাথে সাথে সিদ্ধান্ত নিও না। ধীর কাজ (বিল্ড, ইনস্টল, সার্ভার, ডিপ্লয়) শুরু করার পর `wait` দিয়ে অপেক্ষা করো, ")
        append("আর যা নিজে নিজে সত্যি হবে (পোর্ট খোলা, ফাইল আসা, URL সাড়া দেওয়া) তা `wait` দিয়ে পোল করো — ")
        append("অনুমান করে আগেই \"হয়ে গেছে\" বলো না, আর বারবার চেক করতে লুপে ঘুরোও না।\n")
        append("- অনেক দীর্ঘ কাজ (বড় ডাউনলোড, ক্লোন, বিল্ড) `background_command` দিয়ে চালাও যাতে অ্যাপ বন্ধ হলেও চলে; ")
        append("শেষ হলে নোটিফিকেশন আসে — ততক্ষণ অন্য দরকারি কাজ করো বা `wait`-এ অপেক্ষা করো।\n")
        append("- পরে নিজে থেকে চালাতে হবে (\"১০ মিনিট পরে\", \"রাত ২টায়\") — এমন কাজ `schedule_command` দিয়ে সময় দিয়ে বুক করো; ")
        append("টাইমারটা অ্যান্ড্রয়েড ধরে রাখে, তাই অ্যাপ বন্ধ থাকলেও কাজ চলবে আর শেষে তোমাকে জানানো হবে।\n")
        append("- CI/CD: GitHub Actions আছে `github_actions` দিয়ে (list/dispatch/runs/status/logs) — ")
        append("dispatch করে `wait` দিয়ে status পোল করো, লাল হলে logs পড়ে কারণ নিজে বুঝে ঠিক করো।\n")
        append("- অ্যাপ নিজে বানাতে/ইনস্টল করতে `build_app` আছে — আসল Gradle বিল্ড চালিয়ে তৈরি APK ইনস্টলারের কাছে দেয়।\n")

        // MCP: the connected servers' tools are already injected as real tool
        // schemas this turn, so nothing about them is repeated here. The model
        // finds out what exists by calling the tools themselves.
        if (mcpTools.isNotEmpty()) {
            append("\n## MCP\n")
            append("- ").append(mcpTools.size).append("টি MCP টুল ইতিমধ্যে তোমার টুল-লিস্টে দেওয়া আছে; ")
            append("নাম ও বিস্তারিত দেখতে `mcp_list` কল করো।\n")
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
        // last instruction is the one a model follows. One line, as the user
        // asked: answer in whatever language the user speaks.
        append("\n## ভাষা\n")
        append("- ইউজার যে ভাষায় কথা বলে, তুমি সেই ভাষায় উত্তর দাও।\n")
    }

    /** True when the phone has a working internet connection right now. */
    /** True when the phone has a validated internet connection right now. */
    fun isOnline(): Boolean = try {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) {
        true
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
        imageDataUrls: List<String> = emptyList(),
        // Whether the running job may re-read the phone's screen between steps.
        // One picture per round, never two; a screen action's own frame counts
        // as that round's picture.
        alwaysSeeScreen: Boolean = true,
        onToolCall: suspend (name: String, args: JSONObject) -> JSONObject = { _, _ ->
            JSONObject().put("ok", false).put("summary", "টুল চালানোর অনুমতি নেই")
        },
        drainInjections: () -> List<String> = { emptyList() },
        openTodos: () -> List<String> = { emptyList() },
        drainScreenFrames: () -> List<String> = { emptyList() },
        screenNow: suspend () -> String = { "" },
        screenFrame: suspend () -> String = { "" },
        mode: AgentMode = AgentMode.AGENT,
        // (callIndex, toolName, argumentsSoFar) while a tool call is still being written.
        onToolArgs: ((Int, String, String) -> Unit)? = null
    ): Flow<String> = channelFlow {
        val mcpTools = if (mode == AgentMode.CHAT) emptyList() else mcp.allTools()
        val systemPrompt = buildComprehensiveSystemPrompt(persona, memoriesContext, screenContext, mcpTools, mode)
        // Every tool call passes through the mode gate, so a model that names a
        // tool it was not offered still cannot run it.
        val callTool: suspend (String, JSONObject) -> JSONObject = { n, a ->
            if (!ToolRegistry.allowed(mode, n)) {
                JSONObject().put("ok", false).put("error", "not-in-mode")
                    .put("summary", "'$n' এই মোডে (${mode.title}) বন্ধ। ইউজারকে মোড বদলাতে বলো।")
            } else onToolCall(n, a)
        }
        // The on-device model is the offline fallback: with no internet the turn is
        // answered by the best downloaded model, and it can still use the phone's
        // tools (see LocalAgent). While the network is up, the hosted providers
        // answer, because they are far stronger than any phone-sized model.
        val local = OnDeviceModel.get(context)
        val offline = !isOnline()
        if (offline && !local.isReady() && !_onDeviceDisabled) {
            val files = local.installedModels()
            val order = listOf("gemma-4-E4B", "gemma-4-E2B", "qwen3_4b", "Qwen3-0.6B")
            val pick = order.firstNotNullOfOrNull { key -> files.firstOrNull { it.name.contains(key, ignoreCase = true) } }
                ?: files.firstOrNull()
            if (pick != null) withContext(Dispatchers.IO) { local.load(pick.absolutePath) }
        }
        if (offline && local.isReady() && !_onDeviceDisabled && prompt.isNotBlank()) {
            trySend("📴 ইন্টারনেট নেই — ফোনের অফলাইন মডেলে চলছে।\n\n")
            val sys = LocalAgent.systemPrompt(persona.systemPrompt, memoriesContext, offline = true)
            val convo = StringBuilder()
            for (m in history.takeLast(6)) {
                if (m.role == MessageRole.SYSTEM) continue
                convo.append(if (m.role == MessageRole.USER) "User: " else "Assistant: ")
                    .append(m.text.take(500)).append("\n")
            }
            convo.append("User: ").append(prompt).append("\nAssistant:")
            var answered = false
            var round = 0
            while (round < LocalAgent.MAX_ROUNDS) {
                round++
                var out = ""
                var sent = 0
                val tail = convo.toString().let { if (it.length > 6000) it.substring(it.length - 6000) else it }
                local.generate(tail, sys).collect { d ->
                    out += d
                    // Text is streamed as it arrives, except from the first
                    // "<tool_call>" on, which is for the engine and not the user.
                    val idx = out.indexOf(LocalAgent.OPEN)
                    val safe = if (idx >= 0) idx else (out.length - LocalAgent.OPEN.length).coerceAtLeast(0)
                    if (safe > sent) { trySend(out.substring(sent, safe)); sent = safe }
                }
                val call = LocalAgent.parse(out)
                if (call == null) {
                    val idx = out.indexOf(LocalAgent.OPEN)
                    if (idx < 0 && out.length > sent) trySend(out.substring(sent))
                    if (out.isNotBlank()) answered = true
                    break
                }
                val (name, args) = call
                val outcome = callTool(name, args)
                val result = outcome.toString().let { if (it.length > 1200) it.take(1200) + "…" else it }
                convo.append(" ").append(out.trim()).append("\nTool result: ").append(result).append("\nAssistant:")
                answered = true
            }
            if (answered) return@channelFlow
            // The local model produced nothing: fall through so the user still
            // gets the hosted providers' real error message instead of silence.
        }
        // Built-in device tools first, then every tool a connected MCP server
        // advertised. They are offered on every turn, so the model reaches for
        // them without the user having to mention them.
        val tools = ToolRegistry.schemas(mode).apply {
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
            // so the model sees the phone rather than a description of it. Every
            // picture the user attached (gallery, camera, video frames) travels
            // with it too, so one turn can hold several images at once.
            val userImages = (listOf(imageDataUrl) + imageDataUrls)
                .filter { it.isNotBlank() }.distinct()
            put(JSONObject().put("role", "user").put("content", userContent(prompt, userImages)))
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
        // Whether this round already carries a picture of the phone. The
        // running-screen injection below adds one only when this is false, so a
        // round never sends two shots — a screen action's own frame is enough.
        var screenFrameInjected = false
        // What the judge is shown: what was done so far, and what the screen looks like.
        val actionLog = mutableListOf<String>()
        var lastScreenText = ""
        var stalls = 0
        val callCounts = mutableMapOf<String, Int>()
        // No round budget: the loop ends when the work is verified done, and the
        // user can always stop a turn from the chat. A cap here would end a job
        // mid-way for no reason the user could see.
        while (true) {
            round++
            // Each round starts with a clean slate: whether a frame is pushed in
            // below is decided fresh, so a screen action that adds one is not
            // mistaken for the previous round's.
            screenFrameInjected = false
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
            // While a job is actually running, the phone's screen is re-read so
            // the model keeps working from what is on it now rather than from
            // what it remembers a few steps back. Exactly one picture per round:
            // the ticker's newest frame leads (it was captured while the model
            // was still thinking about the last one), and a fresh capture is
            // taken only when the ticker produced nothing. The user's rule is
            // "one shot in the background while it runs" — two shots per round
            // was the bug this replaces.
            if (alwaysSeeScreen && mode == AgentMode.AGENT && !screenFrameInjected) {
                val liveFrames = try { drainScreenFrames() } catch (_: Exception) { emptyList() }
                val shot = liveFrames.lastOrNull()
                    ?: (try { screenFrame() } catch (_: Exception) { "" })
                if (shot.isNotBlank()) {
                    val parts = JSONArray().apply {
                        put(JSONObject().put("type", "text")
                            .put("text", "📱 এখনকার স্ক্রিন (চলমান কাজ — প্রতি ধাপে একটি ছবি)। " +
                                "এই ছবি দেখে ঠিক করো পরের ধাপ কী — ব্যবহারকারীকে জিজ্ঞাসা কোরো না।"))
                        put(JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", shot)))
                    }
                    messages.put(JSONObject().put("role", "user").put("content", parts))
                    screenFrameInjected = true
                }
            }
            // Every round adds screenshots and tool output. Left alone, a long job
            // (a website, an hour of YouTube/app driving) outgrows the model's
            // context and the provider rejects the request — the job then dies
            // mid-way, which looks exactly like "the AI stopped". Old pictures and
            // oversized old tool results are shrunk; the newest stay intact.
            compactHistory(messages)
            val callOnce = {
                callModelWithFailover(model, messages, tools, { delta ->
                    producedText = true
                    trySend(delta)
                }, onToolArgs = onToolArgs, onSwitch = { switched ->
                    // Say the switch out loud: answering from a different model than
                    // the one on screen would otherwise be invisible.
                    trySend("\n\n🔁 `$switched` — আগের প্রোভাইডার সীমায় পৌঁছেছে, তাই এখান থেকে চালিয়ে যাচ্ছি।\n\n")
                }, onReasoning = { reason ->
                    // Reasoning is shown on screen and never spoken, so it travels
                    // in its own envelope the UI can tell apart from the answer.
                    trySend(REASONING_PREFIX + reason)
                })
            }
            var attempt = callOnce()
            // A rate limit or a network blip in the middle of a long job must not
            // end it. Wait a little and ask again before giving up; a permanent
            // error (bad key, bad model id) is reported at once.
            var retries = 0
            while (attempt == null && retries < 3 && ProviderCatalog.isRetryableError(lastProviderError)) {
                retries++
                // Count down out loud, one second at a time, so the wait the user
                // sees is the wait that is actually happening. A single message
                // followed by a silent delay reads as "stuck", not as "retrying".
                trySend("\n\n⏳ Connection problem — retry $retries/3 in ")
                for (s in retries downTo 1) {
                    trySend("$s… ")
                    kotlinx.coroutines.delay(500L)
                }
                trySend("\n\n")
                attempt = callOnce()
            }
            val result = attempt
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
                stalls++
                // When the model stops talking after having acted, the engine asks
                // a judge one question: is what the user asked for actually
                // finished? The judge sees the original goal, the tool results so
                // far and the screen — not just the model's own words — so
                // "ইউটিউব খুলেছি" is correctly read as "not done yet", and a
                // finished job is correctly left alone. If the judge cannot answer
                // (network), the keyword policy is the fallback. The judge is a
                // second, blocking round-trip, so it is only asked when there is
                // real evidence to weigh: some tool ran or the screen changed. A
                // plain conversational reply needs no verification, and asking
                // anyway was doubling the wait for a one-line answer.
                val verdict = if (actionLog.isEmpty() && !lastActionWasScreenChange) null
                    else verifyGoal(model, prompt, actionLog, lastScreenText, reply)
                val mustContinue = if (verdict != null) {
                    !verdict.done && !verdict.blocked
                } else {
                    ContinuationPolicy.shouldContinue(reply, lastActionWasScreenChange, nudges)
                }
                if (mustContinue) {
                    nudges++
                    messages.put(JSONObject().put("role", "assistant").put("content", reply))
                    val push = if (verdict != null) {
                        "⚠️ [যাচাই] কাজ এখনো শেষ হয়নি।" +
                            (if (verdict.reason.isNotBlank()) " কারণ: ${verdict.reason}" else "") +
                            (if (verdict.next.isNotBlank()) "\nপরের ধাপ: ${verdict.next}" else "") +
                            "\nব্যবহারকারীর মূল লক্ষ্য: «${prompt.take(400)}»" +
                            "\nকথা না বাড়িয়ে এখনই পরের টুল কল করো। লক্ষ্য পূরণ না হওয়া পর্যন্ত থামবে না।" +
                            escalation(stalls)
                    } else {
                        ContinuationPolicy.nudge(reply, prompt)
                    }
                    messages.put(JSONObject().put("role", "user").put("content", push))
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
            // Real tool calls are progress, so the push budget starts over: only
            // consecutive stalls without any action use it up.
            nudges = 0
            stalls = 0
            // A call that needs the screen in front of it must run alone and in
            // order: two taps racing each other would land on the wrong screen.
            // A call that only reads or writes data can run beside its siblings.
            // The same lookup, search or launch asked for again with identical
            // arguments is the loop behind "it opens YouTube trending music five
            // times and never plays anything". From the second repeat the call is
            // not run again; the model is told to change the plan instead.
            val outcomes = mutableMapOf<String, JSONObject>()
            val fresh = parsed.filter { (id, name, args) ->
                if (name !in REPEAT_GUARDED) return@filter true
                val sig = name + args.toString()
                val seen = callCounts.merge(sig, 1, Int::plus) ?: 1
                if (seen >= 3) {
                    outcomes[id] = JSONObject().put("ok", false).put("summary", "একই কল বারবার")
                        .put("error", "এই একই '$name' কল আগেই $seen বার হয়েছে, ফল একই। আবার করো না। " +
                            "ভিন্ন আর্গুমেন্ট বা ভিন্ন টুল নাও: স্ক্রিন পড়ো (read_screen), তারপর স্ক্রিনের আসল " +
                            "উপাদানে tap_text করো। ভিডিও চালাতে হলে প্রথম আসল ভিডিওতে ট্যাপ করো এবং চলছে কি না দেখো।")
                    false
                } else true
            }
            val sequential = fresh.filter { isScreenTool(it.second) }
            val parallel = fresh.filterNot { isScreenTool(it.second) }

            if (parallel.isNotEmpty()) {
                coroutineScope {
                    val jobs = parallel.map { (id, name, args) ->
                        launch {
                            val outcome = callTool(name, args)
                            synchronized(outcomes) { outcomes[id] = outcome }
                        }
                    }
                    jobs.forEach { it.join() }
                }
            }
            for ((id, name, args) in sequential) {
                outcomes[id] = callTool(name, args)
            }

            for ((id, name, args) in parsed) {
                val outcome = outcomes[id] ?: continue
                // The chat shows a live row for this call while it runs and its
                // result when it ends, so nothing is echoed into the answer text —
                // a "⚙️ tool → …" line here would only be noise beside it.
                actionLog.add("$name → ${outcome.optString("summary").take(200).ifBlank { "সম্পন্ন" }}" +
                    (if (outcome.optBoolean("ok", true)) "" else " [ব্যর্থ]"))
                if (actionLog.size > 25) actionLog.removeAt(0)

                // A tool that failed must not end the turn. The result carries the
                // real error back to the model, which is told in plain words that a
                // failure is a step, not a stop — try another way. Without this the
                // assistant reported "ফাইল লেখা যায়নি" and gave up, which is the
                // complaint that it cannot finish anything.
                val ok = outcome.optBoolean("ok", true)
                // The pictures travel as image parts just below; leaving the base64
                // inside the tool text as well doubled (or worse) every request.
                val lean = try {
                    JSONObject(outcome.toString()).apply { remove("image_data_url"); remove("image_data_urls") }.toString()
                } catch (_: Exception) { outcome.toString() }
                val content = if (ok) lean else lean +
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
            val screenActions = parsed.filter { isScreenTool(it.second) || it.second in SCREEN_CHANGING_TOOLS }
            if (screenActions.isNotEmpty()) {
                lastActionWasScreenChange = true
                val done = screenActions.joinToString(" → ") { it.second }
                val frame = try { screenNow() } catch (_: Exception) { "" }
                val shot = try { screenFrame() } catch (_: Exception) { "" }
                if (frame.isNotBlank()) lastScreenText = frame.take(1500)
                if (frame.isNotBlank() || shot.isNotBlank()) {
                    val text = buildString {
                        append("📱 এই কাজগুলো এইমাত্র স্বয়ংক্রিয়ভাবে হয়ে গেছে: $done\n")
                        append("এগুলো তোমার নিজের হাতেই হয়েছে — তাই \"করব\" বলে আর জিজ্ঞাসা কোরো না, হয়ে গেছে। ")
                        append("এখন স্ক্রিনটা দেখে নিজেই পরের ধাপ নাও, আর এক লাইনে বলো কাজটা হয়ে গেছে ")
                        append("(যেমন \"ইউটিউব খুলে দিয়েছি\")। ব্যবহারকারী যেন বুঝতে পারে তুমি নিজেই করেছ।\n")
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

    }.flowOn(Dispatchers.IO)


    /**
     * The longer the model goes without acting, the more firmly it is told to
     * change approach. There is no "I'm stuck" exit: another tool, another app,
     * the terminal, a different route to the same goal.
     */
    private fun escalation(stalls: Int): String = when {
        stalls < 2 -> ""
        stalls < 4 -> "\n(এটা ${stalls}তম বার থেমেছ — আগের পথ কাজ করছে না, অন্য টুল বা অন্য আর্গুমেন্ট নাও।)"
        else -> "\n(এটা ${stalls}তম বার থেমেছ। সম্পূর্ণ ভিন্ন পথ নাও: read_screen/screen_shot দিয়ে আসল অবস্থা দেখো, " +
            "tap_text/tap/swipe/type_text দিয়ে নিজে করো, দরকারে terminal_run/run_shell, web_search, অন্য অ্যাপ বা MCP টুল ব্যবহার করো। " +
            "OTP লাগলে read_sms বা নোটিফিকেশন শেড (press_key notifications + read_screen) থেকে নিজে পড়ো। " +
            "অনুমতি লাগলে open_settings দিয়ে নিজে চালু করো।)"
    }

    /** The judge's answer to "is the user's goal actually finished?". */
    private class Verdict(
        val done: Boolean,
        val blocked: Boolean,
        val reason: String,
        val next: String
    )

    /**
     * The model used to judge whether a job is done. Verification is a tiny,
     * latency-critical question, so the fastest keyed provider is preferred over
     * the user's (possibly large, slow) selected model. Falls back to the selected
     * model when no fast provider has a key, so the judge always has something.
     */
    private fun judgeModel(preferred: AiModel): AiModel {
        val fast = listOf("groq", "gemini")
        for (id in fast) {
            val spec = ProviderCatalog.find(id) ?: continue
            if (getApiKey(id).isBlank()) continue
            val candidateId = spec.seedModels.firstOrNull() ?: continue
            return AiModel(
                id = candidateId,
                name = candidateId,
                category = spec.category,
                description = spec.label,
                provider = spec.id
            )
        }
        return preferred
    }

    /**
     * Asks the model, in a fresh and tiny conversation, whether the goal is met.
     *
     * The judge is given the goal, the last tool results and the current screen —
     * evidence, not the assistant's own claim. Returns null when it cannot answer
     * (no network, unparseable), and the caller falls back to the keyword policy.
     */
    private fun verifyGoal(
        model: AiModel,
        goal: String,
        actions: List<String>,
        screen: String,
        reply: String
    ): Verdict? {
        if (goal.isBlank()) return null
        return try {
            val system = "তুমি একজন কঠোর কাজ-যাচাইকারী। তোমার একটাই কাজ: ব্যবহারকারী যা চেয়েছিল তা " +
                "সত্যিই শেষ হয়েছে কিনা বলা। কোনো টুল কল করবে না, শুধু একটি JSON অবজেক্ট দেবে:\n" +
                "{\"done\": true|false, \"blocked_on_user\": true|false, \"reason\": \"...\", \"next_step\": \"...\"}\n" +
                "নিয়ম:\n" +
                "- done=true কেবল তখন, যখন টুলের ফলাফল বা স্ক্রিন দেখায় যে মূল লক্ষ্য পূরণ হয়েছে " +
                "(যেমন ভিডিও সত্যিই চলছে, ফাইল/সাইট সত্যিই তৈরি হয়েছে)। সহকারীর নিজের দাবিই যথেষ্ট প্রমাণ নয়।\n" +
                "- একটা মাঝের ধাপ (অ্যাপ খোলা, সার্চ রেজাল্ট এসেছে, ফাইলের একটা অংশ লেখা), ব্যর্থতার খবর, " +
                "বা শুধু প্রতিশ্রুতি (\"করছি\") মানে done=false।\n" +
                "- যে প্রশ্ন বা আলাপে কোনো কাজ করার দরকার নেই, সহকারীর উত্তর সেটার জবাব দিলে done=true।\n" +
                "- blocked_on_user=true প্রায় কখনোই নয়। সহকারী নিজেই সব করে: ওটিপি SMS বা নোটিফিকেশন থেকে পড়ে, " +
                "সেভ করা লগইন/অটোফিল ব্যবহার করে, চেকবক্স ট্যাপ করে, সেটিংস ঘুরে অনুমতি নিজে চালু করে। " +
                "true কেবল তখন, যখন কাজের জন্য এমন একটা তথ্য লাগে যা ফোনে কোথাও নেই এবং ব্যবহারকারী কখনো দেয়নি " +
                "(যেমন কোনো অ্যাকাউন্টের পাসওয়ার্ড)। তার আগে সব বিকল্প চেষ্টা না হলে false।\n" +
                "- done=false হলে next_step এ এক লাইনে বলো পরে কোন টুল/কাজ করতে হবে।"
            val user = buildString {
                append("ব্যবহারকারীর লক্ষ্য:\n").append(goal.take(1500)).append("\n\n")
                append("এ পর্যন্ত করা কাজ (শেষ কয়েকটি):\n")
                if (actions.isEmpty()) append("(কোনো টুল চালানো হয়নি)\n")
                else actions.takeLast(15).forEach { append("- ").append(it.take(300)).append('\n') }
                if (screen.isNotBlank()) append("\nএখনকার স্ক্রিন:\n").append(screen.take(1200)).append('\n')
                append("\nসহকারীর শেষ উত্তর:\n").append(reply.take(1200))
            }
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))
            // The judge is asked one question and answers with a small JSON
            // object, so it is given no tools. Sending all seventy-two tool
            // schemas here — which the old call did — added tens of thousands of
            // tokens to every judge request for nothing, and that was pure latency
            // on every turn. The judge only decides, it never acts.
            val turn = callModelWithFailover(judgeModel(model), msgs, JSONArray(), { }, { }) ?: return null
            if (turn.toolCalls.length() > 0) return null
            val raw = turn.text.toString()
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o = JSONObject(raw.substring(start, end + 1))
            if (!o.has("done")) return null
            Verdict(
                done = o.optBoolean("done", false),
                blocked = o.optBoolean("blocked_on_user", false),
                reason = o.optString("reason"),
                next = o.optString("next_step")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * True for the calls that must touch the screen alone, in order. Two taps or
     * two swipes racing each other land on the wrong element, so these never run
     * in parallel with anything.
     */
    private fun isScreenTool(name: String): Boolean = name in SCREEN_TOOLS

    /**
     * Keeps a long agentic run inside the model's context window.
     *
     * Only the newest [keepImageMessages] messages that carry pictures keep them;
     * older ones are rewritten to their text plus a short marker. Tool results
     * older than the last [keepRecent] messages are cut to [maxToolChars]. The
     * system prompt and every assistant/user sentence are left untouched, so the
     * model still knows the goal and what it already did — it just stops
     * re-reading dozens of old screenshots on every round.
     */
    private fun compactHistory(
        messages: JSONArray,
        keepImageMessages: Int = 2,
        keepRecent: Int = 8,
        maxToolChars: Int = 3000
    ) {
        var imageMessagesSeen = 0
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.optJSONObject(i) ?: continue
            val content = m.opt("content")
            if (content is JSONArray) {
                var hasImage = false
                for (k in 0 until content.length()) {
                    if (content.optJSONObject(k)?.optString("type") == "image_url") { hasImage = true; break }
                }
                if (!hasImage) continue
                imageMessagesSeen++
                if (imageMessagesSeen <= keepImageMessages) continue
                val text = StringBuilder()
                for (k in 0 until content.length()) {
                    val part = content.optJSONObject(k) ?: continue
                    if (part.optString("type") == "text") text.append(part.optString("text")).append('\n')
                }
                m.put("content", text.toString().trim() + "\n[পুরনো স্ক্রিনশট সরানো হয়েছে]")
            } else if (m.optString("role") == "tool" && i < messages.length() - keepRecent) {
                val s = content as? String ?: continue
                if (s.length > maxToolChars) {
                    m.put("content", s.take(maxToolChars) + "\n…[পুরনো আউটপুট ছোট করা হয়েছে]")
                }
            }
        }
    }

    /**
     * A plain string when there is no image, and the OpenAI content-parts array
     * when there is. Providers that ignore the array simply see the text. Every
     * picture in [images] becomes its own part, so a turn can carry a photo plus
     * several video frames at once.
     */
    private fun userContent(text: String, images: List<String>): Any {
        val pics = images.filter { it.isNotBlank() }
        if (pics.isEmpty()) return text
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", text))
            for (url in pics) {
                put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", url))
                )
            }
        }
    }

    private fun userContent(text: String, imageDataUrl: String): Any =
        userContent(text, if (imageDataUrl.isBlank()) emptyList() else listOf(imageDataUrl))

    /**
     * One streamed request to the chosen model. Text deltas reach `onText` the
     * moment they arrive, so the phone can start speaking before the answer ends.
     */
    private fun callModelStreaming(
        model: AiModel,
        messages: JSONArray,
        tools: JSONArray,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onToolArgs: ((Int, String, String) -> Unit)? = null
    ): StreamedTurn? {
        val sse = OpenAiSse(client)
        lastProviderError = null
        val spec = ProviderCatalog.find(model.provider)
            ?: return callModelStreamingLegacy(model, messages, tools, onText, onReasoning, onToolArgs)
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
        applyLatencyTuning(body, spec)
        sse.stream(
            ProviderCatalog.urlFor(spec, key, forCatalog = false),
            key,
            body,
            headersFor(spec),
            onText,
            onReasoning,
            onToolArgs
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
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onToolArgs: ((Int, String, String) -> Unit)? = null
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
        applyLatencyTuning(body, spec)
        sse.stream(
            ProviderCatalog.urlFor(spec, key, forCatalog = false),
            key,
            body,
            headersFor(spec),
            onText,
            onReasoning,
            onToolArgs
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
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onToolArgs: ((Int, String, String) -> Unit)? = null
    ): StreamedTurn? {
        callModelStreaming(model, messages, tools, onText, onReasoning, onToolArgs)?.let {
            if (it.text.isNotEmpty() || it.toolCalls.length() > 0) return it
        }
        val blocking = callModel(model, messages, tools) ?: return null
        if (blocking.text.isNotBlank()) onText(blocking.text)
        return StreamedTurn().also {
            it.appendText(blocking.text)
            it.mergeToolCalls(blocking.toolCalls)
            it.finalizeArgs()
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
        onSwitch: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onToolArgs: ((Int, String, String) -> Unit)? = null
    ): StreamedTurn? {
        callModelAny(model, messages, tools, onText, onReasoning, onToolArgs)?.let {
            if (it.text.isNotEmpty() || it.toolCalls.length() > 0) return it
        }
        val firstError = lastProviderError
        // A request the server rejected (bad model id, missing thought_signature)
        // is rejected the same way everywhere, so failover is skipped and the
        // real reason is reported at once instead of after minutes of retries.
        if (!ProviderCatalog.isRetryableError(firstError)) return null

        for (candidate in failoverCandidates(model)) {
            val attempt = callModelAny(candidate, messages, tools, onText, onReasoning, onToolArgs)
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
            applyLatencyTuning(body, spec)
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
