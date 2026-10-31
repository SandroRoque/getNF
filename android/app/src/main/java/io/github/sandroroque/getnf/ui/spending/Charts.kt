package io.github.sandroroque.getnf.ui.spending

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
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
 * the left. [format] writes a value; [formatX], when given, a point's time. With [formatX], touching the chart and
 * sliding along it marks the nearest point and writes its time, value and series at the top (D143).
 */
@Composable
fun TimeSeries(
  series: Map<String, List<Pair<Long, Double>>>, format: (Double) -> String, modifier: Modifier = Modifier,
  formatX: ((Long) -> String)? = null,
) {
  val all = series.values.flatten()
  if (all.isEmpty()) return
  val (x0, x1) = all.minOf { it.first } to all.maxOf { it.first }
  val (lo, hi) = all.minOf { it.second } to all.maxOf { it.second }
  val pad = ((hi - lo) * .15).takeIf { it > 0 } ?: (hi * .05 + 1)
  val (y0, y1) = lo - pad to hi + pad
  val c = io.github.sandroroque.getnf.theme.Calmo
  // One series in the text colour; several (one per store) in the category colours, in order (D81).
  val colors = if (series.size == 1) listOf(c.fg) else c.categories
  // Where the finger is, in the chart's own pixels; null when not touching.
  var touchX by remember { mutableStateOf<Float?>(null) }
  var widthPx by remember { mutableStateOf(0f) }
  val density = LocalDensity.current
  val left = with(density) { 44.dp.toPx() }
  val right = with(density) { 6.dp.toPx() }
  fun xOf(t: Long) = left + if (x1 == x0) (widthPx - left - right) / 2 else (widthPx - left - right) * (t - x0) / (x1 - x0).toFloat()
  // The time nearest the finger, and every series' point there.
  val picked = touchX?.let { x ->
    val t = all.minBy { kotlin.math.abs(xOf(it.first) - x) }.first
    t to series.entries.mapIndexedNotNull { i, (name, ps) -> ps.firstOrNull { it.first == t }?.let { Triple(name, it.second, colors[i % colors.size]) } }
  }
  val scrub = if (formatX == null) Modifier else Modifier
    .pointerInput(series) {
      detectTapGestures(onPress = { o -> touchX = o.x; tryAwaitRelease(); touchX = null })
    }
    .pointerInput(series) {
      detectHorizontalDragGestures(onDragStart = { touchX = it.x }, onDragEnd = { touchX = null }, onDragCancel = { touchX = null }) { change, _ ->
        change.consume(); touchX = change.position.x
      }
    }
  // A band above the plot for what the finger marks, so it never covers the points.
  val band = if (formatX != null) 22.dp else 0.dp
  Box(modifier.fillMaxWidth().height(120.dp + band).onSizeChanged { widthPx = it.width.toFloat() }.then(scrub)) {
    Canvas(Modifier.fillMaxWidth().height(120.dp + band).padding(start = 44.dp, top = 6.dp + band, bottom = 6.dp, end = 6.dp)) {
      fun at(p: Pair<Long, Double>) = Offset(
        if (x1 == x0) size.width / 2 else size.width * (p.first - x0) / (x1 - x0).toFloat(),
        size.height * (1 - ((p.second - y0) / (y1 - y0)).toFloat()),
      )
      drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height))
      picked?.let { (t, _) ->
        val x = at(t to y0).x
        drawLine(c.mute, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
      }
      series.entries.forEachIndexed { i, (_, points) ->
        val color = colors[i % colors.size]
        val sorted = points.sortedBy { it.first }.map(::at)
        sorted.zipWithNext { a, b -> drawLine(color, a, b, strokeWidth = 1.8.dp.toPx()) }
        sorted.forEachIndexed { j, p ->
          drawCircle(if (j == sorted.lastIndex) color else c.bg, 3.5.dp.toPx(), p)
          drawCircle(color, 3.5.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
        }
      }
      picked?.let { (t, here) -> for ((_, v, color) in here) drawCircle(color, 5.5.dp.toPx(), at(t to v)) }
    }
    Text(format(hi), style = MaterialTheme.typography.labelSmall, color = c.mute, modifier = Modifier.align(Alignment.TopStart).padding(top = band))
    if (hi != lo) Text(format(lo), style = MaterialTheme.typography.labelSmall, color = c.mute, modifier = Modifier.align(Alignment.BottomStart))
    if (picked != null && formatX != null) {
      val (t, here) = picked
      val text = formatX(t) + "  " + here.joinToString("  ·  ") { (name, v, _) -> format(v) + if (series.size > 1 && name.isNotBlank()) " $name" else "" }
      Text(text, style = MaterialTheme.typography.labelLarge.merge(io.github.sandroroque.getnf.ui.calmo.Tabular), color = c.fg, maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.TopEnd).background(c.bg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
  }
}
