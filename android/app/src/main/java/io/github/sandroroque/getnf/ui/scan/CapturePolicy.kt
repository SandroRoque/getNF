package io.github.sandroroque.getnf.ui.scan

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
internal data class DocumentPoint(val x: Double, val y: Double)

internal enum class EdgeKind { LEFT, RIGHT, TOP, BOTTOM }
internal data class EdgeSegment(val kind: EdgeKind, val start: DocumentPoint, val end: DocumentPoint)

@Serializable
internal data class CaptureBand(val start: Double, val end: Double) {
  init { require(start.isFinite() && end.isFinite() && end >= start) }
  val length: Double get() = end - start
}

internal data class RegionQuality(val band: CaptureBand, val score: Double, val hasContent: Boolean, val usable: Boolean)

internal data class DocumentObservation(
  val polygon: List<DocumentPoint> = emptyList(),
  val edges: List<EdgeSegment> = emptyList(),
  val confidence: Double = 0.0,
  val top: Double? = null,
  val bottom: Double? = null,
  val quality: List<RegionQuality> = emptyList(),
  val brightness: Double = 0.0,
  val contour: List<DocumentPoint> = emptyList(),
  val supportsTopSimilarity: Boolean = true,
) {
  val located: Boolean get() = polygon.size == 4 && confidence >= .6
  val hasFullOutline: Boolean get() = located && top != null && bottom != null &&
    EdgeKind.entries.all { kind -> edges.any { it.kind == kind } } &&
    polygon.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 } && ReceiptGeometry.validQuad(polygon)
  val hasSides: Boolean get() = edges.any { it.kind == EdgeKind.LEFT } && edges.any { it.kind == EdgeKind.RIGHT }
  val usableBands: List<CaptureBand> get() = if (located && hasSides) mergeBands(quality.filter { it.usable }.map { it.band }) else emptyList()
  val sharp: Boolean get() = located && hasSides && quality.any { it.hasContent && it.usable }
  val score: Double get() = quality.filter { it.usable && it.hasContent }.map { it.score }.sorted().let {
    if (it.isEmpty()) 0.0 else it[(it.size * .2).toInt().coerceAtMost(it.lastIndex)]
  }
  fun contains(x: Double, y: Double): Boolean {
    if (!located) return false
    val boundary = contour.ifEmpty { polygon }
    var inside = false
    for (i in boundary.indices) {
      val a = boundary[i]; val b = boundary[(i + 1) % boundary.size]
      if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
    }
    return inside
  }
}

internal fun mergeBands(bands: List<CaptureBand>, tolerance: Double = .001): List<CaptureBand> {
  val result = mutableListOf<CaptureBand>()
  for (band in bands.sortedBy { it.start }) {
    val old = result.lastOrNull()
    if (old != null && band.start <= old.end + tolerance) result[result.lastIndex] = CaptureBand(old.start, max(old.end, band.end))
    else result.add(band)
  }
  return result
}

internal enum class CaptureHint(val label: String) {
  CENTER("Centralize a nota"),
  FOCUS("Aproxime ou afaste a câmera até focar"),
  TOP("Guie até o topo da nota"),
  BOTTOM("Guie até o fundo da nota"),
  QR("Centralize o QR Code"),
  TRACKING("Volte ao último trecho reconhecido, mantendo sobreposição"),
  OTHER_RECEIPT("Outra nota detectada. Volte à nota desta captura"),
  LIGHT("Ajuste a iluminação da nota"),
  HOLD("Segure um instante"),
}
internal enum class MovementTarget { TOP, BOTTOM, QR }
internal data class GuidanceState(val warnings: List<CaptureHint>, val destination: MovementTarget?)

/** Coverage is credited only after a JPEG has been validated and durably saved. */
internal class CapturePolicy {
  var bands: List<CaptureBand> = emptyList(); private set
  var top: Double? = null; private set
  var bottom: Double? = null; private set
  var key: String? = null; private set
  var conflictingKey = false; private set
  private val qualityBands = mutableListOf<Pair<CaptureBand, Double>>()

