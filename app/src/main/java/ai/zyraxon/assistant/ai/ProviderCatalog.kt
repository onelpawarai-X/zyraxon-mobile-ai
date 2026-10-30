package ai.zyraxon.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * One AI provider the app can really talk to: where its model catalogue lives,
 * where its chat completions live, and how it wants to be authenticated.
 *
 * Every URL here has been checked against the live service — a provider is only
 * listed once its endpoint answers. Nothing in this file invents a model; the
 * catalogue is fetched, and [seedModels] exists only so a first-run user sees a
 * useful list when the network is down.
 */
data class ProviderSpec(
    val id: String,
    val label: String,
    val modelsUrl: String,
    val chatUrl: String,
    val auth: AuthStyle,
    /** Some providers use a different scheme for the catalogue than for chat. */
    val catalogAuth: AuthStyle = auth,
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * Shown only when the live catalogue could not be reached. These are real,
     * long-lived public model ids, so a user with a key is never left with an
     * empty selector. A model is always sent to its own provider, never invented.
     */
    val seedModels: List<String> = emptyList(),
    /** Lower answers sooner; used to sort the selector so fast models come first. */
    val speedRank: Int = 50,
    val category: String = label,
    /** Models list is public (no key needed), e.g. OpenRouter's directory. */
    val catalogIsPublic: Boolean = false,
    /**
     * The socket that carries true speech-to-speech for this provider, or null
     * when the provider has no such endpoint. Every URL here was probed against
     * the live service: Gemini's `BidiGenerateContent`, OpenAI's `/v1/realtime`
     * and xAI's `/v1/realtime` answer; Groq's guessed path returns 404, so it is
     * null. The user's own server decides which models appear — nothing here is a
     * hardcoded model list.
     */
    val realtimeWsUrl: String? = null,
    /**
     * Substrings that mark a model id from *this* provider's own catalogue as a
     * live speech-to-speech model. This is a naming rule, not a model list: the
     * ids still come from the provider. Gemini marks live models with `-live-`
     * or `native-audio`, OpenAI with `realtime`, xAI with `voice`.
     */
    val realtimeModelPatterns: List<String> = emptyList(),
    /** Which wire protocol the realtime socket speaks. */
    val realtimeProtocol: RealtimeProtocol = RealtimeProtocol.NONE
) {
    enum class AuthStyle {
        /** `Authorization: Bearer <key>` */
        BEARER,
        /** `x-api-key: <key>` */
        X_API_KEY,
        /** Both headers; some gateways check one, some the other. */
        BEARER_AND_X_API_KEY,
        /** `?key=<key>` query parameter, as Google's native endpoints want. */
        QUERY_KEY,
        /** Nothing to send. */
        NONE
    }

    /** The wire dialects a realtime socket can speak. */
    enum class RealtimeProtocol {
        /** No realtime socket. */
        NONE,
        /** Gemini `BidiGenerateContent`: `setup`, `realtimeInput`, `serverContent`. */
        GEMINI,
        /** OpenAI Realtime: `session.update`, `input_audio_buffer.append`, `response.audio.delta`. */
        OPENAI
    }

    /** True when this provider can carry a live, spoken conversation. */
    val supportsRealtime: Boolean
        get() = realtimeWsUrl != null && realtimeProtocol != RealtimeProtocol.NONE

    /**
     * Whether a model id from this provider is a live speech-to-speech model.
     * Only providers with a real socket qualify, so a name can never promise a
     * conversation the provider cannot carry.
     */
    fun isRealtimeModel(modelId: String): Boolean {
        if (!supportsRealtime || realtimeModelPatterns.isEmpty()) return false
        val id = modelId.lowercase()
        return realtimeModelPatterns.any { id.contains(it) }
    }

    /** True when the models list can be read without a key. */
    val needsKeyForCatalog: Boolean
        get() = !catalogIsPublic && auth != AuthStyle.NONE
}

/**
 * The providers this app supports. Ids are the storage keys used by the API-key
 * settings, so they must not change.
 */
object ProviderCatalog {

    /** Google's OpenAI-compatible chat endpoint. */
    private const val GEMINI_CHAT =
        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"

