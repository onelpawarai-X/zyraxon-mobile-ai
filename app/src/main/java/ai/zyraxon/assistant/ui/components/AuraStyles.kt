package ai.zyraxon.assistant.ui.components

import ai.zyraxon.assistant.i18n.t
import ai.zyraxon.assistant.ui.theme.Glass
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The aura gallery. Twelve designed auras, all drawn natively, all recoloured by
 * the hue the user picks and all driven by the same four controls (size, glow,
 * speed, hue) plus the voice: they swell while the user speaks and ripple while
 * the assistant answers. "orb" is the original watching-eye aura.
 */
val AuraStyleIds = listOf(
    "orb", "twin", "vortex", "bloom", "helix", "core",
    "sigil", "heart", "storm", "void", "inferno", "swarm",
    "crown", "rift", "ghost", "mandala",
    // Real 3D people that talk, instead of an abstract aura (see AvatarView).
    "avatar_ruhi", "avatar_pori", "avatar_arian", "avatar_tisha", "avatar_maya", "avatar_arnab", "avatar_nazia"
)

/** Bengali display name for an aura style id. */
fun auraStyleLabel(id: String): String = when (id) {
    "orb" -> t("চোখ")
    "twin" -> t("টুইন রিং")
    "vortex" -> t("ভর্টেক্স")
    "bloom" -> t("রক্ত-গোলাপ")
    "helix" -> t("হেলিক্স")
    "core" -> t("রিয়্যাক্টর")
    "sigil" -> t("সিগিল")
    "heart" -> t("হৃদয়")
    "storm" -> t("ঝড়")
    "void" -> t("ভয়েড")
    "inferno" -> t("ইনফার্নো")
    "swarm" -> t("চক্ষু-ঝাঁক")
    "crown" -> t("কাঁটার মুকুট")
    "rift" -> t("চিড়")
    "ghost" -> t("প্রেত")
    "mandala" -> t("মণ্ডল")
    else -> avatarSpec(id)?.label ?: id
}

private const val TAU = 6.2831855f

/** Hue 325 was the old pink default; it now means blood red. */
private fun effectiveHue(hue: Int): Float = (if (hue == 325) 352 else hue).toFloat()

private class AuraPal(hue: Float) {
    val bright: Color = Color.hsv(hue % 360f, 0.50f, 1f)
    val hot: Color = Color.hsv(hue % 360f, 0.88f, 1f)
    val mid: Color = Color.hsv(hue % 360f, 1f, 0.78f)
    val deep: Color = Color.hsv(hue % 360f, 1f, 0.45f)
    val dark: Color = Color.hsv(hue % 360f, 1f, 0.15f)
}

private class Ctx(
    val c: Offset,
    val r: Float,
    val t: Float,
    val beat: Float,
    val pal: AuraPal,
    val glow: Float,
    val listening: Boolean,
    val speaking: Boolean,
    val level: Float
) {
    /** Pixels per "design unit" so strokes scale with the aura. */
    val u: Float = r / 78f

    /** Overall swell: heartbeat, plus the user's voice or the assistant's. */
    val k: Float = 1f + 0.035f * beat +
        (if (listening) level.coerceIn(0f, 1f) * 0.22f else 0f) +
        (if (speaking) 0.05f * (0.5f + 0.5f * sin(t * 16f)) else 0f)
}

private fun pt(c: Offset, a: Float, rad: Float) = Offset(c.x + cos(a) * rad, c.y + sin(a) * rad)

private fun DrawScope.halo(x: Ctx, mult: Float, strength: Float = 0.5f) {
    val rad = x.r * mult * x.k
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                x.pal.hot.copy(alpha = (strength * x.glow).coerceIn(0f, 1f)),
                x.pal.deep.copy(alpha = (strength * 0.45f * x.glow).coerceIn(0f, 1f)),
                Color.Transparent
            ),
            center = x.c,
            radius = rad
        ),
        radius = rad,
        center = x.c
    )
}

/** Rings that leave the aura while the user speaks or the assistant answers. */
private fun DrawScope.voiceRings(x: Ctx) {
    if (!x.listening && !x.speaking) return
    for (i in 0..1) {
        val ph = (x.t * 1.1f + i * 0.5f) % 1f
        drawCircle(
            color = x.pal.hot.copy(alpha = ((1f - ph) * 0.55f).coerceIn(0f, 1f)),
            radius = x.r * (1.05f + 0.55f * ph),
            center = x.c,
            style = Stroke(width = 2.5f * x.u)
        )
    }
}

private fun DrawScope.glowCore(x: Ctx, rad: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(x.pal.bright, x.pal.hot, x.pal.mid.copy(alpha = 0.6f), Color.Transparent),
            center = x.c,
            radius = rad
        ),
        radius = rad,
        center = x.c
    )
}

// ---------------------------------------------------------------- twin

