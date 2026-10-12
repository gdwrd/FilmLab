package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF865819),
    onPrimary = Color(0xFFFAF9F6),
    primaryContainer = Color(0xFF2C2924),
    onPrimaryContainer = Color(0xFFFAF9F6),
    secondary = Color(0xFF865819),
    secondaryContainer = Color(0xFFE5E1DA),
    onSecondaryContainer = Color(0xFF625C52),
    background = Color(0xFFFAF9F6),
    onBackground = Color(0xFF252420),
    surface = Color(0xFFFAF9F6),
    onSurface = Color(0xFF252420),
    surfaceVariant = Color(0xFFEEECE6),
    onSurfaceVariant = Color(0xFF716D65),
    outline = Color(0xFF716D65),
    outlineVariant = Color(0xFFDEDBD3)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8B76B),
    onPrimary = Color(0xFF211B13),
    primaryContainer = Color(0xFFE8B76B),
    onPrimaryContainer = Color(0xFF211B13),
    secondary = Color(0xFFE8B76B),
    secondaryContainer = Color(0xFF363431),
    onSecondaryContainer = Color(0xFFDDD7CD),
    background = Color(0xFF181819),
    onBackground = Color(0xFFF4F2ED),
    surface = Color(0xFF181819),
    onSurface = Color(0xFFF4F2ED),
    surfaceVariant = Color(0xFF272727),
    onSurfaceVariant = Color(0xFFADA9A2),
    outline = Color(0xFFADA9A2),
    outlineVariant = Color(0xFF363533)
)

@Composable
fun FilmLabTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography, content = content)
}
