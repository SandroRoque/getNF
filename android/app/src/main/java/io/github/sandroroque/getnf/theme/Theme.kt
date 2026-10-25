package io.github.sandroroque.getnf.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The app's theme (D81, "Calmo"). No dynamic color: the design's quiet palette is the identity. Material components
 * still read MaterialTheme, so its scheme is derived from the same tokens; screens use [Calmo] for the rest.
 */
@Composable
fun GetNFTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val c = if (darkTheme) DarkCalmo else LightCalmo
  val scheme = (if (darkTheme) darkColorScheme() else lightColorScheme()).copy(
    primary = c.fg, onPrimary = c.bg, secondary = c.acc, onSecondary = c.bg, tertiary = c.acc,
    background = c.bg, onBackground = c.fg, surface = c.bg, onSurface = c.fg, surfaceVariant = c.line,
    onSurfaceVariant = c.mute, surfaceContainer = c.sheet, surfaceContainerLow = c.sheet, surfaceContainerHigh = c.sheet,
    surfaceContainerHighest = c.sheet, surfaceContainerLowest = c.bg, outline = c.line, outlineVariant = c.line,
    secondaryContainer = c.line, onSecondaryContainer = c.fg, primaryContainer = c.line, onPrimaryContainer = c.fg,
    error = c.up,
  )
  CompositionLocalProvider(LocalCalmo provides c) {
    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
  }
}

/** The Calmo tokens of the current theme. */
val Calmo: CalmoColors
  @Composable @ReadOnlyComposable get() = LocalCalmo.current
