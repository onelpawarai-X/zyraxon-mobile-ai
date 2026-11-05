package ai.zyraxon.assistant.ui.shell

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.chat.LiveCode
import ai.zyraxon.assistant.model.ChatMessage
import ai.zyraxon.assistant.model.MediaItem
import ai.zyraxon.assistant.model.MediaJob
import ai.zyraxon.assistant.model.MessageRole
import ai.zyraxon.assistant.model.ToolCallRecord
import ai.zyraxon.assistant.ui.theme.Glass
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SHELL_TOOLS = setOf("run_shell", "system_shell", "linux_run", "terminal_run", "background_command", "ffmpeg", "wait")

internal fun clockOf(ms: Long): String = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ms))

internal fun durationOf(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

internal fun langOf(path: String): String = path.substringAfterLast('.', "").lowercase()

internal fun mimeOf(path: String): String = when (langOf(path)) {
    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "m4a" -> "audio/mp4"; "ogg" -> "audio/ogg"
    "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"
    "html", "htm" -> "text/html"; "txt", "md", "json", "js", "css", "kt", "py" -> "text/plain"
    "apk" -> "application/vnd.android.package-archive"; "zip" -> "application/zip"
    else -> "*/*"
}

/** Every app the user can share to. A named app is tried first; otherwise the system chooser opens. */
internal enum class ShareTarget(val label: String, val pkg: String) {
    WHATSAPP("WhatsApp", "com.whatsapp"),
    TIKTOK("TikTok", "com.zhiliaoapp.musically"),
    YOUTUBE("YouTube", "com.google.android.youtube"),
    FACEBOOK("Facebook", "com.facebook.katana"),
    TELEGRAM("Telegram", "org.telegram.messenger"),
    MESSENGER("Messenger", "com.facebook.orca"),
    OTHER(t("অন্য অ্যাপ…"), "")
}

internal fun share(context: Context, target: ShareTarget, text: String? = null, path: String? = null) {
    val send = Intent(Intent.ACTION_SEND)
    if (path != null) {
        val f = File(path)
        val uri = try { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f) } catch (_: Exception) { null }
        if (uri == null) {
            android.widget.Toast.makeText(context, t("ফাইলটি শেয়ার করা গেল না"), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        send.type = mimeOf(path)
        send.putExtra(Intent.EXTRA_STREAM, uri)
        if (text != null) send.putExtra(Intent.EXTRA_TEXT, text)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, text.orEmpty())
    }
    val installed = target.pkg.isNotEmpty() && try { context.packageManager.getPackageInfo(target.pkg, 0); true } catch (_: Exception) { false }
    try {
        if (installed) {
            context.startActivity(send.setPackage(target.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            context.startActivity(Intent.createChooser(send, t("শেয়ার করো")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, t("শেয়ার করা গেল না: {0}", e.message), android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** The share button: a small menu of apps. */
@Composable
internal fun ShareButton(text: String? = null, path: String? = null, label: String = t("↗ শেয়ার")) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            label, color = Glass.textMuted, fontSize = 11.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 7.dp, vertical = 4.dp)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Glass.sheetFill(0.9f)) {
            ShareTarget.values().forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.label, color = Glass.textPrimary, fontSize = 13.sp) },
                    onClick = { open = false; share(ctx, t, text, path) }
                )
            }
        }
    }
}

/** A thin row of actions under a message: copy, speak, share. */
@Composable
private fun MessageActions(message: ChatMessage, onCopy: () -> Unit, onSpeak: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(t("⧉ কপি"), color = Glass.textMuted, fontSize = 11.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onCopy() }.padding(horizontal = 7.dp, vertical = 4.dp))
        if (message.role == MessageRole.ASSISTANT) {
            Text(t("🔊 শোনো"), color = Glass.textMuted, fontSize = 11.5.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSpeak() }.padding(horizontal = 7.dp, vertical = 4.dp))
        }
        ShareButton(text = message.text)
    }
}

@Composable
private fun TimeLine(message: ChatMessage, streaming: Boolean, startedAt: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(streaming) { while (streaming) { now = System.currentTimeMillis(); delay(500) } }
    val took = if (streaming) now - startedAt else message.durationMs
    Text(
        buildString {
            append(clockOf(message.createdAt))
            if (message.role == MessageRole.ASSISTANT) {
                append(" · ").append(message.modelName)
                if (took > 0) append(" · ⏱ ").append(durationOf(took))
            }
        },
        color = Glass.textMuted, fontSize = 10.5.sp
    )
}

