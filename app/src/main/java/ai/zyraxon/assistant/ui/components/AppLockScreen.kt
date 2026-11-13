// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.security.AppLockStore
import ai.zyraxon.assistant.ui.icons.AppIcon
import ai.zyraxon.assistant.ui.icons.AppIcons
import ai.zyraxon.assistant.ui.theme.Glass
import ai.zyraxon.assistant.viewmodel.ZyraxonViewModel
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The cover shown over the app while it is locked.
 *
 * It asks for the secret the user set inside ZYRAXON — a password or a pattern —
 * and nothing else. The phone's own credential is deliberately not used: asking
 * for it opens a system screen, which must come to the foreground, and that is
 * what used to pull the app back to the front while the agent was working in
 * another app. A secret this app owns is asked for on this screen, so the cover
 * can be raised without ever taking the foreground from anything.
 *
 * There is no way past it except the right answer: back is swallowed, and there
 * is no shortcut off the cover.
 */
@Composable
fun AppLockScreen(vm: ZyraxonViewModel) {
    val context = LocalContext.current
    val mode by vm.appLockMode.collectAsState()

    // The back gesture must not lift the cover. There is nothing behind it to
    // return to.
    BackHandler(enabled = true) { }

    LockChrome(
        title = t("ZYRAXON লক করা আছে"),
        subtitle = if (mode == AppLockStore.MODE_PATTERN) {
            t("আপনার প্যাটার্ন টেনে আনলক করুন।")
        } else {
            t("আপনার পাসওয়ার্ড দিয়ে আনলক করুন।")
        },
        icon = AppIcons.lock
    ) {
        if (mode == AppLockStore.MODE_PATTERN) {
            PatternPad(
                onComplete = { drawn ->
                    val ok = vm.verifyAppLock(drawn)
                    if (ok) vm.unlockApp() else Glass.vibrate(context, 26)
                    ok
                }
            )
        } else {
            PasswordPad(
                confirmLabel = t("আনলক করো"),
                onComplete = { typed ->
                    val ok = vm.verifyAppLock(typed)
                    if (ok) vm.unlockApp() else Glass.vibrate(context, 26)
                    ok
                }
            )
        }
    }
}

/**
 * The screen that collects a new password or pattern, opened from settings.
 *
 * It asks twice — the secret, then the same secret again — because a lock set
 * with a typo is a lock the owner cannot open, and there is no reset to fall
 * back on: the secret is not stored anywhere it could be recovered from.
 */
@Composable
fun AppLockSetup(
    kind: String,
    onCancel: () -> Unit,
    onDone: (String) -> Unit
) {
    val context = LocalContext.current
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }

    val confirming = first != null
    val isPattern = kind == AppLockStore.MODE_PATTERN

    val edge by animateFloatAsState(
        targetValue = if (mismatch) 1f else 0f,
        animationSpec = tween(220),
        label = "setupEdge"
    )

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Glass.fill(0.35f))
            .border(
                (1f + edge).dp,
                Glass.pinkFill.copy(alpha = 0.30f + 0.45f * edge),
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(
                if (isPattern) AppIcons.gesture else AppIcons.password,
                null, size = 18.dp, tint = Glass.pink
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isPattern) t("নতুন প্যাটার্ন") else t("নতুন পাসওয়ার্ড"),
                color = Glass.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold
            )
        }
        Text(
            when {
                mismatch -> t("দুইবার একই দিতে হবে — মিলল না, আবার চেষ্টা করুন।")
                confirming -> t("একইটি আরেকবার দিন, নিশ্চিত করার জন্য।")
                isPattern -> t("কমপক্ষে চারটি বিন্দু জুড়ে একটি প্যাটার্ন আঁকুন।")
                else -> t("৪ থেকে ৮ সংখ্যার একটি পাসওয়ার্ড দিন।")
            },
            color = if (mismatch) Glass.pinkFill else Glass.textMuted,
            fontSize = 11.5.sp, lineHeight = 17.sp, textAlign = TextAlign.Center
        )
        if (isPattern) {
            PatternPad(
                onComplete = { drawn ->
                    val seen = first
                    when {
                        seen == null -> { first = drawn; true }
                        seen == drawn -> { onDone(drawn); true }
                        else -> {
                            mismatch = true; first = null
                            Glass.vibrate(context, 26); false
                        }
                    }
                }
            )
        } else {
            PasswordPad(
                confirmLabel = if (confirming) t("নিশ্চিত করুন") else t("পরবর্তী"),
                onComplete = { typed ->
                    val seen = first
                    when {
                        seen == null -> { first = typed; true }
                        seen == typed -> { onDone(typed); true }
                        else -> {
                            mismatch = true; first = null
                            Glass.vibrate(context, 26); false
                        }
                    }
                }
            )
        }
        TextButton(onClick = onCancel) {
            AppIcon(AppIcons.close, null, size = 14.dp, tint = Glass.textMuted)
            Spacer(Modifier.width(5.dp))
            Text(t("বাতিল"), color = Glass.textMuted, fontSize = 12.5.sp)
        }
    }
}

