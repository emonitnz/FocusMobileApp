package com.example.compx551a4.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealContainer,
    onPrimaryContainer = TealOnContainer,
    secondary = SlateSecondary,
    secondaryContainer = SlateContainer,
    onSecondaryContainer = SlateOnContainer,
    tertiary = SandTertiary,
    tertiaryContainer = SandContainer,
    onTertiaryContainer = SandOnContainer,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = TealOnPrimaryDark,
    primaryContainer = TealContainerDark,
    onPrimaryContainer = TealOnContainerDark,
    secondary = SlateSecondaryDark,
    secondaryContainer = SlateContainerDark,
    onSecondaryContainer = TealOnContainerDark,
    tertiary = SandTertiaryDark,
    tertiaryContainer = SandContainerDark,
    onTertiaryContainer = SandContainer,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
)

/**
 * Colours Material does not have a slot for. Held in a CompositionLocal so screens read them the
 * same way they read [MaterialTheme.colorScheme], rather than branching on `isSystemInDarkTheme()`
 * at each use site.
 */
@Immutable
data class FocusColors(
    val focused: Color,
    val idle: Color,
    val unknown: Color,
    val contactGood: Color,
    val contactFair: Color,
    val contactPoor: Color,
) {
    /** HSI runs 1 (good) to 4 (no contact). */
    fun forHsi(hsi: Int): Color = when {
        hsi <= 1 -> contactGood
        hsi == 2 -> contactFair
        else -> contactPoor
    }
}

private val LightFocusColors = FocusColors(
    focused = FocusedLight,
    idle = IdleLight,
    unknown = UnknownLight,
    contactGood = ContactGoodLight,
    contactFair = ContactFairLight,
    contactPoor = ContactPoorLight,
)

private val DarkFocusColors = FocusColors(
    focused = FocusedDark,
    idle = IdleDark,
    unknown = UnknownDark,
    contactGood = ContactGoodDark,
    contactFair = ContactFairDark,
    contactPoor = ContactPoorDark,
)

private val LocalFocusColors = staticCompositionLocalOf { LightFocusColors }

object FocusTheme {
    val colors: FocusColors
        @Composable @ReadOnlyComposable get() = LocalFocusColors.current
}

/**
 * [dynamicColor] defaults to false. Wallpaper-derived theming is a nice Android 12+ touch, but this
 * app is demoed and screenshotted for a writeup, and a palette that differs per device makes those
 * artefacts inconsistent. It stays available as a parameter.
 */
@Composable
fun COMPX551A4Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalFocusColors provides if (darkTheme) DarkFocusColors else LightFocusColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}

/**
 * Wraps the calibration task screens in a fixed dark, low-luminance scheme — identical on every
 * device, in either system theme, for all three conditions. See [CalibrationBackground].
 */
@Composable
fun CalibrationTaskTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = CalibrationAccent,
        onPrimary = CalibrationBackground,
        background = CalibrationBackground,
        onBackground = CalibrationForeground,
        surface = CalibrationBackground,
        onSurface = CalibrationForeground,
        surfaceVariant = Color(0xFF232A2C),
        onSurfaceVariant = Color(0xFFBFC7C9),
        outline = Color(0xFF5A6366),
        surfaceContainer = Color(0xFF1B2123),
        surfaceContainerHigh = Color(0xFF232A2C),
    )
    CompositionLocalProvider(LocalFocusColors provides DarkFocusColors) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
