package ai.zyraxon.assistant.mcp

import org.json.JSONObject

/**
 * One MCP server ZYRAXON can talk to.
 *
 * The endpoints and auth style are not guesses — each one was probed against the
 * live service. Two details matter and are easy to get wrong:
 *
 *  - Canva, Cloudflare and Supabase all reject a custom app scheme and demand a
 *    loopback redirect, so the app runs a short-lived local HTTP server to catch
 *    the code (see [McpOAuthServer]).
 *  - Canva and Cloudflare block requests that do not look like a browser, so
 *    every MCP call carries a desktop Chrome User-Agent.
 */
data class McpServer(
    val id: String,
    val name: String,
    val tagline: String,
    val emoji: String,
    /** The Streamable-HTTP endpoint that speaks JSON-RPC. */
    val url: String,
    val auth: McpAuth,
    /** Only for [McpAuth.OAUTH]: where the user consents, so the screen can show it. */
    val docsUrl: String = "",
    /** Only for [McpAuth.TOKEN]: where to create the token. */
    val tokenUrl: String = "",
    val tokenHint: String = "",
    /** What the user gets, shown on the card before they connect. */
    val highlights: List<String> = emptyList(),
    /** Pre-filled OAuth scopes, when the server needs them named up front. */
    val scopes: List<String> = emptyList(),
    /** True for servers the user added themselves — they can be removed. */
    val custom: Boolean = false
)

enum class McpAuth { OAUTH, TOKEN, NONE }

/**
 * The four servers the app ships with. URLs verified 2026-10-28.
 */
object McpCatalog {

    const val PROTOCOL_VERSION = "2025-06-18"

    val builtIn: List<McpServer> = listOf(
        McpServer(
            id = "canva",
            name = "Canva",
            tagline = "ডিজাইন বানাও, খোঁজো আর এক্সপোর্ট করো",
            emoji = "🎨",
            url = "https://mcp.canva.com/mcp",
            auth = McpAuth.OAUTH,
            docsUrl = "https://www.canva.com/developers/",
            highlights = listOf(
                "ডিজাইন সার্চ ও ব্রাউজ",
                "নতুন ডিজাইন তৈরি",
                "PNG / PDF এক্সপোর্ট",
                "অটো-ফিল (নাম, ছবি)"
            )
        ),
        McpServer(
            id = "cloudflare",
            name = "Cloudflare",
            tagline = "ওয়ার্কার, KV, R2 আর DNS চালাও",
            emoji = "🧱",
            url = "https://mcp.cloudflare.com/mcp",
            auth = McpAuth.OAUTH,
            docsUrl = "https://developers.cloudflare.com/agents/model-context-protocol/",
            highlights = listOf(
                "Workers স্ক্রিপ্ট",
                "KV / R2 / D1 স্টোরেজ",
                "DNS ও জোন",
                "লগ ও অ্যানালিটিক্স"
            )
        ),
        McpServer(
            id = "supabase",
            name = "Supabase",
            tagline = "ডেটাবেস, অথ, স্টোরেজ — সব এক জায়গায়",
            emoji = "⚡",
            url = "https://mcp.supabase.com/mcp",
            auth = McpAuth.OAUTH,
            docsUrl = "https://supabase.com/docs/guides/getting-started/mcp",
            highlights = listOf(
                "টেবিল ও SQL কুয়েরি",
                "প্রজেক্ট ম্যানেজমেন্ট",
                "Edge Functions",
                "স্টোরেজ বাকেট"
            )
        ),
        McpServer(
            id = "github",
            name = "GitHub",
            tagline = "রিপো, ইস্যু আর PR — টোকেন দিয়ে",
            emoji = "🐙",
            url = "https://api.githubcopilot.com/mcp/",
            auth = McpAuth.TOKEN,
            tokenUrl = "https://github.com/settings/personal-access-tokens/new",
            tokenHint = "fine-grained টোকেন বানাও এবং repo স্কোপ দাও",
            highlights = listOf(
                "কোড পড়া ও লেখা",
                "ইস্যু ও পুল রিকোয়েস্ট",
                "ব্রাঞ্চ ও কমিট",
                "অ্যাকশন রান দেখা"
            )
        )
    )

    fun find(id: String): McpServer? = builtIn.firstOrNull { it.id == id }

