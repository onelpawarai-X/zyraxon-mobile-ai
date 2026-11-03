package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.model.ChatMessage
import ai.zyraxon.assistant.model.MessageRole
import ai.zyraxon.assistant.model.ToolExecutionItem
import ai.zyraxon.assistant.ui.theme.Glass
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    onSpeak: (String) -> Unit,
    onCopy: (String) -> Unit,
    liveTools: List<ToolExecutionItem> = emptyList(),
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER

    val transition = rememberLiveTransition(label = "cursor")
    val cursorAlpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_blink"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp
                    )
                )
                .background(
                    if (isUser) {
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF8B0000), Color(0xFFFF1E44))
                        )
                    } else {
                        Brush.verticalGradient(
                            colors = listOf(Color(0xD9170206), Color(0xCF220409))
                        )
                    }
                )
                // The border beats like a heart: every message is alive.
                .bloodPulseBorder(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp
                    ),
                    if (isUser) 1.4.dp else 1.1.dp
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // The working block sits above the answer. It appears the moment the
            // turn starts — not only when the model happens to emit reasoning — so
            // the user always sees that ZYRAXON is working instead of an empty
            // bubble. When a real chain of thought arrives it shows here too, and
            // the header is tappable so it can be read in full. Never spoken aloud.
            if (!isUser && (message.thinking.isNotBlank() || message.isStreaming)) {
                var expanded by remember(message.id) { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = (if (expanded) "▾ " else "▸ ") + t("ভাবছে"),
                            color = Glass.textMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        // A live ellipsis that fades in and out while the turn runs.
                        // Its blink is the proof of work even when no reasoning
                        // token has arrived yet, which is why it is not gated on
                        // the thinking text any more.
                        Text(
                            text = "…",
                            color = Glass.cyanFill.copy(alpha = if (message.isStreaming) cursorAlpha else 0.6f),
                            fontSize = 12.sp
                        )
                        if (message.isStreaming && message.thinking.isBlank()) {
                            Text(
                                text = t("কাজ করছে"),
                                color = Glass.textMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                    AnimatedVisibility(visible = expanded) {
                        Text(
                            text = message.thinking.ifBlank { t("এখনো ভাবনার বিস্তারিত আসেনি — মডেল সরাসরি উত্তর দিচ্ছে।") },
                            color = Glass.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        )
                    }
                }
            }

            // Live tool rows. Each call the model makes gets a row the moment it
            // starts, with a spinner and the command's own output growing under it
            // as the output arrives, so a long job is visibly working instead of
            // looking like a frozen cursor. Rows disappear when the call ends —
            // the result is kept in the Tools tab.
            if (!isUser && liveTools.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    liveTools.forEach { tool ->
                        LiveToolRow(tool)
                    }
                }
            }

            // Text Body. Selectable, so a word or the whole answer can be picked
            // the way a chat app does it; a long press copies the whole message.
            SelectionContainer {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = message.text,
                        color = if (isUser) Glass.textPrimary else Glass.textPrimary,
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { onCopy(message.text) }
                        )
                    )
                    if (message.isStreaming) {
                        Spacer(modifier = Modifier.width(3.dp))
                        Box(
                            modifier = Modifier
                                .size(width = 3.dp, height = 16.dp)
                                .background(Glass.cyanFill.copy(alpha = cursorAlpha))
                        )
                    }
                }
            }

            // Bottom metadata row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.timestamp,
                    color = if (isUser) Glass.fill(0.30f) else Glass.textMuted,
                    fontSize = 10.5.sp
                )

                if (!isUser && !message.isStreaming) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick = { onSpeak(message.text) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = t("শুনুন"),
                                tint = Glass.cyanFill,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = { onCopy(message.text) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = t("কপি করুন"),
                                tint = Glass.textSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One live tool call: a spinner, the tool's name and a one-line description, and
 * the output it has produced so far, scrollable and growing as the call runs.
 * The whole row is tappable so the output can be read in full.
 */
@Composable
fun LiveToolRow(tool: ToolExecutionItem) {
    var expanded by remember(tool.id) { mutableStateOf(true) }
    val transition = rememberLiveTransition(label = "tool_spin")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tool_spin_angle"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x33000000))
            .border(1.dp, Glass.cyanFill.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // The ring only turns while the call is running; a finished call shows
            // a static dot. The rotation is the "working" signal.
            if (tool.running) {
                androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp)) {
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2f
                    drawArc(
                        color = Color(0xFF38E8FF),
                        startAngle = spin,
                        sweepAngle = 260f,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - stroke, size.height - stroke
                        ),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Glass.cyanFill)
                )
            }
            Text(
                text = tool.icon + " " + tool.name,
                color = Glass.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            if (tool.description.isNotBlank()) {
                Text(
                    text = tool.description,
                    color = Glass.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (expanded && tool.output.isNotBlank()) {
            Text(
                text = tool.output.trimEnd('\n'),
                color = Glass.cyanFill.copy(alpha = 0.9f),
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 12,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun QuickActionChips(
    onChipClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val chips = listOf(
        t("ফেসবুক খোলো"),
        t("ওয়েবসাইট বানাও"),
        t("স্ক্রিন পড়ো"),
        t("ক্যামেরা"),
        t("টর্চ জ্বালাও"),
        t("ইউটিউব"),
        t("কল করো"),
        t("ওয়াইফাই")
    )

    val context = LocalContext.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { chip ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC1A0307))
                    .border(1.dp, Color(0x66FF1E44), RoundedCornerShape(16.dp))
                    .clickable {
                        Glass.vibrate(context, 10)
                        onChipClick(chip)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = chip,
                    color = Color(0xFFFFB3C1),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
