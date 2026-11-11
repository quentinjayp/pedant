package com.pedant.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The app's soft pastel palette: mist surfaces, sky blue highlights, apricot for the main action. */
object Pastel {
    val Bg = Color(0xFFF7FBFD)
    val White = Color(0xFFFFFFFF)
    val SurfaceLow = Color(0xFFEFF6F8)
    val SurfaceContainer = Color(0xFFE4F0F3)
    val SurfaceHigh = Color(0xFFD5E5EA)
    val Ink = Color(0xFF1E343D)
    val InkSoft = Color(0xFF526A73)
    val Outline = Color(0xFF8BA3AC)
    val OutlineSoft = Color(0xFFCFE0E6)

    val Sky = Color(0xFF5CAEF2)
    val SkySoft = Color(0xFFBFE1FC)
    val SkyDark = Color(0xFF235A8C)
    val SkyContainer = Color(0xFFE3F2FD)
    val SkyInk = Color(0xFF163A5C)

    val Apricot = Color(0xFFF49756)
    val ApricotPressed = Color(0xFFE68542)
    val ApricotDark = Color(0xFF8C4715)
    val ApricotContainer = Color(0xFFFFF0E5)
    val ApricotInk = Color(0xFF4A2104)
}

@Composable
fun PedantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Pastel.Sky,
            onPrimary = Pastel.White,
            primaryContainer = Pastel.SkyContainer,
            onPrimaryContainer = Pastel.SkyInk,
            secondary = Pastel.SkyDark,
            onSecondary = Pastel.White,
            secondaryContainer = Pastel.SkySoft,
            onSecondaryContainer = Pastel.SkyInk,
            tertiary = Pastel.Apricot,
            onTertiary = Pastel.White,
            tertiaryContainer = Pastel.ApricotContainer,
            onTertiaryContainer = Pastel.ApricotInk,
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
