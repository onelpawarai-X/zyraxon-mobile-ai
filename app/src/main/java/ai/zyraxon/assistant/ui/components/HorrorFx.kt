package ai.zyraxon.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Blood-horror effect kit. Everything here is decorative Canvas drawing with no
 * input handling, so it can sit on top of live screens without stealing touches.
 */

/** The red halo used behind titles and key labels. */
val BloodGlow = Shadow(color = Color(0xFFFF0033), offset = Offset.Zero, blurRadius = 26f)

/** A softer halo for section titles inside panels. */
val BloodGlowSoft = Shadow(color = Color(0xFFFF0033), offset = Offset.Zero, blurRadius = 12f)

/** Card fills for the settings panes: clotted blood fading into obsidian. */
val BloodCardBrush: Brush = Brush.verticalGradient(listOf(Color(0xE61F030A), Color(0xE60C0104)))
val BloodRowBrush: Brush = Brush.verticalGradient(listOf(Color(0xCC34060E), Color(0xCC1A0307)))

val BloodRedHot = Color(0xFFFF1E44)
val BloodRedDeep = Color(0xFFB00020)
val BloodRedDark = Color(0xFF5A0010)
val BoneWhite = Color(0xFFFFE9E9)

private fun bump(t: Float, centre: Float, width: Float): Float =
    max(0f, 1f - abs(t - centre) / width)

/** A "lub-dub" heartbeat envelope, 0..1, repeating about every 1.7 seconds. */
@Composable
fun rememberHeartbeat(periodMs: Int = 1700): State<Float> {
    val transition = rememberInfiniteTransition(label = "heartbeat")
    val t = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "heartbeatT"
    )
    return remember {
        derivedStateOf {
            (bump(t.value, 0.06f, 0.07f) + 0.6f * bump(t.value, 0.22f, 0.07f)).coerceIn(0f, 1f)
        }
    }
}

private class Drip(val x: Float, val w: Float, val len: Float, val phase: Float)

/**
 * Wet blood running down from the top edge: a ragged crimson band with drips
 * that slowly swell, run, and fade before the next ones form.
 */
@Composable
fun BloodDrips(
    modifier: Modifier = Modifier,
    seed: Int = 11,
    count: Int = 16,
    maxLength: Dp = 110.dp
) {
    val drips = remember(seed, count) {
        val r = Random(seed)
        List(count) {
            Drip(
                x = (it + 0.2f + r.nextFloat() * 0.6f) / count,
                w = 3f + r.nextFloat() * 7f,
                len = 0.35f + r.nextFloat() * 0.65f,
                phase = r.nextFloat()
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "drips")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dripsT"
    )
    Canvas(modifier = modifier.fillMaxWidth().height(maxLength + 16.dp)) {
        val maxPx = maxLength.toPx()
        val band = 12.dp.toPx()
        val blood = Brush.verticalGradient(
            colors = listOf(Color(0xFFFF2A4D), BloodRedDeep, BloodRedDark),
            startY = 0f,
            endY = maxPx + band
        )
        val ragged = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, band * 0.6f)
            var x = size.width
            while (x > 0f) {
                x -= 26f
                lineTo(x, band * (0.55f + 0.45f * sin(x * 0.07f)))
            }
            close()
        }
        drawPath(ragged, blood)
        for (d in drips) {
            val cycle = (t + d.phase) % 1f
            val g = (cycle * 1.25f).coerceAtMost(1f)
            val eased = 1f - (1f - g) * (1f - g)
            val len = band + maxPx * d.len * eased
            val cx = d.x * size.width
            val a = if (cycle > 0.9f) (1f - (cycle - 0.9f) * 10f).coerceIn(0f, 1f) else 1f
            drawRoundRect(
                brush = blood,
                topLeft = Offset(cx - d.w / 2f, 0f),
                size = Size(d.w, len),
                cornerRadius = CornerRadius(d.w / 2f, d.w / 2f),
                alpha = a
            )
            drawCircle(
                color = BloodRedDeep.copy(alpha = a),
                radius = d.w * 0.85f,
                center = Offset(cx, len)
            )
            drawRoundRect(
                color = Color(0x55FFFFFF),
                topLeft = Offset(cx - d.w * 0.28f, 0f),
                size = Size(d.w * 0.2f, len * 0.9f),
                cornerRadius = CornerRadius(d.w * 0.1f, d.w * 0.1f),
                alpha = a
            )
        }
    }
}

/** Dark, pulsing blood-red vignette that beats like a heart. */
@Composable
fun HorrorVignette(modifier: Modifier = Modifier, intensity: Float = 1f) {
    val beat by rememberHeartbeat()
    Canvas(modifier = modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = max(size.width, size.height) * 0.78f
        drawRect(
            brush = Brush.radialGradient(
                0f to Color.Transparent,
                0.5f to Color.Transparent,
                1f to Color(0xFF000000).copy(alpha = 0.62f * intensity),
                center = c,
                radius = r
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                0f to Color.Transparent,
                0.62f to Color.Transparent,
                1f to Color(0xFF9A0016).copy(alpha = (0.22f + 0.34f * beat) * intensity),
                center = c,
                radius = r
            )
        )
    }
}