private fun DrawScope.drawTwin(x: Ctx) {
    halo(x, 1.7f)
    val big = x.r * x.k
    for (ring in 0..1) {
        val rr = big * (if (ring == 0) 1.0f else 0.72f)
        val dir = if (ring == 0) 1f else -1.4f
        for (i in 0 until 48) {
            val a = x.t * 0.5f * dir + i * (TAU / 48f)
            val wave = 0.5f + 0.5f * sin(x.t * 2f + i * 0.6f * (ring + 1))
            drawLine(
                color = x.pal.hot.copy(alpha = (0.25f + 0.7f * wave).coerceIn(0f, 1f)),
                start = pt(x.c, a, rr * 0.93f),
                end = pt(x.c, a, rr * (1.03f + 0.07f * wave)),
                strokeWidth = 3f * x.u,
                cap = StrokeCap.Round
            )
        }
        drawCircle(
            color = x.pal.mid.copy(alpha = 0.6f),
            radius = rr * 0.9f,
            center = x.c,
            style = Stroke(width = 1.4f * x.u)
        )
    }
    glowCore(x, big * 0.46f * (1f + 0.12f * x.beat))
    drawCircle(
        color = x.pal.bright.copy(alpha = 0.9f),
        radius = big * 0.5f,
        center = x.c,
        style = Stroke(width = 1.6f * x.u)
    )
}

// ---------------------------------------------------------------- vortex

private fun DrawScope.drawVortex(x: Ctx) {
    halo(x, 1.6f, 0.38f)
    val big = x.r * x.k
    for (arm in 0 until 5) {
        for (i in 0 until 46) {
            val s = i / 45f
            val a = arm * (TAU / 5f) + s * 5.2f + x.t * 0.9f
            val rad = big * (1.12f - s * 1.0f)
            val sz = (1.0f + 3.2f * s) * x.u
            drawCircle(
                color = x.pal.hot.copy(alpha = (0.10f + 0.85f * s).coerceIn(0f, 1f)),
                radius = sz,
                center = pt(x.c, a, rad)
            )
        }
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Black, x.pal.dark, Color.Transparent),
            center = x.c,
            radius = big * 0.36f
        ),
        radius = big * 0.36f,
        center = x.c
    )
    drawCircle(
        color = x.pal.bright.copy(alpha = 0.55f + 0.4f * x.beat),
        radius = big * 0.17f,
        center = x.c,
        style = Stroke(width = 2f * x.u)
    )
}

// ---------------------------------------------------------------- bloom

private fun DrawScope.petal(x: Ctx, deg: Float, len: Float, wid: Float, a: Float) {
    rotate(deg, x.c) {
        drawOval(
            brush = Brush.verticalGradient(
                colors = listOf(x.pal.hot.copy(alpha = a), x.pal.deep.copy(alpha = a)),
                startY = x.c.y - len,
                endY = x.c.y
            ),
            topLeft = Offset(x.c.x - wid / 2f, x.c.y - len),
            size = Size(wid, len)
        )
        drawOval(
            color = x.pal.bright.copy(alpha = a * 0.5f),
            topLeft = Offset(x.c.x - wid / 2f, x.c.y - len),
            size = Size(wid, len),
            style = Stroke(width = 1.2f * x.u)
        )
    }
}

private fun DrawScope.drawBloom(x: Ctx) {
    halo(x, 1.6f, 0.4f)
    val open = 0.78f + 0.22f * sin(x.t * 0.9f) + 0.10f * x.beat
    val big = x.r * x.k
    val layers = listOf(Triple(9, 1.00f, 0.60f), Triple(8, 0.80f, 0.55f), Triple(6, 0.58f, 0.50f))
    layers.forEachIndexed { li, (n, scl, a) ->
        for (i in 0 until n) {
            val deg = i * (360f / n) + li * 18f + x.t * (6f - li * 4f)
            petal(x, deg, big * scl * open, big * 0.46f * scl, a)
        }
    }
    glowCore(x, big * 0.26f)
    drawCircle(
        color = Color(0xCCFFFFFF),
        radius = big * 0.05f,
        center = Offset(x.c.x - big * 0.06f, x.c.y - big * 0.07f)
    )
}

// ---------------------------------------------------------------- helix

