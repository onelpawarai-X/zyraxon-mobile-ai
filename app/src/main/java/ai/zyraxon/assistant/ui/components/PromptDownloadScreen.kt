// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.ui.theme.Glass
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** How many files the bundle holds: two contracts and six characters. */
private const val FILES = 8

/**
 * The first open after install.
 *
 * The prompt bundle no longer ships in the APK, so the app has nothing to answer
 * with until it has been fetched. This screen is that fetch: [progress] is the
 * real fraction of the bundle that has arrived, not a timer, and the app only
 * starts once it reaches 1.
 *
 * The artwork is the download: one orbit node per file lights as its share of the
 * bundle lands, the tick ring fills, and the core brightens. Everything is drawn
 * from [Glass], so the same screen is blood red on the horror theme, violet on
 * dark and ink on light — it is the app's first impression, so it follows the
 * app's look instead of imposing one.
 *
 * A failure is shown rather than hidden — a user with no connection needs to know
 * why the app is not starting, and to be able to try again instead of watching a
 * spinner that never ends.
 */
@Composable
fun PromptDownloadScreen(
    progress: Float,
    error: String?,
    onRetry: () -> Unit
) {
    val target = progress.coerceIn(0f, 1f)
    // Eased, so a jump between two files glides instead of snapping.
    val p by animateFloatAsState(target, tween(560, easing = FastOutSlowInEasing), label = "promptProgress")

    val clock = rememberInfiniteTransition(label = "promptDownload")
    val spin by clock.animateFloat(
        0f, 360f, infiniteRepeatable(tween(11_000, easing = LinearEasing)), label = "spin"
    )
    val counterSpin by clock.animateFloat(
        0f, -360f, infiniteRepeatable(tween(17_000, easing = LinearEasing)), label = "counterSpin"
    )
    val pulse by clock.animateFloat(
        0.80f, 1f,
        infiniteRepeatable(tween(1_700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse"
    )
    val drift by clock.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3_200, easing = LinearEasing)), label = "drift"
    )
    val shimmer by clock.animateFloat(
        -0.3f, 1.3f, infiniteRepeatable(tween(2_100, easing = LinearEasing)), label = "shimmer"
    )

    val accent = Glass.pink
    val second = Glass.cyan
    val third = Glass.violet
    val ink = Glass.textPrimary
    val muted = Glass.textMuted
    val failed = error != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Glass.screen, Glass.screen2, Glass.screen))
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h * 0.40f
            val unit = minOf(w, h)
            val core = unit * 0.085f
            val rInner = unit * 0.155f
            val rMid = unit * 0.225f
            val rOuter = unit * 0.295f
            val tone = if (failed) Glass.red else accent

            // The bloom behind everything: it grows with the download, so the
            // screen literally gets brighter as the bundle arrives.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        tone.copy(alpha = 0.20f + 0.22f * p),
                        third.copy(alpha = 0.07f + 0.06f * p),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = unit * 0.75f
                ),
                radius = unit * 0.75f,
                center = Offset(cx, cy)
            )

            // Light rays, slow, behind the rings.
            rotate(spin * 0.35f, pivot = Offset(cx, cy)) {
                repeat(12) { i ->
                    val a = Math.toRadians((i * 30).toDouble())
                    val near = rOuter * 1.05f
                    val far = rOuter * (1.35f + 0.18f * p)
                    val from = Offset(cx + cos(a).toFloat() * near, cy + sin(a).toFloat() * near)
                    val to = Offset(cx + cos(a).toFloat() * far, cy + sin(a).toFloat() * far)
                    drawLine(
                        brush = Brush.linearGradient(
                            listOf(tone.copy(alpha = 0.20f + 0.18f * p), Color.Transparent),
                            start = from,
                            end = to
                        ),
                        start = from,
                        end = to,
                        strokeWidth = 1.4f
                    )
                }
            }

            // Three rings, two of them turning the other way, each a sweep so the
            // rotation reads as motion rather than as a static circle.
            fun ring(radius: Float, sweep: Float, angle: Float, colours: List<Color>, width: Float) {
                drawArc(
                    brush = Brush.sweepGradient(colours, center = Offset(cx, cy)),
                    startAngle = angle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = width, cap = StrokeCap.Round)
                )
            }
            ring(
                rOuter, 132f, spin,
                listOf(Color.Transparent, tone.copy(alpha = 0.85f), second.copy(alpha = 0.55f), Color.Transparent),
                unit * 0.004f
            )
            ring(
                rMid, 96f, counterSpin,
                listOf(Color.Transparent, third.copy(alpha = 0.70f), tone.copy(alpha = 0.45f), Color.Transparent),
                unit * 0.003f
            )
            ring(
                rInner, 210f, spin * 1.6f,
                listOf(Color.Transparent, tone.copy(alpha = 0.55f), Color.Transparent),
                unit * 0.0022f
            )

            // The tick ring: 64 marks, filling clockwise with the real progress.
            repeat(64) { i ->
                val lit = i / 64f <= p
                val a = Math.toRadians((i * (360.0 / 64.0) - 90.0))
                val len = if (lit) unit * 0.020f else unit * 0.010f
                val from = rOuter * 1.11f
                drawLine(
                    color = if (lit) tone.copy(alpha = 0.95f) else muted.copy(alpha = 0.22f),
                    start = Offset(cx + cos(a).toFloat() * from, cy + sin(a).toFloat() * from),
                    end = Offset(cx + cos(a).toFloat() * (from + len), cy + sin(a).toFloat() * (from + len)),
                    strokeWidth = if (lit) 2.6f else 1.4f,
                    cap = StrokeCap.Round
                )
            }

            // One node per file. It lights when its eighth of the bundle is here,
            // so the user can count what is left instead of guessing from a bar.
            repeat(FILES) { i ->
                val a = Math.toRadians((i * (360.0 / FILES) - 90.0))
                val lit = p >= (i + 1).toFloat() / FILES
                val nodeRadius = if (lit) unit * 0.011f else unit * 0.007f
                val at = Offset(
                    cx + cos(a).toFloat() * rMid * 1.02f,
                    cy + sin(a).toFloat() * rMid * 1.02f
                )
                if (lit) {
                    drawCircle(color = tone.copy(alpha = 0.28f), radius = nodeRadius * 3.2f * pulse, center = at)
                    drawCircle(color = tone, radius = nodeRadius, center = at)
                    drawCircle(color = Color.White.copy(alpha = 0.75f), radius = nodeRadius * 0.40f, center = at)
                } else {
                    drawCircle(
                        color = muted.copy(alpha = 0.30f),
                        radius = nodeRadius,
                        center = at,
                        style = Stroke(width = 1.4f)
                    )
                }
            }

            // The core: a layered glow that breathes, and a hard centre.
            drawCircle(color = tone.copy(alpha = 0.10f * pulse), radius = core * 2.9f, center = Offset(cx, cy))
            drawCircle(color = tone.copy(alpha = 0.16f * pulse), radius = core * 1.9f, center = Offset(cx, cy))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.95f), tone, tone.copy(alpha = 0f)),
                    center = Offset(cx, cy),
                    radius = core
                ),
                radius = core,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = tone.copy(alpha = 0.55f),
                radius = core * 1.55f,
                center = Offset(cx, cy),
                style = Stroke(width = unit * 0.0025f)
            )

            // Particles falling inward. They are the bytes: each one travels from
            // the rim to the core and vanishes into it.
            repeat(46) { i ->
                val seed = i * 0.1373f
                val travel = (seed + drift) % 1f
                val a = Math.toRadians(i * 37.0 + travel * 120.0 + spin * 0.5)
                val radius = rOuter * (1.18f - 0.98f * travel)
                val fade = (1f - travel) * (0.20f + 0.55f * p)
                drawCircle(
                    color = if (i % 3 == 0) second.copy(alpha = fade) else tone.copy(alpha = fade),
                    radius = unit * (0.0016f + 0.0016f * (1f - travel)),
                    center = Offset(cx + cos(a).toFloat() * radius, cy + sin(a).toFloat() * radius)
                )
            }

            // The last eighth lands with a ring that expands and dies.
            if (p > 0.94f) {
                val burst = (p - 0.94f) / 0.06f
                drawCircle(
                    color = tone.copy(alpha = (1f - burst) * 0.55f),
                    radius = rOuter * (1f + burst * 0.65f),
                    center = Offset(cx, cy),
                    style = Stroke(width = unit * 0.004f * (1f - burst))
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.weight(1f))

            Text(
                text = "ZYRAXON",
                color = if (failed) Glass.red else ink,
                fontSize = 30.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 10.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "SYSTEM CONTRACT",
                color = muted.copy(alpha = 0.75f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp
            )

            Spacer(Modifier.height(40.dp))

            Text(
                text = if (failed) "—" else "${(p * 100).toInt()}",
                color = if (failed) Glass.red else ink,
                fontSize = 46.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                letterSpacing = 2.sp
            )

            Spacer(Modifier.height(20.dp))

            // The bar, drawn rather than laid out, so the sweep can travel its
            // full width whatever the screen size is.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(muted.copy(alpha = 0.16f))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val bw = size.width
                    val bh = size.height
                    val fillW = bw * p
                    if (fillW > 0f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(accent, second, third),
                                startX = 0f,
                                endX = bw
                            ),
                            size = Size(fillW, bh)
                        )
                        // A bright head on the leading edge, so movement is visible
                        // even when two files arrive close together.
                        drawCircle(
                            color = Color.White.copy(alpha = 0.85f),
                            radius = bh * 0.9f,
                            center = Offset(fillW, bh / 2f)
                        )
                        // A sweep that runs along what has already arrived.
                        val sx = shimmer * fillW
                        val from = (sx - bh * 6f).coerceIn(0f, fillW)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                                startX = sx - bh * 6f,
                                endX = sx + bh * 6f
                            ),
                            topLeft = Offset(from, 0f),
                            size = Size((fillW - from).coerceAtMost(bh * 12f), bh)
                        )
                    }
                    // One divider per file, so the bar shows eight steps.
                    repeat(FILES - 1) { i ->
                        val x = bw * (i + 1) / FILES
                        drawLine(
                            color = Color.Black.copy(alpha = 0.45f),
                            start = Offset(x, 0f),
                            end = Offset(x, bh),
                            strokeWidth = 1.2f
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = if (failed) "সংযোগ করা গেল না" else "সিস্টেম কনট্র্যাক্ট নামছে",
                color = if (failed) Glass.red else Glass.textSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center
            )

            if (failed) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = error.orEmpty(),
                    color = Glass.textMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Glass.red.copy(alpha = 0.14f))
                        .clickable { onRetry() }
                        .padding(horizontal = 26.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = "আবার চেষ্টা করো",
                        color = Glass.red,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(Modifier.weight(0.72f))
        }
    }
}