@Composable
fun MessageItem(
    message: ChatMessage,
    isLastStreaming: Boolean,
    liveCode: LiveCode?,
    jobs: List<MediaJob>,
    turnStartedAt: Long,
    onCopy: () -> Unit,
    onSpeak: () -> Unit
) {
    val isUser = message.role == MessageRole.USER
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        if (isUser) {
            Column(
                Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(Glass.pinkFill.copy(alpha = if (Glass.isLight) 0.14f else 0.28f))
                    .border(1.dp, Glass.pink.copy(alpha = 0.45f), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .padding(horizontal = 13.dp, vertical = 9.dp)
            ) {
                Text(message.text, color = Glass.textPrimary, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeLine(message, false, 0)
                ShareButton(text = message.text, label = "↗")
            }
            return@Column
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(Glass.fill(0.5f))
                .border(1.dp, Glass.borderSoft, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (message.thinking.isNotBlank()) ThinkingBlock(message.thinking, message.isStreaming && message.text.isBlank())
            if (message.text.isNotBlank()) {
                MarkdownText(message.text)
            } else if (message.isStreaming && message.toolCalls.isEmpty() && liveCode == null && message.thinking.isBlank()) {
                TypingDots()
            }
            message.toolCalls.forEach { ToolCard(it) }
            if (isLastStreaming && liveCode != null && !liveCode.done) {
                CodeBox(liveCode.text, langOf(liveCode.path), liveCode.path.ifBlank { t("নতুন ফাইল") }, live = true)
            }
            jobs.filter { !it.done }.forEach { MediaProgress(it) }
            message.media.forEach { MediaView(it) }
            if (isLastStreaming) {
                // Nothing under a bubble that is still being written.
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TimeLine(message, isLastStreaming, turnStartedAt)
            if (!message.isStreaming) MessageActions(message, onCopy, onSpeak)
        }
    }
}

@Composable
private fun TypingDots() {
    var step by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(350); step = (step + 1) % 4 } }
    Text("●".repeat(step.coerceAtLeast(1)), color = Glass.pink, fontSize = 12.sp)
}

@Composable
private fun ThinkingBlock(text: String, live: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Glass.rowFill(0.25f))
            .clickable { open = !open }.padding(horizontal = 10.dp, vertical = 6.dp).animateContentSize()
    ) {
        Text(if (live) t("💭 ভাবছে…") else if (open) t("💭 ভাবনা ▾") else t("💭 ভাবনা ▸"), color = Glass.textMuted, fontSize = 11.5.sp)
        if (open || live) Text(text.takeLast(if (open) 4000 else 280), color = Glass.textMuted, fontSize = 11.5.sp, lineHeight = 16.sp)
    }
}