private fun DrawScope.drawHelix(x: Ctx) {
    halo(x, 1.5f, 0.32f)
    val big = x.r * x.k
    val amp = big * 0.56f
    val n = 34
    for (i in 0..n) {
        val s = i / n.toFloat()
        val y = x.c.y - big + s * big * 2f
        val ph = s * 9f + x.t * 1.8f
        val x1 = x.c.x + amp * sin(ph)
        val x2 = x.c.x - amp * sin(ph)
        val d1 = 0.5f + 0.5f * cos(ph)
        val d2 = 1f - d1
        if (i % 2 == 0) {
            drawLine(
                color = x.pal.mid.copy(alpha = 0.45f),
                start = Offset(x1, y),
                end = Offset(x2, y),
                strokeWidth = 1.6f * x.u
            )
        }
        drawCircle(color = x.pal.hot.copy(alpha = 0.35f + 0.65f * d1), radius = (2f + 3.2f * d1) * x.u, center = Offset(x1, y))
        drawCircle(color = x.pal.bright.copy(alpha = 0.35f + 0.65f * d2), radius = (2f + 3.2f * d2) * x.u, center = Offset(x2, y))
    }
    drawCircle(
        color = x.pal.mid.copy(alpha = 0.35f),
        radius = big * 1.02f,
        center = x.c,
        style = Stroke(width = 1.4f * x.u)
    )
}

// ---------------------------------------------------------------- core (reactor)

private fun DrawScope.drawReactor(x: Ctx) {
    halo(x, 1.55f, 0.35f)
    val big = x.r * x.k
    for (ring in 0 until 4) {
        val rr = big * (1.0f - ring * 0.18f)
        val segs = 3 + ring
        val dir = if (ring % 2 == 0) 1f else -1f
        for (s in 0 until segs) {
            val start = (x.t * (24f + ring * 14f) * dir) + s * (360f / segs)
            drawArc(
                color = (if (ring % 2 == 0) x.pal.hot else x.pal.bright).copy(alpha = 0.85f),
                startAngle = start,
                sweepAngle = 360f / segs * 0.62f,
                useCenter = false,
                topLeft = Offset(x.c.x - rr, x.c.y - rr),
                size = Size(rr * 2f, rr * 2f),
                style = Stroke(width = (4.2f - ring * 0.6f) * x.u, cap = StrokeCap.Butt)
            )
        }
    }
    for (i in 0 until 60) {
        val a = i * (TAU / 60f)
        val len = if (i % 5 == 0) 0.10f else 0.05f
        drawLine(
            color = x.pal.mid.copy(alpha = 0.6f),
            start = pt(x.c, a, big * 1.08f),
            end = pt(x.c, a, big * (1.08f + len)),
            strokeWidth = 1.2f * x.u
        )
    }
    glowCore(x, big * 0.30f * (1f + 0.18f * x.beat))
}

// ---------------------------------------------------------------- sigil

