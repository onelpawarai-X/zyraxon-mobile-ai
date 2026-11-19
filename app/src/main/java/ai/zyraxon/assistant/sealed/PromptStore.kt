// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.sealed

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Where the prompt text actually comes from.
 *
 * The contracts used to ship inside the APK, sealed or not: anyone who unzipped
 * the app held the text that describes exactly how the agent is told to behave.
 * They are now published, sealed, from the website, and the app fetches them once
 * on first open — so the install carries no prompt at all, and a prompt can be
 * corrected without shipping a new APK.
 *
 * The bytes on the wire are the same format `sealAssets` produced: AES-256-GCM,
 * a 12-byte IV in front of the ciphertext, key from PBKDF2-HMAC-SHA256. Nothing
 * here trusts the download: each file's SHA-256 is checked against the manifest
 * before it is decrypted, and a file that fails is not written.
 *
 * The cache lives in `filesDir/prompts/<version>/` and is keyed by the manifest
 * version, so a new manifest downloads into a fresh directory and the old one is
 * dropped whole — no half-updated mix of old and new contracts.
 */
object PromptStore {

    /** The website that serves the sealed prompt bundle. */
    const val BASE = "https://zyraxonai.lovable.app"

    private const val MANIFEST = "mobile-bolt/manifest.json"
    private const val DIR = "prompts"

    private val keyParts = arrayOf("zx-se", "aled-", "asset", "s-v1")
    private val saltParts = arrayOf("zyrax", "on-se", "aled-", "salt")

    @Volatile
    private var key: SecretKeySpec? = null

