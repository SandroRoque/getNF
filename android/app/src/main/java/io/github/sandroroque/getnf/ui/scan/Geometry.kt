package io.github.sandroroque.getnf.ui.scan

import kotlin.math.hypot
import kotlinx.serialization.Serializable

/** A point in an image: normalized (0–1) or in pixels, as each use says. */
@Serializable
internal data class DocumentPoint(val x: Double, val y: Double)

internal fun distance(a: DocumentPoint, b: DocumentPoint): Double = hypot(b.x - a.x, b.y - a.y)

/**
 * An 8-bit grayscale image, row-major, with optionally how colourful each pixel is ([chroma], 0 for grey). Plain
 * Kotlin, so the capture's logic runs on the JVM as well as live (D82).
 */
internal class GrayImage(val width: Int, val height: Int, val pixels: ByteArray, val chroma: ByteArray? = null) {
  init { require(pixels.size == width * height && (chroma == null || chroma.size == pixels.size)) }
  operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xff
  fun chroma(x: Int, y: Int): Int = chroma?.let { it[y * width + x].toInt() and 0xff } ?: 0
}