private fun DrawScope.drawSigil(x: Ctx) {
    halo(x, 1.7f, 0.42f)
    val big = x.r * x.k
    val col = x.pal.hot.copy(alpha = (0.55f + 0.4f * x.beat).coerceIn(0f, 1f))
    rotate(x.t * 9f, x.c) {
        drawCircle(color = col, radius = big * 1.02f, center = x.c, style = Stroke(width = 2.6f * x.u))
        drawCircle(color = col, radius = big * 0.90f, center = x.c, style = Stroke(width = 1.2f * x.u))
        for (i in 0 until 48) {
            val a = i * (TAU / 48f)
            val len = if (i % 4 == 0) 0.09f else 0.04f
            drawLine(color = col, start = pt(x.c, a, big * 0.90f), end = pt(x.c, a, big * (0.90f - len)), strokeWidth = 1.4f * x.u)
        }
        val star = Path()
        for (k in 0..5) {
            val idx = (k * 2) % 5
            val a = (-PI.toFloat() / 2f) + idx * (TAU / 5f)
            val p = pt(x.c, a, big * 0.88f)
            if (k == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
        }
        star.close()
        drawPath(star, color = col, style = Stroke(width = 2.2f * x.u, join = StrokeJoin.Round))
        for (idx in 0 until 5) {
            val a = (-PI.toFloat() / 2f) + idx * (TAU / 5f)
            drawCircle(color = x.pal.bright, radius = 3.2f * x.u, center = pt(x.c, a, big * 0.88f))
        }
    }
    rotate(-x.t * 14f, x.c) {
        drawCircle(color = x.pal.mid.copy(alpha = 0.5f), radius = big * 0.36f, center = x.c, style = Stroke(width = 1.4f * x.u))
        for (i in 0 until 6) {
            val a = i * (TAU / 6f)
            drawCircle(color = x.pal.hot.copy(alpha = 0.8f), radius = 2.2f * x.u, center = pt(x.c, a, big * 0.36f))
        }
    }
    glowCore(x, big * 0.22f * (1f + 0.25f * x.beat))
}

// ---------------------------------------------------------------- heart

private fun DrawScope.drawHeart(x: Ctx) {
    halo(x, 1.6f, 0.45f)
    val scale = x.r / 17.5f * (1f + 0.10f * x.beat + (if (x.listening) x.level * 0.15f else 0f))
    val heart = Path()
    val steps = 72
    for (i in 0..steps) {
        val th = i / steps.toFloat() * TAU
        val hx = 16f * sin(th).pow(3)
        val hy = -(13f * cos(th) - 5f * cos(2f * th) - 2f * cos(3f * th) - cos(4f * th))
        val px = x.c.x + hx * scale
        val py = x.c.y + (hy + 1.5f) * scale
        if (i == 0) heart.moveTo(px, py) else heart.lineTo(px, py)
    }
    heart.close()
    drawPath(
        path = heart,
        brush = Brush.radialGradient(
            colors = listOf(x.pal.hot, x.pal.mid, x.pal.deep, x.pal.dark),
            center = Offset(x.c.x - x.r * 0.22f, x.c.y - x.r * 0.30f),
            radius = x.r * 1.3f
        )
    )
    // veins
    for (i in 0 until 9) {
        val a = -0.9f + i * 0.5f
        val p1 = Offset(x.c.x + sin(a) * x.r * 0.15f, x.c.y - x.r * 0.1f)
        val p2 = Offset(x.c.x + sin(a * 1.4f) * x.r * 0.55f, x.c.y + cos(a) * x.r * 0.35f)
        val p3 = Offset(x.c.x + sin(a * 1.6f) * x.r * 0.78f, x.c.y + cos(a) * x.r * 0.62f)
        val v = x.pal.bright.copy(alpha = 0.2f + 0.35f * x.beat)
        drawLine(color = v, start = p1, end = p2, strokeWidth = 1.4f * x.u, cap = StrokeCap.Round)
        drawLine(color = v, start = p2, end = p3, strokeWidth = 1.0f * x.u, cap = StrokeCap.Round)
    }
    drawPath(
        path = heart,
        color = x.pal.bright.copy(alpha = 0.85f),
        style = Stroke(width = 2.2f * x.u, join = StrokeJoin.Round)
    )
    val ph = (x.t * 0.6f) % 1f
    drawCircle(
        color = x.pal.hot.copy(alpha = ((1f - ph) * 0.5f).coerceIn(0f, 1f)),
        radius = x.r * (0.9f + 0.45f * ph),
        center = x.c,
        style = Stroke(width = 2f * x.u)
    )
}

// ---------------------------------------------------------------- storm

private fun DrawScope.drawStorm(x: Ctx) {
    halo(x, 1.7f, 0.42f)
    val big = x.r * x.k
    for (i in 0 until 3) {
        drawArc(
            color = x.pal.mid.copy(alpha = 0.55f),
            startAngle = x.t * (30f + i * 17f) + i * 120f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(x.c.x - big * (1.0f - i * 0.14f), x.c.y - big * (1.0f - i * 0.14f)),
            size = Size(big * 2f * (1.0f - i * 0.14f), big * 2f * (1.0f - i * 0.14f)),
            style = Stroke(width = 4f * x.u, cap = StrokeCap.Round)
        )
    }
    val tick = x.t * 3f
    val slot = tick.toInt()
    val frac = tick - slot
    val flash = (1f - frac).pow(2)
    val rnd = Random(slot * 7919)
    for (b in 0 until 5) {
        var a = rnd.nextFloat() * TAU
        var rad = big * 0.2f
        var prev = pt(x.c, a, rad)
        while (rad < big * 1.05f) {
            rad += big * (0.10f + rnd.nextFloat() * 0.08f)
            a += (rnd.nextFloat() - 0.5f) * 0.55f
            val next = pt(x.c, a, rad)
            drawLine(
                color = Color.White.copy(alpha = (0.15f + 0.85f * flash).coerceIn(0f, 1f)),
                start = prev,
                end = next,
                strokeWidth = (1.4f + 1.8f * flash) * x.u,
                cap = StrokeCap.Round
            )
            drawLine(
                color = x.pal.hot.copy(alpha = (0.25f + 0.6f * flash).coerceIn(0f, 1f)),
                start = prev,
                end = next,
                strokeWidth = (4f + 3f * flash) * x.u,
                cap = StrokeCap.Round
            )
            prev = next
        }
    }
    glowCore(x, big * (0.34f + 0.1f * flash))
}

// ---------------------------------------------------------------- void

private fun DrawScope.drawVoid(x: Ctx) {
    halo(x, 1.6f, 0.30f)
    val big = x.r * x.k
    rotate(-18f, x.c) {
        for (i in 0 until 7) {
            val s = i / 6f
            val w = big * (1.25f - s * 0.55f)
            val h = w * 0.30f
            drawOval(
                color = (if (i % 2 == 0) x.pal.hot else x.pal.bright).copy(alpha = 0.18f + 0.5f * (1f - s)),
                topLeft = Offset(x.c.x - w, x.c.y - h),
                size = Size(w * 2f, h * 2f),
                style = Stroke(width = (3.4f - s * 2f) * x.u)
            )
        }
        for (i in 0 until 40) {
            val a = x.t * (0.8f + (i % 5) * 0.15f) + i * 0.7f
            val s = (i % 7) / 7f
            val w = big * (1.2f - s * 0.6f)
            drawCircle(
                color = x.pal.bright.copy(alpha = 0.8f),
                radius = (1f + (i % 3)) * x.u,
                center = Offset(x.c.x + cos(a) * w, x.c.y + sin(a) * w * 0.30f)
            )
        }
    }
    drawCircle(color = Color.Black, radius = big * 0.36f, center = x.c)
    drawCircle(
        color = x.pal.bright.copy(alpha = 0.7f + 0.3f * x.beat),
        radius = big * 0.37f,
        center = x.c,
        style = Stroke(width = 2.4f * x.u)
    )
    drawArc(
        color = x.pal.hot.copy(alpha = 0.55f),
        startAngle = 200f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(x.c.x - big * 0.55f, x.c.y - big * 0.55f),
        size = Size(big * 1.1f, big * 1.1f),
        style = Stroke(width = 2.2f * x.u, cap = StrokeCap.Round)
    )
}

// ---------------------------------------------------------------- inferno

private fun DrawScope.drawInferno(x: Ctx) {
    halo(x, 1.7f, 0.40f)
    val big = x.r * x.k * 0.78f
    for (layer in 0..1) {
        val flames = Path()
        val n = 36
        for (i in 0 until n) {
            val a = i * (TAU / n)
            val h = big * ((0.34f - layer * 0.14f) + 0.20f * sin(x.t * 3f + i * 1.7f + layer) + 0.10f * sin(x.t * 5.3f + i * 0.9f))
            val sway = 0.05f * sin(x.t * 2f + i)
            val w = 0.07f
            val p0 = pt(x.c, a - w, big)
            val tip = pt(x.c, a + sway, big + max(h, big * 0.08f))
            val p1 = pt(x.c, a + w, big)
            flames.moveTo(p0.x, p0.y)
            flames.lineTo(tip.x, tip.y)
            flames.lineTo(p1.x, p1.y)
            flames.close()
        }
        drawPath(flames, color = (if (layer == 0) x.pal.deep else x.pal.hot).copy(alpha = if (layer == 0) 0.85f else 0.9f))
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(x.pal.bright, x.pal.hot, x.pal.deep, x.pal.dark),
            center = Offset(x.c.x - big * 0.2f, x.c.y - big * 0.25f),
            radius = big * 1.25f
        ),
        radius = big,
        center = x.c
    )
    drawCircle(color = x.pal.bright.copy(alpha = 0.7f), radius = big, center = x.c, style = Stroke(width = 2f * x.u))
}

