// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFF97316),
        secondary = Color(0xFFEC4899),
        tertiary = Color(0xFFFBBF24),
        background = Color(0xFF1A0A2E),
        surface = Color(0xFF1A0A2E),
        // Elevated surfaces (bottom sheets, dialogs, menus) step slightly lighter
        // in the SAME purple hue instead of Material 3's neutral-grey defaults, so
        // they read as part of the app rather than as darker/greyer panels. Values
        // keep the base hue (267°) and saturation, raising only lightness.
        surfaceVariant = Color(0xFF250E41),
        surfaceContainerLowest = Color(0xFF10061C),
        surfaceContainerLow = Color(0xFF1F0C36),
        surfaceContainer = Color(0xFF220D3D),
        surfaceContainerHigh = Color(0xFF270F45),
        surfaceContainerHighest = Color(0xFF2C114D),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color.White,
        onSurface = Color.White,
        // Secondary text and dividers on those purple surfaces.
        onSurfaceVariant = Color(0xFFB6A6C9),
        outline = Color(0xFF896AAF),
        outlineVariant = Color(0xFF4A3267),
    )

@Composable
fun EidoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
