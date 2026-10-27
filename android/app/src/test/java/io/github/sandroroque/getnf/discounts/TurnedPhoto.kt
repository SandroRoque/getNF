package io.github.sandroroque.getnf.discounts

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The angles receipts are photographed at in the tests: upright, every quarter, and slanted either way. */
val PHOTO_ANGLES = listOf(0.0, 15.0, -20.0, 90.0, 100.0, 180.0, 270.0, -10.0)

/**
 * This photo as if the receipt had been held [degrees] clockwise: every line's corners turned about the photo's centre
 * (they keep their reading order, as OCR gives them), the boxes the bounding boxes of those, and the frame the size
 * that holds them all. Lines without corners get them from their box first.
 */
fun PhotoText.turnedBy(degrees: Double): PhotoText {
  val rad = Math.toRadians(degrees); val c = cos(rad); val s = sin(rad)
  val cx = width / 2.0; val cy = height / 2.0
  fun turn(x: Double, y: Double) = (cx + (x - cx) * c - (y - cy) * s) to (cy + (x - cx) * s + (y - cy) * c)
  val turned = lines.map { l ->
    val corners = l.corners?.takeIf { it.size == 8 } ?: listOf(l.left, l.top, l.right, l.top, l.right, l.bottom, l.left, l.bottom)
    l to corners.chunked(2).map { (x, y) -> turn(x.toDouble(), y.toDouble()) }
  }
  val all = turned.flatMap { it.second }
  val dx = -(all.minOf { it.first }.coerceAtMost(0.0)); val dy = -(all.minOf { it.second }.coerceAtMost(0.0))
  return PhotoText(photo, (all.maxOf { it.first } + dx).roundToInt(), (all.maxOf { it.second } + dy).roundToInt(), turned.map { (l, pts) ->
    val p = pts.map { (x, y) -> (x + dx).roundToInt() to (y + dy).roundToInt() }
    l.copy(left = p.minOf { it.first }, top = p.minOf { it.second }, right = p.maxOf { it.first }, bottom = p.maxOf { it.second },
      corners = p.flatMap { listOf(it.first, it.second) })
  })
}
