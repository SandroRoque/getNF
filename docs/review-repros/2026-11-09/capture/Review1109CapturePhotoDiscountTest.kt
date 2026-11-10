package io.github.sandroroque.getnf.data

import io.github.sandroroque.getnf.discounts.PhotoText
import io.github.sandroroque.getnf.discounts.TextLine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Review 2026-11-09 (capture): photo discounts written next to a hand-typed one. Synthetic fixture (R$ 0,50 off,
 * 8 lines), fake OCR, same setup as PhotoDiscountReaderTest.
 */
class Review1109CapturePhotoDiscountTest {
  @get:Rule val tmp = TemporaryFolder()
  private val db = testDatabase()
  private val dao = db.catalog()
  private var clock = 1_000L
  private val store by lazy { ReceiptStore(tmp.root, db, now = { clock++ }) }
  private val chave = "42260111222333000181651090000000011123456784"
  private val html = javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText()

  /** The fake OCR reads line 2 (a POLPA) with R$ 0,30 off. */
  private val reader by lazy {
    PhotoDiscountReader(db, tmp.root) { file ->
      PhotoText(file.name, 3072, 4096, listOf(
        TextLine("02 7896014400287 POLPA BRASFRUT 100G 1 UN 2,98 2,98", 740, 500, 1500, 545, .8f),
        TextLine("Desconto: 0,30", 736, 557, 1100, 602, .8f),
      ))
    }
  }

  private suspend fun receipt(): Receipt {
    store.open(chave, "qr")
    store.savePage(chave, "https://x", html)
    store.addPhoto(chave, store.newPhotoFile(chave).apply { writeBytes(byteArrayOf(1)) })
    ReceiptParser(db, tmp.root, log = { _, _ -> }).parse(store.get(chave)!!)
    return store.get(chave)!!
  }

  /**
   * A hand-typed R$ 0,40 on line 1 (within the receipt's R$ 0,50, so D174's room check lets it through), then
   * "Descontos: ler das fotos" (ReceiptScreen). PhotoDiscounts checks the photo readings alone (0,30 <= 0,50) and
   * calls it PARTIAL; write() keeps the manual 0,40 and adds the photo's 0,30: the lines then carry 0,70 of discount
   * on a receipt that gave 0,50, and spending is R$ 0,20 too low.
   */
  @Test fun photoPlusManualNeverExceedsTheReceiptsDiscount() = runTest {
    val r = receipt()
    val lines = dao.lines(chave)
    dao.deleteLines(chave)
    dao.insertLines(lines.map { if (it.lineNo == 1) it.copy(discountCents = 40, discountSource = "manual", discountReview = "confirmed") else it })
    val out = reader.read(r)
    val after = dao.lines(chave)
    val sum = after.sumOf { it.discountCents }
    assertTrue(
      "expected the lines' discounts to add up to at most the receipt's R$ 0,50 (or nothing applied); got $sum cents " +
        "(verdict ${out.result.verdict}, applied ${out.applied}; " +
        after.filter { it.discountCents != 0L }.joinToString { "line ${it.lineNo}: ${it.discountCents} ${it.discountSource}" } + ")",
      sum <= 50,
    )
  }
}
