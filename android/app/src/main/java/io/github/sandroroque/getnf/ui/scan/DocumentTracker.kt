package io.github.sandroroque.getnf.ui.scan

import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.*

internal enum class LocalizationSource { INITIAL_STRIP, FULL_OUTLINE, TOP_EDGE, CONTENT }
internal data class TrackedDocument(
  val bands: List<CaptureBand>, val top: Double?, val bottom: Double?, val center: Double,
  val visible: CaptureBand, val source: LocalizationSource, val axisReversed: Boolean = false,
)
internal data class TrackingResult(val geometry: ReceiptGeometry?, val position: TrackedDocument?)

/** Geometry, frame registration and saved coverage have independent lifetimes. One serial owner. */
internal class DocumentTracker : AutoCloseable {
  private data class Features(val points: List<Point>, val descriptors: Mat) : AutoCloseable {
    val usable get() = points.size >= 12 && points.maxOf { it.y } - points.minOf { it.y } >= 35 &&
      points.maxOf { it.x } - points.minOf { it.x } >= 25 && !descriptors.empty()
    override fun close() = descriptors.release()
  }
  private data class Anchor(val features: Features, val world: Mat, val visible: CaptureBand, val pixelWidth: Double) : AutoCloseable {
    override fun close() { features.close(); world.release() }
  }
  private var world: Mat? = null
  private var lastImageRotation: Int? = null
  private var lastAxisReversed = false
  private val orb = run { DocumentVision.ensureLoaded(); ORB.create(700) }
  private val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
  private val anchors = mutableListOf<Anchor>()
  var geometry: ReceiptGeometry? = null; private set
  val retainedAnchorCount: Int get() = anchors.size
  private var lastTop: List<DocumentPoint>? = null
  private var lastTopAt: Long? = null
  private var topObservations = 0

  fun breakContinuity() { lastTop = null; lastTopAt = null; topObservations = 0 }

