package com.example.getconnected.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0B2E6F),
    secondary = BrandCyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF6FF),
    onSecondaryContainer = Color(0xFF003A47),
    tertiary = BrandGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6F7E8),
    onTertiaryContainer = Color(0xFF00452D),
    error = DangerRed,
    background = LightBackground,
    onBackground = BrandInk,
    surface = LightSurface,
    onSurface = BrandInk,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = BrandInkSoft,
    outline = BrandLine,
    outlineVariant = BrandLine
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF002B67),
    primaryContainer = Color(0xFF0B3E8F),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = BrandCyanDark,
    onSecondary = Color(0xFF003A47),
    secondaryContainer = Color(0xFF004E5E),
    onSecondaryContainer = Color(0xFFCFF6FF),
    tertiary = BrandGreenDark,
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF005138),
    onTertiaryContainer = Color(0xFFD6F7E8),
    error = Color(0xFFFFB4AB),
    background = DarkBackground,
    onBackground = Color(0xFFE6EAF2),
    surface = DarkSurface,
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB7C1D3),
    outline = Color(0xFF3A4658),
    outlineVariant = Color(0xFF2A3341)
)

@Composable
fun GetConnectedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default so the brand palette stays consistent across devices.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}