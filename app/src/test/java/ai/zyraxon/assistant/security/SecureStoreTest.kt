// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the real [SecureStore] and [Secrets] against Robolectric's
 * preferences, not a stub.
 *
 * The contract these tests defend is the one that matters: a stored secret is not
 * readable as written, it comes back unchanged, and a legacy plaintext value is
 * migrated and then erased.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SecureStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("zyraxon_secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("zyraxon_ai_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `a stored value round-trips`() {
        val store = SecureStore(context)
        store.put("key_gemini", "AIza-super-secret")
        assertEquals("AIza-super-secret", store.get("key_gemini"))
    }

    @Test
    fun `the value on disk is not the plaintext`() {
        SecureStore(context).put("key_openai", "sk-very-secret-value")
        val raw = context.getSharedPreferences("zyraxon_secure", Context.MODE_PRIVATE)
            .getString("key_openai", null)
        assertNotEquals("sk-very-secret-value", raw)
        assertFalse(raw!!.contains("secret"))
    }

    @Test
    fun `a missing key reads as null`() {
        assertNull(SecureStore(context).get("never_stored"))
    }

    @Test
    fun `an empty value removes the entry`() {
        val store = SecureStore(context)
        store.put("key_x", "value")
        store.put("key_x", "")
        assertNull(store.get("key_x"))
    }

    @Test
    fun `a legacy plaintext value is migrated and erased`() {
        val legacy = context.getSharedPreferences("zyraxon_ai_prefs", Context.MODE_PRIVATE)
        legacy.edit().putString("key_anthropic", "sk-ant-legacy").commit()

        val store = SecureStore(context)
        assertEquals("sk-ant-legacy", store.migrate(legacy, "key_anthropic", "key_anthropic"))
        // The old copy is gone, so the upgrade does not leave it in the clear.
        assertNull(legacy.getString("key_anthropic", null))
        assertEquals("sk-ant-legacy", store.get("key_anthropic"))
    }

    @Test
    fun `the secrets facade reads and writes a provider key`() {
        Secrets.setApiKey(context, "gemini", "AIza-facade")
        assertEquals("AIza-facade", Secrets.apiKey(context, "gemini"))
    }

    @Test
    fun `the secrets facade migrates a legacy github token`() {
        val legacy = context.getSharedPreferences("zyraxon_ai_prefs", Context.MODE_PRIVATE)
        legacy.edit().putString("gh_token", "ghp_legacy").commit()
        assertEquals("ghp_legacy", Secrets.githubToken(context))
        assertNull(legacy.getString("gh_token", null))
    }

    @Test
    fun `the secrets facade seals an mcp token`() {
        Secrets.setMcp(context, "access_srv", "mcp-token")
        assertEquals("mcp-token", Secrets.mcp(context, "access_srv"))
        val raw = context.getSharedPreferences("zyraxon_secure", Context.MODE_PRIVATE)
            .getString("access_srv", null)
        assertFalse(raw!!.contains("mcp-token"))
    }
}
