package io.github.sandroroque.getnf.sync

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sandroroque.getnf.discounts.PhotoText
import io.github.sandroroque.getnf.discounts.TextLine
import java.io.File
import org.junit.Ignore
import org.junit.Assert.*
import org.junit.Test

/** Independent raster oracle: private region is at y=180..240; OCR misses it. No real personal data. */
class Round2RedactionTest {
  @Ignore("T-8: reproduces the audit's finding; un-ignore with the fix") @Test fun reviewRound2PartialOcrMustNotDeclarePrivateRegionSafe() {
    val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    val file = File(dir, "round2-private-synthetic.png")
    val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val ink = Paint().apply { color = Color.BLACK; textSize = 25f }
    canvas.drawText("ITEM SINTETICO", 20f, 70f, ink)
    canvas.drawText("DADOS PRIVADOS SINTETICOS", 20f, 220f, ink)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    // The OCR found only the item, not the private region. Output must fail closed or cover that region.
    val text = PhotoText(file.name,600,400,listOf(TextLine("ITEM SINTETICO",20,40,300,80,.9f)))
    try {
      val encoded = runCatching { WebpArchiver { SensitiveText.blanks(text) }.archive(file,null) }
      if (encoded.isFailure) return
      val out = BitmapFactory.decodeByteArray(encoded.getOrThrow(),0,encoded.getOrThrow().size)
      try {
        var dark = 0; var count = 0
        for (y in 185..235) for (x in 20..500) { count++;if(Color.red(out.getPixel(x,y)) < 30) dark++ }
        assertTrue("partial OCR must reject export or mask independently known private region; masked fraction=$dark/$count", dark > count*.95)
      } finally { out.recycle() }
    } finally { file.delete() }
  }
}
