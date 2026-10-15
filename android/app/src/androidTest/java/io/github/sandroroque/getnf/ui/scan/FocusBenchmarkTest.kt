package io.github.sandroroque.getnf.ui.scan

import android.graphics.*
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Synthetic focus benchmark (D37). Ground truth comes from on-device OCR against the known text, so it doesn't
 * depend on the metric under test. A text band is legible when every line in it reads at >= 90% characters.
 * Run alone: ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...FocusBenchmarkTest
 * Results go to logcat with tag FocusBenchmark.
 */
@RunWith(AndroidJUnit4::class)
class FocusBenchmarkTest {
  private data class Scene(val name: String, val font: Float, val ink: Int, val qr: Boolean = false)
  private data class Degradation(val name: String, val apply: (Mat) -> Unit)
  private data class TextLine(val text: String, val top: Double, val bottom: Double)
  private data class Rendered(val bitmap: Bitmap, val lines: List<TextLine>, val qr: List<DocumentPoint>)
  private data class Outcome(val scene: String, val degradation: String, val jpeg: Boolean, val band: CaptureBand,
    val legible: Boolean?, val accuracy: Double, val quality: RegionQuality,
    /** Lowest OCR line confidence in the band, without looking at the expected text; null if OCR read nothing. */
    val ocrConfidence: Double?,
    /** The app's decision: gradient pre-filter plus OCR (TextLegibility.legibleBands). */
    val accepted: Boolean)
  private data class Read(val centerY: Double, val text: String, val confidence: Double)

  private val width = 1440; private val height = 1920
  private val paper = org.opencv.core.Rect(360, 40, 720, 1840)
  private val scenes = listOf(
    Scene("normal-28", 28f, 20), Scene("normal-20", 20f, 20), Scene("normal-36", 36f, 20),
    Scene("faint-28", 28f, 165), Scene("faint-20", 20f, 165), Scene("faint-28-qr", 28f, 165, qr = true),
  )
  private val degradations = buildList {
    add(Degradation("none") { })
    for (sigma in listOf(1.0, 2.0, 3.5)) add(Degradation("gauss-$sigma") { Imgproc.GaussianBlur(it, it, Size(0.0, 0.0), sigma) })
    for (length in listOf(5, 9, 15, 25)) for (angle in listOf(0, 45, 90))
      add(Degradation("motion-$length@$angle") { motion(it, length, angle.toDouble()) })
  }

  private fun render(scene: Scene): Rendered {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(Color.rgb(65, 75, 80))
    paint.color = Color.rgb(238, 238, 232); canvas.drawRect(paper.x.toFloat(), paper.y.toFloat(), (paper.x + paper.width).toFloat(), (paper.y + paper.height).toFloat(), paint)
    paint.color = Color.rgb(scene.ink, scene.ink, scene.ink); paint.textSize = scene.font; paint.typeface = Typeface.MONOSPACE
    val random = java.util.Random(scene.name.hashCode().toLong())
    val words = listOf("ARROZ", "FEIJAO", "LEITE", "CAFE", "PAO", "QUEIJO", "TOMATE", "BANANA", "SABAO", "OLEO", "ACUCAR", "MASSA")
    val lines = mutableListOf<TextLine>()
    val qrSpace = if (scene.qr) 360 else 0
    var y = paper.y + 60f
    val chars = ((paper.width - 40) / (scene.font * .6f)).toInt()
    while (y < paper.y + paper.height - 40 - qrSpace) {
      val text = "%03d %s %d,%02d UN %d".format(random.nextInt(999), words[random.nextInt(words.size)],
        random.nextInt(90) + 1, random.nextInt(100), 1_000_000 + random.nextInt(8_999_999)).take(chars)
      canvas.drawText(text, paper.x + 20f, y, paint)
      lines += TextLine(text, (y - scene.font * .8) / height, (y + scene.font * .25) / height)
      y += scene.font * 1.5f
    }
    val qr = if (!scene.qr) emptyList() else {
      // A QR-like block: high-contrast modules that a scorer could mistake for focused content.
      paint.color = Color.BLACK
      val x0 = paper.x + paper.width / 2f - 150; val y0 = paper.y + paper.height - 340f
      val random2 = java.util.Random(7)
      for (row in 0 until 30) for (col in 0 until 30) if (random2.nextBoolean())
        canvas.drawRect(x0 + col * 10, y0 + row * 10, x0 + col * 10 + 10, y0 + row * 10 + 10, paint)
      listOf(DocumentPoint(x0.toDouble() / width, y0.toDouble() / height), DocumentPoint((x0 + 300.0) / width, y0.toDouble() / height),
        DocumentPoint((x0 + 300.0) / width, (y0 + 300.0) / height), DocumentPoint(x0.toDouble() / width, (y0 + 300.0) / height))
    }
    return Rendered(bitmap, lines, qr)
  }

