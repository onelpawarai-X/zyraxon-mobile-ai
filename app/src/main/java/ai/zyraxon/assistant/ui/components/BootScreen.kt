package ai.zyraxon.assistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val bootLines = listOf(
    "জাগিয়ে তোলা হচ্ছে…",
    "ইন্দ্রিয় খুলছে…",
    "চোখ খুলছে…",
    "সব দেখতে পাচ্ছি…"
)

/**
 * The first thing seen after the app opens: a dark screen, a heartbeat, a slit
 * eye that tears open, blood running down from the top, and the name glitching
 * in. It runs on the frame clock, ends by itself after about 3 seconds, and
 * calls [onDone] so the app underneath takes over.
 */
@Composable
fun BootScreen(onDone: () -> Unit) {
    var p by remember { mutableFloatStateOf(0f) }       // 0..1 over the whole intro
    var secs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = -1L
        while (p < 1f) {
            withFrameNanos { n ->
                if (start < 0) start = n
                secs = (n - start) / 1e9f
                p = min(1f, secs / TOTAL_SECS)
            }
        }
        onDone()
    }

    val fadeOut = ((p - 0.9f) / 0.1f).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(1f - fadeOut)
            .background(Color(0xFF050102))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h * 0.42f
            val beat = 0.5f + 0.5f * sin(secs * 9f)
            // Red glow that swells with the heartbeat.
            val glowR = w * (0.55f + 0.10f * beat + 0.4f * p)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFFE0121E).copy(alpha = 0.55f), Color(0xFF5A060C).copy(alpha = 0.25f), Color.Transparent),
                    center = Offset(cx, cy), radius = glowR
                ), radius = glowR, center = Offset(cx, cy)
            )

            // Blood running from the top, each strand faster than the last.
            for (i in 0 until 11) {
                val x = w * (i + 0.5f) / 11f + sin(i * 2.1f) * w * 0.02f
                val grow = ((secs * (0.55f + (i % 4) * 0.18f) - i * 0.05f)).coerceIn(0f, 1f)
                val len = h * (0.10f + (i * 37 % 11) / 11f * 0.34f) * grow
                val sw = w * (0.012f + (i % 3) * 0.004f)
                drawLine(Color(0xFF8A0A14), Offset(x, 0f), Offset(x, len), strokeWidth = sw)
                drawCircle(Color(0xFFB3121E), radius = sw * 0.9f, center = Offset(x, len))
            }
            drawRect(Color(0xFF8A0A14), size = Size(w, h * 0.012f))

            // The eye: the lid tears open between 15% and 55% of the intro.
            val open = ((p - 0.15f) / 0.40f).coerceIn(0f, 1f)
            val eased = 1f - (1f - open) * (1f - open)
            val ew = w * 0.62f
            val eh = ew * 0.30f * eased + 2f
            val almond = Path().apply {
                moveTo(cx - ew / 2, cy)
                quadraticBezierTo(cx, cy - eh * 1.3f, cx + ew / 2, cy)
                quadraticBezierTo(cx, cy + eh * 1.3f, cx - ew / 2, cy)
                close()
            }
            drawPath(almond, Brush.radialGradient(
                listOf(Color(0xFFF8E6E2), Color(0xFFD98C8C), Color(0xFF5A0A10)),
                center = Offset(cx, cy), radius = ew / 2))
            val ir = ew * 0.17f * (0.6f + 0.4f * eased)
            val look = sin(secs * 1.3f) * ew * 0.05f
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF1A0003), Color(0xFFE0121E), Color(0xFF5A060C)),
                    center = Offset(cx + look, cy), radius = ir),
                radius = ir, center = Offset(cx + look, cy)
            )
            // Pupil widens as the eye comes fully open.
            val pw = ir * (0.10f + 0.30f * eased * (0.7f + 0.3f * beat))
            drawOval(Color.Black, Offset(cx + look - pw, cy - ir * 0.95f), Size(pw * 2, ir * 1.9f))
            drawCircle(Color.White.copy(alpha = 0.8f), ir * 0.13f, Offset(cx + look - ir * 0.32f, cy - ir * 0.36f))
            // Veins twitching in from both corners.
            for (k in 0 until 6) {
                val side = if (k % 2 == 0) -1f else 1f
                val ang = k * 0.8f + 0.2f
                drawLine(
                    Color(0xFF9A0A14).copy(alpha = 0.7f * eased),
                    Offset(cx + side * ew / 2, cy),
                    Offset(cx + side * ew * (0.22f + 0.03f * sin(secs * 3f + k)), cy + sin(ang) * eh * 0.7f),
                    strokeWidth = 2.2f
                )
            }
            drawPath(almond, Color(0xFF1A0206), style = Stroke(width = ew * 0.016f))

            // Scanline tear.
            val ty = (secs * h * 0.8f) % h
            drawRect(Color(0xFFE0121E).copy(alpha = 0.10f), Offset(0f, ty), Size(w, h * 0.012f))
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            val glitch = if ((secs * 14f).toInt() % 7 == 0 && p < 0.85f) 4 else 0
            Box {
                Text(
                    "ZYRAXON", color = Color(0xFF00E5FF).copy(alpha = 0.6f),
                    fontSize = 40.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                    letterSpacing = 8.sp, modifier = Modifier.offset(x = (-glitch).dp)
                )
                Text(
                    "ZYRAXON", color = Color(0xFFFF2A3A).copy(alpha = 0.7f),
                    fontSize = 40.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                    letterSpacing = 8.sp, modifier = Modifier.offset(x = glitch.dp)
                )
                Text(
                    "ZYRAXON", color = Color(0xFFFFE9E9),
                    fontSize = 40.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                    letterSpacing = 8.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                bootLines[(p * bootLines.size).toInt().coerceIn(0, bootLines.size - 1)],
                color = Color(0xFFFF8A92), fontSize = 14.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth(0.62f).height(4.dp).background(Color(0xFF2A0A0E))
            ) {
                Box(
                    Modifier.fillMaxWidth(p).height(4.dp)
                        .background(Brush.horizontalGradient(listOf(Color(0xFF8A0A14), Color(0xFFFF2A3A))))
                )
            }
            Spacer(Modifier.height(64.dp))
        }
    }
}

private const val TOTAL_SECS = 1.1f
