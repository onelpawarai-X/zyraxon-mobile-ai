// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Atmospheric horror fire, smoke, and embers.
 * Designed with natural, organic curves and cinematic dark-crimson tones
 * to avoid artificial, cartoonish, or overly bright glowing spikes.
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
            smoke = List(5) { Plume(r.nextFloat(), r.nextFloat(), 0.02f + r.nextFloat() * 0.025f, r.nextFloat() * 6.28f) },
            embers = List(22) { Ember(r.nextFloat(), r.nextFloat(), 0.04f + r.nextFloat() * 0.09f, 0.9f + r.nextFloat() * 1.8f, r.nextFloat() * 6.28f) },
            tongues = List(9) { r.nextFloat() * 6.28f }
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val t = time.value
        drawTopAtmosphere(t, intensity)
        drawSmoke(seeds.smoke, t, intensity)
        drawFlames(seeds.tongues, t, intensity)
        drawEmbers(seeds.embers, t, intensity)
    }
}

private class Plume(val x: Float, val phase: Float, val speed: Float, val seed: Float)
private class Ember(val x: Float, val phase: Float, val speed: Float, val size: Float, val seed: Float)
private class FireSeeds(val smoke: List<Plume>, val embers: List<Ember>, val tongues: List<Float>)

/** Subtle dark-crimson heat ambiance along the top edge, avoiding harsh fake glare. */
private fun DrawScope.drawTopAtmosphere(t: Float, intensity: Float) {
    val w = size.width
    val h = size.height
    val topGlowHeight = h * 0.12f
    val pulse = 0.85f + 0.15f * sin(t * 1.5f)

    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0x381C0205).copy(alpha = (0.24f * pulse * intensity).coerceIn(0f, 0.45f)),
                Color(0x1830040A).copy(alpha = (0.12f * pulse * intensity).coerceIn(0f, 0.25f)),
                Color.Transparent
            ),
            startY = 0f,
            endY = topGlowHeight
        ),
        topLeft = Offset.Zero,
        size = Size(w, topGlowHeight)
    )
}

/** Soft rising smoke plumes in deep charcoal and ash-crimson. */
private fun DrawScope.drawSmoke(plumes: List<Plume>, t: Float, intensity: Float) {
    val w = size.width
    val h = size.height
    for (p in plumes) {
        val prog = (t * p.speed + p.phase) % 1f
        val cx = w * p.x + sin(t * 0.35f + p.seed) * w * 0.08f + prog * w * 0.10f
        val cy = h * (1.05f - prog * 1.20f)
        val r = w * (0.22f + 0.38f * prog)
        val a = (sin(prog * PI.toFloat()) * 0.18f * intensity).coerceIn(0f, 0.32f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF2E090D).copy(alpha = a),
                    Color(0xFF140305).copy(alpha = a * 0.5f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = r
            ),
            radius = r,
            center = Offset(cx, cy)
        )
    }
}

/**
 * Organic, natural-looking licking flames at the bottom edge.
 * Deep burning coal bases, warm amber bodies, and smooth curving tips.
 */
private fun DrawScope.drawFlames(seeds: List<Float>, t: Float, intensity: Float) {
    val w = size.width
    val h = size.height
    val n = seeds.size
    val unit = w / n

    // Soft low-ember glow base at bottom edge
    val glowH = h * 0.16f
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color(0x28380307).copy(alpha = (0.22f * intensity).coerceIn(0f, 0.4f)),
                Color(0x555A080C).copy(alpha = (0.35f * intensity).coerceIn(0f, 0.55f))
            ),
            startY = h - glowH,
            endY = h
        ),
        topLeft = Offset(0f, h - glowH),
        size = Size(w, glowH)
    )

    // Layered natural flame tongues
    for (i in 0 until n) {
        val s = seeds[i]
        val flick = 0.70f + 0.30f * (sin(t * 2.8f + s * 2.5f) * 0.5f + 0.5f) * (0.75f + 0.25f * sin(t * 1.6f + i))
        val x = unit * (i + 0.5f)
        val sway = sin(t * 1.2f + s) * unit * 0.28f

        // Outer deeper flame body
        val bigH = (h * 0.09f * flick * intensity).coerceAtLeast(10f)
        flame(
            x = x,
            baseY = h,
            halfWidth = unit * 0.95f,
            height = bigH,
            sway = sway,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0x00A02008),
                    Color(0x70660A0D),
                    Color(0xAA3B0407)
                ),
                startY = h - bigH,
                endY = h
            )
        )

        // Inner warm ember tongue (softer and lower)
        val smallH = bigH * 0.62f
        flame(
            x = x + sway * 0.20f,
            baseY = h,
            halfWidth = unit * 0.55f,
            height = smallH,
            sway = sway * 0.65f,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0x00E06515),
                    Color(0x889E280C),
                    Color(0xB0660A0D)
                ),
                startY = h - smallH,
                endY = h
            )
        )
    }
}

/** Draws an organic, fluid flame tongue with gentle bezier curvature. */
private fun DrawScope.flame(x: Float, baseY: Float, halfWidth: Float, height: Float, sway: Float, brush: Brush) {
    val tipX = x + sway
    val tipY = baseY - height
    val midY = baseY - height * 0.50f

    val path = Path().apply {
        moveTo(x - halfWidth, baseY)
        // Left flank curve
        cubicTo(
            x - halfWidth * 0.6f + sway * 0.2f, midY + height * 0.15f,
            x - halfWidth * 0.15f + sway * 0.6f, midY - height * 0.15f,
            tipX, tipY
        )
        // Right flank curve
        cubicTo(
            x + halfWidth * 0.15f + sway * 0.6f, midY - height * 0.15f,
            x + halfWidth * 0.6f + sway * 0.2f, midY + height * 0.15f,
            x + halfWidth, baseY
        )
        close()
    }
    drawPath(path, brush)
}

/** Floating ember sparks: cooling and naturally dissipating as they rise. */
private fun DrawScope.drawEmbers(embers: List<Ember>, t: Float, intensity: Float) {
    val w = size.width
    val h = size.height
    for (e in embers) {
        val prog = (t * e.speed + e.phase) % 1f
        val x = w * e.x + sin(t * 1.2f + e.seed) * w * 0.028f
        val y = h * (1.02f - prog * 0.95f)
        val fade = (1f - prog).coerceIn(0f, 1f)
        val tw = 0.7f + 0.3f * abs(sin(t * 4.5f + e.seed * 3f))
        val a = (fade * fade * tw * intensity).coerceIn(0f, 0.75f)
        val r = e.size * (1f - prog * 0.45f) * (size.minDimension / 400f).coerceAtLeast(0.8f)

        // Ember color cools from warm amber to deep coal-red
        val sparkColor = if (prog < 0.45f) {
            Color(0xFFD96E1E).copy(alpha = a * 0.75f)
        } else {
            Color(0xFF8A1E14).copy(alpha = a * 0.50f)
        }
        val haloColor = Color(0xFF5A0C08).copy(alpha = a * 0.25f)

        val c = Offset(x, y)
        drawCircle(color = haloColor, radius = r * 2.2f, center = c)
        drawCircle(color = sparkColor, radius = r, center = c)
    }
}
