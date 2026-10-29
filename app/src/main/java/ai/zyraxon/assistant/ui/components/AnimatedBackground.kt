package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.model.BgAnimationMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class Star(
    val xRatio: Float,
    val yRatio: Float,
    val radius: Float,
    val baseAlpha: Float,
    val speed: Float
)

@Composable
fun AnimatedBackground(
    mode: BgAnimationMode = BgAnimationMode.LIVING_PINK_AURA,
    audioLevel: Float = 0f,
    isSpeaking: Boolean = false,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "bg_anim")

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(24000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val pulse by transition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(3800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val stars = remember {
        val rand = Random(42)
        List(70) {
            Star(
                xRatio = rand.nextFloat(),
                yRatio = rand.nextFloat(),
                radius = 1.2f + rand.nextFloat() * 2.2f,
                baseAlpha = 0.25f + rand.nextFloat() * 0.75f,
                speed = 0.5f + rand.nextFloat() * 1.5f
            )
        }
    }

    val effectivePulse = if (isSpeaking) pulse * (1f + audioLevel * 0.4f) else pulse

    Canvas(modifier = modifier.fillMaxSize()) {
        // Deep space dark cosmic foundation
        drawRect(color = Color(0xFF05060F))

        when (mode) {
            BgAnimationMode.LIVING_PINK_AURA -> {
                drawLivingPinkAuraStage(center, size.width, rotation, effectivePulse)
                drawTwinklingStars(stars, pulse)
            }
            BgAnimationMode.COSMIC_AURA -> {
                drawCosmicPortal(center, size.width, rotation, effectivePulse)
                drawTwinklingStars(stars, pulse)
            }
            BgAnimationMode.CYBER_GRID -> {
                drawCyberGrid(size.width, size.height, rotation, effectivePulse)
            }
            BgAnimationMode.DEEP_NEBULA -> {
                drawDeepNebula(center, size.width, rotation, effectivePulse)
                drawTwinklingStars(stars, pulse)
            }
            BgAnimationMode.MATRIX_RAIN -> {
                drawMatrixRain(size.width, size.height, rotation)
            }
        }
    }
}

private fun DrawScope.drawLivingPinkAuraStage(
    center: Offset,
    width: Float,
    rotation: Float,
    pulse: Float
) {
    val radius = (width * 0.85f) * pulse

    // Top-left magenta ambient light
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x33FF69CA),
                Color(0x18D946EF),
                Color.Transparent
            ),
            center = Offset(width * 0.2f, center.y * 0.3f),
            radius = radius * 0.9f
        ),
        radius = radius * 0.9f,
        center = Offset(width * 0.2f, center.y * 0.3f)
    )

    // Center-bottom cyan/teal ambient light
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x2200F0FF),
                Color(0x0C38BDF8),
                Color.Transparent
            ),
            center = Offset(width * 0.8f, center.y * 1.3f),
            radius = radius * 0.9f
        ),
        radius = radius * 0.9f,
        center = Offset(width * 0.8f, center.y * 1.3f)
    )
}

private fun DrawScope.drawCosmicPortal(
    center: Offset,
    width: Float,
    rotation: Float,
    pulse: Float
) {
    val radius = (width * 0.7f) * pulse
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x3500F0FF),
                Color(0x208B5CF6),
                Color(0x10EC4899),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

private fun DrawScope.drawCyberGrid(
    width: Float,
    height: Float,
    rotation: Float,
    pulse: Float
) {
    val gridStep = 40f
    var x = 0f
    while (x < width) {
        drawLine(
            color = Color(0x1400F0FF),
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 1f
        )
        x += gridStep
    }
    var y = 0f
    while (y < height) {
        drawLine(
            color = Color(0x14FF69CA),
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 1f
        )
        y += gridStep
    }
}

private fun DrawScope.drawDeepNebula(
    center: Offset,
    width: Float,
    rotation: Float,
    pulse: Float
) {
    val radius = (width * 0.9f) * pulse
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x303B82F6),
                Color(0x206366F1),
                Color(0x109333EA),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

private fun DrawScope.drawMatrixRain(
    width: Float,
    height: Float,
    rotation: Float
) {
    val colStep = 32f
    var x = 16f
    var i = 0
    while (x < width) {
        val yOffset = ((rotation * 8f + i * 70f) % height)
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color(0x8800FF66), Color.Transparent),
                startY = yOffset - 120f,
                endY = yOffset
            ),
            start = Offset(x, yOffset - 120f),
            end = Offset(x, yOffset),
            strokeWidth = 2.5f
        )
        x += colStep
        i++
    }
}

private fun DrawScope.drawTwinklingStars(stars: List<Star>, pulse: Float) {
    stars.forEach { star ->
        val x = star.xRatio * size.width
        val y = star.yRatio * size.height
        val alpha = (star.baseAlpha * pulse).coerceIn(0.1f, 1f)
        drawCircle(
            color = Color.White.copy(alpha = alpha),
            radius = star.radius,
            center = Offset(x, y)
        )
    }
}
