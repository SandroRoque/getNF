package io.github.sandroroque.getnf.ui.scan

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ReceiptGeometryTest {
  private val reference = ReceiptGeometry(listOf(DocumentPoint(0.0, 0.0), DocumentPoint(256.0, 0.0),
    DocumentPoint(256.0, 768.0), DocumentPoint(0.0, 768.0)), 3.0, true)
  private fun quad(left: Double, top: Double, width: Double, height: Double) = listOf(
    DocumentPoint(left, top), DocumentPoint(left + width, top), DocumentPoint(left + width, top + height), DocumentPoint(left, top + height))
  private fun map(h: DoubleArray, p: DocumentPoint) = DocumentPoint(h[0] * p.x + h[1] * p.y + h[2], h[3] * p.x + h[4] * p.y + h[5])

  @Test fun topCoordinatesRemainFixedDuringZoomAndTranslation() {
    for (width in listOf(200.0, 400.0, 600.0)) {
      val q = quad(37.0, 52.0, width, width * 2)
      val h = reference.topTransform(q)!!
      assertEquals(0.0, map(h, q[0]).y, 1e-8)
      assertEquals(256.0, map(h, q[1]).x, 1e-8)
      val content = map(h, DocumentPoint(37 + width / 2, 52 + width / 2))
      assertEquals(128.0, content.x, 1e-8); assertEquals(128.0, content.y, 1e-8)
    }
  }
  @Test fun rotationDoesNotChangePaperCoordinates() {
    val angle = Math.toRadians(18.0)
    fun rotate(p: DocumentPoint) = DocumentPoint(p.x * cos(angle) - p.y * sin(angle), p.x * sin(angle) + p.y * cos(angle))
    val q = quad(100.0, 100.0, 200.0, 600.0).map(::rotate)
    val h = reference.topTransform(q)!!
    val p = map(h, rotate(DocumentPoint(200.0, 300.0)))
    assertEquals(128.0, p.x, 1e-8); assertEquals(256.0, p.y, 1e-8)
  }
  @Test fun taperedAndSkewedPaperRequireContentRegistration() {
    val taper = listOf(DocumentPoint(100.0, 50.0), DocumentPoint(300.0, 50.0), DocumentPoint(400.0, 600.0), DocumentPoint(0.0, 600.0))
    val skew = listOf(DocumentPoint(100.0, 50.0), DocumentPoint(300.0, 50.0), DocumentPoint(450.0, 600.0), DocumentPoint(250.0, 600.0))
    assertNull(reference.topTransform(taper)); assertNull(reference.topTransform(skew))
    assertNull(reference.copy(frontFacing = false).topTransform(quad(100.0, 50.0, 200.0, 600.0)))
  }
  @Test fun fourPolygonPointsDoNotMeanFourPhysicalEdges() {
    val q = quad(.2, .1, .6, .8)
    val sides = listOf(EdgeSegment(EdgeKind.LEFT, q[0], q[3]), EdgeSegment(EdgeKind.RIGHT, q[1], q[2]))
    val strip = DocumentObservation(q, sides, .8)
    assertTrue(strip.located); assertFalse(strip.hasFullOutline)
    val complete = strip.copy(top = .1, bottom = .9, edges = sides + listOf(
      EdgeSegment(EdgeKind.TOP, q[0], q[1]), EdgeSegment(EdgeKind.BOTTOM, q[3], q[2])))
    assertTrue(complete.hasFullOutline)
    assertFalse(complete.copy(polygon = q.map { it.copy(x = it.x + .5) }).hasFullOutline)
    assertFalse(complete.copy(polygon = listOf(q[0], q[2], q[1], q[3])).hasFullOutline)
  }
}
