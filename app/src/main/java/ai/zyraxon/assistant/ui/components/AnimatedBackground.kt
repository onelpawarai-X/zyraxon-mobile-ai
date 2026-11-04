package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.model.BgAnimationMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import ai.zyraxon.assistant.ui.components.rememberLiveTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.zyraxon.assistant.R
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
    val transition = rememberLiveTransition(label = "bg_anim")

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

    val smokeDrift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "smoke_drift"
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

    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.img_blood_cyber_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Animated aura/nebula light glow layer on top
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.85f)
        ) {
            // Subtle obsidian-crimson shading so text and controls pop with luxury contrast
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xB0070103),
                        Color(0x40070103),
                        Color(0xD0070103)
                    )
                )
            )

            when (mode) {
                BgAnimationMode.LIVING_PINK_AURA -> {
                    drawLivingPinkAuraStage(center, size.width, size.height, rotation, effectivePulse, smokeDrift)
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

        // Fire, smoke and embers over the whole picture, in every mode, so the
        // background is never a still image.
        FireSmokeLayer(intensity = 1f + (if (isSpeaking) audioLevel * 0.6f else 0f))
    }
}

private fun DrawScope.drawLivingPinkAuraStage(
    center: Offset,
    width: Float,
    height: Float,
    rotation: Float,
    pulse: Float,
    drift: Float
) {
    val radius = (width * 0.92f) * pulse
    val rad = Math.toRadians(rotation.toDouble())
    val waveCos = cos(rad).toFloat()
    val waveSin = sin(rad).toFloat()
    val waveCos2 = cos(rad * 1.5).toFloat()
    val waveSin2 = sin(rad * 1.5).toFloat()

    // 1. Swirling Blood-Red Smoke Plume #1 (Upper-Left billowing towards Center)
    val smoke1Center = Offset(
        x = width * 0.30f + waveCos * 55f,
        y = height * 0.28f + waveSin * 45f
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x88FF1744),
                Color(0x55990018),
                Color(0x283D0008),
                Color.Transparent
            ),
            center = smoke1Center,
            radius = radius * 1.1f
        ),
        radius = radius * 1.1f,
        center = smoke1Center
    )

    // 2. Swirling Deep Crimson Smoke Plume #2 (Central Living Mist halo wrapping the Aura)
    val smoke2Center = Offset(
        x = center.x - waveSin * 45f,
        y = center.y + waveCos * 40f
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x75DC143C),
                Color(0x45800014),
                Color(0x20350005),
                Color.Transparent
            ),
            center = smoke2Center,
            radius = radius * 1.25f
        ),
        radius = radius * 1.25f,
        center = smoke2Center
    )

    // 3. Rolling Crimson Blood Bank #3 (Bottom-Right curling upwards)
    val smoke3Center = Offset(
        x = width * 0.70f + waveSin2 * 60f,
        y = height * 0.68f - waveCos2 * 50f
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x70FF0834),
                Color(0x4266000E),
                Color(0x181A0002),
                Color.Transparent
            ),
            center = smoke3Center,
            radius = radius * 1.15f
        ),
        radius = radius * 1.15f,
        center = smoke3Center
    )

    // 4. Subtle Blood Vapor Tendril #4 (Top-Right drifting across)
    val smoke4Center = Offset(
        x = width * 0.80f - waveCos * 45f,
        y = height * 0.20f + waveSin * 40f
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x5CB22222),
                Color(0x306B000B),
                Color.Transparent
            ),
            center = smoke4Center,
            radius = radius * 0.95f
        ),
        radius = radius * 0.95f,
        center = smoke4Center
    )

    // 5. Lower Deep Blood Cauldron Mist #5 (Bottom center rising)
    val smoke5Center = Offset(
        x = width * 0.45f + waveCos2 * 40f,
        y = height * 0.88f - waveSin2 * 30f
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x66FF1E44),
                Color(0x387F0012),
                Color.Transparent
            ),
            center = smoke5Center,
            radius = radius * 1.05f
        ),
        radius = radius * 1.05f,
        center = smoke5Center
    )

    // 6. 36 Rising Living Blood-Red Embers / Sparks floating upward through the smoke
    for (i in 0 until 14) {
        val emberSeed = (i * 29) % 100 / 100f
        val driftOffset = sin((drift * 6.28f + i * 1.3f).toDouble()).toFloat() * 30f
        val emberX = (width * emberSeed + driftOffset + width) % width
        val baseEmberY = (height * (1f - ((drift * (0.8f + (i % 5) * 0.1f) + emberSeed) % 1f)))
        val emberAlpha = (0.35f + 0.65f * sin((drift * 6.28f + i * 0.8f).toDouble()).toFloat().coerceIn(0f, 1f))
        val emberColor = when (i % 4) {
            0 -> Color(0xFFFF4D6D).copy(alpha = emberAlpha)
            1 -> Color(0xFFFF1744).copy(alpha = emberAlpha)
            2 -> Color(0xFFFF8599).copy(alpha = emberAlpha)
            else -> Color(0xFFFF0033).copy(alpha = emberAlpha)
        }
        drawCircle(
            color = emberColor,
            radius = (1.8f + (i % 4) * 1.4f) * pulse,
            center = Offset(emberX, baseEmberY)
        )
    }
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

private data class MatrixColumn(val xRatio: Float, val speed: Float, val offset: Float, val length: Int)

/**
 * The Terminal's backdrop: a dark matrix of falling green glyphs, drawn behind
 * the scrollback. It is decorative only — a Canvas with no input — so the shell
 * underneath stays fully usable while it runs.
 */
@Composable
fun MatrixRainBackground(modifier: Modifier = Modifier) {
    val transition = rememberLiveTransition(label = "matrix")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val columns = remember {
        val rnd = Random(7)
        List(22) {
            MatrixColumn(
                xRatio = (it + 0.5f) / 22f,
                speed = 0.6f + rnd.nextFloat() * 0.9f,
                offset = rnd.nextFloat(),
                length = 6 + rnd.nextInt(10)
            )
        }
    }
    Canvas(modifier = modifier) {
        val colWidth = size.width / 22f
        val glyph = 14f
        columns.forEach { column ->
            val headY = ((column.offset + phase * column.speed) % 1.2f) * size.height - size.height * 0.2f
            for (k in 0 until column.length) {
                val y = headY - k * glyph
                if (y < -glyph || y > size.height) continue
                val fade = 1f - k.toFloat() / column.length
                val rainColor = if (k == 0) Color(0xFFFF8599) else Color(0xFFFF1E44)
                drawRect(
                    color = rainColor.copy(alpha = 0.22f * fade * fade),
                    topLeft = Offset(column.xRatio * size.width, y),
                    size = androidx.compose.ui.geometry.Size(colWidth * 0.5f, glyph * 0.7f)
                )
            }
        }
    }
}
