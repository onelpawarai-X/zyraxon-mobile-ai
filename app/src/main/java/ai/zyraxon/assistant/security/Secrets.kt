// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.security

import android.content.Context
import android.content.SharedPreferences

/**
 * The one place the app reads and writes a secret.
 *
 * Before this, four files each opened `zyraxon_ai_prefs` themselves and wrote a
 * key or a token into it in the clear. Centralising it does two things: every
 * secret goes through [SecureStore], and a legacy plaintext value is moved into
 * the sealed store the first time it is read — so an upgrade does not leave the
 * old copy behind, and no call site has to know the migration happened.
 */
object Secrets {

    private const val LEGACY_PREFS = "zyraxon_ai_prefs"
    private const val LEGACY_GH = "gh_token"

    private fun store(context: Context) = SecureStore(context)

    private fun legacy(context: Context): SharedPreferences =
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    /** The API key for a provider, sealed at rest. Empty when none is set. */
    fun apiKey(context: Context, provider: String): String {
        val key = "key_$provider"
        val sealed = store(context).migrate(legacy(context), key, key)
        if (!sealed.isNullOrBlank()) return sealed.trim()
        if (provider == "gemini") return System.getenv("GEMINI_API_KEY").orEmpty().trim()
        return ""
    }

    fun setApiKey(context: Context, provider: String, key: String) {
        store(context).put("key_$provider", key.trim())
    }

    /** The GitHub token, sealed at rest. Empty when none is set. */
    fun githubToken(context: Context): String {
        val sealed = store(context).migrate(legacy(context), LEGACY_GH, LEGACY_GH)
        return sealed?.trim().orEmpty()
    }

    fun setGithubToken(context: Context, token: String) {
        store(context).put(LEGACY_GH, token.trim())
    }

    /** An arbitrary MCP secret (access/refresh token, client secret, PAT). */
    fun mcp(context: Context, key: String): String? = store(context).get(key)

    fun setMcp(context: Context, key: String, value: String?) {
        if (value.isNullOrEmpty()) store(context).remove(key) else store(context).put(key, value)
    }
}
