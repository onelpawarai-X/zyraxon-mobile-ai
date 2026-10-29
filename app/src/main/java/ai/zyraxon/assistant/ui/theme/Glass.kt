package ai.zyraxon.assistant.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The frosted-glass look the user asked for: white, translucent panels with
 * clearly legible near-black text on top. The old panels were almost opaque
 * black (`0xD90B0A1E`), which is what made the whole screen feel dark.
 *
 * [glassFill] takes the user's alpha so the Settings slider can go from a clear
 * pane to a frosted one without touching any component.
 */
object Glass {
    /** White pane. Alpha is the only thing the user tunes. */
    fun fill(alpha: Float): Color = Color.White.copy(alpha = alpha.coerceIn(0.06f, 0.6f))

    /** A slightly stronger pane for sheets that carry a lot of text. */
    fun sheetFill(alpha: Float): Color =
        Color.White.copy(alpha = (alpha + 0.14f).coerceIn(0.10f, 0.72f))

    /** A subtler pane for chips and list rows sitting on top of a panel. */
    fun rowFill(alpha: Float): Color =
        Color.White.copy(alpha = (alpha * 0.7f).coerceIn(0.04f, 0.42f))

    /** The hairline around a pane. White glass needs a cool edge, not a bright one. */
    val border: Color = Color(0x59FFFFFF)
    val borderSoft: Color = Color(0x33FFFFFF)

    /** Text. Near-black, never pure black — pure black on white glass looks harsh. */
    val textPrimary: Color = Color(0xFF0A0C14)
    val textSecondary: Color = Color(0xFF3A4050)
    val textMuted: Color = Color(0xFF5C6478)
    val textDisabled: Color = Color(0xFF8A90A0)

    /** Text that sits on a saturated accent chip. */
    val textOnAccent: Color = Color(0xFFFFFFFF)

    // Accents, darkened so they stay readable as text on a white pane.
    val pink: Color = Color(0xFFC2185B)
    val cyan: Color = Color(0xFF00707F)
    val violet: Color = Color(0xFF4F46E5)
    val green: Color = Color(0xFF047857)
    val red: Color = Color(0xFFB91C1C)
    val amber: Color = Color(0xFF92400E)

    /** Filled accents, for buttons that carry white text. */
    val pinkFill: Color = Color(0xFFD81B60)
    val cyanFill: Color = Color(0xFF0891B2)
    val violetFill: Color = Color(0xFF5B4BE0)
    val greenFill: Color = Color(0xFF059669)
}
