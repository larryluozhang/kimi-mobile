package com.kimi.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 品牌色（与 Android 版 values/colors.xml 对齐）
val BrandBlue = Color(0xFF3D7BFA)
val BrandPurple = Color(0xFF8B5CF6)
val Primary = Color(0xFF5A63E8)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFE3E6FF)
val OnPrimaryContainer = Color(0xFF1B2060)
val BgLight = Color(0xFFF4F5FB)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFECEEF7)
val DividerLight = Color(0xFFE5E7F0)
val TextPrimary = Color(0xFF1B1D2A)
val TextSecondary = Color(0xFF6B7280)
val BubbleUserStart = Color(0xFF4E7BF5)
val BubbleUserEnd = Color(0xFF7B5CF0)
val CodeBg = Color(0xFF23253A)
val CodeText = Color(0xFFE6E9F5)
val StatusBg = Color(0xFFFFF6DE)
val StatusText = Color(0xFF8D6E00)
val ErrorRed = Color(0xFFD32F2F)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    background = BgLight,
    onBackground = TextPrimary,
    surface = SurfaceLight,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondary,
    outline = DividerLight,
    error = ErrorRed
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FA2FF),
    onPrimary = Color(0xFF1B2060),
    primaryContainer = Color(0xFF333B8A),
    onPrimaryContainer = Color(0xFFE3E6FF),
    background = Color(0xFF14151F),
    onBackground = Color(0xFFE6E7F0),
    surface = Color(0xFF1C1D2B),
    onSurface = Color(0xFFE6E7F0),
    surfaceVariant = Color(0xFF262838),
    onSurfaceVariant = Color(0xFFA0A3B8),
    outline = Color(0xFF353749),
    error = Color(0xFFFF6B5E)
)

@Composable
fun KimiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
