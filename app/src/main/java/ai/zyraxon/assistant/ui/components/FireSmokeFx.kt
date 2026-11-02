package ai.zyraxon.assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fire and smoke across the whole app: slow smoke plumes drifting up over the
 * background picture, a flickering wall of flame along the bottom edge, and
 * embers floating up the whole screen. Everything is drawn in one Canvas from
 * the live frame clock, and the clock is read inside the draw block, so a frame
 * redraws this layer only — nothing is recomposed.
 */
@Composable
fun FireSmokeLayer(
    modifier: Modifier = Modifier,
    intensity: Float = 1f
) {
    val clock = rememberLiveTransition(label = "fire_smoke")
    val time = clock.animateFloat(
        initialValue = 0f,
        targetValue = 3600f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_600_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fire_t"
    )
    val seeds = remember {
        val r = Random(1313)
        FireSeeds(
            smoke = List(10) { Plume(r.nextFloat(), r.nextFloat(), 0.025f + r.nextFloat() * 0.03f, r.nextFloat() * 6.28f) },
            embers = List(46) { Ember(r.nextFloat(), r.nextFloat(), 0.05f + r.nextFloat() * 0.12f, 1.2f + r.nextFloat() * 2.6f, r.nextFloat() * 6.28f) },
            tongues = List(17) { r.nextFloat() * 6.28f }
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val t = time.value
        drawSmoke(seeds.smoke, t, intensity)
        drawFlames(seeds.tongues, t, intensity)
        drawEmbers(seeds.embers, t, intensity)
    }
}

private class Plume(val x: Float, val phase: Float, val speed: Float, val seed: Float)
private class Ember(val x: Float, val phase: Float, val speed: Float, val size: Float, val seed: Float)
private class FireSeeds(val smoke: List<Plume>, val embers: List<Ember>, val tongues: List<Float>)

private fun DrawScope.drawSmoke(plumes: List<Plume>, t: Float, intensity: Float) {
    val w = size.width
    val h = size.height
    for (p in plumes) {
        val prog = (t * p.speed + p.phase) % 1f
        val cx = w * p.x + sin(t * 0.45f + p.seed) * w * 0.09f + prog * w * 0.12f
        val cy = h * (1.08f - prog * 1.25f)
        val r = w * (0.20f + 0.42f * prog)
        val a = (sin(prog * PI.toFloat()) * 0.26f * intensity).coerceIn(0f, 0.5f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF4A1418).copy(alpha = a), Color(0xFF1B0709).copy(alpha = a * 0.55f), Color.Transparent),
                center = Offset(cx, cy),
                radius = r
            ),
            radius = r,
            center = Offset(cx, cy)
        )
    }
}

private fun DrawScope.drawFlames(seeds: List<Float>, t: Float, intensity: Float) {
    val w = size.width
    val h = size.height
    val n = seeds.size
    val unit = w / n
    // A low red glow where the fire meets the screen.
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color(0x55FF2A0A).copy(alpha = 0.33f * intensity)),
            startY = h * 0.78f,
            endY = h
        ),
        topLeft = Offset(0f, h * 0.78f),
        size = androidx.compose.ui.geometry.Size(w, h * 0.22f),
        blendMode = BlendMode.Plus
    )
    for (i in 0 until n) {
        val s = seeds[i]
        val flick = 0.55f + 0.45f * (sin(t * 4.1f + s * 3f) * 0.5f + 0.5f) * (0.6f + 0.4f * sin(t * 2.3f + i))
        val x = unit * (i + 0.5f)
        val sway = sin(t * 1.7f + s) * unit * 0.35f
        val big = h * 0.15f * flick * intensity
        flame(x, h, unit * 1.15f, big, sway,
            Brush.verticalGradient(
                listOf(Color(0x00FF3B1A), Color(0xB0E8200F), Color(0xEE7A0608)),
                startY = h - big, endY = h
            ))
        val small = big * 0.58f
        flame(x + sway * 0.25f, h, unit * 0.62f, small, sway * 0.7f,
            Brush.verticalGradient(
                listOf(Color(0x00FFD24A), Color(0xCCFF8A1C), Color(0xEEFF3A10)),
                startY = h - small, endY = h
            ))
    }
}

private fun DrawScope.flame(x: Float, baseY: Float, halfWidth: Float, height: Float, sway: Float, brush: Brush) {
    val path = Path().apply {
        moveTo(x - halfWidth, baseY)
        quadraticBezierTo(x - halfWidth * 0.25f + sway * 0.5f, baseY - height * 0.55f, x + sway, baseY - height)
        quadraticBezierTo(x + halfWidth * 0.25f + sway * 0.5f, baseY - height * 0.55f, x + halfWidth, baseY)
        close()
    }
    drawPath(path, brush, blendMode = BlendMode.Plus)
}

private fun DrawScope.drawEmbers(embers: List<Ember>, t: Float, intensity: Float) {
    val w = size.width
    val h = size.height
    for (e in embers) {
        val prog = (t * e.speed + e.phase) % 1f
        val x = w * e.x + sin(t * 1.6f + e.seed) * w * 0.035f
        val y = h * (1.03f - prog * 0.98f)
        val fade = (1f - prog)
        val tw = 0.6f + 0.4f * abs(sin(t * 7f + e.seed * 5f))
        val a = (fade * tw * intensity).coerceIn(0f, 1f)
        val r = e.size * (1f - prog * 0.5f) * (size.minDimension / 400f).coerceAtLeast(1f)
        val c = Offset(x, y)
        drawCircle(Color(0xFFFF7A1A).copy(alpha = a * 0.35f), radius = r * 3.2f, center = c, blendMode = BlendMode.Plus)
        drawCircle(Color(0xFFFFD27A).copy(alpha = a), radius = r, center = c, blendMode = BlendMode.Plus)
    }
}