// ---------------------------------------------------------------- swarm

private fun DrawScope.smallEye(x: Ctx, at: Offset, size: Float, blink: Float, look: Offset) {
    val h = size * 0.62f * blink
    drawOval(color = x.pal.dark, topLeft = Offset(at.x - size * 1.1f, at.y - h * 1.05f), size = Size(size * 2.2f, h * 2.1f))
    drawOval(
        color = x.pal.hot,
        topLeft = Offset(at.x - size, at.y - h),
        size = Size(size * 2f, h * 2f)
    )
    val ip = Offset(at.x + look.x * size * 0.35f, at.y + look.y * h * 0.2f)
    drawOval(
        color = Color.Black,
        topLeft = Offset(ip.x - size * 0.10f, ip.y - h * 0.85f),
        size = Size(size * 0.20f, h * 1.7f)
    )
}

private fun DrawScope.drawSwarm(x: Ctx) {
    halo(x, 1.6f, 0.34f)
    val big = x.r * x.k
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(x.pal.deep, x.pal.dark, Color.Black),
            center = x.c,
            radius = big * 0.52f
        ),
        radius = big * 0.52f,
        center = x.c
    )
    val look = Offset(sin(x.t * 0.7f), cos(x.t * 0.5f) * 0.5f)
    val blink = if (((x.t * 0.35f) % 1f) > 0.94f) 0.15f else 1f
    smallEye(x, x.c, big * 0.30f, blink, look)
    val n = 7
    for (i in 0 until n) {
        val a = x.t * (0.45f + (i % 3) * 0.08f) * (if (i % 2 == 0) 1f else -1f) + i * (TAU / n)
        val rad = big * (0.74f + 0.14f * sin(x.t * 0.9f + i * 1.3f))
        val p = pt(x.c, a, rad)
        val bl = if (((x.t * 0.3f + i * 0.17f) % 1f) > 0.95f) 0.15f else 1f
        smallEye(x, p, big * 0.15f, bl, Offset((x.c.x - p.x) / big, (x.c.y - p.y) / big))
    }
}

// ---------------------------------------------------------------- dispatch