@Composable
fun ToolCard(rec: ToolCallRecord) {
    val running = rec.status == "running"
    val ok = rec.status != "error"
    when {
        rec.name == "edit_file" && rec.output.isNotBlank() -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CodeBox(rec.output, langOf(rec.detail), rec.detail.ifBlank { t("ফাইল") }, live = false)
                Text(
                    (if (ok) "✅ " else "⚠️ ") + rec.summary, color = if (ok) Glass.textMuted else Glass.red, fontSize = 11.sp
                )
            }
        }
        rec.name in SHELL_TOOLS -> TerminalBox(rec.detail.ifBlank { rec.name }, rec.output, running, ok)
        else -> {
            var open by remember { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Glass.rowFill(0.3f))
                    .border(1.dp, if (running) Glass.pink.copy(alpha = 0.6f) else Glass.borderSoft, RoundedCornerShape(12.dp))
                    .clickable { open = !open }.padding(10.dp).animateContentSize()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rec.icon, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(rec.name, color = Glass.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                        val sub = rec.summary.ifBlank { rec.detail }
                        if (sub.isNotBlank()) Text(sub, color = Glass.textMuted, fontSize = 11.5.sp, maxLines = if (open) 6 else 1)
                    }
                    when {
                        running -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Glass.pink)
                        ok -> Text("✓", color = Glass.green, fontWeight = FontWeight.Bold)
                        else -> Text("✕", color = Glass.red, fontWeight = FontWeight.Bold)
                    }
                    if (rec.ms > 0) Text("  " + durationOf(rec.ms), color = Glass.textMuted, fontSize = 10.sp)
                }
                if (open && rec.output.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(stripAnsi(rec.output.takeLast(3000)), color = Glass.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun MediaProgress(job: MediaJob) {
    val pct by animateFloatAsState(job.percent / 100f, label = "p")
    val icon = when (job.kind) { "image" -> "🖼️"; "audio" -> "🎧"; else -> "🎬" }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass.rowFill(0.3f))
            .border(1.dp, Glass.pink.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (job.kind) { "image" -> t("ছবি তৈরি হচ্ছে"); "audio" -> t("অডিও তৈরি হচ্ছে"); else -> t("ভিডিও তৈরি হচ্ছে") },
                    color = Glass.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
                Text(job.label.take(80), color = Glass.textMuted, fontSize = 11.sp, maxLines = 1)
            }
            Text("${job.percent}%", color = Glass.pink, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { pct }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = Glass.pink, trackColor = Glass.rowFill(0.5f)
        )
    }
}

@Composable
private fun animateFloatAsState(target: Float, label: String): State<Float> =
    androidx.compose.animation.core.animateFloatAsState(target, androidx.compose.animation.core.tween(400), label = label)

@Composable
fun MediaView(item: MediaItem) {
    val file = remember(item.path) { File(item.path) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass.rowFill(0.25f))
            .border(1.dp, Glass.borderSoft, RoundedCornerShape(14.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (item.kind) {
            "image" -> {
                var big by remember { mutableStateOf(false) }
                coil.compose.AsyncImage(
                    model = file, contentDescription = item.title,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).clip(RoundedCornerShape(10.dp)).clickable { big = true },
                    contentScale = ContentScale.Fit
                )
                if (big) Dialog(onDismissRequest = { big = false }) {
                    coil.compose.AsyncImage(model = file, contentDescription = null,
                        modifier = Modifier.fillMaxWidth().clickable { big = false }, contentScale = ContentScale.Fit)
                }
            }
            "audio" -> AudioPlayer(item.path, item.title)
            "video" -> VideoPlayer(item.path)
            else -> Text("📎 ${file.name}", color = Glass.textPrimary, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.title.ifBlank { file.name }, color = Glass.textMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
            ShareButton(path = item.path)
        }
    }
}

@Composable
private fun AudioPlayer(path: String, title: String) {
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0f) }
    val player = remember(path) {
        try { MediaPlayer().apply { setDataSource(path); prepare() } } catch (_: Exception) { null }
    }
    DisposableEffect(path) { onDispose { try { player?.release() } catch (_: Exception) {} } }
    LaunchedEffect(playing) {
        while (playing && player != null) {
            pos = if (player.duration > 0) player.currentPosition / player.duration.toFloat() else 0f
            if (!player.isPlaying) { playing = false; pos = 0f }
            delay(200)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(Glass.pinkFill).clickable {
                if (player == null) return@clickable
                if (playing) { player.pause(); playing = false } else { player.start(); playing = true }
            }, contentAlignment = Alignment.Center
        ) { Text(if (playing) "❚❚" else "▶", color = Color.White, fontSize = 15.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title.ifBlank { t("অডিও") }, color = Glass.textPrimary, fontSize = 12.5.sp, maxLines = 1)
            LinearProgressIndicator(progress = { pos }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = Glass.pink, trackColor = Glass.rowFill(0.5f))
        }
        if (player != null) Text(" " + durationOf(player.duration.toLong()), color = Glass.textMuted, fontSize = 10.5.sp)
    }
}

@Composable
private fun VideoPlayer(path: String) {
    AndroidView(
        factory = { c ->
            android.widget.VideoView(c).apply {
                setVideoPath(path)
                val mc = android.widget.MediaController(c)
                mc.setAnchorView(this)
                setMediaController(mc)
                setOnPreparedListener { seekTo(1) }
            }
        },
        modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(10.dp))
    )
}
