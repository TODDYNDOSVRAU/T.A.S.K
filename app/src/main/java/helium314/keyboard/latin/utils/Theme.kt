// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R

// Official T.A.L. v1.2 Palette (WCAG 2.2 AA verified contrast pairs)
object TalColors {
    val AbsoluteBlack = Color(0xFF080808)
    val DarkSurface1 = Color(0xFF141414)
    val DarkSurface2 = Color(0xFF1D1D1D)
    val DarkSurface3 = Color(0xFF2D2D2D)

    val PrecisionWhite = Color(0xFFF2F2F0)
    val LightSurface1 = Color(0xFFFFFFFF)
    val LightSurface2 = Color(0xFFE4E4E2)
    val LightSurface3 = Color(0xFFD2D2CF)

    val TechnicalGrayDark = Color(0xFF8C8C8C)   // 6.0:1 on #080808, 5.3:1 on #141414
    val TechnicalGrayLight = Color(0xFF6B6B6B)  // 4.8:1 on #F2F2F0, 5.4:1 on #FFFFFF

    val IceDark = Color(0xFF7EE3EA)             // 13.4:1 on #080808 (forbidden on light)
    val DeepTealLight = Color(0xFF0D6C74)       // 5.5:1 on #F2F2F0
}

val TalSpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk_700, FontWeight.Bold)
)

val TalInter = FontFamily(
    Font(R.font.inter, FontWeight.Normal)
)

val TalJetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal)
)

private val TalDarkScheme = darkColorScheme(
    primary = TalColors.IceDark,
    onPrimary = TalColors.AbsoluteBlack,
    primaryContainer = TalColors.DarkSurface2,
    onPrimaryContainer = TalColors.IceDark,
    secondary = TalColors.IceDark,
    onSecondary = TalColors.AbsoluteBlack,
    background = TalColors.AbsoluteBlack,
    onBackground = TalColors.PrecisionWhite,
    surface = TalColors.DarkSurface1,
    onSurface = TalColors.PrecisionWhite,
    surfaceVariant = TalColors.DarkSurface2,
    onSurfaceVariant = TalColors.TechnicalGrayDark,
    surfaceContainer = TalColors.DarkSurface1,
    surfaceContainerHigh = TalColors.DarkSurface2,
    surfaceContainerHighest = TalColors.DarkSurface3,
    outline = TalColors.DarkSurface3,
    outlineVariant = TalColors.DarkSurface2
)

private val TalLightScheme = lightColorScheme(
    primary = TalColors.DeepTealLight,
    onPrimary = TalColors.PrecisionWhite,
    primaryContainer = TalColors.LightSurface2,
    onPrimaryContainer = TalColors.DeepTealLight,
    secondary = TalColors.DeepTealLight,
    onSecondary = TalColors.PrecisionWhite,
    background = TalColors.PrecisionWhite,
    onBackground = TalColors.AbsoluteBlack,
    surface = TalColors.LightSurface1,
    onSurface = TalColors.AbsoluteBlack,
    surfaceVariant = TalColors.LightSurface2,
    onSurfaceVariant = TalColors.TechnicalGrayLight,
    surfaceContainer = TalColors.LightSurface1,
    surfaceContainerHigh = TalColors.LightSurface2,
    surfaceContainerHighest = TalColors.LightSurface3,
    outline = TalColors.LightSurface3,
    outlineVariant = TalColors.LightSurface2
)

// T.A.L. 2x Grid typography scale: strictly 12, 18, 24 sp (nothing < 12 sp)
@Composable
fun Theme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val talTypography = Typography(
        headlineMedium = TextStyle(
            fontFamily = TalSpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.02).em
        ),
        titleLarge = TextStyle(
            fontFamily = TalSpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.02).em
        ),
        titleMedium = TextStyle(
            fontFamily = TalSpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            letterSpacing = (-0.02).em
        ),
        titleSmall = TextStyle(
            fontFamily = TalInter,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = TalInter,
            fontWeight = FontWeight.Normal,
            fontSize = 18.sp,
            lineHeight = 24.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = TalInter,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 18.sp
        ),
        bodySmall = TextStyle(
            fontFamily = TalInter,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp
        ),
        labelLarge = TextStyle(
            fontFamily = TalJetBrainsMono,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp
        ),
        labelMedium = TextStyle(
            fontFamily = TalJetBrainsMono,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp
        ),
        labelSmall = TextStyle(
            fontFamily = TalJetBrainsMono,
            fontWeight = FontWeight.Normal,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
    )
    MaterialTheme(
        colorScheme = if (dark) TalDarkScheme else TalLightScheme,
        typography = talTypography,
        content = content
    )
}

const val previewDark = true
