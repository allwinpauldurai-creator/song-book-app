package com.imsbooks.songbook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Same brand palette as the web app (frontend/src/theme/theme.ts), light and dark.
private val Light = lightColorScheme(
    primary = Color(0xFF3949AB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFFB45309),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFF6F7FB),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEEF0F7),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9FA8FF),
    onPrimary = Color(0xFF0B1A73),
    primaryContainer = Color(0xFF2A3696),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFF4DB6AC),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF005147),
    onSecondaryContainer = Color(0xFFB2DFDB),
    tertiary = Color(0xFFFFB77C),
    background = Color(0xFF12131A),
    surface = Color(0xFF12131A),
    surfaceContainerLowest = Color(0xFF0D0E14),
    surfaceContainerLow = Color(0xFF1C1D26),
    surfaceContainer = Color(0xFF22232D),
)

@Composable
fun SongBookTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(10.dp),
            large = RoundedCornerShape(14.dp),
        ),
        content = content,
    )
}
