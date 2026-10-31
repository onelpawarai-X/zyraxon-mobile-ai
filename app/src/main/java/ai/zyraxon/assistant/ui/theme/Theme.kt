package ai.zyraxon.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val VoidBlack = Color(0xFF050000)
val DarkBlood = Color(0xFF1A0000)
val BloodRed = Color(0xFF8B0000)
val BloodPink = Color(0xFFFF1744)
val DeepBlood = Color(0xFF4A0000)
val GlassSurface = Color(0xCC1A0000)
val GlassSurfaceLight = Color(0x992E0000)

val TextPrimary = Color(0xFFFFEBEB)
val TextSecondary = Color(0xFFFF9999)
val TextMuted = Color(0xFF8B4444)
val BorderGlow = Color(0x66FF1744)

private val DarkColorScheme = darkColorScheme(
    primary = BloodPink,
    onPrimary = Color.White,
    primaryContainer = BloodRed,
    onPrimaryContainer = Color.White,
    secondary = BloodRed,
    onSecondary = Color.White,
    secondaryContainer = DeepBlood,
    onSecondaryContainer = TextPrimary,
    tertiary = BloodPink,
    background = VoidBlack,
    onBackground = TextPrimary,
    surface = DarkBlood,
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
