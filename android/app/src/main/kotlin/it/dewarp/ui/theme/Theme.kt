package it.dewarp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette editoriale, restrained. Carta calda + inchiostro blu + accento rosso mattone.
private val Paper = Color(0xFFF7F4EE)
private val Paper2 = Color(0xFFEFEAE0)
private val Ink = Color(0xFF1B1F2C)
private val InkSoft = Color(0xFF454A5C)
private val InkMuted = Color(0xFF7B7F8C)
private val Accent = Color(0xFFB04632)
private val AccentStrong = Color(0xFF8E3624)

// Dark variant: studio serale, ambiente con poca luce.
private val DarkPaper = Color(0xFF13141A)
private val DarkPaper2 = Color(0xFF1D1F28)
private val DarkInk = Color(0xFFEAE6DC)
private val DarkInkSoft = Color(0xFFB5B2A9)

private val LightColors = lightColorScheme(
    primary = Ink, onPrimary = Paper,
    secondary = Accent, onSecondary = Paper,
    background = Paper, onBackground = Ink,
    surface = Paper2, onSurface = Ink,
    surfaceVariant = Paper2, onSurfaceVariant = InkSoft,
    outline = InkMuted,
)

private val DarkColors = darkColorScheme(
    primary = DarkInk, onPrimary = DarkPaper,
    secondary = Accent, onSecondary = DarkPaper,
    background = DarkPaper, onBackground = DarkInk,
    surface = DarkPaper2, onSurface = DarkInk,
    surfaceVariant = DarkPaper2, onSurfaceVariant = DarkInkSoft,
    outline = DarkInkSoft,
)

private val Typo = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 30.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp),
)

@Composable
fun DewarpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typo,
        content = content,
    )
}
