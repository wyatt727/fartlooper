package com.wobbz.fartloop.design.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import timber.log.Timber

/**
 * Main Fart-Looper theme composable.
 * Supports dynamic color on Android 12+ with fallback to custom palette.
 * Features Material 3 design system with brand colors as specified in PDR.
 *
 * @param darkTheme Whether to use dark theme (defaults to system preference)
 * @param dynamicColor Whether to use dynamic colors on Android 12+ (default true)
 * @param content The composable content to theme
 */
@Composable
fun FartLooperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,  // Dynamic color available on Android 12+
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic color support on Android 12+ (API 31+)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }.also {
                Timber.d("Using dynamic color scheme (Android 12+)")
            }
        }
        // Fallback to custom brand colors for older Android versions
        darkTheme -> {
            Timber.d("Using custom dark color scheme")
            DarkColorScheme
        }
        else -> {
            Timber.d("Using custom light color scheme")
            LightColorScheme
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FartLooperTypography,
        shapes = FartLooperShapes,
        content = content
    )
}

/**
 * Preview-friendly theme for Compose previews
 */
@Composable
fun FartLooperThemePreview(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    FartLooperTheme(
        darkTheme = darkTheme,
        dynamicColor = false,  // Disable dynamic color for consistent previews
        content = content
    )
}