/**
 * One choice card on the lock settings: the password pad, or the pattern grid.
 * [selected] draws the ring, so the user can see which kind is in force without
 * reading the description.
 */
@Composable
fun LockKindCard(
    icon: ImageVector,
    title: String,
    desc: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Glass.pinkFill.copy(alpha = 0.16f) else Glass.fill(0.28f))
            .border(
                (if (selected) 1.5f else 1f).dp,
                if (selected) Glass.pinkFill else Glass.borderSoft,
                RoundedCornerShape(14.dp)
            )
            .pointerInput(title) { detectTapGestures { onClick() } }
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AppIcon(icon, null, size = 26.dp, tint = if (selected) Glass.pink else Glass.textSecondary)
        Text(title, color = Glass.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(desc, color = Glass.textMuted, fontSize = 10.5.sp, textAlign = TextAlign.Center)
        if (selected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(AppIcons.check, null, size = 12.dp, tint = Glass.pink)
                Spacer(Modifier.width(4.dp))
                Text(t("সেট আছে"), color = Glass.pink, fontSize = 10.5.sp)
            }
        }
    }
}

/**
 * The shared frame: a dark field, one centred card, an icon, a title and a line
 * of guidance. [error] tints the card's edge and the subtitle, so a wrong answer
 * is felt as well as read.
 */
