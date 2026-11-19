// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.tools

import ai.zyraxon.assistant.core.safe

import android.content.Context
import ai.zyraxon.assistant.ai.ToolRegistry
import ai.zyraxon.assistant.shell.LinuxRuntime
import ai.zyraxon.assistant.voice.RemoteTtsRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Pictures, speech and video the assistant makes on request.
 *
 * - Pictures come from Pollinations (free, no key).
 * - Speech comes from the app's own website voices, one request per segment, so a
 *   dialogue can use several voices and languages.
 * - Video is real editing with ffmpeg inside ZYRAXON Linux: stills, clips and text
 *   cards are cut, joined, narrated and scored. Nothing is AI-generated footage.
 *
 * Progress goes out through [live] as `@@progress <0-100> <label>` lines; the chat
 * turns them into the percentage bar. The finished file travels back in the result
 * under `media`, which the chat renders with play and share buttons.
 */
object MediaTools {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun ok(summary: String, media: JSONArray?, extra: JSONObject = JSONObject()): JSONObject =
        extra.put("ok", true).put("summary", summary).apply { if (media != null) put("media", media) }

    private fun err(summary: String) = JSONObject().put("ok", false).put("error", summary).put("summary", summary)

    private fun item(kind: String, file: File, title: String = file.name, mime: String): JSONObject =
        JSONObject().put("kind", kind).put("path", file.absolutePath).put("title", title).put("mime", mime)

    fun mediaDir(context: Context, sub: String): File =
        File(ToolRegistry.workspace(context), "media/$sub").apply { mkdirs() }

    private fun safeName(raw: String, fallback: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9_\\-\\u0980-\\u09FF]"), "-").trim('-').take(48).ifBlank { fallback }

    private fun progress(live: ((String) -> Unit)?, pct: Int, label: String) {
        live?.invoke("@@progress ${pct.coerceIn(0, 100)} $label\n")
    }

