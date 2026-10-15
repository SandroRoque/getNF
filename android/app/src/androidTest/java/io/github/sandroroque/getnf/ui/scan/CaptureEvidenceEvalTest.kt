package io.github.sandroroque.getnf.ui.scan

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Evaluates saved capture JPEGs + masks with the D37 pipeline. Skipped unless files were placed in the app's
 * private `files/eval/` (via run-as) on a test device; the repository never holds real captures. Logs only
 * numbers and verdicts (tag CaptureEval), never recognized text.
 */
@RunWith(AndroidJUnit4::class)
class CaptureEvidenceEvalTest {
  @Test fun evaluateSavedCaptures() {
    val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "eval")
    val photos = dir.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().sortedBy { it.name }
    assumeTrue("No captures in ${dir.path}", photos.isNotEmpty())
    DocumentVision.ensureLoaded()
    TextLegibility().use { legibility ->
      for (photo in photos) {
        val maskFile = File(dir, photo.name.removeSuffix(".jpg") + ".mask.png")
        val bitmap = BitmapFactory.decodeFile(photo.path); val maskBitmap = BitmapFactory.decodeFile(maskFile.path)
        val rgba = Mat(); val gray = Mat(); val small = Mat(); val mask = Mat()
        try {
          Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
          Utils.bitmapToMat(maskBitmap, small); Imgproc.cvtColor(small, small, Imgproc.COLOR_RGBA2GRAY)
          Imgproc.resize(small, mask, gray.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
          val quality = DocumentVision.quality(gray, mask)
          val started = System.nanoTime()
          val lines = legibility.read(bitmap, mask)
          val ms = (System.nanoTime() - started) / 1_000_000
          val legible = TextLegibility.legibleBands(quality, lines, emptyList())
          val content = quality.filter { it.hasContent }
          val covered = content.count { q -> legible.any { it.start <= q.band.start + 1e-9 && it.end >= q.band.end - 1e-9 } }
          Log.i("CaptureEval", "%s %dx%d ocrMs=%d lines=%d confident=%d contentBands=%d legibleContentBands=%d prefilterPass=%d medianConf=%.2f".format(
            photo.name.takeLast(12), bitmap.width, bitmap.height, ms, lines.size, lines.count { it.confidence >= TextLegibility.MIN_CONFIDENCE },
            content.size, covered, content.count { it.usable }, lines.map { it.confidence }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }))
          for (q in content) Log.i("CaptureEval", "  %.3f-%.3f score=%.2f prefilter=%s conf=%s".format(q.band.start, q.band.end, q.score, q.usable,
            lines.filter { it.center.y >= q.band.start && it.center.y < q.band.end }.map { "%.2f".format(it.confidence) }))
        } finally { bitmap.recycle(); maskBitmap.recycle(); listOf(rgba, gray, small, mask).forEach { it.release() } }
      }
    }
  }
}
