package io.github.sandroroque.getnf.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The "Calmo" palette (D81): neutral surfaces with a slight cool bias, one quiet accent, and category colors used
 * only as small dots. The same names in light and dark; values from the approved mockups ("Telas do getNF").
 */
@Immutable
data class CalmoColors(
  val bg: Color, val fg: Color, val mute: Color, val line: Color, val acc: Color, val sheet: Color,
  val up: Color, val down: Color,
  /** Top-level categories take these in tree order; "no category" is [none]. */
  val categories: List<Color>, val none: Color,
)

val LightCalmo = CalmoColors(
  bg = Color(0xFFF7F7F5), fg = Color(0xFF22252A), mute = Color(0xFF6F747C), line = Color(0xFFE4E4E0),
  acc = Color(0xFF3D5A80), sheet = Color(0xFFFFFFFF), up = Color(0xFFB4553F), down = Color(0xFF3F7D5A),
  categories = listOf(0xFF5B8C6F, 0xFF5F86B0, 0xFFC39A1F, 0xFFB56576, 0xFF8A6FB0, 0xFFCF7D57, 0xFF4F9A9A, 0xFF8C8C4A, 0xFF9E7A5F, 0xFF6D7FB8).map(::Color),
  none = Color(0xFFB0B3B8),
)

val DarkCalmo = CalmoColors(
  bg = Color(0xFF141517), fg = Color(0xFFE8E9EC), mute = Color(0xFF8F949C), line = Color(0xFF2A2C30),
  acc = Color(0xFF93B2DA), sheet = Color(0xFF1C1D20), up = Color(0xFFE08A74), down = Color(0xFF7CC29A),
  categories = listOf(0xFF79AD8D, 0xFF86A9D1, 0xFFD8B44A, 0xFFD18797, 0xFFA993CC, 0xFFE59A77, 0xFF6FBCBC, 0xFFB5B56A, 0xFFC09C80, 0xFF95A5DA).map(::Color),
  none = Color(0xFF6B6F75),
)

val LocalCalmo = staticCompositionLocalOf { LightCalmo }
