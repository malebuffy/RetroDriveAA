package com.codeodyssey.retrodriveaa.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

enum class AppThemeMode {
    LIGHT,
    DARK,
    DARK_RETRO
}

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = DarkOnBackground,
    onSurface = DarkOnSurface
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightOnBackground,
    onSurface = LightOnSurface

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

private val DarkRetroColorScheme = darkColorScheme(
    primary = RetroNeonGreen,
    onPrimary = RetroBlack,
    secondary = RetroNeonMagenta,
    tertiary = RetroNeonBlue,
    background = RetroBackground,
    surface = RetroSurface,
    onBackground = RetroOnBackground,
    onSurface = RetroOnSurface
)

@Composable
fun RetroDriveTheme(
    themeMode: AppThemeMode = if (isSystemInDarkTheme()) AppThemeMode.DARK else AppThemeMode.LIGHT,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && themeMode != AppThemeMode.DARK_RETRO -> {
            val context = LocalContext.current
            if (themeMode == AppThemeMode.DARK) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        themeMode == AppThemeMode.DARK_RETRO -> DarkRetroColorScheme
        themeMode == AppThemeMode.DARK -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typographyForTheme(themeMode),
        content = content
    )
}