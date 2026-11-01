package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.ui.theme.Glass
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.graphics.StrokeCap
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
    val transition = rememberInfiniteTransition(label = "living_aura_anim")

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
            val baseRadius = (size.width / 2f) * 0.70f * effectiveScale

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

            // 3. Ethereal Living Core — Translucent Deep Ruby-Blood (Not an opaque solid ball)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x66FF1744), // Translucent vibrant scarlet center
                        Color(0x55B00020), // Translucent deep ruby
                        Color(0x3545000A), // Translucent obsidian blood
                        Color(0x15200004)  // Soft dark outer edge
                    ),
                    center = center,
                    radius = baseRadius
                ),
                radius = baseRadius,
                center = center
            )

            // 4. Rotating Swirling Plasma Currents inside the Aura
            for (arcIndex in 0 until 3) {
                val arcStartAngle = angle * (1f + arcIndex * 0.35f) + arcIndex * 120f
                val arcRadius = baseRadius * (0.55f + arcIndex * 0.18f)
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
                    style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 5. Living Blood Droplets & Embers Orbiting, Rising, and Falling along the sides
            // "ওরা ভিতরে এই রক্তে ভেজা লাল এগুলো ঘোরাফেরা করবে, উড়বে মানে একটু যেন পড়তেছে সাইড দিয়ে উঠতেছে এমন হবে"
            for (i in 0 until 24) {
                val speedMultiplier = if (i % 2 == 0) 1f else -0.7f
                val orbitAngle = Math.toRadians((angle * speedMultiplier + i * 15f + ribbonShift * 90f).toDouble())
                // Radial variance: some droplets drift outside, some inside
                val rVariance = 0.50f + 0.45f * sin((angle * 0.05f + i).toDouble()).toFloat()
                val orbR = baseRadius * rVariance
                val dropletX = center.x + (cos(orbitAngle) * orbR).toFloat()
                // Droplet vertical cascading effect (rising on one side, falling on the other)
                val verticalFloat = sin((angle * 0.08f + i * 1.2f).toDouble()).toFloat() * (baseRadius * 0.22f)
                val dropletY = center.y + (sin(orbitAngle) * orbR).toFloat() + verticalFloat

                val dropletRadius = (2.2f + (i % 4) * 1.2f) * breath
                val dropletAlpha = (0.45f + 0.50f * sin((angle * 0.1f + i).toDouble()).toFloat().coerceIn(0f, 1f))

                val dropletColor = when (i % 3) {
                    0 -> Color(0xFFFF2A4D).copy(alpha = dropletAlpha)
                    1 -> Color(0xFFFF6680).copy(alpha = dropletAlpha)
                    else -> Color(0xFFDC143C).copy(alpha = dropletAlpha)
                }

                drawCircle(
                    color = dropletColor,
                    radius = dropletRadius,
                    center = Offset(dropletX, dropletY)
                )
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