private fun DrawScope.drawStyle(style: String, x: Ctx) {
    when (style) {
        "twin" -> drawTwin(x)
        "vortex" -> drawVortex(x)
        "bloom" -> drawBloom(x)
        "helix" -> drawHelix(x)
        "core" -> drawReactor(x)
        "sigil" -> drawSigil(x)
        "heart" -> drawHeart(x)
        "storm" -> drawStorm(x)
        "void" -> drawVoid(x)
        "inferno" -> drawInferno(x)
        "swarm" -> drawSwarm(x)
        "crown" -> drawCrown(x)
        "rift" -> drawRift(x)
        "ghost" -> drawGhost(x)
        "mandala" -> drawMandala(x)
        else -> drawMiniEye(x)
    }
    voiceRings(x)
}


// ---------------------------------------------------------------- crown

/** Two counter-rotating rings of thorns around a slit-pupil core. */
private fun DrawScope.drawCrown(x: Ctx) {
    halo(x, 1.7f, 0.40f)
    val big = x.r * x.k
    fun ring(count: Int, inner: Float, outer: Float, spin: Float, alpha: Float) {
        for (i in 0 until count) {
            val a = spin + i * TAU / count
            val len = outer * (0.9f + 0.1f * sin(x.t * 2.2f + i * 1.3f) + x.level * 0.12f)
            val half = TAU / count * 0.26f
            val path = Path().apply {
                moveTo(pt(x.c, a - half, big * inner).x, pt(x.c, a - half, big * inner).y)
                lineTo(pt(x.c, a, big * len).x, pt(x.c, a, big * len).y)
                lineTo(pt(x.c, a + half, big * inner).x, pt(x.c, a + half, big * inner).y)
                close()
            }
            drawPath(path, x.pal.hot.copy(alpha = alpha), style = Fill)
            drawPath(path, x.pal.bright.copy(alpha = alpha * 0.9f), style = Stroke(width = 1.2f * x.u))
        }
    }
    ring(18, 0.62f, 1.00f, x.t * 0.45f, 0.70f)
    ring(12, 0.50f, 0.78f, -x.t * 0.75f, 0.85f)
    drawCircle(x.pal.deep.copy(alpha = 0.7f), radius = big * 0.5f, center = x.c, style = Stroke(width = 3f * x.u))
    glowCore(x, big * 0.42f)
    val pw = big * (0.05f + 0.10f * (if (x.speaking || x.listening) 0.6f + x.level else 0.3f * x.beat))
    drawOval(Color.Black, Offset(x.c.x - pw, x.c.y - big * 0.30f), Size(pw * 2f, big * 0.60f))
}

// ---------------------------------------------------------------- rift

/** A glowing tear in the air with forking lightning and an eye in the middle. */
private fun DrawScope.drawRift(x: Ctx) {
    halo(x, 1.5f, 0.34f)
    val big = x.r * x.k
    val seed = (x.t * 7f).toInt()
    fun crack(widthMul: Float, color: Color, strokeW: Float) {
        val p = Path()
        val steps = 14
        for (i in 0..steps) {
            val s = i / steps.toFloat()
            val yy = x.c.y - big * 1.05f + s * big * 2.1f
            val xx = x.c.x + sin(s * 11f + x.t * 1.4f) * big * 0.10f * widthMul +
                sin(s * 27f + seed) * big * 0.035f
            if (i == 0) p.moveTo(xx, yy) else p.lineTo(xx, yy)
        }
        drawPath(p, color, style = Stroke(width = strokeW, cap = StrokeCap.Round))
    }
    crack(1f, x.pal.deep.copy(alpha = 0.55f), 22f * x.u)
    crack(1f, x.pal.hot.copy(alpha = 0.9f), 8f * x.u)
    crack(1f, Color.White.copy(alpha = 0.85f), 2.2f * x.u)
    // Lightning forks, re-rolled several times a second.
    for (k in 0 until 7) {
        val a = (seed * 0.91f + k * 0.9f) % TAU
        var cur = x.c
        val path = Path().apply { moveTo(cur.x, cur.y) }
        for (j in 1..4) {
            val na = a + sin(seed * 1.7f + k * 3f + j * 2.3f) * 0.5f
            cur = pt(cur, na, big * 0.22f)
            path.lineTo(cur.x, cur.y)
        }
        drawPath(path, x.pal.bright.copy(alpha = 0.55f + 0.4f * x.beat),
            style = Stroke(width = 1.6f * x.u, cap = StrokeCap.Round))
    }
    drawOval(Color.Black, Offset(x.c.x - big * 0.30f, x.c.y - big * 0.16f), Size(big * 0.60f, big * 0.32f))
    drawCircle(x.pal.hot, big * 0.12f * (1f + 0.4f * x.level), x.c)
    drawOval(Color.Black, Offset(x.c.x - big * 0.025f, x.c.y - big * 0.12f), Size(big * 0.05f, big * 0.24f))
}

// ---------------------------------------------------------------- ghost

