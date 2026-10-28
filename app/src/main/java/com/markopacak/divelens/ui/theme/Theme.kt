package com.markopacak.divelens.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The "fixed" roles keep the same colour in light and dark mode.
private val PrimaryFixed = Color(0xFFCDE6F1)
private val CoralFixed = Color(0xFFFADAD3)
private val CoralFixedDim = Color(0xFFF5B4A6)
private val OnCoralFixed = Color(0xFF3D0F06)
private val OnCoralFixedVariant = Color(0xFF8A2B1A)

/**
 * The brand palette in light mode (add-branding-and-watermark D3). Every role is set, so none of Material's baseline
 * purples shows through; the roles the palette doesn't name are tints between its colours.
 */
val LightColors = lightColorScheme(
    primary = LensBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE6F1),
    onPrimaryContainer = DeepOcean,
    inversePrimary = Surf,
    secondary = CoralDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFADAD3),
    onSecondaryContainer = Color(0xFF5A1A0E),
    tertiary = CoralDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFADAD3),
    onTertiaryContainer = Color(0xFF5A1A0E),
    background = Mist,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFDCE8EE),
    onSurfaceVariant = Slate,
    surfaceTint = LensBlue,
    inverseSurface = Ink,
    inverseOnSurface = Mist,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF6B828E),
    outlineVariant = Color(0xFFC3D3DA),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceContainer = Color(0xFFEEF5F8),
    surfaceContainerHigh = Color(0xFFE8F1F5),
    surfaceContainerHighest = Color(0xFFE1ECF1),
    surfaceContainerLow = Color(0xFFF4F9FB),
    surfaceContainerLowest = Color.White,
    surfaceDim = Color(0xFFD5E2E8),
    primaryFixed = PrimaryFixed,
    primaryFixedDim = Surf,
    onPrimaryFixed = DeepOcean,
    onPrimaryFixedVariant = LensBlue,
    secondaryFixed = CoralFixed,
    secondaryFixedDim = CoralFixedDim,
    onSecondaryFixed = OnCoralFixed,
    onSecondaryFixedVariant = OnCoralFixedVariant,
    tertiaryFixed = CoralFixed,
    tertiaryFixedDim = CoralFixedDim,
    onTertiaryFixed = OnCoralFixed,
    onTertiaryFixedVariant = OnCoralFixedVariant,
)

/** The brand palette in dark mode (add-branding-and-watermark D3). */
val DarkColors = darkColorScheme(
    primary = Surf,
    onPrimary = DeepOcean,
    primaryContainer = LensBlue,
    onPrimaryContainer = Color(0xFFCDE6F1),
    inversePrimary = LensBlue,
    secondary = CoralGlow,
    onSecondary = DeepOcean,
    secondaryContainer = Color(0xFF6E2A1D),
    onSecondaryContainer = Color(0xFFFADAD3),
    tertiary = CoralGlow,
    onTertiary = DeepOcean,
    tertiaryContainer = Color(0xFF6E2A1D),
    onTertiaryContainer = Color(0xFFFADAD3),
    background = DeepOcean,
    onBackground = Foam,
    surface = DeepSurface,
    onSurface = Foam,
    surfaceVariant = Color(0xFF1A4A63),
    onSurfaceVariant = Fog,
    surfaceTint = Surf,
    inverseSurface = Foam,
    inverseOnSurface = Ink,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF7F98A4),
    outlineVariant = Color(0xFF2C5568),
    scrim = Color.Black,
    surfaceBright = Color(0xFF24536B),
    surfaceContainer = DeepSurface,
    surfaceContainerHigh = Color(0xFF154460),
    surfaceContainerHighest = Color(0xFF1B4E6A),
    surfaceContainerLow = Color(0xFF0D3349),
    surfaceContainerLowest = Color(0xFF082536),
    surfaceDim = DeepOcean,
    primaryFixed = PrimaryFixed,
    primaryFixedDim = Surf,
    onPrimaryFixed = DeepOcean,
    onPrimaryFixedVariant = LensBlue,
    secondaryFixed = CoralFixed,
    secondaryFixedDim = CoralFixedDim,
    onSecondaryFixed = OnCoralFixed,
    onSecondaryFixedVariant = OnCoralFixedVariant,
    tertiaryFixed = CoralFixed,
    tertiaryFixedDim = CoralFixedDim,
    onTertiaryFixed = OnCoralFixed,
    onTertiaryFixedVariant = OnCoralFixedVariant,
)

/** The app theme: the brand palette, not the wallpaper's colours, so DiveLens looks the same on every phone. */
@Composable
fun DiveLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
