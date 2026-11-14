package com.pedant.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app's soft pastel palette: a mist-blue canvas, pastel cyan as the main colour, pastel violet
 * as the second (the learner's lines, sounds and word tags), and pastel orange for the main action.
 * Text on the pastel fills is dark, since white doesn't read on them.
 */
object Pastel {
    val Bg = Color(0xFFD4E5EA)
    val White = Color(0xFFFFFFFF)
    val SurfaceLow = Color(0xFFF4FAFC)
    val SurfaceContainer = Color(0xFFE4F0F3)
    val SurfaceHigh = Color(0xFFC6DBE1)
    val Ink = Color(0xFF162B33)
    val InkSoft = Color(0xFF425A63)
    val Outline = Color(0xFF7C959E)
    val OutlineSoft = Color(0xFFB3C9D1)

    // Main colour: pastel cyan.
    val Primary = Color(0xFF7CE8F7)
    val PrimarySoft = Color(0xFFB3F2FC)
    val PrimaryDark = Color(0xFF09525E)
    val PrimaryContainer = Color(0xFFE0F9FD)
    val PrimaryInk = Color(0xFF063740)

    // Second colour: pastel violet.
    val Violet = Color(0xFFA89FF9)
    val VioletSoft = Color(0xFFC9C3FC)
    val VioletDark = Color(0xFF2A1E7A)
    val VioletContainer = Color(0xFFECE9FE)
    val VioletInk = Color(0xFF1D1359)

    // The main action: pastel orange.
    val Cta = Color(0xFFFFC266)
    val CtaPressed = Color(0xFFFFB547)
    val CtaDark = Color(0xFF7A4600)
    val CtaContainer = Color(0xFFFFF0D6)
    val CtaInk = Color(0xFF3D2200)

    // Words said wrong.
    val Wrong = Color(0xFFC62828)
}

/** The logo's own colours, brighter than the app's: orange, violet and cyan. */
object LogoColors {
    val Orange = Color(0xFFFF9A00)
    val Violet = Color(0xFF1B00FF)
    val Cyan = Color(0xFF00E4FF)
}

@Composable
fun PedantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Pastel.Primary,
            onPrimary = Pastel.PrimaryInk,
            primaryContainer = Pastel.PrimaryContainer,
            onPrimaryContainer = Pastel.PrimaryInk,
            secondary = Pastel.Violet,
            onSecondary = Pastel.VioletInk,
            secondaryContainer = Pastel.VioletContainer,
            onSecondaryContainer = Pastel.VioletDark,
            tertiary = Pastel.Cta,
            onTertiary = Pastel.CtaInk,
            tertiaryContainer = Pastel.CtaContainer,
            onTertiaryContainer = Pastel.CtaInk,
            background = Pastel.Bg,
            onBackground = Pastel.Ink,
            surface = Pastel.Bg,
            onSurface = Pastel.Ink,
            surfaceVariant = Pastel.SurfaceContainer,
            onSurfaceVariant = Pastel.InkSoft,
            surfaceContainerLowest = Pastel.White,
            surfaceContainerLow = Pastel.SurfaceLow,
            surfaceContainer = Pastel.SurfaceContainer,
            surfaceContainerHigh = Pastel.White,
            surfaceContainerHighest = Pastel.SurfaceHigh,
            outline = Pastel.Outline,
            outlineVariant = Pastel.OutlineSoft,
        ),
        content = content,
    )
}