  fun identify(value: String): Boolean {
    if (key != null && value != key) { conflictingKey = true; return false }
    key = value
    conflictingKey = false
    return true
  }
  fun sameDocumentVisible() { conflictingKey = false }

  fun credit(valid: List<CaptureBand>, observedTop: Double?, observedBottom: Double?, score: Double = 0.0) {
    bands = mergeBands(bands + valid)
    qualityBands += valid.map { it to score }
    if (observedTop != null && valid.any { observedTop >= it.start - 2 && observedTop <= it.end + 2 }) top = observedTop
    if (observedBottom != null && valid.any { observedBottom >= it.start - 2 && observedBottom <= it.end + 2 }) bottom = observedBottom
  }
  val connected: Boolean get() {
    val a = top ?: return false; val b = bottom ?: return false
    return b > a && bands.any { it.start <= a + 2 && it.end >= b - 2 }
  }
  val complete: Boolean get() = key != null && connected && !conflictingKey

  fun gain(candidate: List<CaptureBand>): Double = candidate.sumOf { c ->
    max(0.0, c.length - bands.sumOf { old -> max(0.0, min(c.end, old.end) - max(c.start, old.start)) })
  }
  fun useful(candidate: List<CaptureBand>, candidateTop: Double?, candidateBottom: Double?, score: Double = 0.0): Boolean {
    if (candidate.isEmpty()) return false
    if ((top == null && candidateTop != null) || (bottom == null && candidateBottom != null)) return true
    val improves = score > 0 && candidate.any { band ->
      val old = qualityBands.filter { (b, _) -> b.start < band.end && b.end > band.start }.maxOfOrNull { it.second }
      old != null && old > 0 && score > old * 1.25
    }
    return improves || bands.isEmpty() || gain(candidate) >= candidate.sumOf { it.length } * .35 || fillsGap(candidate)
  }
  /** Both ends are saved but something between them isn't: any new coverage there is what's left to do (D53). */
  private fun fillsGap(candidate: List<CaptureBand>): Boolean {
    val a = top ?: return false; val b = bottom ?: return false
    if (connected || b <= a) return false
    return gain(candidate.mapNotNull { c -> if (min(c.end, b) > max(c.start, a)) CaptureBand(max(c.start, a), min(c.end, b)) else null }) > .5
  }
  fun guidance(document: DocumentObservation, tracked: Boolean, centerY: Double?, topInView: Boolean = false, geometryKnown: Boolean = false): GuidanceState {
    val warnings = mutableListOf<CaptureHint>()
    val center = document.polygon.map { it.x }.average()
    if (!document.located || !document.hasSides || center !in .3.. .7) warnings += CaptureHint.CENTER
    if (document.located && (!document.sharp || document.quality.any { it.hasContent && !it.usable })) warnings += CaptureHint.FOCUS
    if (document.located && document.brightness < 45) warnings += CaptureHint.LIGHT
    if (conflictingKey) warnings += CaptureHint.OTHER_RECEIPT
    if ((bands.isNotEmpty() || geometryKnown) && !tracked && document.located) warnings += CaptureHint.TRACKING
    var destination = when {
      top == null -> MovementTarget.TOP
      bottom == null -> MovementTarget.BOTTOM
      !connected -> {
        val gaps = bands.zipWithNext().map { (a, b) -> (a.end + b.start) / 2 }
        val nearest = gaps.minByOrNull { kotlin.math.abs(it - (centerY ?: top!!)) } ?: top!!
        if (centerY == null || nearest < centerY) MovementTarget.TOP else MovementTarget.BOTTOM
      }
      key == null -> MovementTarget.QR
      else -> null
    }
    if (top == null && destination == MovementTarget.TOP && topInView) {
      destination = null
      if (document.sharp) warnings += CaptureHint.HOLD
    }
    destination?.let { warnings += when (it) {
      MovementTarget.TOP -> CaptureHint.TOP
      MovementTarget.BOTTOM -> CaptureHint.BOTTOM
      MovementTarget.QR -> CaptureHint.QR
    } }
    return GuidanceState(warnings.distinct(), destination)
  }
}
