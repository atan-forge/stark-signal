package com.atan.starkaudio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF211300),
    primaryContainer = Color(0xFF3A260B),
    onPrimaryContainer = AmberSoft,
    secondary = InkMuted,
    onSecondary = OledBlack,
    background = OledBlack,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = InkMuted,
    outline = Divider,
    error = Error,
    onError = OledBlack
)

@Composable
fun StarkSignalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StarkColors, typography = StarkTypography, shapes = StarkShapes, content = content)
}