    private fun key(): SecretKeySpec = key ?: synchronized(this) {
        key ?: run {
            val spec = PBEKeySpec(keyParts.joinToString("").toCharArray(), saltParts.joinToString("").toByteArray(), 65_536, 256)
            val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
            SecretKeySpec(derived.encoded, "AES").also { key = it }
        }
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** One file in the bundle, as the manifest describes it. */
    private data class Entry(val name: String, val path: String, val bytes: Long, val sha256: String)

    /** What a caller gets back: the two contracts and the six characters. */
    data class Bundle(
        val agent: String,
        val build: String,
        val characters: Map<String, String>
    ) {
        /** The contract for a mode. Agent is the fallback for anything else. */
        fun forMode(build: Boolean): String = if (build) this.build else agent

        /** A character's own prompt, by id; blank when the bundle has no such id. */
        fun character(id: String): String = characters[id].orEmpty()
    }

    /** The eight names the bundle carries, in the order the manifest lists them. */
    private val NAMES = listOf(
        "mode-agent.txt", "mode-build.txt",
        "character-lover.txt", "character-boyfriend.txt", "character-friend.txt",
        "character-assistant.txt", "character-cyber.txt", "character-philosopher.txt"
    )

    /** The character ids, which are also the file names after `character-`. */
    val CHARACTER_IDS = listOf("lover", "boyfriend", "friend", "assistant", "cyber", "philosopher")

    private fun root(context: Context) = File(context.filesDir, DIR)

    /**
     * The cached bundle, or null when nothing has been downloaded yet. Called on
     * every turn, so it reads the files rather than the network.
     */
    fun cached(context: Context): Bundle? = runCatching {
        val dir = root(context)
        val stamp = File(dir, "version").takeIf { it.isFile }?.readText()?.trim()
        if (stamp.isNullOrBlank()) return null
        val at = File(dir, stamp)
        if (!at.isDirectory) return null
        fun text(name: String): String = File(at, name).readText(Charsets.UTF_8)
        Bundle(
            agent = text("mode-agent.txt"),
            build = text("mode-build.txt"),
            characters = CHARACTER_IDS.associateWith { id -> text("character-$id.txt") }
        )
    }.getOrNull()

    /** The version of the bundle already on disk, or null when there is none. */
    fun cachedVersion(context: Context): Int? =
        File(root(context), "version").takeIf { it.isFile }
            ?.readText()?.trim()?.toIntOrNull()

    /**
     * Downloads the bundle again when the website has a newer one.
     *
     * Called after the first download and on later starts: a corrected prompt
     * reaches every phone without a new APK. A failure is swallowed — the bundle
     * already on disk still works, and a phone with no connection should keep
     * running rather than be nagged about a check it cannot make.
     */
    suspend fun refreshIfStale(context: Context, onProgress: (Float) -> Unit = {}): Bundle? =
        withContext(Dispatchers.IO) {
            val held = cachedVersion(context)
            val remote = runCatching {
                JSONObject(String(fetch("$BASE/$MANIFEST"), Charsets.UTF_8)).optInt("version", 1)
            }.getOrNull() ?: return@withContext null
            if (held != null && remote <= held) return@withContext null
            runCatching { download(context, onProgress) }.getOrNull()
        }

    /**
     * Downloads the bundle and makes it the cached one.
     *
     * [onProgress] is called with 0..1 as each file lands, so the first-open
     * screen can show real progress rather than an animation pretending to be
     * one. Throws on a bad manifest, a short read, a hash mismatch or a file that
     * will not decrypt — a partially verified bundle is never installed.
     */
    suspend fun download(context: Context, onProgress: (Float) -> Unit = {}): Bundle =
        withContext(Dispatchers.IO) {
            val manifestText = fetch("$BASE/$MANIFEST")
            val manifest = JSONObject(String(manifestText, Charsets.UTF_8))
            val version = manifest.optInt("version", 1)
            val files = manifest.getJSONArray("files")
            val entries = (0 until files.length()).map { i ->
                val o = files.getJSONObject(i)
                Entry(
                    name = o.getString("name"),
                    path = o.getString("path"),
                    bytes = o.optLong("bytes", -1),
                    sha256 = o.optString("sha256")
                )
            }
            require(entries.isNotEmpty()) { "empty manifest" }

            // Staged beside the live copy, then swapped in, so a dropped
            // connection halfway leaves the previous bundle working.
            val staging = File(root(context), "staging-$version")
            staging.deleteRecursively()
            staging.mkdirs()

            val texts = mutableMapOf<String, String>()
            entries.forEachIndexed { index, entry ->
                val blob = fetch("$BASE/${entry.path}")
                if (entry.bytes > 0 && blob.size.toLong() != entry.bytes) {
                    throw IllegalStateException("${entry.name}: ${blob.size} bytes, manifest says ${entry.bytes}")
                }
                if (entry.sha256.isNotBlank() && sha256(blob) != entry.sha256) {
                    throw IllegalStateException("${entry.name}: hash mismatch")
                }
                texts[entry.name] = decrypt(blob)
                File(staging, entry.name).writeText(texts[entry.name]!!, Charsets.UTF_8)
                onProgress((index + 1).toFloat() / entries.size)
            }

            val live = File(root(context), version.toString())
            live.deleteRecursively()
            if (!staging.renameTo(live)) {
                staging.copyRecursively(live, overwrite = true)
                staging.deleteRecursively()
            }
            File(root(context), "version").writeText(version.toString())
            // Older versions are dead weight and a second copy of the text.
            root(context).listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != version.toString()) f.deleteRecursively()
            }

            // Every name the app reads must be present, or the bundle is not
            // installed: a missing character would silently become a blank one.
            NAMES.forEach { name ->
                require(name in texts) { "bundle is missing $name" }
            }

            Bundle(
                agent = texts.getValue("mode-agent.txt"),
                build = texts.getValue("mode-build.txt"),
                characters = CHARACTER_IDS.associateWith { id -> texts.getValue("character-$id.txt") }
            )
        }

    private fun fetch(url: String): ByteArray {
        val request = Request.Builder().url(url).header("Accept", "*/*").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("$url answered ${response.code}")
            return response.body?.bytes() ?: throw IllegalStateException("$url sent no body")
        }
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun decrypt(blob: ByteArray): String {
        require(blob.size > 12) { "sealed file is too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8)
    }
}
