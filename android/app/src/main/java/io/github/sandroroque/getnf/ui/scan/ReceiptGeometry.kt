package io.github.sandroroque.getnf.ui.scan

import kotlin.math.*

/** Session-space outline learned from physical edges. This is never evidence of saved coverage. */
internal data class ReceiptGeometry(
  val outline: List<DocumentPoint>,
  val referenceAspect: Double,
  val frontFacing: Boolean,
  val confirmed: Boolean = false,
) {
  val height: Double get() = (distance(outline[0], outline[3]) + distance(outline[1], outline[2])) / 2

  fun matchesFullShape(pixels: List<DocumentPoint>): Boolean =
    validQuad(pixels) && abs(aspect(pixels) / referenceAspect - 1) <= .10

  /** Restricted similarity, deliberately unavailable for strong perspective or an unknown end. */
  fun topTransform(pixels: List<DocumentPoint>): DoubleArray? {
    if (!frontFacing || !isFrontFacing(pixels)) return null
    val a = pixels[0]; val b = pixels[1]; val target = outline[0]; val end = outline[1]
    val sx = b.x - a.x; val sy = b.y - a.y; val squared = sx * sx + sy * sy
    if (squared < 1) return null
    val tx = end.x - target.x; val ty = end.y - target.y
    val c = (tx * sx + ty * sy) / squared
    val s = (ty * sx - tx * sy) / squared
    return doubleArrayOf(c, -s, target.x - c * a.x + s * a.y,
      s, c, target.y - s * a.x - c * a.y, 0.0, 0.0, 1.0)
  }

  companion object {
    fun distance(a: DocumentPoint, b: DocumentPoint) = hypot(b.x - a.x, b.y - a.y)
    fun aspect(p: List<DocumentPoint>): Double =
      (distance(p[0], p[3]) + distance(p[1], p[2])) / (distance(p[0], p[1]) + distance(p[3], p[2]))

    fun validQuad(p: List<DocumentPoint>): Boolean {
      if (p.size != 4 || p.any { !it.x.isFinite() || !it.y.isFinite() }) return false
      val cross = p.indices.map { i ->
        val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
        (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
      }
      return cross.all { it > 1e-8 }
    }

    fun isFrontFacing(p: List<DocumentPoint>): Boolean {
      if (!validQuad(p)) return false
      val tx = p[1].x - p[0].x; val ty = p[1].y - p[0].y
      val width = hypot(tx, ty); val bottomWidth = distance(p[3], p[2])
      if (width < 1 || abs(bottomWidth / width - 1) > .10) return false
      return listOf(p[0] to p[3], p[1] to p[2]).all { (a, b) ->
        val x = b.x - a.x; val y = b.y - a.y; val length = hypot(x, y)
        length > 1 && abs((tx * x + ty * y) / (width * length)) <= sin(Math.toRadians(8.0)) && tx * y - ty * x > 0
      }
    }
  }
}
