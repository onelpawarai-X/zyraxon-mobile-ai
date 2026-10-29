package ai.zyraxon.assistant.ui.components

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
import androidx.compose.ui.unit.dp

/**
 * LIVING PINK AURA (আগের ডিজাইনের গোলাপি কালার এবং সুন্দর ওরা)
 * Recreates the iconic glowing pearlescent pink/magenta living aura with
 * luminous cyan outer halo ring, dynamic plasma rotation, and audio-reactive breathing.
 */
@Composable
fun LivingAuraSphere(
    isListening: Boolean,
    isSpeaking: Boolean,
    audioLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "living_aura_anim")

    // Slow plasma rotation
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "plasma_spin"
    )

    // Breathing rhythm
    val breath by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aura_breath"
    )

    // Fast ripple for voice interaction
    val ripple by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "voice_ripple"
    )

    // Secondary ribbon drift
    val ribbonShift by transition.animateFloat(
        initialValue = -0.2f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ribbon_drift"
    )

    val effectiveScale = when {
        isListening -> breath * (1f + audioLevel * 0.5f)
        isSpeaking -> breath * (1f + (ripple - 0.85f) * 0.4f)
        else -> breath
    }

    Box(
        modifier = modifier
            .size(240.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.width / 2f) * 0.62f * effectiveScale

            // 1. Outermost soft ambient pink nebula glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x66FF69CA),
                        Color(0x33FF3DB9),
                        Color(0x1500F0FF),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.7f
                ),
                radius = baseRadius * 1.7f,
                center = center
            )

            // 2. Audio-reactive pulsating soft pink living ripple waves
            if (isListening || isSpeaking) {
                val alpha = (0.55f * (1.35f - ripple)).coerceIn(0f, 0.55f)
                drawCircle(
                    color = Color(0xFFFF69CA).copy(alpha = alpha),
                    radius = baseRadius * ripple * 1.25f,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx())
                )
                val secondAlpha = (0.35f * (1.2f - ripple + 0.15f)).coerceIn(0f, 0.35f)
                drawCircle(
                    color = Color(0xFFFF30B5).copy(alpha = secondAlpha),
                    radius = baseRadius * ripple * 1.12f,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // 3. Soft Radiant Rose Pearlescent Halo Edge
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFFFFB8E8),
                        Color(0xFFFF69CA),
                        Color(0xFFFF38B9),
                        Color(0xFFFF80D7),
                        Color(0xFFFFB8E8)
                    ),
                    center = center
                ),
                radius = baseRadius * 1.02f,
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )

            // 4. THE LIVING PINK AURA SPHERE CORE (Original vibrant pink/magenta pearlescent gradient)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFE7F8), // Soft pearlescent highlight
                        Color(0xFFFF91D9), // Vibrant orchid pink
                        Color(0xFFFF4CBE), // Hot electric pink
                        Color(0xFFFF1493), // Deep radiant magenta
                        Color(0xFFD946EF)  // Deep violet-pink edge
                    ),
                    center = Offset(center.x - baseRadius * 0.28f, center.y - baseRadius * 0.28f),
                    radius = baseRadius * 1.15f
                ),
                radius = baseRadius,
                center = center
            )

            // 5. Plasma Swirl Layer (Rotating smooth conic energy)
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0x44FFFFFF),
                        Color(0x33FFC5ED),
                        Color(0x44FF3DB9),
                        Color(0x22FF80D7),
                        Color(0x44FFFFFF)
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
                        Color(0x88FFFFFF),
                        Color(0xAAFFD8F2),
                        Color(0x66FF38B9),
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
                        Color(0xEEFFFFFF),
                        Color(0x66FFD8F2),
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
                        Color(0x88FF9CDE)
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