  private fun motion(mat: Mat, length: Int, angle: Double) {
    val kernel = Mat.zeros(length, length, CvType.CV_32F)
    try {
      val c = (length - 1) / 2.0; val dx = Math.cos(Math.toRadians(angle)) * c; val dy = Math.sin(Math.toRadians(angle)) * c
      Imgproc.line(kernel, Point(c - dx, c - dy), Point(c + dx, c + dy), Scalar(1.0), 1)
      Core.divide(kernel, Scalar(Core.sumElems(kernel).`val`[0]), kernel)
      Imgproc.filter2D(mat, mat, -1, kernel)
    } finally { kernel.release() }
  }

  private fun toBitmap(gray: Mat): Bitmap {
    val rgba = Mat()
    try {
      Imgproc.cvtColor(gray, rgba, Imgproc.COLOR_GRAY2RGBA)
      return Bitmap.createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }
    } finally { rgba.release() }
  }
  private fun toGray(bitmap: Bitmap): Mat {
    val rgba = Mat(); val gray = Mat()
    try { Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); return gray } finally { rgba.release() }
  }
  private fun jpeg(bitmap: Bitmap): Bitmap {
    val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
    return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
  }

  private fun levenshtein(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
      val current = IntArray(b.length + 1); current[0] = i
      for (j in 1..b.length) current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
      previous = current
    }
    return previous[b.length]
  }

  private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
  private val ocrMillis = mutableListOf<Long>()
  private fun ocr(bitmap: Bitmap): List<Pair<android.graphics.Rect, Read>> {
    val started = System.nanoTime()
    val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
    ocrMillis += (System.nanoTime() - started) / 1_000_000
    return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
      line.boundingBox?.let { it to Read(it.exactCenterY().toDouble() / height, line.text, line.confidence.toDouble()) }
    }
  }

  /** Per drawn line: fraction of characters OCR got right (spaces ignored). */
  private fun lineAccuracy(read: List<Pair<android.graphics.Rect, Read>>, lines: List<TextLine>): List<Double> {
    run {
      return lines.map { truth ->
        val found = read.filter { (box, _) -> box.exactCenterY() / height in truth.top..truth.bottom }
          .sortedBy { it.first.left }.joinToString("") { it.second.text }.replace(" ", "")
        val expected = truth.text.replace(" ", "")
        max(0.0, 1.0 - levenshtein(found, expected).toDouble() / expected.length)
      }
    }
  }

  private fun evaluate(scene: Scene, degradation: Degradation, useJpeg: Boolean, rendered: Rendered): List<Outcome> {
    val source = toGray(rendered.bitmap); val mask = Mat.zeros(source.size(), CvType.CV_8UC1)
    var degraded: Bitmap? = null; var stored: Bitmap? = null; var gray: Mat? = null
    try {
      degradation.apply(source)
      degraded = toBitmap(source)
      stored = if (useJpeg) jpeg(degraded) else degraded
      gray = toGray(stored)
      Imgproc.rectangle(mask, paper.tl(), paper.br(), Scalar(255.0), Imgproc.FILLED)
      val quality = DocumentVision.quality(gray, mask, rendered.qr)
      val read = ocr(stored)
      val accuracy = lineAccuracy(read, rendered.lines)
      val appBands = TextLegibility.legibleBands(quality,
        read.map { (box, r) -> OcrLine(DocumentPoint(box.exactCenterX().toDouble() / width, r.centerY), r.confidence) }, rendered.qr)
      return quality.map { q ->
        val inside = rendered.lines.indices.filter { i -> val l = rendered.lines[i]; l.bottom > q.band.start && l.top < q.band.end }
        val worst = inside.minOfOrNull { accuracy[it] } ?: 1.0
        val confidence = read.map { it.second }.filter { it.centerY in q.band.start..q.band.end }.minOfOrNull { it.confidence }
        val accepted = appBands.any { it.start <= q.band.start + 1e-9 && it.end >= q.band.end - 1e-9 }
        Outcome(scene.name, degradation.name, useJpeg, q.band, if (inside.isEmpty()) null else worst >= .9, worst, q, confidence, accepted)
      }
    } finally {
      source.release(); mask.release(); gray?.release()
      if (stored !== degraded) stored?.recycle(); degraded?.recycle()
    }
  }

  @Test fun focusMetricAgainstOcrGroundTruth() {
    DocumentVision.ensureLoaded()
    val outcomes = mutableListOf<Outcome>()
    for (scene in scenes) {
      val rendered = render(scene)
      try { for (d in degradations) for (j in listOf(false, true)) outcomes += evaluate(scene, d, j, rendered) }
      finally { rendered.bitmap.recycle() }
    }
    recognizer.close()
    // Only bands fully on paper and away from its ends: paper ends are covered by other tests.
    val bands = outcomes.filter { it.band.start * height >= paper.y + 60 && it.band.end * height <= paper.y + paper.height - 60 }
    val text = bands.filter { it.legible != null }
    val illegibleAccepted = text.filter { it.legible == false && it.accepted }
    val legibleRejected = text.filter { it.legible == true && !it.accepted }
    val tag = "FocusBenchmark"
    for ((key, group) in text.groupBy { Triple(it.scene, it.degradation, it.jpeg) }) {
      Log.i(tag, "%-12s %-14s jpeg=%-5s legible=%2d/%-2d prefilter=%2d accepted=%2d/%-2d minScore=%.3f content=%2d acc=%.2f".format(
        key.first, key.second, key.third, group.count { it.legible == true }, group.size, group.count { it.quality.usable }, group.count { it.accepted }, group.size,
        group.minOf { it.quality.score }, group.count { it.quality.hasContent }, group.minOf { it.accuracy }))
    }
    Log.i(tag, "SUMMARY textBands=${text.size} legible=${text.count { it.legible == true }} illegibleAccepted=${illegibleAccepted.size} legibleRejected=${legibleRejected.size}")
    // Calibration: decision = content band with row score >= threshold (what quality() does at its threshold).
    for (threshold in listOf(.5, .6, .7, .8, .85, .9, .95, 1.0, 1.1, 1.18, 1.3)) {
      val accepted = { o: Outcome -> o.quality.hasContent && o.quality.score >= threshold }
      Log.i(tag, "SWEEP metric>=%.2f illegibleAccepted=%d legibleRejected=%d".format(threshold,
        text.count { it.legible == false && accepted(it) }, text.count { it.legible == true && !accepted(it) }))
    }
    // Pre-filter plus OCR >= 0.8, per pre-filter threshold.
    for (threshold in listOf(.5, .6, .7, .8, .85, .9, .95, 1.0)) {
      val accepted = { o: Outcome -> o.quality.hasContent && o.quality.score >= threshold && o.ocrConfidence != null && o.ocrConfidence >= .8 }
      Log.i(tag, "SWEEP pipeline metric>=%.2f+ocr>=0.8 illegibleAccepted=%d legibleRejected=%d".format(threshold,
        text.count { it.legible == false && accepted(it) }, text.count { it.legible == true && !accepted(it) }))
    }
    // OCR alone, as the app could use it: every line read in the band has confidence >= threshold.
    for (threshold in listOf(.5, .6, .7, .8, .9)) {
      val accepted = { o: Outcome -> o.ocrConfidence != null && o.ocrConfidence >= threshold }
      Log.i(tag, "SWEEP ocr>=%.1f illegibleAccepted=%d legibleRejected=%d".format(threshold,
        text.count { it.legible == false && accepted(it) }, text.count { it.legible == true && !accepted(it) }))
    }
    Log.i(tag, "OCR ms per 1440x1920 image: median=${ocrMillis.sorted()[ocrMillis.size / 2]} max=${ocrMillis.max()} (emulator)")
    Log.i(tag, "BREAKDOWN illegibleAccepted acc<0.5: withLines=%d unread=%d; acc>=0.5: withLines=%d unread=%d".format(
      illegibleAccepted.count { it.accuracy < .5 && it.ocrConfidence != null }, illegibleAccepted.count { it.accuracy < .5 && it.ocrConfidence == null },
      illegibleAccepted.count { it.accuracy >= .5 && it.ocrConfidence != null }, illegibleAccepted.count { it.accuracy >= .5 && it.ocrConfidence == null }))
    for (o in illegibleAccepted.filter { it.accuracy < .5 }) Log.i(tag, "SEVERE ${o.scene} ${o.degradation} jpeg=${o.jpeg} band=%.3f-%.3f acc=%.2f minConf=%s".format(
      o.band.start, o.band.end, o.accuracy, o.ocrConfidence?.let { "%.2f".format(it) }))
    for (o in illegibleAccepted.distinctBy { Triple(it.scene, it.degradation, it.jpeg) })
      Log.i(tag, "FALSE-ACCEPT ${o.scene} ${o.degradation} jpeg=${o.jpeg} score=${"%.3f".format(o.quality.score)} content=${o.quality.hasContent} acc=${"%.2f".format(o.accuracy)}")
    // D38 regression bounds. The confidence threshold is calibrated on real thermal receipts, where legible lines
    // read at 0.5–0.85, so some partially legible synthetic bands (50–89% characters right) are accepted. Severe
    // cases (under half the characters right) must stay rare, and legible text must mostly pass.
    val severe = illegibleAccepted.count { it.accuracy < .5 }
    assertTrue("Severely illegible bands accepted: $severe (see logcat FocusBenchmark)", severe <= 5)
    assertTrue("Legible bands rejected: ${legibleRejected.size} of ${text.count { it.legible == true }}",
      legibleRejected.size <= text.count { it.legible == true } * .15)
  }
}