    private fun download(url: String, out: File): Boolean {
        val req = Request.Builder().url(url).header("User-Agent", "ZYRAXON/1.0").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return false
            val body = r.body ?: return false
            out.parentFile?.mkdirs()
            out.outputStream().use { o -> body.byteStream().copyTo(o) }
        }
        return out.length() > 0
    }

    // ------------------------------------------------------------ pictures ---

    fun image(context: Context, args: JSONObject, live: ((String) -> Unit)?): JSONObject {
        val prompt = args.safe("prompt").trim()
        if (prompt.isEmpty()) return err("prompt দরকার")
        val w = args.optInt("width", 1024).coerceIn(256, 2048)
        val h = args.optInt("height", 1024).coerceIn(256, 2048)
        val seed = if (args.has("seed")) args.optInt("seed") else (System.currentTimeMillis() % 1_000_000).toInt()
        val model = args.safe("model", "flux").ifBlank { "flux" }
        val file = File(mediaDir(context, "images"), safeName(args.safe("name"), "image-$seed") + ".jpg")
        progress(live, 8, "ছবি আঁকা হচ্ছে")
        val url = "https://image.pollinations.ai/prompt/" + URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") +
            "?width=$w&height=$h&seed=$seed&model=$model&nologo=true&enhance=true"
        var done = false
        var lastErr = ""
        for (attempt in 1..3) {
            try {
                progress(live, 10 + attempt * 20, "ছবি আঁকা হচ্ছে ($attempt)")
                if (download(url, file)) { done = true; break }
            } catch (e: Exception) { lastErr = e.message ?: e.toString() }
            Thread.sleep(1500)
        }
        if (!done) return err("ছবি বানানো যায়নি${if (lastErr.isNotBlank()) ": $lastErr" else ""}")
        progress(live, 100, "ছবি তৈরি")
        return ok("ছবি তৈরি হয়েছে: ${file.name}",
            JSONArray().put(item("image", file, prompt.take(60), "image/jpeg")),
            JSONObject().put("path", file.absolutePath))
    }

    // --------------------------------------------------------------- speech ---

    /** The server's own voice list, so the app never hardcodes voices. */
    fun voices(): JSONObject {
        return try {
            val req = Request.Builder().url(RemoteTtsRequest.VOICES_URL).build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) return err("কণ্ঠের তালিকা পাওয়া যায়নি (HTTP ${r.code})")
                JSONObject().put("ok", true).put("summary", "সার্ভারের কণ্ঠ")
                    .put("data", if (body.length > 12000) body.take(12000) else body)
            }
        } catch (e: Exception) { err("কণ্ঠের তালিকা পাওয়া যায়নি: ${e.message}") }
    }

    private fun ttsUrl(text: String, voice: JSONObject?, defaults: JSONObject): String {
        fun pick(k: String): String = voice?.safe(k).orEmpty().ifBlank { defaults.safe(k) }
        val locale = RemoteTtsRequest.localeFor(pick("language").ifBlank { pick("locale") })
        val gender = RemoteTtsRequest.genderFor(pick("gender"))
        val tone = pick("tone").ifBlank { RemoteTtsRequest.toneFor(text) }
        val sb = StringBuilder(RemoteTtsRequest.TTS_URL)
        sb.append("?text=").append(URLEncoder.encode(text, "UTF-8"))
        sb.append("&locale=").append(locale).append("&gender=").append(gender).append("&tone=").append(tone)
        pick("age").takeIf { it.isNotBlank() }?.let { sb.append("&age=").append(URLEncoder.encode(it, "UTF-8")) }
        pick("name").takeIf { it.isNotBlank() }?.let { sb.append("&voice=").append(URLEncoder.encode(it, "UTF-8")) }
        return sb.toString()
    }

    fun audio(context: Context, args: JSONObject, live: ((String) -> Unit)?): JSONObject {
        if (args.safe("action") == "voices") return voices()
        val segs = JSONArray()
        val given = args.optJSONArray("segments")
        if (given != null && given.length() > 0) for (i in 0 until given.length()) segs.put(given.getJSONObject(i))
        else {
            val t = args.safe("text").trim()
            if (t.isEmpty()) return err("text বা segments দরকার")
            segs.put(JSONObject().put("text", t))
        }
        val out = File(mediaDir(context, "audio"),
            safeName(args.safe("name"), "audio-${System.currentTimeMillis() / 1000}") + ".mp3")
        out.delete()
        val total = segs.length()
        try {
            out.outputStream().use { sink ->
                for (i in 0 until total) {
                    val seg = segs.getJSONObject(i)
                    val text = seg.safe("text").trim()
                    if (text.isEmpty()) continue
                    progress(live, 5 + (i * 90) / total, "কণ্ঠ ${i + 1}/$total")
                    // The endpoint speaks a bounded piece at a time; long text is cut at sentence ends.
                    for (chunk in splitSentences(text, 420)) {
                        val url = ttsUrl(chunk, seg.optJSONObject("voice"), args)
                        var ok = false
                        for (attempt in 1..3) {
                            try {
                                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                                    if (r.isSuccessful) { r.body?.byteStream()?.copyTo(sink); ok = true }
                                }
                            } catch (_: Exception) {}
                            if (ok) break
                            Thread.sleep(1200)
                        }
                        if (!ok) return err("অডিও বানানো যায়নি (সার্ভার সাড়া দেয়নি)")
                    }
                }
            }
        } catch (e: Exception) { return err("অডিও বানানো যায়নি: ${e.message}") }
        if (out.length() == 0L) return err("অডিও খালি এসেছে")
        progress(live, 100, "অডিও তৈরি")
        return ok("অডিও তৈরি হয়েছে: ${out.name}",
            JSONArray().put(item("audio", out, out.nameWithoutExtension, "audio/mpeg")),
            JSONObject().put("path", out.absolutePath))
    }

    private fun splitSentences(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (part in text.split(Regex("(?<=[।.!?\\n])\\s*"))) {
            if (cur.length + part.length > max && cur.isNotEmpty()) { out.add(cur.toString().trim()); cur.setLength(0) }
            if (part.length > max) { part.chunked(max).forEach { out.add(it) } } else cur.append(part).append(' ')
        }
        if (cur.isNotBlank()) out.add(cur.toString().trim())
        return out.filter { it.isNotBlank() }
    }

    // ---------------------------------------------------------------- video ---

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Installs ffmpeg in the guest on first use. */
    private fun ensureFfmpeg(context: Context, live: ((String) -> Unit)?): String? {
        if (!LinuxRuntime.isInstalled(context)) {
            if (!LinuxRuntime.isSupported(context)) return "এই ফোনে ZYRAXON Linux চলে না"
            progress(live, 1, "Linux প্রস্তুত হচ্ছে")
            if (!LinuxRuntime.install(context) { live?.invoke(it + "\n") }) return "ZYRAXON Linux ইনস্টল হয়নি"
        }
        val probe = StringBuilder()
        LinuxRuntime.runStreaming(context, "command -v ffmpeg >/dev/null 2>&1 && echo HAVE", { _, t -> probe.append(t) })
        if (probe.contains("HAVE")) return null
        progress(live, 2, "ffmpeg ইনস্টল হচ্ছে (প্রথমবার)")
        val r = LinuxRuntime.runStreaming(context,
            "yes | pkg install -y ffmpeg 2>&1 || (apt-get update 2>&1 && apt-get install -y ffmpeg 2>&1)",
            { _, t -> live?.invoke(t) })
        val again = StringBuilder()
        LinuxRuntime.runStreaming(context, "command -v ffmpeg >/dev/null 2>&1 && echo HAVE", { _, t -> again.append(t) })
        return if (again.contains("HAVE")) null else "ffmpeg ইনস্টল হয়নি${r?.let { " (exit ${it.code})" } ?: ""}"
    }

    /** Runs ffmpeg with `-progress` and maps its out_time to a percentage of [totalSec]. */
    private fun runFfmpeg(context: Context, argsLine: String, totalSec: Double, cwd: String,
                          from: Int, to: Int, label: String, live: ((String) -> Unit)?): Pair<Boolean, String> {
        val log = StringBuilder()
        val cmd = "cd ${q(cwd)} && ffmpeg -y -hide_banner -loglevel error -nostats -progress pipe:1 $argsLine"
        var lastPct = -1
        val r = LinuxRuntime.runStreaming(context, cmd, { _, text ->
            for (line in text.split('\n')) {
                if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                    val us = line.substringAfter('=').trim().toLongOrNull() ?: continue
                    if (totalSec > 0) {
                        val frac = (us / 1_000_000.0 / totalSec).coerceIn(0.0, 1.0)
                        val pct = from + ((to - from) * frac).toInt()
                        if (pct != lastPct) { lastPct = pct; progress(live, pct, label) }
                    }
                } else if (line.isNotBlank() && !line.contains('=')) log.append(line).append('\n')
                else if (line.startsWith("[") || line.contains("Error", true)) log.append(line).append('\n')
            }
        })
        val success = r != null && r.code == 0
        return success to log.toString().takeLast(1500)
    }

    fun ffmpegTool(context: Context, args: JSONObject, live: ((String) -> Unit)?): JSONObject {
        ensureFfmpeg(context, live)?.let { return err(it) }
        val cwd = args.safe("cwd").ifBlank { ToolRegistry.workspace(context).absolutePath }
        val line = args.safe("args").trim()
        if (line.isEmpty()) return err("args দরকার")
        progress(live, 5, "ffmpeg চলছে")
        val out = StringBuilder()
        val r = LinuxRuntime.runStreaming(context, "cd ${q(cwd)} && ffmpeg -y -hide_banner $line 2>&1", { _, t ->
            out.append(t); live?.invoke(t)
        })
        if (r == null || r.code != 0) return err("ffmpeg ব্যর্থ: " + out.toString().takeLast(1200))
        progress(live, 100, "ffmpeg শেষ")
        val outPath = args.safe("output").trim()
        val media = JSONArray()
        if (outPath.isNotEmpty()) {
            val f = if (File(outPath).isAbsolute) File(outPath) else File(cwd, outPath)
            if (f.isFile) {
                val ext = f.extension.lowercase()
                val kind = when (ext) { "mp3", "wav", "m4a", "aac", "ogg" -> "audio"; "png", "jpg", "jpeg", "gif", "webp" -> "image"; else -> "video" }
                media.put(item(kind, f, f.name, when (kind) { "audio" -> "audio/mpeg"; "image" -> "image/$ext"; else -> "video/mp4" }))
            }
        }
        return ok("ffmpeg সম্পন্ন", if (media.length() > 0) media else null)
    }

    fun video(context: Context, args: JSONObject, live: ((String) -> Unit)?): JSONObject {
        val scenes = args.optJSONArray("scenes")
        if (scenes == null || scenes.length() == 0) return err("scenes দরকার")
        ensureFfmpeg(context, live)?.let { return err(it) }
        val w = (args.optInt("width", 1280) / 2) * 2
        val h = (args.optInt("height", 720) / 2) * 2
        val fps = args.optInt("fps", 25).coerceIn(10, 60)
        val name = safeName(args.safe("name").ifBlank { args.safe("title") }, "video-${System.currentTimeMillis() / 1000}")
        val work = File(ToolRegistry.workspace(context), "media/video-work/$name").apply { deleteRecursively(); mkdirs() }
        val outFile = File(mediaDir(context, "video"), "$name.mp4")
        val n = scenes.length()
        val clips = mutableListOf<String>()
        var totalDur = 0.0
        val voiceDefaults = args.optJSONObject("voice") ?: JSONObject()

        for (i in 0 until n) {
            val sc = scenes.getJSONObject(i)
            val base = 5 + (i * 75) / n
            val next = 5 + ((i + 1) * 75) / n
            progress(live, base, "দৃশ্য ${i + 1}/$n")
            val type = sc.safe("type", "image").lowercase()
            var dur = sc.optDouble("duration", 4.0).coerceIn(0.5, 600.0)
            val clip = File(work, "clip$i.mp4")

            // Narration first, because it can set the scene's length.
            var narr: File? = null
            val narrText = sc.safe("narration").trim()
            if (narrText.isNotEmpty()) {
                val res = audio(context, JSONObject().put("text", narrText).put("name", "$name-n$i")
                    .put("language", voiceDefaults.safe("language")).put("gender", voiceDefaults.safe("gender"))
                    .put("age", voiceDefaults.safe("age")).put("tone", voiceDefaults.safe("tone"))
                    .apply { sc.optJSONObject("voice")?.let { put("segments", JSONArray().put(JSONObject().put("text", narrText).put("voice", it))) } }, null)
                if (res.optBoolean("ok")) {
                    narr = File(res.safe("path"))
                    val d = probeDuration(context, narr.absolutePath)
                    if (d > dur) dur = d + 0.3
                }
            }
            totalDur += dur

            var src = sc.safe("source").trim()
            // An image source that is not a file or link is a prompt: draw it.
            if (type == "image" && src.isNotEmpty() && !src.startsWith("/") && !src.startsWith("http")) {
                val gen = image(context, JSONObject().put("prompt", src).put("width", w).put("height", h).put("name", "$name-s$i"), null)
                if (gen.optBoolean("ok")) src = gen.safe("path")
            }
            if (src.startsWith("http")) {
                val ext = src.substringAfterLast('.', "bin").substringBefore('?').take(4)
                val f = File(work, "src$i.$ext")
                try { if (download(src, f)) src = f.absolutePath } catch (_: Exception) {}
            }
            val text = sc.safe("text").ifBlank { sc.safe("caption") }
            val draw = if (text.isNotBlank()) {
                val tf = File(work, "t$i.txt").apply { writeText(text) }
                ",drawtext=textfile=${tf.absolutePath}:fontcolor=white:fontsize=${h / 16}:box=1:boxcolor=black@0.55:boxborderw=18:x=(w-text_w)/2:y=h-text_h-${h / 10}"
            } else ""
            val scale = "scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=$fps,format=yuv420p"
            val audioIn = if (narr != null) "-i ${q(narr.absolutePath)}" else "-f lavfi -i anullsrc=r=44100:cl=stereo"
            val args1 = when {
                (type == "image") && src.isNotEmpty() ->
                    "-loop 1 -t $dur -i ${q(src)} $audioIn -vf \"$scale,zoompan=z='min(zoom+0.0008,1.12)':d=${(dur * fps).toInt()}:s=${w}x$h:fps=$fps$draw\" -t $dur -shortest -c:v libx264 -preset veryfast -crf 23 -c:a aac -ar 44100 -ac 2 ${q(clip.absolutePath)}"
                type == "video" && src.isNotEmpty() ->
                    "-i ${q(src)} $audioIn -t $dur -vf \"$scale$draw\" -map 0:v:0 -map ${if (narr != null) "1:a:0" else "0:a:0?"} -c:v libx264 -preset veryfast -crf 23 -c:a aac -ar 44100 -ac 2 ${q(clip.absolutePath)}"
                else -> {
                    val color = sc.safe("color", "0x101018").ifBlank { "0x101018" }
                    "-f lavfi -i color=c=$color:s=${w}x$h:r=$fps:d=$dur $audioIn -t $dur -vf \"format=yuv420p$draw\" -shortest -c:v libx264 -preset veryfast -crf 23 -c:a aac -ar 44100 -ac 2 ${q(clip.absolutePath)}"
                }
            }
            val (okc, elog) = runFfmpeg(context, args1, dur, work.absolutePath, base, next, "দৃশ্য ${i + 1}/$n", live)
            if (!okc || !clip.isFile) return err("দৃশ্য ${i + 1} বানানো যায়নি: $elog")
            clips.add(clip.name)
        }

        progress(live, 82, "দৃশ্য জোড়া লাগছে")
        File(work, "list.txt").writeText(clips.joinToString("\n") { "file '$it'" })
        val joined = File(work, "joined.mp4")
        val (okj, jlog) = runFfmpeg(context, "-f concat -safe 0 -i list.txt -c copy ${q(joined.absolutePath)}", totalDur, work.absolutePath, 82, 90, "জোড়া", live)
        if (!okj) return err("জোড়া লাগানো যায়নি: $jlog")

        var finalSrc = joined
        val music = args.safe("music").trim()
        if (music.isNotEmpty()) {
            progress(live, 91, "মিউজিক মেশানো হচ্ছে")
            var m = music
            if (m.startsWith("http")) {
                val f = File(work, "music.mp3")
                try { if (download(m, f)) m = f.absolutePath } catch (_: Exception) {}
            }
            val mixed = File(work, "mixed.mp4")
            val (okm, mlog) = runFfmpeg(context,
                "-i ${q(joined.absolutePath)} -stream_loop -1 -i ${q(m)} -filter_complex \"[1:a]volume=0.18[bg];[0:a][bg]amix=inputs=2:duration=first[a]\" -map 0:v -map \"[a]\" -c:v copy -c:a aac ${q(mixed.absolutePath)}",
                totalDur, work.absolutePath, 91, 97, "মিউজিক", live)
            if (okm && mixed.isFile) finalSrc = mixed else live?.invoke("music skipped: $mlog\n")
        }
        finalSrc.copyTo(outFile, overwrite = true)
        work.deleteRecursively()
        progress(live, 100, "ভিডিও তৈরি")
        val mins = (totalDur / 60.0)
        return ok("ভিডিও তৈরি: ${outFile.name} (${"%.1f".format(mins)} মিনিট)",
            JSONArray().put(item("video", outFile, args.safe("title").ifBlank { outFile.nameWithoutExtension }, "video/mp4")),
            JSONObject().put("path", outFile.absolutePath).put("duration_sec", totalDur))
    }

    private fun probeDuration(context: Context, path: String): Double {
        val sb = StringBuilder()
        LinuxRuntime.runStreaming(context,
            "ffprobe -v error -show_entries format=duration -of csv=p=0 ${q(path)} 2>/dev/null", { _, t -> sb.append(t) })
        return sb.toString().trim().lineSequence().firstOrNull()?.toDoubleOrNull() ?: 0.0
    }
}