  fun track(frame: VisionFrame, timestampMs: Long, allowReferenceReset: Boolean = false): TrackingResult {
    val doc = frame.observation
    if (!doc.located || !doc.hasSides) { breakContinuity(); return TrackingResult(geometry, null) }
    val gray = frame.gray
    val pixels = doc.polygon.map { DocumentPoint(it.x * gray.cols(), it.y * gray.rows()) }
    if (!ReceiptGeometry.validQuad(pixels)) { breakContinuity(); return TrackingResult(geometry, null) }
    var existingGeometry = geometry
    val full = doc.hasFullOutline
    val orientationCompatible = lastImageRotation == null || lastImageRotation == frame.rotation
    val topModel = geometry?.takeIf { doc.top != null && doc.supportsTopSimilarity && orientationCompatible && !lastAxisReversed }?.topTransform(pixels)
    if (topModel != null) {
      val old = lastTop
      val oldAt = lastTopAt
      val coherent = old != null && oldAt != null && timestampMs - oldAt in 1..1000 &&
        kotlin.math.abs(ReceiptGeometry.distance(pixels[0], pixels[1]) / ReceiptGeometry.distance(old[0], old[1]) - 1) <= .25 &&
        ReceiptGeometry.distance(pixels[0], old[0]) <= hypot(gray.cols().toDouble(), gray.rows().toDouble()) * .15
      topObservations = if (coherent) topObservations + 1 else 1
      lastTop = pixels; lastTopAt = timestampMs
    } else breakContinuity()

    // Extract once. Even a frame unsuitable for capture can carry useful registration features.
    val features = extract(gray, frame.mask, pixels)
    try {
      // Before any photo/candidate exists, an unanchored provisional map must not trap
      // the session on an incidental object seen while opening the camera.
      if (world != null && anchors.isEmpty() && full && features.usable && allowReferenceReset) {
        world?.release(); world = null; geometry = null; existingGeometry = null
        lastImageRotation = null; lastAxisReversed = false; breakContinuity()
      }
      var source = LocalizationSource.CONTENT
      var transform: Mat? = null
      if (world == null) {
        if (!full && (!doc.sharp || !features.usable)) return TrackingResult(geometry, null)
        val width = (ReceiptGeometry.distance(pixels[0], pixels[1]) + ReceiptGeometry.distance(pixels[2], pixels[3])) / 2
        val height = (ReceiptGeometry.distance(pixels[0], pixels[3]) + ReceiptGeometry.distance(pixels[1], pixels[2])) / 2 * 256 / width
        val rectangle = listOf(DocumentPoint(0.0, 0.0), DocumentPoint(256.0, 0.0), DocumentPoint(256.0, height), DocumentPoint(0.0, height))
        transform = perspective(pixels, rectangle)
        source = if (full) LocalizationSource.FULL_OUTLINE else LocalizationSource.INITIAL_STRIP
        if (full) geometry = ReceiptGeometry(rectangle, ReceiptGeometry.aspect(pixels), doc.supportsTopSimilarity && ReceiptGeometry.isFrontFacing(pixels))
      } else {
        transform = relocalize(features)
        val reference = geometry
        if (transform == null && orientationCompatible && full && reference != null && reference.matchesFullShape(pixels)) {
          val outline = reference.outline
          val target = if (lastAxisReversed) listOf(outline[2], outline[3], outline[0], outline[1]) else outline
          transform = perspective(pixels, target)
          source = LocalizationSource.FULL_OUTLINE
        }
        if (transform == null && topModel != null && topObservations >= 2) {
          transform = Mat(3, 3, CvType.CV_64F).also { it.put(0, 0, *topModel) }
          source = LocalizationSource.TOP_EDGE
        }
      }
      val accepted = transform ?: return TrackingResult(geometry, null)
      var transferred = false
      try {
        val mapped = mapDocument(doc, gray.cols(), gray.rows(), accepted, source) ?: return TrackingResult(geometry, null)
        if (full) {
          val projected = project(pixels, accepted)
          val mappedOutline = if (mapped.axisReversed) listOf(projected[2], projected[3], projected[0], projected[1]) else projected
          if (!ReceiptGeometry.validQuad(mappedOutline)) return TrackingResult(geometry, null)
          if (geometry == null) {
            // Integrate later overviews only through registration; never rebase existing coordinates.
            geometry = ReceiptGeometry(mappedOutline, ReceiptGeometry.aspect(pixels),
              doc.supportsTopSimilarity && ReceiptGeometry.isFrontFacing(pixels) && ReceiptGeometry.isFrontFacing(mappedOutline) &&
                abs(ReceiptGeometry.aspect(mappedOutline) / ReceiptGeometry.aspect(pixels) - 1) <= .10, true)
          } else if (existingGeometry != null && mappedOutline.zip(existingGeometry.outline).all { (a, b) ->
              ReceiptGeometry.distance(a, b) <= max(5.0, existingGeometry.height * .03)
            }) geometry = geometry!!.copy(confirmed = true)
        } else if (topModel != null && topObservations >= 2 && mapped.top != null && geometry != null &&
          abs(mapped.top - geometry!!.outline.take(2).map { it.y }.average()) <= 5) {
          geometry = geometry!!.copy(confirmed = true)
        }
        retain(features, accepted, mapped, pixels)
        world?.release(); world = accepted; lastImageRotation = frame.rotation; lastAxisReversed = mapped.axisReversed; transferred = true
        return TrackingResult(geometry, mapped)
      } finally { if (!transferred) accepted.release() }
    } finally { features.close() }
  }

