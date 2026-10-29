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
    val catalogIsPublic: Boolean = false
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
            seedModels = listOf("gemini-2.0-flash", "gemini-2.5-flash")
        ),
        ProviderSpec(
            id = "openai",
            label = "OpenAI",
            modelsUrl = "https://api.openai.com/v1/models",
            chatUrl = "https://api.openai.com/v1/chat/completions",
            auth = ProviderSpec.AuthStyle.BEARER,
            speedRank = 3,
            category = "OpenAI",
            seedModels = listOf("gpt-4o-mini", "gpt-4o")
        ),
        ProviderSpec(
            id = "anthropic",
            label = "Claude",
            modelsUrl = "https://api.anthropic.com/v1/models",
            // Anthropic's OpenAI-compatibility layer; the SDK points here too.
            chatUrl = "https://api.anthropic.com/v1/chat/completions",
            auth = ProviderSpec.AuthStyle.BEARER_AND_X_API_KEY,
            extraHeaders = mapOf("anthropic-version" to "2023-06-01"),
            speedRank = 4,
            category = "Claude",
            seedModels = listOf("claude-sonnet-4-5", "claude-3-5-haiku-latest")
        ),
        ProviderSpec(
            id = "deepseek",
            label = "DeepSeek",
            modelsUrl = "https://api.deepseek.com/models",
            chatUrl = "https://api.deepseek.com/chat/completions",
            auth = ProviderSpec.AuthStyle.BEARER,
            speedRank = 5,
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
            speedRank = 6,
            category = "OpenRouter"
        ),
        ProviderSpec(
            id = "opencode",
            label = "OpenCode",
            modelsUrl = "https://opencode.ai/zen/v1/models",
            chatUrl = "https://opencode.ai/zen/v1/chat/completions",
            auth = ProviderSpec.AuthStyle.BEARER_AND_X_API_KEY,
            extraHeaders = mapOf("x-opencode-client" to "cli"),
            speedRank = 7,
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
            // Google returns embedding and other non-chat models too; the chat
            // endpoint would reject them, so they are not offered as chat models.
            val lowered = raw.lowercase()
            if (lowered.contains("embedding") || lowered.contains("aqa") ||
                lowered.contains("imagen") || lowered.contains("veo")
            ) continue
            if (ids.none { it == raw }) ids.add(raw)
        }
        return ids
    }

    /**
     * Pulls the provider's own error text out of a failure body, so the user is
     * told the real reason (a bad key, a quota) rather than a generic message.
     */
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