/** CRT scan-lines plus a slow red sweep, for the terminal. */
@Composable
fun Scanlines(modifier: Modifier = Modifier, lineAlpha: Float = 0.12f) {
    val transition = rememberInfiniteTransition(label = "scan")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanT"
    )
    Canvas(modifier = modifier) {
        var y = 0f
        while (y < size.height) {
            drawRect(
                color = Color.Black.copy(alpha = lineAlpha),
                topLeft = Offset(0f, y),
                size = Size(size.width, 1.5f)
            )
            y += 5f
        }
        val sy = t * (size.height + 160f) - 80f
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color(0x26FF1E44), Color.Transparent),
                startY = sy - 70f,
                endY = sy + 70f
            ),
            topLeft = Offset(0f, sy - 70f),
            size = Size(size.width, 140f)
        )
    }
}

private class Vein(val pts: List<Offset>, val w: Float)

/** Pulsing veins crawling in from the four corners of the screen. */
@Composable
fun BloodVeins(modifier: Modifier = Modifier, seed: Int = 5) {
    val veins = remember(seed) {
        val r = Random(seed)
        val out = mutableListOf<Vein>()
        fun grow(sx: Float, sy: Float, ang: Float, depth: Int, w: Float) {
            var x = sx
            var y = sy
            var a = ang
            val pts = mutableListOf(Offset(x, y))
            repeat(9) {
                a += (r.nextFloat() - 0.5f) * 0.9f
                x += cos(a) * 0.06f
                y += sin(a) * 0.06f
                pts.add(Offset(x, y))
                if (depth < 2 && r.nextFloat() < 0.28f) {
                    grow(x, y, a + (if (r.nextBoolean()) 0.9f else -0.9f), depth + 1, w * 0.6f)
                }
            }
            out.add(Vein(pts, w))
        }
        grow(0f, 0f, 0.8f, 0, 5f)
        grow(1f, 0f, 2.3f, 0, 5f)
        grow(0f, 1f, -0.8f, 0, 5f)
        grow(1f, 1f, -2.3f, 0, 5f)
        out
    }
    val beat by rememberHeartbeat()
    Canvas(modifier = modifier) {
        val col = BloodRedDeep.copy(alpha = 0.16f + 0.38f * beat)
        for (v in veins) {
            val p = Path()
            v.pts.forEachIndexed { i, o ->
                val px = o.x * size.width
                val py = o.y * size.height
                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            drawPath(
                path = p,
                color = col,
                style = Stroke(width = v.w * (1f + 0.5f * beat), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/** A slowly turning blood-sigil: rings, rune ticks and a five-point star. */
@Composable
fun RitualCircle(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ritual")
    val rot by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(46000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ritualRot"
    )
    val beat by rememberHeartbeat()
    Canvas(modifier = modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = min(size.width, size.height) / 2f * 0.94f
        val col = BloodRedHot.copy(alpha = 0.22f + 0.30f * beat)
        rotate(rot, c) {
            drawCircle(color = col, radius = r, center = c, style = Stroke(width = 2.5f))
            drawCircle(color = col, radius = r * 0.9f, center = c, style = Stroke(width = 1.2f))
            for (i in 0 until 48) {
                val a = i * (2.0 * PI / 48.0)
                val len = if (i % 4 == 0) r * 0.07f else r * 0.035f
                val p1 = Offset(c.x + cos(a).toFloat() * r * 0.9f, c.y + sin(a).toFloat() * r * 0.9f)
                val p2 = Offset(c.x + cos(a).toFloat() * (r * 0.9f - len), c.y + sin(a).toFloat() * (r * 0.9f - len))
                drawLine(color = col, start = p1, end = p2, strokeWidth = 1.5f)
            }
            val star = Path()
            for (k in 0..5) {
                val idx = (k * 2) % 5
                val a = (-90.0 + idx * 72.0) * PI / 180.0
                val px = c.x + cos(a).toFloat() * r * 0.88f
                val py = c.y + sin(a).toFloat() * r * 0.88f
                if (k == 0) star.moveTo(px, py) else star.lineTo(px, py)
            }
            star.close()
            drawPath(path = star, color = col, style = Stroke(width = 1.8f, join = StrokeJoin.Round))
        }
    }
}

/** Blood border whose glow throbs. */
@Composable
fun Modifier.bloodPulseBorder(
    shape: Shape = RoundedCornerShape(14.dp),
    width: Dp = 1.dp
): Modifier {
    val beat by rememberHeartbeat()
    val hot = BloodRedHot.copy(alpha = 0.45f + 0.55f * beat)
    return this.border(
        width = width,
        brush = Brush.linearGradient(listOf(hot, BloodRedDark, hot)),
        shape = shape
    )
}

/** A faulty-light flicker: steady, then a sudden stutter. */
@Composable
fun Modifier.horrorFlicker(): Modifier {
    val transition = rememberInfiniteTransition(label = "flicker")
    val a by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4200
                1f at 0
                1f at 3000
                0.3f at 3060
                1f at 3120
                0.55f at 3200
                1f at 3270
            }
        ),
        label = "flickerA"
    )
    return this.graphicsLayer { alpha = a }
}

private class EyePair(val x: Float, val y: Float, val r: Float, val phase: Float)

/**
 * Pairs of slit-pupil red eyes that open in the dark corners of the screen,
 * blink once, and close again. They never sit on controls and take no touches.
 */
@Composable
fun WatchingEyes(modifier: Modifier = Modifier, seed: Int = 66, pairs: Int = 3) {
    val eyes = remember(seed, pairs) {
        val rnd = Random(seed)
        val spots = listOf(0.10f to 0.36f, 0.90f to 0.52f, 0.16f to 0.74f, 0.86f to 0.22f, 0.50f to 0.90f)
        val n = pairs.coerceIn(1, spots.size)
        List(n) { EyePair(spots[it].first, spots[it].second, 5f + rnd.nextFloat() * 3f, it / n.toFloat() + rnd.nextFloat() * 0.05f) }
    }
    val transition = rememberInfiniteTransition(label = "eyes")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "eyesT"
    )
    Canvas(modifier = modifier) {
        for (e in eyes) {
            val c = (t + e.phase) % 1f
            val vis = when {
                c < 0.06f -> c / 0.06f
                c < 0.28f -> 1f
                c < 0.32f -> 1f - (c - 0.28f) / 0.04f
                else -> 0f
            }
            if (vis <= 0f) continue
            val blink = if (c >= 0.18f && c <= 0.21f) 0.15f else 1f
            val rad = e.r.dp.toPx()
            val cy = e.y * size.height
            for (side in listOf(-1f, 1f)) {
                val cx = e.x * size.width + side * rad * 1.9f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFF1E44).copy(alpha = 0.32f * vis), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = rad * 3.4f
                    ),
                    radius = rad * 3.4f,
                    center = Offset(cx, cy)
                )
                drawOval(
                    color = Color(0xFFFF1E44).copy(alpha = vis),
                    topLeft = Offset(cx - rad, cy - rad * 0.55f * blink),
                    size = Size(rad * 2f, rad * 1.1f * blink)
                )
                drawOval(
                    color = Color.Black.copy(alpha = vis),
                    topLeft = Offset(cx - rad * 0.12f, cy - rad * 0.5f * blink),
                    size = Size(rad * 0.24f, rad * 1.0f * blink)
                )
            }
        }
    }
}

