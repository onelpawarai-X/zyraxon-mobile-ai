// ZYRAXON AI — Copyright (c) 2026 onelpawarai. All rights reserved.
// Distributed under the ZYRAXON Sovereign License, X Edition (ZSL-X).
// See LICENSE in the repository root.

package ai.zyraxon.assistant.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws one icon from [AppIcons] at a fixed size.
 *
 * A thin wrapper over Material's `Icon` so a call site reads `AppIcon(AppIcons.mic)`
 * instead of repeating the size and tint, and so an unknown icon is simply
 * nothing rather than a crash.
 */
@Composable
fun AppIcon(
    icon: ImageVector?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 18.dp
) {
    if (icon == null) return
    Icon(icon, contentDescription, modifier.size(size), tint = tint)
}

/**
 * Draws the icon for a stored key. Used where the key comes from data — a tool
 * call's saved icon, a model's own icon — and may be an emoji from before this
 * set existed.
 */
@Composable
fun AppIconOf(
    key: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 18.dp
) = AppIcon(AppIcons.of(key), contentDescription, modifier, tint, size)
