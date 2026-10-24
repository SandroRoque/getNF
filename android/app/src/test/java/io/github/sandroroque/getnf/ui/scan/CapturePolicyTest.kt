package io.github.sandroroque.getnf.ui.scan

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CapturePolicyTest {
  private fun document(offset: Double = 0.0, sharp: Boolean = true) = DocumentObservation(
    polygon = listOf(DocumentPoint(.2 + offset, 0.0), DocumentPoint(.8 + offset, 0.0), DocumentPoint(.8 + offset, 1.0), DocumentPoint(.2 + offset, 1.0)),
    edges = listOf(EdgeSegment(EdgeKind.LEFT, DocumentPoint(.2, 0.0), DocumentPoint(.2, 1.0)), EdgeSegment(EdgeKind.RIGHT, DocumentPoint(.8, 0.0), DocumentPoint(.8, 1.0))),
    confidence = .8,
    quality = if (sharp) listOf(RegionQuality(CaptureBand(0.0, 1.0), 1.5, true, true)) else emptyList(),
    brightness = 200.0,
  )
  @Test fun partialStripCanBeUsefulWithoutQrOrEndpoints() {
    val policy = CapturePolicy()
    assertTrue(document().sharp)
    assertTrue(policy.useful(listOf(CaptureBand(0.0, 100.0)), null, null))
    assertFalse(policy.complete)
  }
  @Test fun topAndBottomWithoutConnectingCoverageCannotComplete() {
    val policy = CapturePolicy()
    policy.identify("a")
    policy.credit(listOf(CaptureBand(0.0, 100.0)), 0.0, null)
    policy.credit(listOf(CaptureBand(200.0, 300.0)), null, 300.0)
    assertFalse(policy.complete)
    policy.credit(listOf(CaptureBand(80.0, 220.0)), null, null)
    assertTrue(policy.connected)
    assertTrue(policy.complete)
  }
  @Test fun aCloseUpThatFillsTheLastGapIsUsefulEvenIfMostOfItIsAlreadyCovered() {
    // One overview saved top and bottom but missed a sliver (glare); a close-up mostly repeats what's saved (D53).
    val policy = CapturePolicy()
    policy.credit(listOf(CaptureBand(0.0, 180.0), CaptureBand(190.0, 400.0)), 0.0, 400.0)
    assertFalse(policy.connected)
    assertTrue(policy.useful(listOf(CaptureBand(120.0, 260.0)), null, null))
    // A close-up of what's already saved still isn't.
    assertFalse(policy.useful(listOf(CaptureBand(200.0, 340.0)), null, null))
  }
  @Test fun endpointsAreOnlyCreditedInsideValidatedBands() {
    val policy = CapturePolicy()
    policy.credit(listOf(CaptureBand(20.0, 80.0)), 0.0, 100.0)
    assertNull(policy.top); assertNull(policy.bottom)
  }
  @Test fun qrMayBeReadLastAndAnotherReceiptBlocksCompletion() {
    val policy = CapturePolicy()
    policy.credit(listOf(CaptureBand(0.0, 100.0)), 0.0, 100.0)
    assertFalse(policy.complete)
    policy.identify("a"); assertTrue(policy.complete)
    assertFalse(policy.identify("b")); assertFalse(policy.complete)
    policy.identify("a"); assertTrue(policy.complete)
  }
  @Test fun warningsAreConcurrentAndDestinationIsSingle() {
    val policy = CapturePolicy()
    val warnings = policy.guidance(document(.25, sharp = false), true, 50.0)
    assertEquals(listOf(CaptureHint.CENTER, CaptureHint.FOCUS, CaptureHint.TOP), warnings.warnings)
    assertEquals(MovementTarget.TOP, warnings.destination)
    policy.credit(listOf(CaptureBand(0.0, 100.0)), 0.0, null)
    assertEquals(MovementTarget.BOTTOM, policy.guidance(document(), true, 50.0).destination)
    policy.credit(listOf(CaptureBand(70.0, 200.0)), null, 200.0)
    assertEquals(MovementTarget.QR, policy.guidance(document(), true, 150.0).destination)
    policy.identify("a")
    assertNull(policy.guidance(document(), true, 150.0).destination)
  }
  @Test fun sharpRegionDoesNotHideFocusWarningForBlurredText() {
    val document = document().copy(quality = listOf(
      RegionQuality(CaptureBand(0.0, .5), 1.6, true, true),
      RegionQuality(CaptureBand(.5, 1.0), 1.0, true, false),
    ))
    assertTrue(document.sharp) // The good region can still be captured.
    assertEquals(listOf(CaptureBand(0.0, .5)), document.usableBands)
    assertTrue(CaptureHint.FOCUS in CapturePolicy().guidance(document, true, .5).warnings)
  }
  @Test fun observedTopWaitsForFocusAndStorageWithoutClaimingCoverage() {
    val policy = CapturePolicy()
    val waiting = policy.guidance(document(sharp = false), true, 50.0, topInView = true)
    assertEquals(listOf(CaptureHint.FOCUS), waiting.warnings); assertNull(waiting.destination)
    val ready = policy.guidance(document(), true, 50.0, topInView = true)
    assertEquals(listOf(CaptureHint.HOLD), ready.warnings); assertNull(ready.destination)
    assertNull(policy.top); assertNull(policy.bottom); assertTrue(policy.bands.isEmpty()); assertFalse(policy.complete)
  }
  @Test fun lostGeometryRequestsReacquisitionEvenBeforeSavingAPhoto() {
    val policy = CapturePolicy()
    val guidance = policy.guidance(document(), false, null, geometryKnown = true)
    assertTrue(CaptureHint.TRACKING in guidance.warnings)
    assertTrue(policy.bands.isEmpty()); assertFalse(policy.complete)
  }
  @Test fun stationaryCameraDoesNotCreateDuplicateSegments() {
    val policy = CapturePolicy()
    val strip = listOf(CaptureBand(0.0, 100.0))
    policy.credit(strip, null, null)
    assertFalse(policy.useful(strip, null, null))
    assertFalse(policy.useful(listOf(CaptureBand(0.0, 110.0)), null, null))
    assertTrue(policy.useful(listOf(CaptureBand(60.0, 160.0)), null, null))
    assertTrue(policy.useful(strip, 0.0, null))
  }
  @Test fun maskedPolygonExcludesBoundingBoxCorners() {
    val doc = document().copy(polygon = listOf(DocumentPoint(.5, 0.0), DocumentPoint(1.0, .5), DocumentPoint(.5, 1.0), DocumentPoint(0.0, .5)))
    assertTrue(doc.contains(.5, .5)); assertFalse(doc.contains(.05, .05))
  }
}
