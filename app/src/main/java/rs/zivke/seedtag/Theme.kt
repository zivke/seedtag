// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 zivke

package rs.zivke.seedtag

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================== theme ===
//
// One fixed palette, matching the icon: ink surfaces, a single amber accent, red only for what is
// risky or permanent, green only for a result that's good. No dynamic (wallpaper) colors, so the
// app looks the same on every phone and its warnings always look like warnings.

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2B84B),
    onPrimary = Color(0xFF241A05),
    primaryContainer = Color(0xFF3A2D12),
    onPrimaryContainer = Color(0xFFF8DDA4),
    secondary = Color(0xFFB9C1CC),
    onSecondary = Color(0xFF1B1F24),
    secondaryContainer = Color(0xFF2A2F37),
    onSecondaryContainer = Color(0xFFE2E6EC),
    background = Color(0xFF111317),
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF111317),
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF22262D),
    onSurfaceVariant = Color(0xFF9EA4AD),
    surfaceContainerLowest = Color(0xFF0C0E11),
    surfaceContainerLow = Color(0xFF16191D),
    surfaceContainer = Color(0xFF1A1D22),
    surfaceContainerHigh = Color(0xFF21252B),
    surfaceContainerHighest = Color(0xFF2A2E35),
    outline = Color(0xFF3A3F47),
    outlineVariant = Color(0xFF2A2E35),
    error = Color(0xFFF28B82),
    onError = Color(0xFF3B0A06),
    errorContainer = Color(0xFF3A1D1B),
    onErrorContainer = Color(0xFFF7CCC7),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBEBCB),
    onPrimaryContainer = Color(0xFF2C1B00),
    secondary = Color(0xFF555D68),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E8EC),
    onSecondaryContainer = Color(0xFF1B1F24),
    background = Color(0xFFF8F8F6),
    onBackground = Color(0xFF1A1C1F),
    surface = Color(0xFFF8F8F6),
    onSurface = Color(0xFF1A1C1F),
    surfaceVariant = Color(0xFFECECE8),
    onSurfaceVariant = Color(0xFF5B616A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F2EF),
    surfaceContainer = Color(0xFFEDEDEA),
    surfaceContainerHigh = Color(0xFFE7E7E3),
    surfaceContainerHighest = Color(0xFFE0E0DB),
    outline = Color(0xFFCBCBC5),
    outlineVariant = Color(0xFFE0E0DA),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFBE9E7),
    onErrorContainer = Color(0xFF410E0B),
)

/**
 * Colors Material has no slot for: a good result, "fair" in the strength meter, and advice (amber: what's always
 * worth knowing, as opposed to red for what's wrong right now, board B31). Light "fair" is 5.1:1 on the
 * background, for its 12sp text (board B46; B06A00 was 4.0:1).
 */
internal class ExtraColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val fair: Color,
    val adviceContainer: Color,
    val onAdviceContainer: Color,
)

private val DarkExtra = ExtraColors(
    Color(0xFF7FD3A0), Color(0xFF16291D), Color(0xFFC9EED6), Color(0xFFE5A54B), Color(0xFF2B2213), Color(0xFFF3DDB5),
)
private val LightExtra = ExtraColors(
    Color(0xFF1E7B45), Color(0xFFE3F3E8), Color(0xFF0B2E18), Color(0xFF9A5C00), Color(0xFFFBF0DD), Color(0xFF2C1B00),
)
internal val LocalExtra = staticCompositionLocalOf { DarkExtra }

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        bodyMedium = t.bodyMedium.copy(lineHeight = 21.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SeedTagTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

internal val Gutter = 20.dp