/** A floating spirit: ragged hem, hollow eyes, trailing wisps. */
private fun DrawScope.drawGhost(x: Ctx) {
    halo(x, 1.6f, 0.34f)
    val big = x.r * x.k
    val bob = sin(x.t * 1.6f) * big * 0.07f
    val cx = x.c.x
    val top = x.c.y - big * 0.82f + bob
    val bottom = x.c.y + big * 0.82f + bob
    val halfW = big * 0.62f
    for (layer in 2 downTo 0) {
        val spread = 1f + layer * 0.16f
        val body = Path().apply {
            moveTo(cx - halfW * spread, bottom)
            lineTo(cx - halfW * spread, x.c.y - big * 0.15f + bob)
            cubicTo(cx - halfW * spread, top, cx + halfW * spread, top, cx + halfW * spread, x.c.y - big * 0.15f + bob)
            lineTo(cx + halfW * spread, bottom)
            val waves = 5
            for (i in waves downTo 1) {
                val x0 = cx - halfW * spread + (halfW * 2f * spread) * (i - 1) / waves
                val x1 = cx - halfW * spread + (halfW * 2f * spread) * i / waves
                val wob = sin(x.t * 3f + i * 1.7f + layer) * big * 0.06f
                quadraticBezierTo((x0 + x1) / 2f, bottom + big * 0.18f + wob, x0, bottom)
            }
            close()
        }
        drawPath(body, x.pal.bright.copy(alpha = 0.10f + 0.10f * (3 - layer)), style = Fill)
        drawPath(body, x.pal.hot.copy(alpha = 0.55f - layer * 0.12f), style = Stroke(width = (2.2f - layer * 0.4f) * x.u))
    }
    val ey = x.c.y - big * 0.22f + bob
    val open = 1f + 0.35f * (if (x.speaking || x.listening) x.level else 0f)
    for (side in listOf(-1f, 1f)) {
        val ex = cx + side * big * 0.24f
        drawOval(Color.Black, Offset(ex - big * 0.11f, ey - big * 0.16f * open), Size(big * 0.22f, big * 0.32f * open))
        drawCircle(x.pal.hot.copy(alpha = 0.5f + 0.5f * x.beat), big * 0.035f, Offset(ex, ey + big * 0.02f))
    }
    val mouth = big * (0.07f + 0.17f * (if (x.speaking) x.level else 0.2f * x.beat))
    drawOval(Color.Black, Offset(cx - big * 0.12f, x.c.y + big * 0.14f + bob), Size(big * 0.24f, mouth * 2f))
    for (i in 0 until 14) {
        val a = x.t * 0.6f + i * 0.9f
        drawCircle(x.pal.bright.copy(alpha = 0.55f), (1f + i % 3) * x.u,
            Offset(cx + cos(a) * big * 0.95f, x.c.y + sin(a * 1.3f) * big * 0.8f))
    }
}

// ---------------------------------------------------------------- mandala

/** Layered petals turning against each other around a burning core. */
private fun DrawScope.drawMandala(x: Ctx) {
    halo(x, 1.6f, 0.36f)
    val big = x.r * x.k
    for (layer in 3 downTo 0) {
        val n = 8 + layer * 2
        val dir = if (layer % 2 == 0) 1f else -1f
        val spin = dir * x.t * (0.25f + layer * 0.12f)
        val len = big * (0.42f + layer * 0.19f) * (1f + 0.05f * sin(x.t * 2f + layer))
        for (i in 0 until n) {
            val a = spin + i * TAU / n
            val base = pt(x.c, a, big * 0.14f)
            val tip = pt(x.c, a, len)
            val l = pt(x.c, a - TAU / n * 0.38f, len * 0.62f)
            val r = pt(x.c, a + TAU / n * 0.38f, len * 0.62f)
            val petal = Path().apply {
                moveTo(base.x, base.y)
                quadraticBezierTo(l.x, l.y, tip.x, tip.y)
                quadraticBezierTo(r.x, r.y, base.x, base.y)
                close()
            }
            drawPath(petal, (if (layer % 2 == 0) x.pal.hot else x.pal.mid).copy(alpha = 0.16f + layer * 0.05f), style = Fill)
            drawPath(petal, x.pal.bright.copy(alpha = 0.55f), style = Stroke(width = 1.3f * x.u))
        }
    }
    drawCircle(x.pal.bright.copy(alpha = 0.7f), big * 0.2f, x.c, style = Stroke(width = 2f * x.u))
    glowCore(x, big * 0.24f)
    drawOval(Color.Black, Offset(x.c.x - big * 0.03f, x.c.y - big * 0.14f), Size(big * 0.06f, big * 0.28f))
}

