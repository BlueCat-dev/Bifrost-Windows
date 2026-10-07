package com.bifrost.twp.ui.theme

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import java.util.Locale

private val DarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = DarkBackground,
    secondary = NeonEmerald,
    onSecondary = DarkBackground,
    tertiary = WarningAmber,
    background = DarkBackground,
    surface = CardBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    outline = CardStroke,
    outlineVariant = CardStrokeActive
)

val LocalAppLanguage = androidx.compose.runtime.compositionLocalOf { "en" }

@Composable
fun BifrostTheme(
    language: String = "en",
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    val currentContext = LocalContext.current

    if (!view.isInEditMode && currentContext is Activity) {
        SideEffect {
            val window = currentContext.window
            window.statusBarColor = DarkBackground.toArgb()
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    val locale = Locale(language)
    val config = Configuration(LocalConfiguration.current).apply {
        setLocale(locale)
    }
    val localizedContext = currentContext.createConfigurationContext(config)
    val layoutDirection = if (language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(
        LocalAppLanguage provides language,
        LocalConfiguration provides config,
        LocalContext provides localizedContext,
        LocalLayoutDirection provides layoutDirection
    ) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = getAppTypography(language),
            content = content
        )
    }
}