    /** A server the user typed in by hand, so it has no catalogue metadata. */
    fun custom(url: String, token: String?): McpServer {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        val clean = host.removePrefix("www.").removePrefix("mcp.")
        // Runs of non-alphanumerics collapse to one `_`: a punycode host like
        // `xn--80ak6aa92e.com` would otherwise produce `xn__80ak...`, and `__` is the
        // separator used to namespace tools, so the id would split in the wrong place.
        val slug = clean.replace(Regex("[^a-zA-Z0-9]+"), "_").trim('_').ifBlank { "host" }
        // Truncating alone can make two hosts share an id, and then each would
        // overwrite the other's tools, so a short stable hash of the host is kept.
        val fingerprint = clean.hashCode().toUInt().toString(16).padStart(8, '0')
        return McpServer(
            id = "custom_${slug.take(24)}_$fingerprint",
            name = clean,
            tagline = "নিজের MCP সার্ভার",
            emoji = "🔌",
            url = url,
            auth = if (token.isNullOrBlank()) McpAuth.NONE else McpAuth.TOKEN,
            tokenHint = "সার্ভারের টোকেন (থাকলে)",
            highlights = emptyList(),
            custom = true
        )
    }

    // ---- persisted form -----------------------------------------------------

    fun toJson(s: McpServer): JSONObject = JSONObject()
        .put("id", s.id).put("name", s.name).put("tagline", s.tagline)
        .put("emoji", s.emoji).put("url", s.url).put("auth", s.auth.name)
        .put("docsUrl", s.docsUrl).put("tokenUrl", s.tokenUrl)
        .put("tokenHint", s.tokenHint).put("custom", s.custom)
        .put("highlights", org.json.JSONArray(s.highlights))
        .put("scopes", org.json.JSONArray(s.scopes))

    fun fromJson(o: JSONObject): McpServer = McpServer(
        id = o.optString("id"),
        name = o.optString("name"),
        tagline = o.optString("tagline"),
        emoji = o.optString("emoji", "🔌"),
        url = o.optString("url"),
        auth = runCatching { McpAuth.valueOf(o.optString("auth", "NONE")) }.getOrDefault(McpAuth.NONE),
        docsUrl = o.optString("docsUrl"),
        tokenUrl = o.optString("tokenUrl"),
        tokenHint = o.optString("tokenHint"),
        highlights = o.optJSONArray("highlights")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        scopes = o.optJSONArray("scopes")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        custom = o.optBoolean("custom")
    )
}

/** One tool a server advertised, in OpenAI function shape. */
data class McpTool(
    val serverId: String,
    val serverName: String,
    val name: String,
    val description: String,
    val inputSchema: JSONObject
) {
    /** Prefixed so two servers cannot collide, and so the engine can route it. */
    val qualifiedName: String get() = "${serverId}__$name"

    fun toOpenAiSchema(): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", qualifiedName)
            .put("description", "[$serverName] $description".take(1000))
            .put("parameters", inputSchema))
}

enum class McpStatus(val label: String) {
    DISCONNECTED("কানেক্ট করা হয়নি"),
    CONNECTING("কানেক্ট হচ্ছে…"),
    CONNECTED("কানেক্টেড"),
    NEEDS_AUTH("অনুমতি দরকার"),
    FAILED("ব্যর্থ")
}

/** A file, document or record a server exposes. Read with `resources/read`. */
data class McpResource(
    val serverId: String,
    val serverName: String,
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
    /** A template has placeholders in the uri and cannot be read directly. */
    val template: Boolean = false
) {
    /** `<serverId>__<uri>` so a read routes back to the right server. */
    val qualifiedUri: String get() = "${serverId}__$uri"
}

/** A prompt template the server ships. Rendered with `prompts/get`. */
data class McpPrompt(
    val serverId: String,
    val serverName: String,
    val name: String,
    val description: String,
    val arguments: List<McpPromptArg>
) {
    val qualifiedName: String get() = "${serverId}__$name"
}

data class McpPromptArg(
    val name: String,
    val description: String,
    val required: Boolean
)

data class McpConnection(
    val server: McpServer,
    val status: McpStatus = McpStatus.DISCONNECTED,
    val tools: List<McpTool> = emptyList(),
    val resources: List<McpResource> = emptyList(),
    val prompts: List<McpPrompt> = emptyList(),
    val error: String = "",
    val account: String = "",
    val lastUsedAt: Long = 0L
) {
    val toolCount: Int get() = tools.size
    val resourceCount: Int get() = resources.size
    val promptCount: Int get() = prompts.size
}
