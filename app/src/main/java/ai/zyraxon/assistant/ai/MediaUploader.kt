// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ai

import android.content.Context
import android.util.Base64
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Turns a picture, video or audio file on the phone into a public HTTPS link.
 *
 * The cloud agent works in its own machine and cannot read the phone's storage,
 * so a file the user attaches is useless to it until it has an address it can
 * download. The public hosts are tried in turn; the first that answers with a
 * link wins. Only when every one of them has failed does the project's own
 * upload service ([ZyraxonUploader]) run, so a public host being down does not
 * cost the user their attachment. The upload streams from disk, so a long video
 * does not have to fit in memory.
 */
object MediaUploader {

    private const val AGENT = "Mozilla/5.0 (Linux; Android) Zyraxon/1.0"

    /** A file the user attached, as the agent will see it. */
    data class Link(
        val kind: String,
        val name: String,
        val url: String,
        /**
         * Set only for a file held by the project's own service. It lets the
         * agent delete the file once it has read it, so the user's upload does
         * not sit on a public link longer than it has to.
         */
        val id: String? = null,
        val deleteToken: String? = null
    ) {
        val isOwn: Boolean get() = !id.isNullOrBlank() && !deleteToken.isNullOrBlank()
    }

    private val HOSTS = listOf("tmpfiles", "catbox", "0x0")

    fun kindOf(mime: String): String = when {
        mime.startsWith("image") -> "image"
        mime.startsWith("video") -> "video"
        mime.startsWith("audio") -> "audio"
        else -> "file"
    }

    fun extensionOf(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("mp4") -> "mp4"
        mime.contains("webm") -> "webm"
        mime.contains("quicktime") -> "mov"
        mime.contains("mpeg") && mime.startsWith("audio") -> "mp3"
        mime.contains("wav") -> "wav"
        mime.contains("ogg") -> "ogg"
        mime.contains("aac") -> "aac"
        mime.contains("m4a") || mime.contains("mp4a") -> "m4a"
        else -> "bin"
    }

    /** Uploads a `data:` URL (what the picture attachments are held as). */
    fun uploadDataUrl(cacheDir: File, dataUrl: String, context: Context? = null): Link? {
        val mime = dataUrl.substringAfter("data:", "image/jpeg").substringBefore(';').ifBlank { "image/jpeg" }
        val bytes = runCatching { Base64.decode(dataUrl.substringAfter("base64,", ""), Base64.DEFAULT) }
            .getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val file = File(cacheDir, "up_${System.currentTimeMillis()}.${extensionOf(mime)}")
        return try {
            file.writeBytes(bytes)
            uploadFile(file, mime, context)
        } finally {
            runCatching { file.delete() }
        }
    }

    /**
     * Puts [file] somewhere the cloud can download it.
     *
     * The three public hosts are tried first, in order. Only if all of them fail
     * does the project's own service get a turn — the user's attachment must not
     * be lost just because a free host is down.
     */
    fun uploadFile(file: File, mime: String, context: Context? = null): Link? {
        if (!file.exists() || file.length() == 0L) return null
        for (host in HOSTS) {
            val url = runCatching { post(host, file, mime) }.getOrNull()
            if (!url.isNullOrBlank() && url.startsWith("http")) return Link(kindOf(mime), file.name, url)
        }
        val own = runCatching {
            context?.let { ZyraxonUploader.upload(file, mime, ZyraxonUploader.deviceId(it)) }
        }.getOrNull()
        if (own != null && own.url.startsWith("http")) {
            return Link(kindOf(mime), file.name, own.url, own.id, own.deleteToken)
        }
        return null
    }

    private fun post(host: String, file: File, mime: String): String? {
        val (endpoint, field, extra) = when (host) {
            "catbox" -> Triple("https://catbox.moe/user/api.php", "fileToUpload", mapOf("reqtype" to "fileupload"))
            "0x0" -> Triple("https://0x0.st", "file", emptyMap<String, String>())
            else -> Triple("https://tmpfiles.org/api/v1/upload", "file", emptyMap<String, String>())
        }
        val boundary = "----zx" + UUID.randomUUID().toString().replace("-", "")
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            // Short on purpose. A public host that is down or refusing must fail
            // quickly so the next one is tried, and the project's own service gets
            // its turn, instead of the user waiting on a dead host.
            connectTimeout = 6_000
            readTimeout = 45_000
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("User-Agent", AGENT)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                for ((k, v) in extra) {
                    out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
                }
                out.writeBytes(
                    "--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"${file.name}\"\r\n" +
                        "Content-Type: $mime\r\n\r\n"
                )
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (host == "tmpfiles") {
                val link = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.replace("\\/", "/")
                    ?: return null
                // The page address becomes a direct download address.
                return link.replace("http://", "https://").replace("tmpfiles.org/", "tmpfiles.org/dl/")
            }
            return body.lineSequence().firstOrNull { it.startsWith("http") }?.trim()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The paragraph appended to the user's message so the agent can fetch the files.
     *
     * A file held by the project's own service carries its id and delete token
     * here, together with the exact call that removes it. The agent is told to
     * delete it once it has read the file, so the user's upload does not stay on a
     * public link any longer than the task needs. Public-host links have no such
     * token and are simply listed.
     */
    fun block(links: List<Link>): String {
        if (links.isEmpty()) return ""
        return buildString {
            append("\n\n")
            for (l in links) {
                append("📎 ").append(l.kind).append(": ").append(l.url).append('\n')
                if (l.isOwn) {
                    append("   (held by ZYRAXON's own storage — id=").append(l.id)
                        .append(" delete_token=").append(l.deleteToken).append(")\n")
                    append("   After you have read this file, delete it with: DELETE ")
                        .append(ZyraxonUploader.deleteUrl(l.id!!))
                        .append(" and the header X-Delete-Token: ").append(l.deleteToken).append('\n')
                }
            }
        }.trimEnd()
    }
}
