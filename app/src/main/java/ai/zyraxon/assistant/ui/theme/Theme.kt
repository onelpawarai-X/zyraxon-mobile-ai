package ai.zyraxon.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val VoidBlack = Color(0xFF05060F)
val DarkNavy = Color(0xFF0B0E1E)
val GlassSurface = Color(0xE613172E)
val GlassSurfaceLight = Color(0xB31E2342)
val NeonCyan = Color(0xFF00F0FF)
val ElectricBlue = Color(0xFF38BDF8)
val NeonMagenta = Color(0xFFD946EF)
val HotPink = Color(0xFFEC4899)
val DeepViolet = Color(0xFF8B5CF6)
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextMuted = Color(0xFF64748B)
val BorderGlow = Color(0x3338BDF8)

private val DarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = VoidBlack,
    primaryContainer = DeepViolet,
    onPrimaryContainer = Color.White,
    secondary = NeonMagenta,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3B185F),
    onSecondaryContainer = Color(0xFFF5D0FE),
    tertiary = ElectricBlue,
    background = VoidBlack,
    onBackground = TextPrimary,
    surface = DarkNavy,
    onSurface = TextPrimary,
    surfaceVariant = GlassSurface,
    onSurfaceVariant = TextSecondary
)

@Composable
fun ZyraxonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
