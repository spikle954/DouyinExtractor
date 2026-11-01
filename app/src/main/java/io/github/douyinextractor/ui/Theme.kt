package io.github.douyinextractor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF83D8C6), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF124B40), onPrimaryContainer = Color(0xFFC0F3E5),
    secondary = Color(0xFFBBCFC5), onSecondary = Color(0xFF263A31),
    secondaryContainer = Color(0xFF344C40), onSecondaryContainer = Color(0xFFD8EBDD),
    tertiary = Color(0xFFAACBD0), onTertiary = Color(0xFF14343A),
    tertiaryContainer = Color(0xFF2C4C52), onTertiaryContainer = Color(0xFFCFEAF0),
    background = Color(0xFF111817), onBackground = Color(0xFFE1ECE7),
    surface = Color(0xFF111817), onSurface = Color(0xFFE1ECE7),
    surfaceVariant = Color(0xFF34443C), onSurfaceVariant = Color(0xFFB6C7BF),
    surfaceContainer = Color(0xFF1A2421), surfaceContainerHigh = Color(0xFF25312C),
    surfaceContainerHighest = Color(0xFF2D3A33), surfaceContainerLow = Color(0xFF161F1B),
    surfaceContainerLowest = Color(0xFF0C120F), surfaceDim = Color(0xFF111817), surfaceBright = Color(0xFF2B3832),
    surfaceTint = Color(0xFF83D8C6), outline = Color(0xFF82968B), outlineVariant = Color(0xFF34443C),
    error = Color(0xFFFFB4AB), onError = Color(0xFF601410),
    errorContainer = Color(0xFF59332F), onErrorContainer = Color(0xFFFFDAD5),
    inverseSurface = Color(0xFFE1ECE7), inverseOnSurface = Color(0xFF27352E), inversePrimary = Color(0xFF21695B), scrim = Color.Black,
)

// Preserve system light-mode support with coherent teal semantic roles.
private val LightColors = lightColorScheme(
    primary = Color(0xFF21695B), onPrimary = Color.White,
    primaryContainer = Color(0xFFC0F3E5), onPrimaryContainer = Color(0xFF00382F),
    secondary = Color(0xFF4C6358), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8EBDD), onSecondaryContainer = Color(0xFF263A31),
    tertiary = Color(0xFF43656B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFEAF0), onTertiaryContainer = Color(0xFF14343A),
    background = Color(0xFFF6FAF7), onBackground = Color(0xFF17231E),
    surface = Color(0xFFF6FAF7), onSurface = Color(0xFF17231E),
    surfaceVariant = Color(0xFFDDE7E1), onSurfaceVariant = Color(0xFF40534A),
    surfaceContainer = Color(0xFFEBF2EC), surfaceContainerHigh = Color(0xFFE3ECE5),
    surfaceContainerHighest = Color(0xFFDCE6DF), surfaceContainerLow = Color(0xFFF0F6F1),
    surfaceContainerLowest = Color.White, surfaceDim = Color(0xFFD7E1DA), surfaceBright = Color(0xFFF6FAF7),
    surfaceTint = Color(0xFF21695B), outline = Color(0xFF63796D), outlineVariant = Color(0xFFC1CFC5),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD5), onErrorContainer = Color(0xFF601410),
    inverseSurface = Color(0xFF27352E), inverseOnSurface = Color(0xFFE1ECE7), inversePrimary = Color(0xFF83D8C6),
)
private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp,
)
private val AppTypography = Typography(
    headlineLarge = type(28, 36, FontWeight.SemiBold), headlineMedium = type(24, 32, FontWeight.SemiBold),
    headlineSmall = type(22, 28, FontWeight.SemiBold),
    titleLarge = type(20, 28, FontWeight.SemiBold), titleMedium = type(18, 24, FontWeight.SemiBold),
    titleSmall = type(16, 24, FontWeight.Medium),
    bodyLarge = type(16, 24), bodyMedium = type(14, 20), bodySmall = type(12, 18),
    labelLarge = type(14, 20, FontWeight.SemiBold), labelMedium = type(12, 18, FontWeight.Medium), labelSmall = type(11, 16, FontWeight.Medium),
)

@Composable
fun ShiYingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = AppTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)), content = content)
}
