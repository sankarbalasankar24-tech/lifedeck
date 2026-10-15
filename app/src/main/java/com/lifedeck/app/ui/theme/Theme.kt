package com.lifedeck.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Calming, premium palette: indigo primary, teal secondary, warm rose accent.
private val Indigo = Color(0xFF3D5AFE)
private val IndigoDark = Color(0xFF8C9EFF)
private val Teal = Color(0xFF00BFA5)
private val Rose = Color(0xFFFF6D9D)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E8FF),
    onPrimaryContainer = Color(0xFF0D1B54),
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2F5EA),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Rose,
    background = Color(0xFFF8F9FF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F1F8),
    onBackground = Color(0xFF1A1B25),
    onSurface = Color(0xFF1A1B25),
    onSurfaceVariant = Color(0xFF5B5D6E),
    outline = Color(0xFFDCDDE8),
    error = Color(0xFFD32F2F),
)

private val DarkColors = darkColorScheme(
    primary = IndigoDark,
    onPrimary = Color(0xFF0D1B54),
    primaryContainer = Color(0xFF26356F),
    onPrimaryContainer = Color(0xFFE3E8FF),
    secondary = Color(0xFF4EDBC3),
    onSecondary = Color(0xFF00201B),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFB2F5EA),
    tertiary = Rose,
    background = Color(0xFF121319),
    surface = Color(0xFF191A21),
    surfaceVariant = Color(0xFF23242D),
    onBackground = Color(0xFFE4E5EE),
    onSurface = Color(0xFFE4E5EE),
    onSurfaceVariant = Color(0xFFA6A8BB),
    outline = Color(0xFF3A3B47),
    error = Color(0xFFFF8A80),
)

@Composable
fun LifeDeckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