    val specs: List<ProviderSpec> = listOf(
        ProviderSpec(
            id = "zyraxon",
            label = "ZYRAXON",
            modelsUrl = "https://zyraxonai.lovable.app/api/public/zyraxon/v1/models",
            chatUrl = "https://zyraxonai.lovable.app/api/public/zyraxon/v1/chat/completions",
            auth = ProviderSpec.AuthStyle.BEARER,
            // The catalogue is public, so the ten models list before a key is
            // set. Chat still needs the key.
            catalogIsPublic = true,
            speedRank = 0,
            category = "ZYRAXON"
        ),
        ProviderSpec(
            id = "groq",
            label = "Groq",
            modelsUrl = "https://api.groq.com/openai/v1/models",
            chatUrl = "https://api.groq.com/openai/v1/chat/completions",
            auth = ProviderSpec.AuthStyle.BEARER,
            // Groq is the fastest hosted inference, so its models lead the list.
            speedRank = 1,
            category = "Groq (দ্রুততম)",
            seedModels = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
        ),
        ProviderSpec(
            id = "gemini",
            label = "Gemini",
            // The OpenAI-compat path has no models endpoint; Google's native one does.
            modelsUrl = "https://generativelanguage.googleapis.com/v1beta/models",
            chatUrl = GEMINI_CHAT,
            auth = ProviderSpec.AuthStyle.BEARER,
            catalogAuth = ProviderSpec.AuthStyle.QUERY_KEY,
            speedRank = 2,
            category = "Gemini",
            // Live models come from Google's own catalogue; these patterns only
            // recognise them (`gemini-2.5-flash-native-audio-*`,
            // `gemini-3.1-flash-live-*`) so the selector can label them.
            realtimeWsUrl = "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            realtimeModelPatterns = listOf("native-audio", "-live-", "live-preview"),
            realtimeProtocol = ProviderSpec.RealtimeProtocol.GEMINI,
            // Google retires model ids for new users (gemini-2.5-flash now
            // answers 404 "no longer available to new users"), so the first-run
            // seeds are the aliases Google keeps pointing at the current fast
            // model. The live catalogue replaces these as soon as it loads.
            seedModels = listOf("gemini-flash-latest", "gemini-flash-lite-latest")
        ),
        ProviderSpec(
            id = "openai",
            label = "OpenAI",
            modelsUrl = "https://api.openai.com/v1/models",
            chatUrl = "https://api.openai.com/v1/chat/completions",
            auth = ProviderSpec.AuthStyle.BEARER,
            speedRank = 3,
            category = "OpenAI",
            // Probed: wss://api.openai.com/v1/realtime answers.
            realtimeWsUrl = "wss://api.openai.com/v1/realtime",
            realtimeModelPatterns = listOf("realtime"),
            realtimeProtocol = ProviderSpec.RealtimeProtocol.OPENAI
        ),
        ProviderSpec(
            id = "xai",
            label = "Grok (xAI)",
            modelsUrl = "https://api.x.ai/v1/models",
            chatUrl = "https://api.x.ai/v1/chat/completions",
            auth = ProviderSpec.AuthStyle.BEARER,
            speedRank = 4,
            category = "Grok",
            // Probed: wss://api.x.ai/v1/realtime answers 401 (needs a key), so it
            // is a real socket. xAI's own catalogue names them `grok-voice-*`.
            realtimeWsUrl = "wss://api.x.ai/v1/realtime",
            realtimeModelPatterns = listOf("voice"),
            realtimeProtocol = ProviderSpec.RealtimeProtocol.OPENAI
        ),
        ProviderSpec(
            id = "anthropic",
            label = "Claude",
            modelsUrl = "https://api.anthropic.com/v1/models",
            // Anthropic's OpenAI-compatibility layer; the SDK points here too.
            chatUrl = "https://api.anthropic.com/v1/chat/completions",
            auth = ProviderSpec.AuthStyle.BEARER_AND_X_API_KEY,
            extraHeaders = mapOf("anthropic-version" to "2023-06-01"),
            speedRank = 5,
            category = "Claude",
            seedModels = listOf("claude-sonnet-4-5", "claude-3-5-haiku-latest")
        ),
        ProviderSpec(
            id = "deepseek",
            label = "DeepSeek",
            modelsUrl = "https://api.deepseek.com/models",
            chatUrl = "https://api.deepseek.com/chat/completions",
            auth = ProviderSpec.AuthStyle.BEARER,
            speedRank = 6,
            category = "DeepSeek",
            seedModels = listOf("deepseek-chat", "deepseek-reasoner")
        ),
        ProviderSpec(
            id = "openrouter",
            label = "OpenRouter",
            modelsUrl = "https://openrouter.ai/api/v1/models",
            chatUrl = "https://openrouter.ai/api/v1/chat/completions",
            auth = ProviderSpec.AuthStyle.BEARER,
            // The directory is public, so models load even before a key is set.
            catalogIsPublic = true,
            speedRank = 7,
            category = "OpenRouter"
        ),
        ProviderSpec(
            id = "opencode",
            label = "OpenCode",
            modelsUrl = "https://opencode.ai/zen/v1/models",
            chatUrl = "https://opencode.ai/zen/v1/chat/completions",
            auth = ProviderSpec.AuthStyle.BEARER_AND_X_API_KEY,
            extraHeaders = mapOf("x-opencode-client" to "cli"),
            speedRank = 8,
            category = "OpenCode"
        )
    )

    private val byId = specs.associateBy { it.id }

    fun find(id: String): ProviderSpec? = byId[id]

    /** The provider a model belongs to, or null when the id is unknown. */
    fun forModelProvider(provider: String): ProviderSpec? = byId[provider]

    /** Providers whose catalogue can be read right now, given the keys on hand. */
    fun loadable(keyLookup: (String) -> String): List<ProviderSpec> =
        specs.filter { spec ->
            spec.catalogIsPublic || spec.auth == ProviderSpec.AuthStyle.NONE ||
                keyLookup(spec.id).isNotBlank()
        }

