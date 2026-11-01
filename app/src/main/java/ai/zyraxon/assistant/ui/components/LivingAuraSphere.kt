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
            .size(260.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                Glass.vibrate(context, 22)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(260.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.width / 2f) * 0.62f * effectiveScale

            // 1. Outermost ambient blood-red velvet nebula glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x77FF1744),
                        Color(0x44B91C1C),
                        Color(0x187F0014),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.8f
                ),
                radius = baseRadius * 1.8f,
                center = center
            )

            // 2. Audio-reactive pulsating blood ripple waves
            if (isListening || isSpeaking) {
                val alpha = (0.65f * (1.40f - ripple)).coerceIn(0f, 0.65f)
                drawCircle(
                    color = Color(0xFFFF1E44).copy(alpha = alpha),
                    radius = baseRadius * ripple * 1.28f,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
                val secondAlpha = (0.42f * (1.25f - ripple + 0.15f)).coerceIn(0f, 0.42f)
                drawCircle(
                    color = Color(0xFFFF4D6D).copy(alpha = secondAlpha),
                    radius = baseRadius * ripple * 1.14f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // 3. Radiant Crimson Ruby Pearlescent Halo Edge
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
                radius = baseRadius * 1.03f,
                center = center,
                style = Stroke(width = 3.5.dp.toPx())
            )

            // 4. THE LIVING BLOOD-RED AURA SPHERE CORE
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFE0E6), // Pearlescent white-rose highlight
                        Color(0xFFFF3355), // Vibrant electric scarlet
                        Color(0xFFFF1744), // Primary blood red
                        Color(0xFFB00020), // Deep blood ruby
                        Color(0xFF45000A)  // Deep obsidian blood edge
                    ),
                    center = Offset(center.x - baseRadius * 0.28f, center.y - baseRadius * 0.28f),
                    radius = baseRadius * 1.15f
                ),
                radius = baseRadius,
                center = center
            )

            // 5. Plasma Swirl Layer (Rotating smooth conic crimson energy)
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0x55FFFFFF),
                        Color(0x33FFA8B8),
                        Color(0x55FF1744),
                        Color(0x228B0000),
                        Color(0x55FFFFFF)
                    ),
                    center = center
                ),
                radius = baseRadius * 0.96f,
                center = center
            )

            // 6. Shimmering Floating Wave Ribbon
            val ribbonY = center.y + baseRadius * ribbonShift
            val ribbonWidth = baseRadius * 1.6f
            val ribbonHeight = baseRadius * 0.45f
            drawArc(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0x99FFFFFF),
                        Color(0xAAFFCCD5),
                        Color(0x66FF1744),
                        Color.Transparent
                    )
                ),
                startAngle = angle,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(center.x - ribbonWidth / 2f, ribbonY - ribbonHeight / 2f),
                size = Size(ribbonWidth, ribbonHeight),
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            )

            // 7. Glossy Glass Specular Highlights (Top-left shine reflection)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xF0FFFFFF),
                        Color(0x66FFCCD5),
                        Color.Transparent
                    ),
                    center = Offset(center.x - baseRadius * 0.35f, center.y - baseRadius * 0.35f),
                    radius = baseRadius * 0.45f
                ),
                radius = baseRadius * 0.45f,
                center = Offset(center.x - baseRadius * 0.35f, center.y - baseRadius * 0.35f)
            )

            // 8. Inset Rim Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0x44FFFFFF),
                        Color(0x99FF5277)
                    ),
                    center = center,
                    radius = baseRadius
                ),
                radius = baseRadius,
                center = center,
                style = Stroke(width = 4.dp.toPx())
            )
        }
    }
}

