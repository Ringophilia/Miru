// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.design

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class MiruColors(
    val success: Color,
    val adblock: Color,
    val incognitoSurface: Color = Color(0xFF221D1B),
    val incognitoContainer: Color = Color(0xFF36302C),
)

private val LocalMiruColors = staticCompositionLocalOf {
    MiruColors(success = Color(0xFF47643B), adblock = Color(0xFF47643B))
}

object MiruTheme {
    val colors: MiruColors @Composable get() = LocalMiruColors.current
}

// Taken from the launcher icon: apple red, paper, ink and leaf green (design/tokens.json).
// Every role is set so Material components never fall back to the baseline purple palette.
private val LightColors = lightColorScheme(
    primary = Color(0xFFBE1625), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD7), onPrimaryContainer = Color(0xFF410007),
    inversePrimary = Color(0xFFFFB3AE),
    secondary = Color(0xFF775650), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2E1D9), onSecondaryContainer = Color(0xFF2C1510),
    tertiary = Color(0xFF47643B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8EBB4), onTertiaryContainer = Color(0xFF062103),
    background = Color(0xFFFAF5EA), onBackground = Color(0xFF2B1E1A),
    surface = Color(0xFFFAF5EA), onSurface = Color(0xFF2B1E1A),
    surfaceVariant = Color(0xFFECE2D4), onSurfaceVariant = Color(0xFF6D605A),
    surfaceTint = Color(0xFFBE1625),
    surfaceDim = Color(0xFFE0D7C8), surfaceBright = Color(0xFFFAF5EA),
    surfaceContainerLowest = Color(0xFFFFFDF8), surfaceContainerLow = Color(0xFFF6F0E4),
    surfaceContainer = Color(0xFFF1EADD), surfaceContainerHigh = Color(0xFFE8DFD0),
    surfaceContainerHighest = Color(0xFFE1D7C7),
    inverseSurface = Color(0xFF362E2B), inverseOnSurface = Color(0xFFF8EEE7),
    outline = Color(0xFF857770), outlineVariant = Color(0xFFDDD2C3),
    error = Color(0xFFC4381A), onError = Color.White,
    errorContainer = Color(0xFFFFDBCF), onErrorContainer = Color(0xFF3A0B00),
    scrim = Color.Black,
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB3AE), onPrimary = Color(0xFF680010),
    primaryContainer = Color(0xFF8F0F22), onPrimaryContainer = Color(0xFFFFDAD7),
    inversePrimary = Color(0xFFBE1625),
    secondary = Color(0xFFE7BDB4), onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF5D3F3A), onSecondaryContainer = Color(0xFFFFDAD4),
    tertiary = Color(0xFFA9CE97), onTertiary = Color(0xFF163808),
    tertiaryContainer = Color(0xFF304D25), onTertiaryContainer = Color(0xFFC5EBB1),
    background = Color(0xFF1F1917), onBackground = Color(0xFFF0E6DE),
    surface = Color(0xFF1F1917), onSurface = Color(0xFFF0E6DE),
    surfaceVariant = Color(0xFF4F4540), onSurfaceVariant = Color(0xFFB5A8A0),
    surfaceTint = Color(0xFFFFB3AE),
    surfaceDim = Color(0xFF1F1917), surfaceBright = Color(0xFF463C38),
    surfaceContainerLowest = Color(0xFF191412), surfaceContainerLow = Color(0xFF241E1B),
    surfaceContainer = Color(0xFF2B2421), surfaceContainerHigh = Color(0xFF382F2B),
    surfaceContainerHighest = Color(0xFF433935),
    inverseSurface = Color(0xFFF0E6DE), inverseOnSurface = Color(0xFF362E2B),
    outline = Color(0xFFA0918A), outlineVariant = Color(0xFF4A3F3A),
    error = Color(0xFFFFB59C), onError = Color(0xFF5C1900),
    errorContainer = Color(0xFF812800), onErrorContainer = Color(0xFFFFDBCF),
    scrim = Color.Black,
)

private val MiruTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun MiruTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    incognito: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = incognito || when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        incognito -> DarkColors.copy(surface = Color(0xFF221D1B), surfaceContainer = Color(0xFF36302C))
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val extraColors = MiruColors(
        success = if (dark) Color(0xFFA9CE97) else Color(0xFF47643B),
        adblock = if (dark) Color(0xFFA9CE97) else Color(0xFF47643B),
    )
    // enableEdgeToEdge only follows the system night mode; the app theme and incognito can differ from it.
    val view = LocalView.current
    if (!view.isInEditMode) {
        fun applySystemBars() {
            val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.window
            if (window != null) WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        SideEffect { applySystemBars() }
        // Returning from the always-dark player or dismissing a dialog can restore window flags
        // without recomposing this theme. Reapply contrast once the browser regains focus.
        DisposableEffect(view, dark) {
            val observer = view.viewTreeObserver
            val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if(focused) applySystemBars() }
            observer.addOnWindowFocusChangeListener(listener)
            onDispose { if(observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
        }
    }
    CompositionLocalProvider(LocalMiruColors provides extraColors) {
        MaterialTheme(colorScheme = scheme, typography = MiruTypography, content = content)
    }
}
