// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.ui.theme.Glass
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import ai.zyraxon.assistant.ui.components.rememberLiveTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * LIVING BLOOD-RED AURA SPHERE (রক্তে ভেজা লালের মতো প্রাণবন্ত ওরা)
 * Deep crimson-scarlet pearlescent living aura core, dynamic plasma swirl,
 * audio-reactive breathing ripples, and luminous ruby halo glow.
 */
@Composable
fun LivingAuraSphere(
    isListening: Boolean,
    isSpeaking: Boolean,
    audioLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val transition = rememberLiveTransition(label = "living_aura_anim")

    // Slow plasma rotation
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "plasma_spin"
    )

    // Breathing rhythm
    val breath by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aura_breath"
    )

    // Fast ripple for voice interaction
    val ripple by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.40f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "voice_ripple"
    )

    // Secondary ribbon drift
    val ribbonShift by transition.animateFloat(
        initialValue = -0.22f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ribbon_drift"
    )

    val beat by rememberHeartbeat()

    val effectiveScale = when {
        isListening -> breath * (1f + audioLevel * 0.55f)
        isSpeaking -> breath * (1f + (ripple - 0.85f) * 0.45f)
        else -> breath
    }

    Box(
        modifier = modifier
            .size(240.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                Glass.vibrate(context, 22)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.width / 2f) * 0.64f * effectiveScale

            // 1. Outermost soft velvet ambient blood-red nebula glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x88FF1744),
                        Color(0x44B91C1C),
                        Color(0x187F0014),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.65f
                ),
                radius = baseRadius * 1.65f,
                center = center
            )

            // 2. Audio-reactive pulsating blood ripple waves
            if (isListening || isSpeaking) {
                val alpha = (0.70f * (1.40f - ripple)).coerceIn(0f, 0.70f)
                drawCircle(
                    color = Color(0xFFFF1E44).copy(alpha = alpha),
                    radius = baseRadius * ripple * 1.25f,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
                val secondAlpha = (0.45f * (1.25f - ripple + 0.15f)).coerceIn(0f, 0.45f)
                drawCircle(
                    color = Color(0xFFFF4D6D).copy(alpha = secondAlpha),
                    radius = baseRadius * ripple * 1.12f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // 3. The orb: dark clotted blood with a wet highlight, throbbing with
            //    the heartbeat. Not a flat ball — the light comes from upper left.
            val orbR = baseRadius * (1f + 0.035f * beat)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF8A0018), Color(0xFF45000B), Color(0xFF130003)),
                    center = Offset(center.x - orbR * 0.25f, center.y - orbR * 0.3f),
                    radius = orbR * 1.35f
                ),
                radius = orbR,
                center = center
            )

            // 3b. The watching eye: it drifts, dilates with the voice, blinks.
            val look = Math.toRadians((angle * 0.6f).toDouble())
            val eye = Offset(
                center.x + (sin(look) * orbR * 0.10).toFloat(),
                center.y + (cos(look * 1.3) * orbR * 0.05).toFloat()
            )
            val irisR = orbR * 0.52f
            val blinkU = (angle / 120f) % 1f
            val closure = if (blinkU > 0.94f) (1f - kotlin.math.abs(blinkU - 0.97f) / 0.03f).coerceIn(0f, 1f) else 0f
            val lid = 1f - 0.92f * closure
            val dilate = when {
                isListening -> 0.20f + audioLevel.coerceIn(0f, 1f) * 0.30f
                isSpeaking -> 0.16f + (ripple - 0.85f) * 0.25f
                else -> 0.12f + 0.08f * beat
            }
            scale(scaleX = 1f, scaleY = lid, pivot = eye) {
                // iris
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFF6680), Color(0xFFC0102B), Color(0xFF3A0008)),
                        center = eye,
                        radius = irisR
                    ),
                    radius = irisR,
                    center = eye
                )
                // iris fibres
                for (k in 0 until 30) {
                    val a = k * (2.0 * Math.PI / 30.0)
                    drawLine(
                        color = Color(0x66200004),
                        start = Offset(eye.x + (cos(a) * irisR * 0.30).toFloat(), eye.y + (sin(a) * irisR * 0.30).toFloat()),
                        end = Offset(eye.x + (cos(a) * irisR * 0.98).toFloat(), eye.y + (sin(a) * irisR * 0.98).toFloat()),
                        strokeWidth = 1.2f
                    )
                }
                // slit pupil
                drawOval(
                    color = Color.Black,
                    topLeft = Offset(eye.x - irisR * dilate, eye.y - irisR * 0.82f),
                    size = Size(irisR * dilate * 2f, irisR * 1.64f)
                )
                // wet highlight
                drawCircle(
                    color = Color(0xAAFFFFFF),
                    radius = irisR * 0.10f,
                    center = Offset(eye.x - irisR * 0.34f, eye.y - irisR * 0.38f)
                )
            }

            // 3c. Veins running from the eye to the rim of the orb.
            for (k in 0 until 14) {
                val a = k * (2.0 * Math.PI / 14.0) + 0.2
                val p1 = Offset(eye.x + (cos(a) * irisR * 1.02).toFloat(), eye.y + (sin(a) * irisR * 1.02).toFloat())
                val a2 = a + 0.10
                val p2 = Offset(center.x + (cos(a2) * orbR * 0.78).toFloat(), center.y + (sin(a2) * orbR * 0.78).toFloat())
                val a3 = a - 0.05
                val p3 = Offset(center.x + (cos(a3) * orbR * 0.97).toFloat(), center.y + (sin(a3) * orbR * 0.97).toFloat())
                val vein = Color(0xFFB00020).copy(alpha = 0.30f + 0.45f * beat)
                drawLine(color = vein, start = p1, end = p2, strokeWidth = 1.6f, cap = StrokeCap.Round)
                drawLine(color = vein, start = p2, end = p3, strokeWidth = 1.2f, cap = StrokeCap.Round)
            }

            // 4. Plasma currents now circle the outside of the orb.
            for (arcIndex in 0 until 3) {
                val arcStartAngle = angle * (1f + arcIndex * 0.35f) + arcIndex * 120f
                val arcRadius = baseRadius * (1.10f + arcIndex * 0.05f)
                val arcColor = when (arcIndex) {
                    0 -> Color(0xAAFF3355)
                    1 -> Color(0x88FF1744)
                    else -> Color(0x66FF8599)
                }
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            arcColor,
                            Color.Transparent
                        ),
                        center = center
                    ),
                    startAngle = arcStartAngle,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2f, arcRadius * 2f),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 4b. A ring of thorns turning slowly against the plasma.
            val thornBase = baseRadius * 1.24f
            val thorns = Path()
            for (k in 0 until 36) {
                val a = Math.toRadians((-angle * 0.5f + k * 10f).toDouble())
                val tip = thornBase + baseRadius * (if (k % 3 == 0) 0.15f else 0.09f)
                val w = 0.05
                thorns.moveTo(center.x + (cos(a - w) * thornBase).toFloat(), center.y + (sin(a - w) * thornBase).toFloat())
                thorns.lineTo(center.x + (cos(a) * tip).toFloat(), center.y + (sin(a) * tip).toFloat())
                thorns.lineTo(center.x + (cos(a + w) * thornBase).toFloat(), center.y + (sin(a + w) * thornBase).toFloat())
                thorns.close()
            }
            drawPath(path = thorns, color = Color(0xCC8A0016))
            drawCircle(
                color = Color(0x99FF1E44),
                radius = thornBase,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 5. Blood droplets drifting between the orb and the thorns.
            for (i in 0 until 18) {
                val speedMultiplier = if (i % 2 == 0) 1f else -0.7f
                val orbitAngle = Math.toRadians((angle * speedMultiplier + i * 20f + ribbonShift * 90f).toDouble())
                val rVariance = 1.06f + 0.08f * sin((angle * 0.05f + i).toDouble()).toFloat()
                val dr = baseRadius * rVariance
                val dropletX = center.x + (cos(orbitAngle) * dr).toFloat()
                val dropletY = center.y + (sin(orbitAngle) * dr).toFloat()
                val dropletRadius = (1.8f + (i % 4) * 0.9f) * breath
                val dropletAlpha = (0.45f + 0.50f * sin((angle * 0.1f + i).toDouble()).toFloat().coerceIn(0f, 1f))
                val dropletColor = when (i % 3) {
                    0 -> Color(0xFFFF2A4D).copy(alpha = dropletAlpha)
                    1 -> Color(0xFFFF6680).copy(alpha = dropletAlpha)
                    else -> Color(0xFFDC143C).copy(alpha = dropletAlpha)
                }
                drawCircle(color = dropletColor, radius = dropletRadius, center = Offset(dropletX, dropletY))
            }

            // 5b. Blood dripping off the bottom of the orb.
            for (k in 0 until 5) {
                val phase = ((angle / 360f) * 3f + k * 0.2f) % 1f
                val dx = center.x + (k - 2) * orbR * 0.2f
                val dy = center.y + orbR * 0.9f + phase * orbR * 0.45f
                val da = (1f - phase).coerceIn(0f, 1f)
                val dRad = 2.6f * breath
                drawLine(
                    color = Color(0xFFB00020).copy(alpha = da),
                    start = Offset(dx, dy - dRad * 3f),
                    end = Offset(dx, dy),
                    strokeWidth = dRad * 1.1f,
                    cap = StrokeCap.Round
                )
                drawCircle(color = Color(0xFFB00020).copy(alpha = da), radius = dRad, center = Offset(dx, dy))
            }

            // 6. Radiant Crimson Ruby Pearlescent Outer Halo Ring
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFFFF8599),
                        Color(0xFFFF1E44),
                        Color(0xFFDC143C),
                        Color(0xFFFF4060),
                        Color(0xFFFF8599)
                    ),
                    center = center
                ),
                radius = baseRadius * 1.02f,
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )

            // 7. Subtle inner neon boundary ring
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0x33FF1744),
                        Color(0x88FF3355)
                    ),
                    center = center,
                    radius = baseRadius
                ),
                radius = baseRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

