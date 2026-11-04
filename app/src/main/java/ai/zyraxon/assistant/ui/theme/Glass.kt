package ai.zyraxon.assistant.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** The three looks of the app. */
enum class ZyThemeMode(val title: String, val icon: String) {
    HORROR("ভয়ঙ্কর", "🩸"),
    DARK("ডার্ক", "🌙"),
    LIGHT("লাইট", "☀️")
}

private class Pal(
    val base: Color, val sheet: Color, val row: Color,
    val border: Color, val borderSoft: Color, val borderGlow: Color,
    val tp: Color, val ts: Color, val tm: Color, val td: Color,
    val accent: Color, val accent2: Color, val accent3: Color, val accentFill: Color,
    val screen: Color, val screen2: Color, val light: Boolean
)

private val HorrorPal = Pal(
    base = Color(0xFF0E0104), sheet = Color(0xFF100206), row = Color(0xFF2E050C),
    border = Color(0xAAFF1E44), borderSoft = Color(0x55FF1E44), borderGlow = Color(0xAAFF1744),
    tp = Color(0xFFFFF0F2), ts = Color(0xFFE8B5BD), tm = Color(0xFFA66F7B), td = Color(0xFF6E434B),
    accent = Color(0xFFFF1E44), accent2 = Color(0xFFFF6B4A), accent3 = Color(0xFFD946EF), accentFill = Color(0xFFE50914),
    screen = Color(0xFF080103), screen2 = Color(0xFF1A0408), light = false
)

private val DarkPal = Pal(
    base = Color(0xFF12151D), sheet = Color(0xFF151923), row = Color(0xFF1E2330),
    border = Color(0x55FFFFFF), borderSoft = Color(0x26FFFFFF), borderGlow = Color(0x668B7BFF),
    tp = Color(0xFFF2F4FA), ts = Color(0xFFB9C0D4), tm = Color(0xFF7D869D), td = Color(0xFF515873),
    accent = Color(0xFF8B7BFF), accent2 = Color(0xFF37D6C5), accent3 = Color(0xFFE879F9), accentFill = Color(0xFF6D5BF0),
    screen = Color(0xFF0A0C12), screen2 = Color(0xFF151A26), light = false
)

private val LightPal = Pal(
    base = Color(0xFFFFFFFF), sheet = Color(0xFFFFFFFF), row = Color(0xFFEEF0F6),
    border = Color(0x33000000), borderSoft = Color(0x1F000000), borderGlow = Color(0x665B3DF5),
    tp = Color(0xFF14161C), ts = Color(0xFF3F4558), tm = Color(0xFF6B7287), td = Color(0xFF9AA0B2),
    accent = Color(0xFF5B3DF5), accent2 = Color(0xFF0E9F8E), accent3 = Color(0xFFC026D3), accentFill = Color(0xFF5B3DF5),
    screen = Color(0xFFF3F4F9), screen2 = Color(0xFFE6E9F3), light = true
)

/**
 * The palette every screen reads. Each colour is a getter over the current
 * theme, and the theme is Compose state, so changing it repaints everything
 * that has already been drawn — no screen has to be told.
 */
object Glass {
    /** Which of the three looks is on. */
    var mode: ZyThemeMode by mutableStateOf(ZyThemeMode.HORROR)

    private val p: Pal get() = when (mode) {
        ZyThemeMode.HORROR -> HorrorPal
        ZyThemeMode.DARK -> DarkPal
        ZyThemeMode.LIGHT -> LightPal
    }

    val isLight: Boolean get() = p.light
    val isHorror: Boolean get() = mode == ZyThemeMode.HORROR

    /** The screen behind everything. */
    val screen: Color get() = p.screen
    val screen2: Color get() = p.screen2

    /** A glass pane. */
    fun fill(alpha: Float): Color =
        if (p.light) p.base.copy(alpha = alpha.coerceIn(0.62f, 0.97f))
        else p.base.copy(alpha = alpha.coerceIn(0.30f, 0.94f))

    /** A richer pane for sheets that carry a lot of text. */
    fun sheetFill(alpha: Float): Color =
        if (p.light) p.sheet.copy(alpha = (alpha + 0.30f).coerceIn(0.85f, 0.99f))
        else p.sheet.copy(alpha = (alpha + 0.30f).coerceIn(0.55f, 0.98f))

    /** A subtler pane for chips and list rows. */
    fun rowFill(alpha: Float): Color =
        if (p.light) p.row.copy(alpha = (alpha + 0.45f).coerceIn(0.6f, 1f))
        else p.row.copy(alpha = (alpha * 0.95f).coerceIn(0.25f, 0.85f))

    val border: Color get() = p.border
    val borderSoft: Color get() = p.borderSoft
    val borderGlow: Color get() = p.borderGlow

    val textPrimary: Color get() = p.tp
    val textSecondary: Color get() = p.ts
    val textMuted: Color get() = p.tm
    val textDisabled: Color get() = p.td

    /** Text that sits on a saturated accent chip. */
    val textOnAccent: Color = Color(0xFFFFFFFF)

    // Accents
    val pink: Color get() = p.accent
    val cyan: Color get() = p.accent2
    val violet: Color get() = p.accent3
    val green: Color get() = Color(0xFF10B981)
    val red: Color get() = if (mode == ZyThemeMode.HORROR) Color(0xFFFF1744) else Color(0xFFEF4444)
    val amber: Color get() = Color(0xFFFF9F1C)

    /** Filled accents, for primary buttons and active pills. */
    val pinkFill: Color get() = p.accentFill
    val cyanFill: Color get() = if (mode == ZyThemeMode.HORROR) Color(0xFFD62828) else p.accent2
    val violetFill: Color get() = Color(0xFFC026D3)
    val greenFill: Color get() = Color(0xFF059669)

    val bloodRed: Color get() = p.accent
    val darkBlood: Color get() = if (mode == ZyThemeMode.HORROR) Color(0xFF8B0000) else p.row
    val deepObsidian: Color get() = p.screen

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

