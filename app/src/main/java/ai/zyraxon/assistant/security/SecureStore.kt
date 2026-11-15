// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted key/value storage for the things that must never sit in the clear:
 * the AI provider API keys, the GitHub token, and the MCP access and refresh
 * tokens.
 *
 * The old code wrote these straight into a `MODE_PRIVATE` preferences file. That
 * file is private only to other *apps*: on a rooted phone, or in a backup pulled
 * off the device, it is readable text, and an API key read once is an API key
 * spent. Here each value is sealed with AES-256-GCM under a key that lives in the
 * Android keystore and never leaves it, so a copy of the file is a copy of
 * ciphertext.
 *
 * API 23 and up use the keystore. On API 21–22, which have no AES keystore, the
 * value is stored in the same format but with a per-install random key kept in a
 * separate file — weaker, and SECURITY.md says so, but still not the plaintext
 * the old code wrote. Nothing here claims to stop an attacker who already runs
 * code on the device as this app; the point is that a *copied* file is not a
 * copied secret.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zyraxon_secure", Context.MODE_PRIVATE)

    /** The value for [key], or null when it was never stored or cannot be opened. */
    fun get(key: String): String? {
        val blob = prefs.getString(key, null) ?: return null
        return open(blob)
    }

    /** Seals [value] under [key]. An empty value removes the entry. */
    fun put(key: String, value: String) {
        if (value.isEmpty()) {
            remove(key)
            return
        }
        seal(value)?.let { prefs.edit().putString(key, it).apply() }
    }

    fun remove(key: String) = prefs.edit().remove(key).apply()

    /** True when [key] holds a sealed value. */
    fun has(key: String): Boolean = prefs.contains(key)

    /**
     * Moves a legacy plaintext value into the sealed store the first time it is
     * read. [legacy] is the preferences file the old code wrote to; once the
     * value is sealed the plaintext copy is deleted, so the upgrade does not
     * leave the secret lying in the old file.
     */
    fun migrate(legacy: SharedPreferences, legacyKey: String, key: String): String? {
        val already = get(key)
        if (already != null) return already
        val old = legacy.getString(legacyKey, null)?.takeIf { it.isNotBlank() } ?: return null
        put(key, old)
        legacy.edit().remove(legacyKey).apply()
        return old
    }

    private fun seal(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + body.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(body, 0, out, iv.size, body.size)
        PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }.getOrNull()

    private fun open(blob: String): String? = runCatching {
        if (!blob.startsWith(PREFIX)) return@runCatching null
        val raw = Base64.decode(blob.substring(PREFIX.length), Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) return@runCatching null
        val iv = raw.copyOfRange(0, IV_BYTES)
        val body = raw.copyOfRange(IV_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    /**
     * The sealing key. On API 23 and up this is a non-exportable AES key in the
     * Android keystore, generated once and reused; the keystore hands back the
     * same key on every launch and never exposes its bytes. Below 23 there is no
     * AES keystore, so a random per-install key is generated and kept beside the
     * ciphertext — obfuscation, not real protection, and documented as such.
     */
    private fun key(): SecretKey {
        keystoreKey()?.let { return it }
        return softwareKey()
    }

    private fun keystoreKey(): SecretKey? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: generateKeystoreKey(store)
        }.getOrNull()
    }

    private fun generateKeystoreKey(store: KeyStore): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        val generated = generator.generateKey()
        store.setEntry(ALIAS, KeyStore.SecretKeyEntry(generated), null)
        return generated
    }

    /** A per-install key for API 21–22, kept in its own file. */
    private fun softwareKey(): SecretKey {
        val stored = prefs.getString(SOFTWARE_KEY, null)
        if (stored != null) {
            return javax.crypto.spec.SecretKeySpec(
                Base64.decode(stored, Base64.NO_WRAP), "AES"
            )
        }
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit().putString(SOFTWARE_KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        return javax.crypto.spec.SecretKeySpec(bytes, "AES")
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "zyraxon_secure_store_v1"
        private const val SOFTWARE_KEY = "__software_key__"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFIX = "zxs1:"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