    /**
     * Applies a provider's auth scheme to an already-built request. Kept in one
     * place so the catalogue and chat paths cannot drift apart.
     */
    fun applyAuth(
        builder: okhttp3.Request.Builder,
        spec: ProviderSpec,
        key: String,
        forCatalog: Boolean = false
    ) {
        val style = if (forCatalog) spec.catalogAuth else spec.auth
        if (key.isNotBlank()) {
            when (style) {
                ProviderSpec.AuthStyle.BEARER ->
                    builder.addHeader("Authorization", "Bearer $key")
                ProviderSpec.AuthStyle.X_API_KEY ->
                    builder.addHeader("x-api-key", key)
                ProviderSpec.AuthStyle.BEARER_AND_X_API_KEY -> {
                    builder.addHeader("Authorization", "Bearer $key")
                    builder.addHeader("x-api-key", key)
                }
                ProviderSpec.AuthStyle.QUERY_KEY -> Unit // applied to the URL instead
                ProviderSpec.AuthStyle.NONE -> Unit
            }
        }
        for ((name, value) in spec.extraHeaders) builder.addHeader(name, value)
    }

    /** The URL to call, with the key folded in when the provider wants it there. */
    fun urlFor(spec: ProviderSpec, key: String, forCatalog: Boolean): String {
        val style = if (forCatalog) spec.catalogAuth else spec.auth
        if (style != ProviderSpec.AuthStyle.QUERY_KEY || key.isBlank()) return if (forCatalog) spec.modelsUrl else spec.chatUrl
        val base = if (forCatalog) spec.modelsUrl else spec.chatUrl
        val separator = if (base.contains("?")) "&" else "?"
        return "$base${separator}key=$key"
    }

    /**
     * Reads a provider's model list. Providers disagree on the shape, so both the
     * OpenAI `data[]` form and Google's native `models[]` form are handled, and
     * only a non-blank id survives.
     */
    fun parseModels(spec: ProviderSpec, body: String): List<String> {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return emptyList()
        }
        val array: JSONArray = root.optJSONArray("data")
            ?: root.optJSONArray("models")
            ?: return emptyList()
        val ids = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            // Google's native list puts the id in `name` as "models/gemini-2.0-flash".
            val raw = item.optString("id").ifBlank {
                item.optString("name").removePrefix("models/")
            }.trim()
            if (raw.isBlank()) continue
            // Google returns embedding, image, video and speech-only models too;
            // the chat endpoint would reject them, so they are not offered as
            // chat models. `-tts` matters here: those speak but cannot hold a
            // conversation, and the live `native-audio` models do not end in it.
            val lowered = raw.lowercase()
            if (lowered.contains("embedding") || lowered.contains("aqa") ||
                lowered.contains("imagen") || lowered.contains("veo") ||
                lowered.contains("lyria") || lowered.contains("whisper") ||
                lowered.contains("-image") || lowered.contains("image-") ||
                lowered.contains("banana") || lowered.contains("robotics") ||
                lowered.contains("transcribe") || lowered.contains("computer-use") ||
                lowered.endsWith("-tts") || lowered.contains("-tts-")
            ) continue
            if (ids.none { it == raw }) ids.add(raw)
        }
        return ids
    }

    /**
     * Pulls the provider's own error text out of a failure body, so the user is
     * told the real reason (a bad key, a quota) rather than a generic message.
     */
    /**
     * True when a failed request is worth trying again — or worth trying on
     * another provider — rather than a request the server will keep rejecting.
     *
     * A rejected *request* (an invalid model id, a missing thought_signature, a
     * bad tool schema) fails the same way on every provider, so retrying it just
     * burns minutes while the user watches a frozen cursor. Only genuine
     * availability problems — quota, rate limit, overload, timeout, dropped
     * connection — are retried.
     */
    fun isRetryableError(message: String?): Boolean {
        val m = message?.lowercase() ?: return true
        val permanent = listOf(
            "invalid_argument", "invalid argument", "not_found", "not found",
            "no longer available", "permission_denied", "unauthenticated",
            "api key not valid", "api_key_invalid", "unsupported", "does not exist",
            "missing a thought_signature", "invalid api key"
        )
        return permanent.none { m.contains(it) }
    }

    fun parseError(body: String, code: Int): String {
        val fallback = "HTTP $code"
        if (body.isBlank()) return fallback
        try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.let { e ->
                e.optString("message").takeIf { it.isNotBlank() }?.let { return it }
            }
            json.optString("message").takeIf { it.isNotBlank() }?.let { return it }
            json.optJSONArray("error")?.optJSONObject(0)?.optString("message")
                ?.takeIf { it.isNotBlank() }?.let { return it }
            return fallback
        } catch (_: Exception) {
            // Not JSON. A short plain-text reason is worth showing; an HTML error
            // page is not, so the status code stands in for it.
            val trimmed = body.trim()
            val looksLikeHtml = trimmed.startsWith("<")
            return if (!looksLikeHtml && trimmed.isNotBlank()) trimmed.take(200) else fallback
        }
    }
}
