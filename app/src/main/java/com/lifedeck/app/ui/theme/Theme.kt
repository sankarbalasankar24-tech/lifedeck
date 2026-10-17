package com.lifedeck.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 1. Botanical Nocturne (Studio Preview Signature Default)
private val BotanicalDarkColors = darkColorScheme(
    primary = Color(0xFF4EDEA3),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF183B2E),
    onPrimaryContainer = Color(0xFF6FFBBE),
    secondary = Color(0xFF6FFBBE),
    onSecondary = Color(0xFF003824),
    secondaryContainer = Color(0xFF142E25),
    onSecondaryContainer = Color(0xFFB2F5EA),
    tertiary = Color(0xFFF59E0B),
    background = Color(0xFF0E1514),
    surface = Color(0xFF141D1B),
    surfaceVariant = Color(0xFF1B2724),
    onBackground = Color(0xFFDDE4E2),
    onSurface = Color(0xFFF2F7F4),
    onSurfaceVariant = Color(0xFF9BB3AA),
    outline = Color(0xFF273B37),
    outlineVariant = Color(0xFF1F302B),
    error = Color(0xFFFF6B6B),
)

// 2. OLED Obsidian (Pitch black & mint)
private val ObsidianColors = darkColorScheme(
    primary = Color(0xFF6FFBBE),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF122820),
    onPrimaryContainer = Color(0xFF6FFBBE),
    secondary = Color(0xFF4EDEA3),
    onSecondary = Color(0xFF003824),
    background = Color(0xFF000000),
    surface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFF141414),
    onBackground = Color(0xFFE5E5E5),
    onSurface = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF1F1F1F),
    outlineVariant = Color(0xFF181818),
    error = Color(0xFFFF6B6B),
)

// 3. Deep Forest (Spruce & Sage)
private val DeepForestColors = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF143B30),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF6EE7B7),
    onSecondary = Color(0xFF003824),
    background = Color(0xFF0A1411),
    surface = Color(0xFF101E1A),
    surfaceVariant = Color(0xFF182E27),
    onBackground = Color(0xFFD6E5DF),
    onSurface = Color(0xFFF0FDF4),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF1F3F35),
    outlineVariant = Color(0xFF19332B),
    error = Color(0xFFFF6B6B),
)

// 4. Solar Dusk (Charcoal & Amber Glow)
private val SolarDuskColors = darkColorScheme(
    primary = Color(0xFFFBBF24),
    onPrimary = Color(0xFF451A03),
    primaryContainer = Color(0xFF422006),
    onPrimaryContainer = Color(0xFFFDE68A),
    secondary = Color(0xFFF59E0B),
    onSecondary = Color(0xFF451A03),
    background = Color(0xFF15100E),
    surface = Color(0xFF1E1714),
    surfaceVariant = Color(0xFF2D221E),
    onBackground = Color(0xFFEBDCD7),
    onSurface = Color(0xFFFFFBEB),
    onSurfaceVariant = Color(0xFFA8A29E),
    outline = Color(0xFF3F302A),
    outlineVariant = Color(0xFF342621),
    error = Color(0xFFFF6B6B),
)

// 5. Botanical Light (Crisp daylight)
private val BotanicalLightColors = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF10B981),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA7F3D0),
    onSecondaryContainer = Color(0xFF064E3B),
    background = Color(0xFFF4F8F6),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE5EDE9),
    onBackground = Color(0xFF192B23),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCFDED7),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
)

@Composable
fun LifeDeckTheme(
    darkTheme: Boolean = true,
    palette: String = "BOTANICAL_DARK",
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        !darkTheme || palette == "BOTANICAL_LIGHT" -> BotanicalLightColors
        palette == "OBSIDIAN" -> ObsidianColors
        palette == "DEEP_FOREST" -> DeepForestColors
        palette == "SOLAR_DUSK" -> SolarDuskColors
        else -> BotanicalDarkColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
