package ai.zyraxon.assistant.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.ui.graphics.Color

/**
 * Blood-Red Cyber-Luxury Glass Theme:
 * Deep obsidian velvet, glowing crimson/blood-red crystal borders,
 * luminous silver-white typography, and tactile haptic vibration feedback.
 */
object Glass {
    /** Dark blood obsidian pane. */
    fun fill(alpha: Float): Color =
        Color(0xFF140205).copy(alpha = alpha.coerceIn(0.25f, 0.92f))

    /** A slightly richer pane for sheets that carry a lot of text. */
    fun sheetFill(alpha: Float): Color =
        Color(0xFF180307).copy(alpha = (alpha + 0.28f).coerceIn(0.50f, 0.98f))

    /** A subtler crimson wine pane for chips and list rows. */
    fun rowFill(alpha: Float): Color =
        Color(0xFF28060C).copy(alpha = (alpha * 0.95f).coerceIn(0.20f, 0.80f))

    /** The crystal neon hairline around a pane in vivid blood-red. */
    val border: Color = Color(0x77FF1E44)
    val borderSoft: Color = Color(0x38FF1E44)
    val borderGlow: Color = Color(0xAAFF1744)

    /** Text. Crisp luminous white and warm silver for dark blood glass. */
    val textPrimary: Color = Color(0xFFFFF0F2)
    val textSecondary: Color = Color(0xFFE8B5BD)
    val textMuted: Color = Color(0xFFA66F7B)
    val textDisabled: Color = Color(0xFF6E434B)

    /** Text that sits on a saturated accent chip. */
    val textOnAccent: Color = Color(0xFFFFFFFF)

    // Blood-red & cyber accents
    val pink: Color = Color(0xFFFF1E44)       // Primary Blood-Red
    val cyan: Color = Color(0xFF00E5FF)       // Electric Cyan
    val violet: Color = Color(0xFFD946EF)     // Cyber Orchid
    val green: Color = Color(0xFF10B981)      // Emerald Matrix
    val red: Color = Color(0xFFFF1744)        // Pure Crimson Blood
    val amber: Color = Color(0xFFFF9F1C)      // Fiery Amber

    /** Filled accents, for primary buttons and active pills. */
    val pinkFill: Color = Color(0xFFE50914)   // High-impact Blood Red
    val cyanFill: Color = Color(0xFF00B4D8)
    val violetFill: Color = Color(0xFFC026D3)
    val greenFill: Color = Color(0xFF059669)

    /** Blood Red / Crimson palette shortcuts */
    val bloodRed: Color = Color(0xFFFF1E44)
    val darkBlood: Color = Color(0xFF8B0000)
    val deepObsidian: Color = Color(0xFF080103)

    /**
     * Tactile Haptic Vibration Feedback for buttons and transitions
     */
    fun vibrate(context: Context, milliseconds: Long = 18) {
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            milliseconds,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(milliseconds)
                }
            }
        }
    }

    /** Subtle haptic tick for list scrolling or light selections */
    fun subtleTick(context: Context) {
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(8, 50)
                    )
                }
            }
        }
    }
}

