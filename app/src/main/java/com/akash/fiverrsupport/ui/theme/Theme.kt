package com.akash.fiverrsupport.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryGreen,
    onPrimary = White,
    primaryContainer = PrimaryGreenDark,
    onPrimaryContainer = White,

    secondary = SecondaryBlue,
    onSecondary = White,
    secondaryContainer = SecondaryBlueDark,
    onSecondaryContainer = White,

    tertiary = AccentTeal,
    onTertiary = White,

    background = DarkBackground,
    onBackground = White,

    surface = DarkSurface,
    onSurface = White,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = NeutralLight,

    error = ErrorRed,
    onError = White,

    outline = NeutralMedium,
    outlineVariant = NeutralDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryGreen,
    onPrimary = White,
    primaryContainer = PrimaryGreenLight,
    onPrimaryContainer = NeutralDark,

    secondary = SecondaryBlue,
    onSecondary = White,
    secondaryContainer = SecondaryBlueLight,
    onSecondaryContainer = NeutralDark,

    tertiary = AccentTeal,
    onTertiary = White,

    background = NeutralBg,
    onBackground = NeutralDark,

    surface = White,
    onSurface = NeutralDark,
    surfaceVariant = NeutralBg,
    onSurfaceVariant = NeutralMedium,

    error = ErrorRed,
    onError = White,

    outline = NeutralLight,
    outlineVariant = NeutralLight.copy(alpha = 0.5f)
)

@Composable
fun FiverrSupportTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disabled dynamic color
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}