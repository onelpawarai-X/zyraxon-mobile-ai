// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The project's own upload service.
 *
 * This is the last resort, not the first: the public hosts in [MediaUploader] are
 * tried first, and this runs only after every one of them has failed. It exists so
 * a file still gets a link when the public hosts are down or refusing, instead of
 * the user's attachment silently going nowhere.
 *
 * The service takes a file in three steps — reserve an id, send the bytes to a
 * signed URL, then confirm. The confirm is what proves the stored file matches
 * what was promised, so it is not optional: without it the service deletes both
 * the row and the file rather than leave a link that downloads nothing.
 */
object ZyraxonUploader {

    private const val BASE = "https://zyraxonai.lovable.app/api/public/zyraxonai/v1"
    private const val AGENT = "Mozilla/5.0 (Linux; Android) Zyraxon/1.0"

    /** The address an agent calls to remove an upload it has finished reading. */
    fun deleteUrl(id: String): String = "$BASE/upload/$id"

    /** Images are capped lower than everything else by the service. */
    private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    private const val MAX_OTHER_BYTES = 100L * 1024 * 1024

    /**
     * The types the service refuses outright. Checked here so a file that cannot
     * possibly be accepted does not cost a round trip.
     */
    private val FORBIDDEN = setOf("exe", "apk", "sh", "bat", "js", "html", "svg")

    /**
     * The id this phone is counted under, for the service's per-device quota.
     *
     * `ANDROID_ID` is stable for this app on this device and needs no permission.
     * A device that cannot report one still gets a working upload; it simply is
     * not quota-tracked under a stable name.
     */
    fun deviceId(context: Context): String {
        val id = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        return id?.takeIf { it.isNotBlank() } ?: "zyraxon-unknown"
    }

    /** The id and the delete token of an upload, for deleting it later. */
    data class Receipt(val id: String, val deleteToken: String, val url: String)

    /** Uploads [file] and returns its link and delete token, or null if refused. */
    fun upload(file: File, mime: String, deviceId: String): Receipt? {
        if (!file.exists() || file.length() == 0L) return null
        if (!acceptable(file, mime)) return null

        val init = init(deviceId, file.name, mime, file.length()) ?: return null
        val id = init.optString("id").takeIf { it.isNotBlank() } ?: return null
        val uploadUrl = init.optString("upload_url").takeIf { it.isNotBlank() } ?: return null
        val publicUrl = init.optString("url").takeIf { it.isNotBlank() } ?: return null
        val deleteToken = init.optString("delete_token").takeIf { it.isNotBlank() } ?: return null

        // The signed URL carries its own token, so nothing is added to the request
        // beyond the body's content type. It is valid for ten minutes.
        if (!put(uploadUrl, file, mime)) return null
        if (!complete(id)) return null
        return Receipt(id = id, deleteToken = deleteToken ?: "", url = publicUrl)
    }

    /**
     * Removes an upload. Called by the agent once it has read the file, so the
     * user's attachment does not stay on a public link.
     */
    fun delete(id: String, deleteToken: String): Boolean = runCatching {
        if (id.isBlank() || deleteToken.isBlank()) return@runCatching false
        val conn = (URL("$BASE/upload/$id").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("X-Delete-Token", deleteToken)
            setRequestProperty("User-Agent", AGENT)
        }
        try {
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    private fun acceptable(file: File, mime: String): Boolean {
        val ext = file.extension.lowercase()
        if (ext in FORBIDDEN) return false
        val limit = if (mime.startsWith("image")) MAX_IMAGE_BYTES else MAX_OTHER_BYTES
        return file.length() <= limit
    }

    private fun init(deviceId: String, name: String, mime: String, size: Long): JSONObject? {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("name", name)
            .put("mime", mime)
            .put("size", size)
        return runCatching {
            val conn = (URL("$BASE/upload/init").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", AGENT)
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                if (conn.responseCode !in 200..299) return@runCatching null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(text)
                if (!o.optBoolean("ok", false)) null else o
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun put(uploadUrl: String, file: File, mime: String): Boolean = runCatching {
        val conn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 180_000
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Content-Type", mime)
            setRequestProperty("User-Agent", AGENT)
        }
        try {
            conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    private fun complete(id: String): Boolean = runCatching {
        val conn = (URL("$BASE/upload/complete").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 12_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", AGENT)
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("id", id).toString().toByteArray()) }
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}
