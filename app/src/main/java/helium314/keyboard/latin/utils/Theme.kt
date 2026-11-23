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
import androidx.compose.ui.unit.sp
import helium314.keyboard.latin.R

// Official T.A.L. v1.2 Palette (matching T.A.R.K.A.S. finish)
object TalColors {
    val AbsoluteBlack = Color(0xFF080808)
    val DarkSurface1 = Color(0xFF141414)
    val DarkSurface2 = Color(0xFF1D1D1D)
    val DarkSurface3 = Color(0xFF2D2D2D)

    val PrecisionWhite = Color(0xFFF2F2F0)
    val LightSurface1 = Color(0xFFFFFFFF)
    val LightSurface2 = Color(0xFFE4E4E2)
    val LightSurface3 = Color(0xFFD2D2CF)

    val TechnicalGrayDark = Color(0xFF8C8C8C)
    val TechnicalGrayLight = Color(0xFF6B6B6B)

    val IceDark = Color(0xFF7EE3EA)
    val DeepTealLight = Color(0xFF0D6C74)
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

@Composable
fun Theme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val base = Typography()
    val talTypography = Typography(
        headlineMedium = TextStyle(
            fontFamily = TalSpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            letterSpacing = (-0.02).sp
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = TalSpaceGrotesk,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.02).sp
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = TalSpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            letterSpacing = (-0.01).sp
        ),
        titleSmall = base.titleSmall.copy(
            fontFamily = TalSpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        ),
        bodyLarge = base.bodyLarge.copy(fontFamily = TalInter),
        bodyMedium = base.bodyMedium.copy(fontFamily = TalInter),
        bodySmall = base.bodySmall.copy(fontFamily = TalInter),
        labelMedium = base.labelMedium.copy(fontFamily = TalJetBrainsMono, fontSize = 11.sp),
        labelSmall = base.labelSmall.copy(fontFamily = TalJetBrainsMono, fontSize = 10.sp)
    )
    MaterialTheme(
        colorScheme = if (dark) TalDarkScheme else TalLightScheme,
        typography = talTypography,
        content = content
    )
}

const val previewDark = true
