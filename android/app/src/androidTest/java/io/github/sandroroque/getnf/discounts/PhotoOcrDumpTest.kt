package io.github.sandroroque.getnf.discounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes what PhotoOcr reads from real photos, so the discount reading (D51) can be developed and checked on the
 * JVM. Local only: copy receipt directories (`<chave>/<photo>.jpg`) into the app's files and pass
 * `-e ocrDir <path>`; each photo gets a `<photo>.ocr.json` next to it.
 */
@RunWith(AndroidJUnit4::class)
class PhotoOcrDumpTest {
  @Test fun dump() {
    val dir = InstrumentationRegistry.getArguments().getString("ocrDir")?.let(::File)
    assumeTrue("ocrDir not given", dir != null)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val json = Json { prettyPrint = false }
    var n = 0
    PhotoOcr(context).use { ocr ->
      for (photo in dir!!.walkTopDown().filter { it.extension == "jpg" && !it.name.contains("mask") }) {
        val started = System.nanoTime()
        val text = ocr.read(photo)
        File(photo.path + ".ocr.json").writeText(json.encodeToString(text))
        android.util.Log.i("PhotoOcr", "${photo.parentFile!!.name.takeLast(6)}/${photo.name}: ${text.lines.size} lines, ${(System.nanoTime() - started) / 1_000_000} ms")
        n++
      }
    }
    android.util.Log.i("PhotoOcr", "$n photos")
  }
}
