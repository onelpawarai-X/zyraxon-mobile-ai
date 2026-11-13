package ai.zyraxon.assistant.sealed

import android.content.Context
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads the sealed assets — the system prompt and the tool text.
 *
 * These files are the most valuable thing in the APK: they describe exactly how
 * the agent is told to behave. As plain assets anyone could read them with
 * `unzip`, so they are encrypted into the build by the `sealAssets` Gradle task
 * and decrypted here, in memory, on first use.
 *
 * The key is assembled from parts at run time rather than written as one
 * constant, and R8 renames and inlines the rest of this class with the app. That
 * is a real cost to an attacker, not a wall: someone who controls the device can
 * still recover the plaintext. SECURITY.md is explicit about that limit.
 */
object SealedAssets {

    private const val DIR = "sealed"

    /** The key material, split so it is not one readable string in the dex. */
    private val keyParts = arrayOf("zx-se", "aled-", "asset", "s-v1")

    private val saltParts = arrayOf("zyrax", "on-se", "aled-", "salt")

    @Volatile
    private var key: SecretKeySpec? = null

    private fun key(): SecretKeySpec = key ?: synchronized(this) {
        key ?: run {
            val passphrase = keyParts.joinToString("").toCharArray()
            val salt = saltParts.joinToString("").toByteArray()
            val spec = PBEKeySpec(passphrase, salt, 65_536, 256)
            val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
            SecretKeySpec(derived.encoded, "AES").also { key = it }
        }
    }

    /**
     * The plaintext of a sealed file, or null when it is not there. The path is
     * the one used before sealing, e.g. `knowledge/prompt-agent.txt`.
     */
    fun text(context: Context, path: String): String? = runCatching {
        val blob = readBytes(context, path) ?: return null
        val iv = blob.copyOfRange(0, 12)
        val body = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    private fun readBytes(context: Context, path: String): ByteArray? = runCatching {
        context.assets.open("$DIR/$path").use { it.readBytes() }
    }.getOrNull()

    /**
     * The sealed text, or the plain asset when the file is not sealed. The
     * fallback keeps a local build and the tests working without running the
     * sealing task first.
     */
    fun textOrAsset(context: Context, path: String): String? =
        text(context, path) ?: runCatching {
            context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()

    /** True when the sealed copy exists; used by the tests and by diagnostics. */
    fun exists(context: Context, path: String): Boolean = readBytes(context, path) != null
}
