package io.github.sandroroque.getnf.ui.scan

import java.awt.image.BufferedImage

/** How colourful each pixel is (max minus min of its channels), as the camera's chroma planes give it live. */
internal fun chroma(image: BufferedImage): ByteArray {
  val rgb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
  return ByteArray(rgb.size) { i ->
    val p = rgb[i]; val r = p shr 16 and 0xff; val g = p shr 8 and 0xff; val b = p and 0xff
    (maxOf(r, g, b) - minOf(r, g, b)).toByte()
  }
}