@Composable
private fun LockChrome(
    title: String,
    subtitle: String,
    icon: ImageVector,
    error: Boolean = false,
    content: @Composable () -> Unit
) {
    val edge by animateFloatAsState(
        targetValue = if (error) 1f else 0f,
        animationSpec = tween(220),
        label = "lockEdge"
    )

    Box(
        Modifier.fillMaxSize().background(Color(0xFF07070A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(Glass.fill(0.92f))
                .border(
                    (1f + edge).dp,
                    Glass.pinkFill.copy(alpha = 0.35f + 0.45f * edge),
                    RoundedCornerShape(24.dp)
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .background(Glass.pinkFill.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(icon, null, size = 32.dp, tint = Glass.pink)
            }
            Text(title, color = Glass.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = if (error) Glass.pinkFill else Glass.textMuted,
                fontSize = 12.5.sp, lineHeight = 18.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/**
 * A numeric password pad. Digits fill a row of dots; the filled count is the
 * only feedback the pad gives, and it never shows more than the maximum, so the
 * exact length of a shorter secret is not advertised.
 */
@Composable
private fun PasswordPad(confirmLabel: String?, onComplete: (String) -> Boolean) {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    val min = AppLockStore.PASSWORD_MIN
    val max = AppLockStore.PASSWORD_MAX

    fun submit() {
        if (typed.length < min) return
        val accepted = onComplete(typed)
        typed = ""
        if (!accepted) Glass.vibrate(context, 26)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(max) { i ->
                val filled = i < typed.length
                Box(
                    Modifier.size(if (filled) 13.dp else 11.dp).clip(CircleShape)
                        .background(if (filled) Glass.pinkFill else Color.Transparent)
                        .border(1.5.dp, if (filled) Glass.pinkFill else Glass.borderSoft, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9").chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    PadKey(key) {
                        if (typed.length < max) {
                            typed += key
                            Glass.vibrate(context, 10)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PadKey("⌫", subtle = true) {
                if (typed.isNotEmpty()) typed = typed.dropLast(1)
            }
            PadKey("0") {
                if (typed.length < max) {
                    typed += "0"
                    Glass.vibrate(context, 10)
                }
            }
            PadKey("✓", accent = true, enabled = typed.length >= min) { submit() }
        }
        if (confirmLabel != null) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { submit() },
                enabled = typed.length >= min,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Glass.pinkFill,
                    disabledContainerColor = Glass.pinkFill.copy(alpha = 0.30f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                AppIcon(AppIcons.lockOpen, null, size = 16.dp, tint = Glass.textOnAccent)
                Spacer(Modifier.width(7.dp))
                Text(confirmLabel, color = Glass.textOnAccent, fontSize = 14.sp)
            }
        }
    }
}

/** One round key on the pad. */
@Composable
private fun PadKey(
    label: String,
    subtle: Boolean = false,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val fill = when {
        accent -> Glass.pinkFill.copy(alpha = if (enabled) 1f else 0.30f)
        subtle -> Glass.fill(0.16f)
        else -> Glass.fill(0.26f)
    }
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(fill)
            .border(1.dp, Glass.borderSoft, CircleShape)
            .pointerInput(label, enabled) {
                if (enabled) detectTapGestures { onClick() }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (accent) Glass.textOnAccent else Glass.textPrimary,
            fontSize = if (label.length > 1) 16.sp else 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * A 3×3 pattern grid. A drag joins the dots it passes over, in order; lifting the
 * finger submits. The joins are drawn as they are made, so the user sees exactly
 * the pattern they are entering.
 */
@Composable
private fun PatternPad(onComplete: (String) -> Boolean) {
    val context = LocalContext.current
    val selected = remember { mutableStateListOf<Int>() }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    val dots = 3

    fun indexAt(position: Offset, side: Float): Int {
        val cell = side / dots
        val col = (position.x / cell).toInt().coerceIn(0, dots - 1)
        val row = (position.y / cell).toInt().coerceIn(0, dots - 1)
        val center = Offset((col + 0.5f) * cell, (row + 0.5f) * cell)
        return if ((position - center).getDistance() <= cell * 0.42f) row * dots + col else -1
    }

    fun submit() {
        if (selected.size < AppLockStore.PATTERN_MIN_DOTS) {
            selected.clear(); cursor = null; return
        }
        val accepted = onComplete(selected.joinToString("-"))
        selected.clear()
        cursor = null
        if (!accepted) Glass.vibrate(context, 26)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth(0.70f).aspectRatio(1f)
                .pointerInput(Unit) {
                    val side = size.width.toFloat()
                    detectDragGestures(
                        onDragStart = { start ->
                            selected.clear()
                            cursor = start
                            val hit = indexAt(start, side)
                            if (hit >= 0) selected.add(hit)
                        },
                        onDragEnd = { submit() },
                        onDragCancel = { selected.clear(); cursor = null },
                        onDrag = { change, _ ->
                            cursor = change.position
                            val hit = indexAt(change.position, side)
                            if (hit >= 0 && !selected.contains(hit)) selected.add(hit)
                        }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cell = size.width / dots
                val reach = cell * 0.42f
                val centers = (0 until dots).flatMap { r ->
                    (0 until dots).map { c -> Offset((c + 0.5f) * cell, (r + 0.5f) * cell) }
                }
                // Joins first, so the dots sit on top of their own lines.
                for (i in 0 until selected.size - 1) {
                    drawLine(
                        color = Glass.pinkFill,
                        start = centers[selected[i]],
                        end = centers[selected[i + 1]],
                        strokeWidth = cell * 0.07f,
                        cap = StrokeCap.Round
                    )
                }
                val last = selected.lastOrNull()
                val at = cursor
                if (last != null && at != null) {
                    drawLine(
                        color = Glass.pinkFill.copy(alpha = 0.55f),
                        start = centers[last],
                        end = at,
                        strokeWidth = cell * 0.07f,
                        cap = StrokeCap.Round
                    )
                }
                centers.forEachIndexed { i, center ->
                    val on = selected.contains(i)
                    if (on) {
                        drawCircle(color = Glass.pinkFill, radius = reach, center = center, style = Fill)
                        drawCircle(
                            color = Glass.textOnAccent,
                            radius = reach * 0.34f,
                            center = center,
                            style = Fill
                        )
                    } else {
                        drawCircle(
                            color = Glass.borderSoft,
                            radius = reach,
                            center = center,
                            style = Stroke(width = cell * 0.05f)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            t("বিন্দুগুলো টেনে জোড়া লাগান — আঙুল ছাড়লেই জমা হবে।"),
            color = Glass.textMuted, fontSize = 11.5.sp, textAlign = TextAlign.Center
        )
    }
}
