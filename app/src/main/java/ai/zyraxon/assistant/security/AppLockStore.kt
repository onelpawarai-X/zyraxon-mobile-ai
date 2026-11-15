// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.security

import android.content.Context
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The app's own lock. There are three ways in, and they combine:
 *
 *  - a **pattern** or a **PIN** — the same slot, two shapes. Setting one replaces
 *    the other, because a second secret for the same door is a way to be locked
 *    out, not a way to be safer.
 *  - a **spoken word**, which is optional and stacks on top. Set only a word and
 *    the voice opens the app; set a word *and* a secret and both are asked for,
 *    so the word is a second lock rather than a replacement for the first.
 *
 * The device credential is not used here. It is a good secret, but it belongs to
 * the phone, and asking for it opens a system screen — a second activity on top
 * of this app. That is what used to pull ZYRAXON back to the front while the
 * agent was working in another app. A secret this app owns is asked for on a
 * screen this app draws, so the cover appears without ever taking the foreground
 * from whatever the agent opened.
 *
 * Only hashes are kept. The secrets themselves are never written down, so a copy
 * of the preferences file gives an attacker nothing to read: what is stored is a
 * PBKDF2-HMAC-SHA256 digest over a per-secret random salt, and the only way to
 * check a guess is to run the same slow derivation against it.
 *
 * The spoken words that lock and unlock are stored as written: the agent has to
 * be able to say the unlock word, and a word the user chose to speak aloud is not
 * a secret to hide. They live here, not in the AI prefs, so the lock owns
 * everything about locking.
 */
class AppLockStore(context: Context) {

    private val prefs = context.getSharedPreferences("zyraxon_app_lock", Context.MODE_PRIVATE)

    /** True once a pattern has been set. */
    val hasPattern: Boolean get() = prefs.contains(KEY_PATTERN_HASH)

    /** True once a PIN has been set. Never both: setting one clears the other. */
    val hasPin: Boolean get() = prefs.contains(KEY_PIN_HASH)

    /** True when a pattern or a PIN exists. */
    val hasSecret: Boolean get() = hasPattern || hasPin

    /** True when a spoken unlock word is set. */
    val hasVoice: Boolean get() = !unlockCommand.isNullOrBlank()

    /** Whether the lock is armed. A lock with no secret is off regardless. */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** True once at least one secret exists and the user has armed the lock. */
    val isArmed: Boolean get() = isEnabled && hasSecret

    /**
     * The kind of secret in force. The pattern and the PIN are one slot, so at
     * most one of them is ever set; this is which. Null when neither is set.
     */
    val activeKind: String?
        get() = when {
            hasPattern -> MODE_PATTERN
            hasPin -> MODE_PIN
            else -> null
        }

    /**
     * Sets the pattern. The PIN is removed first: the pattern and the PIN are the
     * same slot, so the newest one is the one that opens the door.
     */
    fun setPattern(pattern: String) {
        val salt = newSalt()
        prefs.edit()
            .remove(KEY_PIN_SALT).remove(KEY_PIN_HASH)
            .putString(KEY_PATTERN_SALT, encode(salt))
            .putString(KEY_PATTERN_HASH, encode(hash(pattern, salt)))
            .putBoolean(KEY_ENABLED, true)
            .apply()
    }

    /** Sets the PIN, replacing any pattern for the same reason [setPattern] does. */
    fun setPin(pin: String) {
        val salt = newSalt()
        prefs.edit()
            .remove(KEY_PATTERN_SALT).remove(KEY_PATTERN_HASH)
            .putString(KEY_PIN_SALT, encode(salt))
            .putString(KEY_PIN_HASH, encode(hash(pin, salt)))
            .putBoolean(KEY_ENABLED, true)
            .apply()
    }

    fun removePattern() = prefs.edit().remove(KEY_PATTERN_HASH).remove(KEY_PATTERN_SALT).apply()

    fun removePin() = prefs.edit().remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).apply()

    /**
     * Checks a pattern guess. False when no pattern is set, so a missing secret
     * can never read as a match.
     */
    fun verifyPattern(guess: String): Boolean = verify(KEY_PATTERN_SALT, KEY_PATTERN_HASH, guess)

    /** Checks a PIN guess. False when no PIN is set. */
    fun verifyPin(guess: String): Boolean = verify(KEY_PIN_SALT, KEY_PIN_HASH, guess)

    /**
     * Checks a guess against whichever kind the cover is asking for, so the
     * unlock screen does not have to know which store call to make.
     */
    fun verify(guess: String, kind: String = activeKind ?: MODE_PATTERN): Boolean =
        if (kind == MODE_PIN) verifyPin(guess) else verifyPattern(guess)

    /** Removes both secrets and turns the lock off. */
    fun clear() = prefs.edit().clear().apply()

    // ---- spoken commands ------------------------------------------------

    /** The spoken word that locks the app, or null when none is set. */
    var lockCommand: String?
        get() = prefs.getString(KEY_LOCK_CMD, null)?.takeIf { it.isNotBlank() }
        set(value) = store(KEY_LOCK_CMD, value)

    /** The spoken word that unlocks the app, or null when none is set. */
    var unlockCommand: String?
        get() = prefs.getString(KEY_UNLOCK_CMD, null)?.takeIf { it.isNotBlank() }
        set(value) = store(KEY_UNLOCK_CMD, value)

    /**
     * True when [spoken] is the unlock word. The comparison folds case and
     * surrounding space, because a recognizer does not return a phrase the same
     * way twice.
     */
    fun matchesUnlock(spoken: String): Boolean = matches(unlockCommand, spoken)

    /** True when [spoken] is the lock word. */
    fun matchesLock(spoken: String): Boolean = matches(lockCommand, spoken)

    private fun matches(word: String?, spoken: String): Boolean {
        if (word.isNullOrBlank()) return false
        return word.trim().equals(spoken.trim(), ignoreCase = true)
    }

    private fun store(key: String, value: String?) {
        if (value.isNullOrBlank()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, value.trim()).apply()
    }

    private fun verify(saltKey: String, hashKey: String, guess: String): Boolean {
        val salt = decode(prefs.getString(saltKey, null)) ?: return false
        val stored = decode(prefs.getString(hashKey, null)) ?: return false
        return constantTimeEquals(stored, hash(guess, salt))
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
        const val MODE_PATTERN = "pattern"
        const val MODE_PIN = "pin"

        /** Shortest PIN the pad accepts. */
        const val PIN_MIN = 4
        const val PIN_MAX = 8

        /** A pattern that joins fewer than this many dots is not accepted. */
        const val PATTERN_MIN_DOTS = 4

        private const val KEY_ENABLED = "enabled"
        private const val KEY_PATTERN_SALT = "pattern_salt"
        private const val KEY_PATTERN_HASH = "pattern_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_LOCK_CMD = "voice_lock_command"
        private const val KEY_UNLOCK_CMD = "voice_unlock_command"

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