  private fun extract(gray: Mat, mask: Mat, pixels: List<DocumentPoint>): Features {
    val keys = MatOfKeyPoint(); val descriptors = Mat(); val normalized = Mat(); val normalizedMask = Mat()
    try {
      // Normalize paper width before descriptor extraction: zoom must not turn identical
      // receipt text into a different sampling scale. Keep the workload strictly bounded.
      val width = (ReceiptGeometry.distance(pixels[0], pixels[1]) + ReceiptGeometry.distance(pixels[2], pixels[3])) / 2
      val scale = min(324.0 / width, 960.0 / max(gray.cols(), gray.rows()))
      Imgproc.resize(gray, normalized, Size(), scale, scale, if (scale < 1) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
      Imgproc.resize(mask, normalizedMask, normalized.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
      orb.detectAndCompute(normalized, normalizedMask, keys, descriptors)
      val sx = normalized.cols().toDouble() / gray.cols(); val sy = normalized.rows().toDouble() / gray.rows()
      return Features(keys.toArray().map { Point(it.pt.x / sx, it.pt.y / sy) }, descriptors)
    } catch (error: Throwable) { descriptors.release(); throw error }
    finally { keys.release(); normalized.release(); normalizedMask.release() }
  }
  private fun retain(features: Features, transform: Mat, mapped: TrackedDocument, pixels: List<DocumentPoint>) {
    if (!features.usable) return
    val pixelWidth = (ReceiptGeometry.distance(pixels[0], pixels[1]) + ReceiptGeometry.distance(pixels[2], pixels[3])) / 2
    val seen = mergeBands(anchors.map { it.visible })
    val fresh = mapped.visible.length - seen.sumOf { max(0.0, min(it.end, mapped.visible.end) - max(it.start, mapped.visible.start)) }
    val closest = anchors.asReversed().minByOrNull { abs((it.visible.start + it.visible.end) / 2 - mapped.center) }
    val scaleChanged = closest != null && max(pixelWidth / closest.pixelWidth, closest.pixelWidth / pixelWidth) >= 1.25
    if (anchors.isNotEmpty() && fresh < mapped.visible.length * .20 && !scaleChanged) return
    anchors += Anchor(Features(features.points, features.descriptors.clone()), transform.clone(), mapped.visible, pixelWidth)
    if (anchors.size > 100) anchors.removeAt(1).close()
  }
  private fun relocalize(current: Features): Mat? {
    if (!current.usable) return null
    for (anchor in anchors.asReversed()) {
      val matches = mutableListOf<MatOfDMatch>(); val reverse = mutableListOf<MatOfDMatch>()
      try {
        matcher.knnMatch(current.descriptors, anchor.features.descriptors, matches, 2)
        matcher.knnMatch(anchor.features.descriptors, current.descriptors, reverse, 2)
        val forwardPairs = matches.map { it.toArray() }
        val backwardPairs = reverse.map { it.toArray() }
        val good = forwardPairs.filter { it.size == 2 }.mapNotNull { pair ->
          val m = pair[0]; val back = backwardPairs[m.trainIdx]
          if (back.size != 2 || back[0].trainIdx != m.queryIdx) null
          else if (m.distance < .6 * pair[1].distance || back[0].distance < .6 * back[1].distance) m else null
        }
        val local = homography(good.map { current.points[it.queryIdx] }, good.map { anchor.features.points[it.trainIdx] })
        if (local == null) continue
        try { return multiply(anchor.world, local) } finally { local.release() }
      } finally { matches.forEach { it.release() }; reverse.forEach { it.release() } }
    }
    return null
  }
  private fun perspective(from: List<DocumentPoint>, to: List<DocumentPoint>): Mat {
    val src = MatOfPoint2f(*from.map { Point(it.x, it.y) }.toTypedArray())
    val dst = MatOfPoint2f(*to.map { Point(it.x, it.y) }.toTypedArray())
    try { return Imgproc.getPerspectiveTransform(src, dst) } finally { src.release(); dst.release() }
  }
  private fun project(points: List<DocumentPoint>, transform: Mat): List<DocumentPoint> {
    val src = MatOfPoint2f(*points.map { Point(it.x, it.y) }.toTypedArray()); val dst = MatOfPoint2f()
    try { Core.perspectiveTransform(src, dst, transform); return dst.toArray().map { DocumentPoint(it.x, it.y) } }
    finally { src.release(); dst.release() }
  }
  private fun homography(from: List<Point>, to: List<Point>): Mat? {
    if (from.size < 12) return null
    if (from.maxOf { it.y } - from.minOf { it.y } < 35 || from.maxOf { it.x } - from.minOf { it.x } < 25) return null
    val src = MatOfPoint2f(*from.toTypedArray()); val dst = MatOfPoint2f(*to.toTypedArray()); val inliers = Mat()
    try {
      val h = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 2.5, inliers, 1000, .995)
      if (h.empty() || Core.countNonZero(inliers) < max(12, ceil(from.size * .55).toInt())) {
        h.release(); return null
      }
      val flags = ByteArray(inliers.rows()); inliers.get(0, 0, flags)
      val accepted = flags.indices.filter { flags[it].toInt() != 0 }.map { from[it] }
      if (accepted.maxOf { it.y } - accepted.minOf { it.y } < 35 || accepted.maxOf { it.x } - accepted.minOf { it.x } < 25) { h.release(); return null }
      val values = DoubleArray(9); h.get(0, 0, values)
      if (values.any { !it.isFinite() } || abs(values[6]) > .003 || abs(values[7]) > .003) { h.release(); return null }
      return h
    } finally { src.release(); dst.release(); inliers.release() }
  }
  private fun multiply(a: Mat, b: Mat): Mat {
    val result = Mat(); val empty = Mat()
    try { Core.gemm(a, b, 1.0, empty, 0.0, result); return result } finally { empty.release() }
  }
  private fun mapDocument(doc: DocumentObservation, w: Int, h: Int, transform: Mat, source: LocalizationSource): TrackedDocument? {
    fun y(value: Double): Double {
      val topY = (doc.polygon[0].y + doc.polygon[1].y) / 2
      val bottomY = (doc.polygon[2].y + doc.polygon[3].y) / 2
      val t = (value - topY) / (bottomY - topY)
      val left = doc.polygon[0].x + (doc.polygon[3].x - doc.polygon[0].x) * t
      val right = doc.polygon[1].x + (doc.polygon[2].x - doc.polygon[1].x) * t
      val point = MatOfPoint2f(Point((left + right) / 2 * w, value * h)); val output = MatOfPoint2f()
      return try { Core.perspectiveTransform(point, output, transform); output.toArray()[0].y } finally { point.release(); output.release() }
    }
    val bands = doc.usableBands.map {
      val a = y(it.start); val b = y(it.end)
      if (!a.isFinite() || !b.isFinite() || a == b) return null
      CaptureBand(min(a, b), max(a, b))
    }
    if (bands.any { !it.start.isFinite() || !it.end.isFinite() || it.length > 4000 }) return null
    val corners = MatOfPoint2f(*doc.polygon.map { Point(it.x * w, it.y * h) }.toTypedArray()); val mapped = MatOfPoint2f()
    try {
      Core.perspectiveTransform(corners, mapped, transform)
      val p = mapped.toArray()
      val width = (distance(p[0], p[1]) + distance(p[2], p[3])) / 2
      if (width !in 170.0..385.0) return null
    } finally { corners.release(); mapped.release() }
    val a = y((doc.polygon[0].y + doc.polygon[1].y) / 2)
    val b = y((doc.polygon[2].y + doc.polygon[3].y) / 2)
    if (!a.isFinite() || !b.isFinite() || a == b) return null
    val reversed = b < a
    return TrackedDocument(bands, (if (reversed) doc.bottom else doc.top)?.let(::y),
      (if (reversed) doc.top else doc.bottom)?.let(::y), y(.5), CaptureBand(min(a,b), max(a,b)), source, reversed)
  }
  override fun close() {
    world?.release()
    anchors.forEach { it.close() }; anchors.clear()
    orb.clear(); matcher.clear()
  }
  private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
}
