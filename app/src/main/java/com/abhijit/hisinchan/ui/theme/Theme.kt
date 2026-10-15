package com.abhijit.hisinchan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhijit.hisinchan.data.ThemeMode

@Immutable
data class Ambient(val top: Color, val middle: Color, val bottom: Color, val blobA: Color, val blobB: Color, val glass: Color, val onGlass: Color)

val LocalAmbient = staticCompositionLocalOf {
    Ambient(Color.White, Color.White, Color.White, Color.White, Color.White, Color.White, Color.Black)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF6A47F0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFFE85A87),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E2),
    tertiary = Color(0xFF2FA866),
    tertiaryContainer = Color(0xFFC9F6DA),
    background = Color(0xFFFFF7F1),
    surface = Color(0xFFFFFBF8),
    surfaceVariant = Color(0xFFF1E9F5),
    onSurface = Color(0xFF1E1A2B),
    onSurfaceVariant = Color(0xFF5B5470),
    error = Color(0xFFC62E4B),
    outline = Color(0xFFCBC2DB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9A8FF),
    onPrimary = Color(0xFF2B1B5E),
    primaryContainer = Color(0xFF4A31B8),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFFF9EB8),
    onSecondary = Color(0xFF5C1130),
    secondaryContainer = Color(0xFF7A2747),
    tertiary = Color(0xFF7CE0A3),
    tertiaryContainer = Color(0xFF145A36),
    background = Color(0xFF130E26),
    surface = Color(0xFF1A1433),
    surfaceVariant = Color(0xFF2A2347),
    onSurface = Color(0xFFEDE7FF),
    onSurfaceVariant = Color(0xFFC6BEE0),
    error = Color(0xFFFF8A9E),
    outline = Color(0xFF4D4570),
)

private val LightAmbient = Ambient(
    top = Color(0xFFFFE9DC), middle = Color(0xFFF6E6FF), bottom = Color(0xFFE2F1FF),
    blobA = Color(0xFFFFB8CC), blobB = Color(0xFFB8A6FF),
    glass = Color.White.copy(alpha = 0.62f), onGlass = Color(0xFF1E1A2B),
)
private val DarkAmbient = Ambient(
    top = Color(0xFF1B1240), middle = Color(0xFF2A1652), bottom = Color(0xFF0E1B3A),
    blobA = Color(0xFF7B2C6B), blobB = Color(0xFF3B2FA8),
    glass = Color(0xFF221A44).copy(alpha = 0.72f), onGlass = Color(0xFFEDE7FF),
)

private val Rounded = FontFamily.SansSerif

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Rounded, fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Rounded, fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Medium, fontSize = 13.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun HiSinchanTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalAmbient provides if (dark) DarkAmbient else LightAmbient) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
