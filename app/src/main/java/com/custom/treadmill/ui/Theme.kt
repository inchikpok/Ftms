package com.custom.treadmill.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF0D47A1),
    onPrimary = Color.White,
    secondary = Color(0xFF1976D2),
    tertiary = Color(0xFFFFB300),
    surfaceVariant = Color(0xFFEBEBEB)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF64B5F6),
    onPrimary = Color(0xFF00325A),
    secondary = Color(0xFF90CAF9),
    tertiary = Color(0xFFFFCA28),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurface = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFFBDBDBD)
)

/**
 * [ФИКС] Семантические цвета, которых нет в MaterialTheme.colorScheme.
 * Разделены на группы:
 *  - background / filled: используются как фон кнопок и бейджей, с белым текстом
 *    поверх. Одинаковы в обеих темах — контраст с белым везде достаточный.
 *  - on-surface: используются как цвет текста/иконки/точки на поверхности темы.
 *    В тёмной теме берутся более светлые оттенки, чтобы читались на 0xFF1E1E1E.
 *  - chart / speed: палитра для графиков и мини-баров. Одинакова в обеих темах —
 *    это заполненные области, у них и так хороший контраст.
 */
@Immutable
data class TreadmillColors(
    // Filled backgrounds (white text on top) — same in both themes
    val exerciseRed: Color,
    val successGreen: Color,
    val warningYellow: Color,

    // Accents on theme surfaces — lighter in dark theme
    val metricRed: Color,
    val metricBlue: Color,
    val statusDisconnected: Color,
    val statusConnecting: Color,
    val statusConnected: Color,
    val statusError: Color,

    // Chart series — same in both themes
    val chartBlue: Color,
    val chartGreen: Color,
    val chartYellow: Color,
    val chartOrange: Color,
    val chartRed: Color,
    val chartPurple: Color,

    // Speed levels for mini-chart — same in both themes
    val speedLow: Color,
    val speedMid: Color,
    val speedMidHigh: Color,
    val speedHigh: Color,
    val speedVeryHigh: Color
)

private val LightTreadmillColors = TreadmillColors(
    exerciseRed = Color(0xFFC62828),
    successGreen = Color(0xFF2E7D32),
    warningYellow = Color(0xFFF9A825),
    metricRed = Color(0xFFE53935),
    metricBlue = Color(0xFF1976D2),
    statusDisconnected = Color(0xFF9E9E9E),
    statusConnecting = Color(0xFFF9A825),
    statusConnected = Color(0xFF2E7D32),
    statusError = Color(0xFFC62828),
    chartBlue = Color(0xFF64B5F6),
    chartGreen = Color(0xFF81C784),
    chartYellow = Color(0xFFFFD54F),
    chartOrange = Color(0xFFFFB74D),
    chartRed = Color(0xFFE57373),
    chartPurple = Color(0xFFAB47BC),
    speedLow = Color(0xFF29B6F6),
    speedMid = Color(0xFF66BB6A),
    speedMidHigh = Color(0xFFFFCA28),
    speedHigh = Color(0xFFFF7043),
    speedVeryHigh = Color(0xFFE53935)
)

private val DarkTreadmillColors = TreadmillColors(
    // Filled — same values as light. Белый текст поверх даёт одинаковый контраст
    // в обеих темах, а на тёмном фоне сама кнопка хорошо выделяется.
    exerciseRed = Color(0xFFC62828),
    successGreen = Color(0xFF2E7D32),
    warningYellow = Color(0xFFF9A825),
    // Accents — светлее для контраста с 0xFF1E1E1E
    metricRed = Color(0xFFFF6E6E),
    metricBlue = Color(0xFF64B5F6),
    statusDisconnected = Color(0xFFBDBDBD),
    statusConnecting = Color(0xFFFFCA28),
    statusConnected = Color(0xFF66BB6A),
    statusError = Color(0xFFEF5350),
    // Charts — те же, что в light: заполненные области хорошо видны на тёмном фоне.
    // Исключение — chartPurple (используется как тонкая пунктирная линия наклона),
    // её берём светлее, иначе 2dp линии теряются на 0xFF1E1E1E.
    chartBlue = Color(0xFF64B5F6),
    chartGreen = Color(0xFF81C784),
    chartYellow = Color(0xFFFFD54F),
    chartOrange = Color(0xFFFFB74D),
    chartRed = Color(0xFFE57373),
    chartPurple = Color(0xFFCE93D8),
    speedLow = Color(0xFF29B6F6),
    speedMid = Color(0xFF66BB6A),
    speedMidHigh = Color(0xFFFFCA28),
    speedHigh = Color(0xFFFF7043),
    speedVeryHigh = Color(0xFFE53935)
)

val LocalTreadmillColors = staticCompositionLocalOf { LightTreadmillColors }

@Composable
fun TreadmillTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val treadmillColors = if (darkTheme) DarkTreadmillColors else LightTreadmillColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalTreadmillColors provides treadmillColors) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
