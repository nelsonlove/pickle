package com.callumalpass.wickle.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = Action,
    secondary = Hold,
    tertiary = Reject,
    background = Charcoal,
    surface = DarkSurface,
    onBackground = Paper,
    onSurface = Paper,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Action,
    secondary = Hold,
    tertiary = Reject,
    background = Paper,
    surface = Color(0xFFFBF7EF),
    surfaceVariant = Color(0xFFEDE3D3),
    outline = Line,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = MutedInk,
  )

@Composable
fun WickleTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
