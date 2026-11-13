// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.security

import android.content.Context
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The app's own lock secret: a password, a pattern, or neither.
 *
 * The device credential is not used here. It is a good secret, but it belongs to
 * the phone, and asking for it opens a system screen — a second activity on top
 * of this app. That is what used to pull ZYRAXON back to the front while the
 * agent was working in another app, because the credential screen must be in the
 * foreground to be shown. A secret this app owns is asked for on a screen this
 * app draws, so the cover appears without ever taking the foreground from
 * whatever the agent opened.
 *
 * Only the hash is kept. The secret itself is never written down, so a copy of
 * the preferences file gives an attacker nothing to read: what is stored is a
 * PBKDF2-HMAC-SHA256 digest over a per-install random salt, and the only way to
 * check a guess is to run the same slow derivation against it.
 */
class AppLockStore(context: Context) {

    private val prefs = context.getSharedPreferences("zyraxon_app_lock", Context.MODE_PRIVATE)

    /**
     * Which kind of secret is set. [MODE_NONE] means the lock has nothing to ask
     * for, so it is off no matter what [isEnabled] says — a cover with no answer
     * would only seal the owner out.
     */
    var mode: String
        get() = prefs.getString(KEY_MODE, MODE_NONE) ?: MODE_NONE
        private set(value) = prefs.edit().putString(KEY_MODE, value).apply()

    /** Whether a configured lock is armed. Independent of whether a secret is set. */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** True once a secret exists and the user has armed it. */
    val isArmed: Boolean get() = isEnabled && mode != MODE_NONE

    fun setPassword(password: String) {
        val salt = newSalt()
        prefs.edit()
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_HASH, encode(hash(password, salt)))
            .putString(KEY_MODE, MODE_PASSWORD)
            .putBoolean(KEY_ENABLED, true)
            .apply()
    }

    fun setPattern(pattern: String) {
        val salt = newSalt()
        prefs.edit()
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_HASH, encode(hash(pattern, salt)))
            .putString(KEY_MODE, MODE_PATTERN)
            .putBoolean(KEY_ENABLED, true)
            .apply()
    }

    /**
     * Checks a guess. False for every guess when nothing is set, so a missing
     * secret can never read as a match.
     */
    fun verify(guess: String): Boolean {
        if (mode == MODE_NONE) return false
        val salt = decode(prefs.getString(KEY_SALT, null)) ?: return false
        val stored = decode(prefs.getString(KEY_HASH, null)) ?: return false
        return constantTimeEquals(stored, hash(guess, salt))
    }

    /** Removes the secret and turns the lock off. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    private fun hash(secret: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Compares without leaking where the first difference is. The digests are a
     * fixed length, so walking all of it costs nothing and closes the timing
     * side channel an early return would open.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun decode(value: String?): ByteArray? {
        if (value.isNullOrEmpty()) return null
        return runCatching { android.util.Base64.decode(value, android.util.Base64.NO_WRAP) }.getOrNull()
    }

    companion object {
        const val MODE_NONE = "none"
        const val MODE_PASSWORD = "password"
        const val MODE_PATTERN = "pattern"

        /** Shortest password the pad accepts. */
        const val PASSWORD_MIN = 4
        const val PASSWORD_MAX = 8

        /** A pattern that joins fewer than this many dots is not accepted. */
        const val PATTERN_MIN_DOTS = 4

        private const val KEY_MODE = "mode"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"

        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256

        /**
         * Cost of one guess. High enough that guessing a short secret off a
         * copied preferences file is not worth the time, low enough that the
         * unlock itself is not something the user waits on.
         */
        private const val ITERATIONS = 120_000
    }
}
