package com.mathmaster.app

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Single place where the whole palette is defined.
@Immutable
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val onBackground: Color,
    val muted: Color,
    val accentA: Color,
    val accentB: Color,
    val success: Color,
    val error: Color,
    val warning: Color,
    val isDark: Boolean
) {
    val accent: Brush get() = Brush.linearGradient(listOf(accentA, accentB))
    
    // A dedicated, theme-aware border color for light/dark modes
    val borderColor: Color
        get() = if (isDark) Color.White.copy(alpha = 0.10f) else accentA.copy(alpha = 0.35f)
}

val LightColors = AppColors(
    background = Color(0xFFF5F6FB),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFE9EBF5),
    onBackground = Color(0xFF1B1D2E),
    muted = Color(0xFF2E3143), // Darker gray/blue so it's readable in Light Mode
    accentA = Color(0xFF6C5CE7),
    accentB = Color(0xFF00CEC9),
    success = Color(0xFF00B894),
    error = Color(0xFFFF5C7A),
    warning = Color(0xFFE29A00),
    isDark = false
)

val DarkColors = AppColors(
    background = Color(0xFF0F1020),
    surface = Color(0xFF1A1C33),
    surfaceAlt = Color(0xFF262945),
    onBackground = Color(0xFFF2F3FF),
    muted = Color(0xFFA0A5CC),
    accentA = Color(0xFF8B7CFF),
    accentB = Color(0xFF22D3EE),
    success = Color(0xFF2EE6A8),
    error = Color(0xFFFF7A93),
    warning = Color(0xFFFDCB6E),
    isDark = true
)

val LocalAppColors = staticCompositionLocalOf { LightColors }

object AppTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
}

@Composable
fun MathTheme(themeMode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val c = if (darkTheme) DarkColors else LightColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.accentA, background = c.background, surface = c.surface,
            onSurface = c.onBackground, onBackground = c.onBackground,
            surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.muted, error = c.error
        )
    } else {
        lightColorScheme(
            primary = c.accentA, background = c.background, surface = c.surface,
            onSurface = c.onBackground, onBackground = c.onBackground,
            surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.muted, error = c.error
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalAppColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