/** A compact native version of the watching eye, used for the gallery preview. */
private fun DrawScope.drawMiniEye(x: Ctx) {
    halo(x, 1.6f, 0.42f)
    val orb = x.r * x.k * 0.95f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(x.pal.mid, x.pal.deep, x.pal.dark),
            center = Offset(x.c.x - orb * 0.25f, x.c.y - orb * 0.3f),
            radius = orb * 1.35f
        ),
        radius = orb,
        center = x.c
    )
    val eye = Offset(x.c.x + sin(x.t * 0.6f) * orb * 0.10f, x.c.y + cos(x.t * 0.8f) * orb * 0.05f)
    val iris = orb * 0.52f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(x.pal.bright, x.pal.mid, x.pal.dark),
            center = eye,
            radius = iris
        ),
        radius = iris,
        center = eye
    )
    drawOval(
        color = Color.Black,
        topLeft = Offset(eye.x - iris * 0.15f, eye.y - iris * 0.82f),
        size = Size(iris * 0.30f, iris * 1.64f)
    )
    for (k in 0 until 12) {
        val a = k * (TAU / 12f) + 0.2f
        drawLine(
            color = x.pal.hot.copy(alpha = 0.3f + 0.4f * x.beat),
            start = pt(eye, a, iris * 1.02f),
            end = pt(x.c, a + 0.1f, orb * 0.97f),
            strokeWidth = 1.3f * x.u,
            cap = StrokeCap.Round
        )
    }
    drawCircle(
        color = x.pal.hot.copy(alpha = 0.8f),
        radius = orb * 1.24f,
        center = x.c,
        style = Stroke(width = 1.5f * x.u)
    )
}

/** Rotates the hue of everything drawn inside, so the eye aura follows the hue picker. */
private fun Modifier.hueShift(deg: Float): Modifier {
    if (abs(deg) < 1f) return this
    return this.drawWithContent {
        val rad = Math.toRadians(deg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val m = ColorMatrix(
            floatArrayOf(
                0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
                0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
                0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        drawIntoCanvas { canvas ->
            val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(m) }
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            drawContent()
            canvas.restore()
        }
    }
}

/**
 * The aura on the home screen. The chosen [style] is drawn, recoloured by [hue],
 * scaled by [size], brightened by [glow] and paced by [speed]. Tapping it is the
 * microphone.
 */
@Composable
fun ZyraxonAura(
    style: String,
    hue: Int,
    scaleFactor: Float,
    glow: Float,
    speed: Float,
    isListening: Boolean,
    isSpeaking: Boolean,
    audioLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sz = scaleFactor.coerceIn(0.6f, 1.5f)
    if (isAvatarStyle(style)) {
        // A real 3D person fills the space the aura used to float in. Its mouth
        // and hands follow the assistant's voice through AvatarBridge.
        AvatarView(
            style = style,
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(top = 30.dp, bottom = 34.dp)
                .graphicsLayer { scaleX = sz.coerceAtMost(1.15f); scaleY = sz.coerceAtMost(1.15f) }
        )
        return
    }
    if (style == "orb" || style !in AuraStyleIds) {
        LivingAuraSphere(
            isListening = isListening,
            isSpeaking = isSpeaking,
            audioLevel = audioLevel,
            onClick = onClick,
            modifier = modifier
                .graphicsLayer { scaleX = sz; scaleY = sz }
                .hueShift(effectiveHue(hue) - 352f)
        )
        return
    }
    val context = LocalContext.current
    val transition = rememberLiveTransition(label = "aura_gallery")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3600f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_600_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "aura_clock"
    )
    val beat by rememberHeartbeat()
    val pal = remember(hue) { AuraPal(effectiveHue(hue)) }
    Box(
        modifier = modifier
            .size(240.dp)
            .graphicsLayer { scaleX = sz; scaleY = sz }
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
            val ctx = Ctx(
                c = Offset(size.width / 2f, size.height / 2f),
                r = min(size.width, size.height) / 2f * 0.62f,
                t = clock * speed,
                beat = beat,
                pal = pal,
                glow = glow,
                listening = isListening,
                speaking = isSpeaking,
                level = audioLevel
            )
            drawStyle(style, ctx)
        }
    }
}

/** A small live preview of one aura style, for the settings gallery. */
@Composable
fun AuraPreview(
    style: String,
    hue: Int,
    clock: Float,
    modifier: Modifier = Modifier
) {
    val pal = remember(hue) { AuraPal(effectiveHue(hue)) }
    Canvas(modifier = modifier) {
        val beatT = (clock * 0.6f) % 1f
        val beat = (max(0f, 1f - abs(beatT - 0.06f) / 0.07f) + 0.6f * max(0f, 1f - abs(beatT - 0.22f) / 0.07f))
            .coerceIn(0f, 1f)
        val ctx = Ctx(
            c = Offset(size.width / 2f, size.height / 2f),
            r = min(size.width, size.height) / 2f * 0.66f,
            t = clock,
            beat = beat,
            pal = pal,
            glow = 1f,
            listening = false,
            speaking = false,
            level = 0f
        )
        drawStyle(if (isAvatarStyle(style)) "mandala" else style, ctx)
    }
}
