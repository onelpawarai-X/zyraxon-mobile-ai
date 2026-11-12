// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

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

private val NeutralDarkScheme = darkColorScheme(
    primary = Color(0xFF8B7BFF), onPrimary = Color.White,
    background = Color(0xFF0A0C12), onBackground = Color(0xFFF2F4FA),
    surface = Color(0xFF151923), onSurface = Color(0xFFF2F4FA),
    surfaceVariant = Color(0xFF1E2330), onSurfaceVariant = Color(0xFFB9C0D4)
)

private val LightScheme = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFF5B3DF5), onPrimary = Color.White,
    background = Color(0xFFF3F4F9), onBackground = Color(0xFF14161C),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFEEF0F6), onSurfaceVariant = Color(0xFF3F4558)
)

@Composable
fun ZyraxonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = when (Glass.mode) {
            ZyThemeMode.HORROR -> DarkColorScheme
            ZyThemeMode.DARK -> NeutralDarkScheme
            ZyThemeMode.LIGHT -> LightScheme
        },
        content = content
    )
}
