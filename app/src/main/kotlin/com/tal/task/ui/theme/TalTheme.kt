package com.tal.task.ui.theme

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
import com.tal.task.R
import com.tal.task.data.ThemeMode

// Official T.A.L. v1.2 Palette (Ice accent only on dark surfaces)
object TalPalette {
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

    val IceDarkAccent = Color(0xFF7EE3EA)     // 13.4:1 on #080808 (forbidden on light)
    val DeepTealLightAccent = Color(0xFF0D6C74) // 5.5:1 on #F2F2F0
}

val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk_700, FontWeight.Bold)
)

val InterFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium)
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono_700, FontWeight.Bold)
)

private val TalDarkColors = darkColorScheme(
    primary = TalPalette.IceDarkAccent,
    onPrimary = TalPalette.AbsoluteBlack,
    background = TalPalette.AbsoluteBlack,
    onBackground = TalPalette.PrecisionWhite,
    surface = TalPalette.DarkSurface1,
    onSurface = TalPalette.PrecisionWhite,
    surfaceVariant = TalPalette.DarkSurface2,
    onSurfaceVariant = TalPalette.TechnicalGrayDark,
    outline = TalPalette.DarkSurface3
)

private val TalLightColors = lightColorScheme(
    primary = TalPalette.DeepTealLightAccent,
    onPrimary = TalPalette.PrecisionWhite,
    background = TalPalette.PrecisionWhite,
    onBackground = TalPalette.AbsoluteBlack,
    surface = TalPalette.LightSurface1,
    onSurface = TalPalette.AbsoluteBlack,
    surfaceVariant = TalPalette.LightSurface2,
    onSurfaceVariant = TalPalette.TechnicalGrayLight,
    outline = TalPalette.LightSurface3
)

// T.A.L. 2x Grid typography sizes: 12, 18, 24 sp
private val TalTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = (-0.02).sp
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGroteskFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = (-0.02).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    )
)

@Composable
fun TalTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (useDark) TalDarkColors else TalLightColors,
        typography = TalTypography,
        content = content
    )
}
