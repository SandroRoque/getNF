package io.github.sandroroque.getnf.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.sandroroque.getnf.R

/*
 * Type (D81): Newsreader, a serif, for the one large number or title per screen; Figtree for everything else. Both
 * are variable fonts bundled in res/font (SIL OFL, licenses in third_party/fonts).
 */
@OptIn(ExperimentalTextApi::class)
private fun figtree(w: Int) = Font(R.font.figtree, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
@OptIn(ExperimentalTextApi::class)
private fun newsreader(w: Int) = Font(R.font.newsreader, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Figtree = FontFamily(figtree(400), figtree(500), figtree(600))
val Newsreader = FontFamily(newsreader(500), newsreader(600))

/** The screen's one large figure ("R$ 617,98"). */
val Display = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight(500), fontSize = 42.sp, lineHeight = 44.sp, letterSpacing = (-0.6).sp)
/** A secondary figure or a detail screen's title. */
val DisplaySmall = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight(500), fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp)
val Title = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight(500), fontSize = 23.sp, lineHeight = 27.sp)

val Typography = Typography(
  bodyLarge = TextStyle(fontFamily = Figtree, fontSize = 16.sp, lineHeight = 22.sp),
  bodyMedium = TextStyle(fontFamily = Figtree, fontSize = 14.sp, lineHeight = 20.sp),
  bodySmall = TextStyle(fontFamily = Figtree, fontSize = 12.5.sp, lineHeight = 17.sp),
  labelLarge = TextStyle(fontFamily = Figtree, fontWeight = FontWeight(500), fontSize = 14.sp, lineHeight = 18.sp),
  labelMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight(500), fontSize = 12.sp, lineHeight = 16.sp),
  labelSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight(500), fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp),
  titleLarge = Title,
  titleMedium = TextStyle(fontFamily = Figtree, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 22.sp),
  titleSmall = TextStyle(fontFamily = Figtree, fontWeight = FontWeight(600), fontSize = 14.sp, lineHeight = 20.sp),
  headlineSmall = DisplaySmall,
  headlineLarge = Display,
)