/** A rare horizontal glitch jolt, like a bad signal. */
@Composable
fun Modifier.glitchShake(): Modifier {
    val transition = rememberInfiniteTransition(label = "glitch")
    val dx by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 5000
                0f at 0
                0f at 3900
                7f at 3930
                -6f at 3960
                3f at 3990
                0f at 4020
            }
        ),
        label = "glitchX"
    )
    return this.graphicsLayer { translationX = dx }
}

private class Splat(val x: Float, val y: Float, val r: Float)

/** Dried blood splatters pooled in the corners of a screen. Static, drawn once. */
@Composable
fun BloodSplatter(modifier: Modifier = Modifier, seed: Int = 8) {
    val splats = remember(seed) {
        val rnd = Random(seed)
        val out = mutableListOf<Splat>()
        val corners = listOf(0.02f to 0.97f, 0.98f to 0.96f)
        for ((cx, cy) in corners) {
            out.add(Splat(cx, cy, 0.075f + rnd.nextFloat() * 0.03f))
            repeat(14) {
                val a = rnd.nextFloat() * 2f * PI.toFloat()
                val d = 0.07f + rnd.nextFloat() * 0.16f
                out.add(Splat(cx + cos(a) * d, cy + sin(a) * d * 0.7f, 0.004f + rnd.nextFloat() * 0.012f))
            }
        }
        out
    }
    Canvas(modifier = modifier) {
        for (s in splats) {
            drawCircle(
                color = BloodRedDeep.copy(alpha = 0.5f),
                radius = s.r * size.width,
                center = Offset(s.x * size.width, s.y * size.height)
            )
        }
    }
}


/**
 * The horror behind a sheet or panel: veins crawling in from the edges, a
 * heartbeat vignette, blood running from the top and a pulsing red frame. Drawn
 * *behind* the content (put it first inside a Box and size it with
 * `matchParentSize()`), so text and buttons stay fully readable and tappable.
 */
@Composable
fun HorrorSheetBackdrop(modifier: Modifier = Modifier, seed: Int = 3) {
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        BloodVeins(Modifier.fillMaxSize(), seed = seed)
        HorrorVignette(Modifier.fillMaxSize(), intensity = 0.9f)
        BloodDrips(Modifier.align(androidx.compose.ui.Alignment.TopStart), seed = seed + 10, count = 16, maxLength = 70.dp)
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().bloodPulseBorder(RoundedCornerShape(0.dp), 1.5.dp)
        )
    }
}
