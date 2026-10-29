package io.github.sandroroque.getnf.ui.spending

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.sandroroque.getnf.data.Period
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val BR = Locale.forLanguageTag("pt-BR")

/** Distinct, readable on light and dark backgrounds; top-level categories take them in tree order. */
val PALETTE = listOf(
  Color(0xFF4E79A7), Color(0xFFF28E2B), Color(0xFF59A14F), Color(0xFFE15759), Color(0xFF76B7B2),
  Color(0xFFEDC948), Color(0xFFB07AA1), Color(0xFFFF9DA7), Color(0xFF9C755F), Color(0xFF6B8E23),
)
val UNCATEGORIZED = Color(0xFFBAB0AC)

/** "out" for a month, "2026" for a year; with [long], "outubro de 2026". */
fun label(p: Period, long: Boolean = false): String {
  if (p.isYear) return p.key
  val ym = YearMonth.parse(p.key)
  return if (long) "${ym.month.getDisplayName(TextStyle.FULL, BR)} de ${ym.year}"
  else ym.month.getDisplayName(TextStyle.SHORT, BR).trimEnd('.') + if (ym.monthValue == 1) "\n${ym.year % 100}" else ""
}

/**
 * Points over time joined by lines, one series per name (stores, say), with the lowest and highest value written at
 * the left. [format] writes a value.
 */
@Composable
fun TimeSeries(series: Map<String, List<Pair<Long, Double>>>, format: (Double) -> String, modifier: Modifier = Modifier) {
  val all = series.values.flatten()
  if (all.isEmpty()) return
  val (x0, x1) = all.minOf { it.first } to all.maxOf { it.first }
  val (lo, hi) = all.minOf { it.second } to all.maxOf { it.second }
  val pad = ((hi - lo) * .15).takeIf { it > 0 } ?: (hi * .05 + 1)
  val (y0, y1) = lo - pad to hi + pad
  val c = io.github.sandroroque.getnf.theme.Calmo
  // One series in the text colour; several (one per store) in the category colours, in order (D81).
  val colors = if (series.size == 1) listOf(c.fg) else c.categories
  Box(modifier.fillMaxWidth().height(120.dp)) {
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(start = 44.dp, top = 6.dp, bottom = 6.dp, end = 6.dp)) {
      fun at(p: Pair<Long, Double>) = Offset(
        if (x1 == x0) size.width / 2 else size.width * (p.first - x0) / (x1 - x0).toFloat(),
        size.height * (1 - ((p.second - y0) / (y1 - y0)).toFloat()),
      )
      drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height))
      series.entries.forEachIndexed { i, (_, points) ->
        val color = colors[i % colors.size]
        val sorted = points.sortedBy { it.first }.map(::at)
        sorted.zipWithNext { a, b -> drawLine(color, a, b, strokeWidth = 1.8.dp.toPx()) }
        sorted.forEachIndexed { j, p ->
          drawCircle(if (j == sorted.lastIndex) color else c.bg, 3.5.dp.toPx(), p)
          drawCircle(color, 3.5.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
        }
      }
    }
    Text(format(hi), style = MaterialTheme.typography.labelSmall, color = c.mute, modifier = Modifier.align(Alignment.TopStart))
    if (hi != lo) Text(format(lo), style = MaterialTheme.typography.labelSmall, color = c.mute, modifier = Modifier.align(Alignment.BottomStart))
  }
}
