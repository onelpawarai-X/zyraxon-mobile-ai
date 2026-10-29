package ai.zyraxon.assistant.ai

import ai.zyraxon.assistant.mcp.McpManager
import ai.zyraxon.assistant.model.AiModel
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
     */
    val mcp: McpManager = McpManager(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
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

        /** Shared endpoints so the streaming and non-streaming paths cannot drift apart. */
        private const val OPENCODE_URL = "https://opencode.ai/zen/v1/chat/completions"
        private const val ZYRAXON_URL = "https://zyraxonai.lovable.app/api/public/zyraxon/v1/chat/completions"
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"

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
            try {
                val env = System.getenv("GEMINI_API_KEY")
                if (!env.isNullOrBlank()) return env.trim()
            } catch (_: Exception) {}
            try {
                val clazz = Class.forName("com.example.BuildConfig")
                val field = clazz.getField("GEMINI_API_KEY")
                val key = field.get(null) as? String
                if (!key.isNullOrBlank()) return key.trim()
            } catch (_: Exception) {}
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
     * Dynamically fetches live models from official OpenCode endpoints.
     */
    suspend fun fetchLiveModels(): List<AiModel> = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://opencode.ai/zen/v1/models",
            "https://opencode.ai/inference/openai/v1/models"
        )
        // The user's own provider. Its catalogue is public even while the model
        // line is gated, so it is listed here and used the moment it opens.
        val zyraxonUrl = "https://zyraxonai.lovable.app/api/public/zyraxon/v1/models"
        val ocKey = getApiKey("opencode")

        val resultList = mutableListOf<AiModel>()
        val seenIds = mutableSetOf<String>()

        for (ep in endpoints) {
            try {
                val reqBuilder = Request.Builder()
                    .url(ep)
                    .addHeader("Accept", "application/json")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .addHeader("x-opencode-client", "cli")

                if (ocKey.isNotEmpty()) {
                    reqBuilder.addHeader("Authorization", "Bearer $ocKey")
                    reqBuilder.addHeader("x-api-key", ocKey)
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val dataArray = json.optJSONArray("data")
                            ?: json.optJSONArray("models")

                        if (dataArray != null && dataArray.length() > 0) {
                            for (i in 0 until dataArray.length()) {
                                val item = dataArray.optJSONObject(i) ?: continue
                                val rawId = item.optString("id", "").trim()
                                if (rawId.isBlank() || rawId.equals("test", ignoreCase = true)) continue

                                if (seenIds.add(rawId)) {
                                    val isFree = rawId.endsWith("-free") ||
                                            item.optBoolean("free", false) ||
                                            rawId.contains("free") ||
                                            rawId == "big-pickle"

                                    val cleanName = formatModelName(rawId)
                                    val desc = if (isFree) {
                                        "ওপেন কোড সার্ভার সাইট থেকে সরাসরি লাইভ — ফ্রি কোটা"
                                    } else {
                                        "ওপেন কোড সার্ভার সাইট থেকে লাইভ মডেল (${item.optString("owned_by", "opencode")})"
                                    }

                                    resultList.add(
                                        AiModel(
                                            id = rawId,
                                            name = cleanName,
                                            category = "OpenCode",
                                            description = desc,
                                            provider = "opencode",
                                            isFree = isFree
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            if (resultList.isNotEmpty()) break
        }

        // The user's own ZYRAXON line. Fastest models are listed first so the
        // selector opens on a model that answers quickly.
        try {
            val req = Request.Builder()
                .url(zyraxonUrl)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val root = JSONObject(response.body?.string() ?: "{}")
                    val arr = root.optJSONArray("data")
                    // The gate says whether the line answers yet. While it is shut,
                    // a ZYRAXON model would only return the gate notice, so those
                    // models go last and a working provider keeps the default slot.
                    val gateOpen = root.optJSONObject("gate")?.optBoolean("open", false) ?: false
                    if (arr != null) {
                        val zyraxonModels = mutableListOf<AiModel>()
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val rawId = item.optString("id", "").trim()
                            if (rawId.isBlank() || !seenIds.add(rawId)) continue
                            val display = item.optString("display_name").ifBlank {
                                item.optString("name").ifBlank { formatModelName(rawId) }
                            }
                            val desc = item.optString("description").ifBlank { "ZYRAXON মডেল" }
                            zyraxonModels.add(
                                AiModel(
                                    id = rawId,
                                    name = display,
                                    category = "ZYRAXON",
                                    description = desc,
                                    provider = "zyraxon",
                                    isFree = true
                                )
                            )
                        }
                        val ranked = zyraxonModels.sortedBy { zyraxonSpeedRank(it.id) }
                        if (gateOpen) resultList.addAll(0, ranked) else resultList.addAll(ranked)
                    }
                }
            }
        } catch (_: Exception) {}

        // Cache the real, live models so a later offline launch still has a list.
        // The cache only ever holds models the server actually returned — nothing
        // is hardcoded here.
        if (resultList.isNotEmpty()) {
            val arr = JSONArray()
            resultList.forEach { m ->
                val obj = JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("cat", m.category)
                    .put("desc", m.description)
                    .put("prov", m.provider)
                    .put("free", m.isFree)
                arr.put(obj)
            }
            prefs.edit().putString("cached_models", arr.toString()).apply()
            return@withContext resultList
        }

        // Offline: replay the last real catalogue the server gave us. If we have
        // never reached the server there is no model list to invent, so return
        // empty and let the UI say so — an invented model would be a lie.
        val cachedStr = prefs.getString("cached_models", null)
        if (!cachedStr.isNullOrBlank()) {
            try {
                val arr = JSONArray(cachedStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    resultList.add(
                        AiModel(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            category = obj.getString("cat"),
                            description = obj.getString("desc"),
                            provider = obj.getString("prov"),
                            isFree = obj.getBoolean("free")
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        resultList
    }

    /**
     * Lower is faster. Derived from each model's published latency profile, so
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

    /** The bundled operating contract. One source of truth, shared with the web layer. */
    private val bundledContract: String by lazy {
        try {
            context.assets.open("knowledge/zyraxon-system-prompt.md")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) { "" }
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
        append("- Shell: `sh -c` inside the app sandbox. `run_shell` runs there and returns real stdout/stderr.\n")
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

        if (screenContext.isNotBlank()) {
            append("\n## The screen right now (automatic — do not tool-call to find out where the user is)\n")
            append(screenContext).append("\n")
        }

        append("\n## Active character\n").append(persona.systemPrompt).append("\n")

        if (memoriesContext.isNotBlank()) {
            append("\n## The user's durable memory (local DB)\n")
            append(memoriesContext).append("\n")
        }
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
        drainInjections: () -> List<String> = { emptyList() }
    ): Flow<String> = channelFlow {
        val mcpTools = mcp.allTools()
        val systemPrompt = buildComprehensiveSystemPrompt(persona, memoriesContext, screenContext, mcpTools)
        // Built-in device tools first, then every tool a connected MCP server
        // advertised. They are offered on every turn, so the model reaches for
        // them without the user having to mention them.
        val tools = ToolRegistry.schemas().apply {
            for (tool in mcpTools) put(tool.toOpenAiSchema())
        }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            for (msg in history.takeLast(12)) {
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
        while (round < MAX_TOOL_ROUNDS) {
            round++
            // A message the user spoke or typed mid-task joins the conversation
            // here, so the running job keeps going instead of being thrown away.
            for (late in drainInjections()) {
                messages.put(JSONObject().put("role", "user").put("content", late))
            }
            val result = callModelAny(model, messages, tools) { delta ->
                producedText = true
                trySend(delta)
            }
                ?: throw IllegalStateException(
                    lastProviderError
                        ?: "কোনো মডেল উত্তর দিতে পারেনি। ইন্টারনেট, এপিআই কী ও মডেল নির্বাচন দেখে আবার চেষ্টা করো।"
                )

            if (result.toolCalls.length() == 0) {
                if (producedText) return@channelFlow
                throw IllegalStateException("মডেলটি খালি উত্তর দিয়েছে। আবার বলো।")
            }

            messages.put(JSONObject().put("role", "assistant").put("content", result.text.toString())
                .put("tool_calls", result.toolCalls))

            for (i in 0 until result.toolCalls.length()) {
                val call = result.toolCalls.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val rawArgs = fn.optString("arguments", "{}")
                val args = try { JSONObject(rawArgs) } catch (_: Exception) { JSONObject() }

                val outcome = onToolCall(name, args)
                // Show what the tool actually returned, so the user watches the
                // work happen instead of staring at a frozen cursor.
                val summary = outcome.optString("summary").take(200)
                trySend("\n\n⚙️ `$name` → ${summary.ifBlank { "সম্পন্ন" }}\n\n")

                messages.put(JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", call.optString("id", "call_$round$i"))
                    .put("name", name)
                    .put("content", outcome.toString()))

                // A picture the tool just produced joins the conversation now, so
                // the very next round can look at it instead of waiting for the
                // user to speak again.
                outcome.optString("image_data_url").takeIf { it.isNotBlank() }?.let { shot ->
                    messages.put(JSONObject().put("role", "user")
                        .put("content", userContent("এইমাত্র $name টুল থেকে পাওয়া ছবিটি দেখো।", shot)))
                }
            }
        }

        // Reaching here means the round budget ran out with tools still pending.
        // Saying so is the honest end state: the user can just say "চালিয়ে যাও"
        // and the conversation continues from exactly this point.
        trySend("\n\n⚠️ অনেক ধাপ কাজ করার পরও কাজ শেষ হয়নি। \"চালিয়ে যাও\" বললে ঠিক এখান থেকেই আবার শুরু করব।")
    }.flowOn(Dispatchers.IO)


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
        if (model.provider == "zyraxon") {
            val body = JSONObject()
                .put("model", model.id)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("max_tokens", 8000)
                .put("stream", true)
            sse.stream(ZYRAXON_URL, getApiKey("zyraxon"), body, emptyMap(), onText)?.let { return it }
            lastProviderError = sse.lastError
        }
        if (model.provider == "opencode") {
            val body = JSONObject()
                .put("model", model.id)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("max_tokens", 8000)
                .put("stream", true)
            val headers = mapOf(
                "x-opencode-client" to "cli",
                "x-opencode-session" to generateOpenCodeSession()
            )
            sse.stream(OPENCODE_URL, getApiKey("opencode"), body, headers, onText)?.let { return it }
            lastProviderError = sse.lastError
        }
        val key = getApiKey("gemini")
        if (model.provider == "gemini" || key.isNotEmpty()) {
            if (key.isEmpty()) return null
            val body = JSONObject()
                .put("model", model.id.removePrefix("models/"))
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("max_tokens", 8000)
                .put("stream", true)
            sse.stream(GEMINI_URL, key, body, emptyMap(), onText)?.let { return it }
            lastProviderError = sse.lastError
        }
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

    private class ModelResult(val text: String, val toolCalls: JSONArray)

    /**
     * One request to the chosen model. Returns null when nothing answered, so the
     * caller can report honestly instead of inventing a reply.
     */
    private fun callModel(model: AiModel, messages: JSONArray, tools: JSONArray): ModelResult? {
        if (model.provider == "zyraxon") {
            zyraxonRequest(model, messages, tools)?.let { return it }
        }
        if (model.provider == "opencode") {
            openCodeRequest(model, messages, tools)?.let { return it }
        }
        if (model.provider == "gemini" || getApiKey("gemini").isNotEmpty()) {
            geminiRequest(model, messages, tools)?.let { return it }
        }
        return null
    }

    private fun zyraxonRequest(model: AiModel, messages: JSONArray, tools: JSONArray): ModelResult? {
        return try {
            val key = getApiKey("zyraxon")
            val reqObj = JSONObject()
                .put("model", model.id)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("max_tokens", 8000)
            val b = Request.Builder()
                .url(ZYRAXON_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .post(reqObj.toString().toRequestBody("application/json".toMediaType()))
            if (key.isNotEmpty()) b.addHeader("Authorization", "Bearer $key")
            client.newCall(b.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                parseCompletion(response.body?.string() ?: "")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openCodeRequest(model: AiModel, messages: JSONArray, tools: JSONArray): ModelResult? {
        return try {
            val ocKey = getApiKey("opencode")
            val reqObj = JSONObject()
                .put("model", model.id)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("max_tokens", 8000)

            val b = Request.Builder()
                .url(OPENCODE_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .addHeader("x-opencode-client", "cli")
                .addHeader("x-opencode-session", generateOpenCodeSession())
                .post(reqObj.toString().toRequestBody("application/json".toMediaType()))
            if (ocKey.isNotEmpty()) {
                b.addHeader("Authorization", "Bearer $ocKey")
                b.addHeader("x-api-key", ocKey)
            }
            client.newCall(b.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                parseCompletion(response.body?.string() ?: "")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun geminiRequest(model: AiModel, messages: JSONArray, tools: JSONArray): ModelResult? {
        return try {
            val key = getApiKey("gemini")
            if (key.isEmpty()) return null
            val gModel = model.id.removePrefix("models/")
            val reqObj = JSONObject()
                .put("model", gModel)
                .put("messages", messages)
                .put("tools", tools)
                .put("tool_choice", "auto")
                .put("max_tokens", 8000)
            val b = Request.Builder()
                .url(GEMINI_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $key")
                .post(reqObj.toString().toRequestBody("application/json".toMediaType()))
            client.newCall(b.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                parseCompletion(response.body?.string() ?: "")
            }
        } catch (_: Exception) {
            null
        }
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
