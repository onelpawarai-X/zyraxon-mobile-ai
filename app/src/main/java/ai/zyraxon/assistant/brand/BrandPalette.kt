// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.brand

/**
 * Picks a brand's colours out of a logo's pixels, using only arithmetic so it
 * runs the same on the phone and on a desktop test.
 *
 * Pixels are counted in coarse colour buckets; saturated colours count for more
 * than greys, so the logo's real colour wins over its white or black surround.
 */
object BrandPalette {

    data class Palette(
        val primary: String, val secondary: String, val accent: String,
        val background: String, val text: String, val onPrimary: String
    ) {
        fun css(): String = ":root{--brand-primary:$primary;--brand-secondary:$secondary;--brand-accent:$accent;" +
            "--brand-bg:$background;--brand-text:$text;--brand-on-primary:$onPrimary}"
    }

    private class Bucket { var n = 0; var r = 0L; var g = 0L; var b = 0L }

    private fun hex(r: Int, g: Int, b: Int) = "#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    /** `#RRGGBB` or `RRGGBB` (also 3-digit) → an RGB triple, or null. */
    fun parse(s: String): IntArray? {
        var t = s.trim().removePrefix("#")
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val v = t.toInt(16)
        return intArrayOf((v shr 16) and 255, (v shr 8) and 255, v and 255)
    }

    private fun hue(r: Int, g: Int, b: Int): Float {
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        if (mx == mn) return 0f
        val d = (mx - mn).toFloat()
        val h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        return if (h < 0) h + 360f else h
    }

    private fun sat(r: Int, g: Int, b: Int): Float {
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        return if (mx == 0) 0f else (mx - mn).toFloat() / mx
    }

    private fun lum(r: Int, g: Int, b: Int): Double = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0

    private fun mix(c: IntArray, with: Int, t: Double) = IntArray(3) { (c[it] * (1 - t) + with * t).toInt() }

    /** A darker shade of [hex] (0 = same, 1 = black). */
    fun shade(hex: String, amount: Double): String {
        val c = parse(hex) ?: return hex
        val m = mix(c, 0, amount); return hex(m[0], m[1], m[2])
    }

    /** A lighter tint of [hex] (0 = same, 1 = white). */
    fun tint(hex: String, amount: Double): String {
        val c = parse(hex) ?: return hex
        val m = mix(c, 255, amount); return hex(m[0], m[1], m[2])
    }

    fun readableOn(hex: String): String {
        val c = parse(hex) ?: return "#FFFFFF"
        return if (lum(c[0], c[1], c[2]) > 0.6) "#14161C" else "#FFFFFF"
    }

    /** Colours from ARGB pixels. Returns a neutral blue palette when the picture has no usable colour. */
    fun fromPixels(pixels: IntArray): Palette {
        val buckets = HashMap<Int, Bucket>()
        for (p in pixels) {
            val a = (p ushr 24) and 255
            if (a < 128) continue
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            if (minOf(r, g, b) > 238) continue // paper white
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val bk = buckets.getOrPut(key) { Bucket() }
            bk.n++; bk.r += r; bk.g += g; bk.b += b
        }
        data class C(val r: Int, val g: Int, val b: Int, val score: Double)
        val ranked = buckets.values.map {
            val r = (it.r / it.n).toInt(); val g = (it.g / it.n).toInt(); val b = (it.b / it.n).toInt()
            val s = sat(r, g, b)
            val darkPenalty = if (lum(r, g, b) < 0.08) 0.25 else 1.0
            C(r, g, b, it.n * (0.15 + s * s * 1.6) * darkPenalty)
        }.sortedByDescending { it.score }
        fun far(a: C, b: C): Boolean {
            val dh = Math.abs(hue(a.r, a.g, a.b) - hue(b.r, b.g, b.b)).let { minOf(it, 360f - it) }
            val dist = Math.abs(a.r - b.r) + Math.abs(a.g - b.g) + Math.abs(a.b - b.b)
            return dist > 150 || dh > 40f
        }
        val picked = mutableListOf<C>()
        for (c in ranked) { if (picked.none { !far(it, c) }) picked.add(c); if (picked.size == 3) break }
        val base = picked.getOrNull(0) ?: C(47, 84, 150, 1.0)
        val sec = picked.getOrNull(1) ?: C(
            mix(intArrayOf(base.r, base.g, base.b), 0, 0.35)[0], mix(intArrayOf(base.r, base.g, base.b), 0, 0.35)[1],
            mix(intArrayOf(base.r, base.g, base.b), 0, 0.35)[2], 0.0)
        val acc = picked.getOrNull(2) ?: C(
            mix(intArrayOf(base.r, base.g, base.b), 255, 0.35)[0], mix(intArrayOf(base.r, base.g, base.b), 255, 0.35)[1],
            mix(intArrayOf(base.r, base.g, base.b), 255, 0.35)[2], 0.0)
        val primary = hex(base.r, base.g, base.b)
        return Palette(
            primary = primary, secondary = hex(sec.r, sec.g, sec.b), accent = hex(acc.r, acc.g, acc.b),
            background = tint(primary, 0.94), text = shade(primary, 0.82), onPrimary = readableOn(primary)
        )
    }

    /** A palette built around hand-given colours (hex strings), filling in what is missing. */
    fun fromColors(colors: List<String>): Palette? {
        val ok = colors.mapNotNull { c -> parse(c)?.let { hex(it[0], it[1], it[2]) } }
        if (ok.isEmpty()) return null
        val primary = ok[0]
        return Palette(
            primary = primary, secondary = ok.getOrElse(1) { shade(primary, 0.35) }, accent = ok.getOrElse(2) { tint(primary, 0.35) },
            background = tint(primary, 0.94), text = shade(primary, 0.82), onPrimary = readableOn(primary)
        )
    }
}
