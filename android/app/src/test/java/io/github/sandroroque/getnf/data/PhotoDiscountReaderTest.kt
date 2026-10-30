package io.github.sandroroque.getnf.data

import io.github.sandroroque.getnf.discounts.PhotoDiscounts.Verdict
import io.github.sandroroque.getnf.discounts.PhotoText
import io.github.sandroroque.getnf.discounts.TextLine
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Applying discounts read from photos (D54), with a fake OCR, on the synthetic fixture (R$ 0,50 off, 8 lines). */
class PhotoDiscountReaderTest {
  @get:Rule val tmp = TemporaryFolder()
  private val db = testDatabase()
  private val dao = db.catalog()
  private var clock = 1_000L
  private val store by lazy { ReceiptStore(tmp.root, db, now = { clock++ }) }
  private val chave = "42260111222333000181651090000000011123456784"
  private val html = javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText()

  private var ocrCalls = 0
  /** What the fake OCR "reads": line 2 (a POLPA, told apart from the other four by its number) has 0,30 off. */
  private var discountText = "Desconto: 0,30"
  private val reader by lazy {
    PhotoDiscountReader(db, tmp.root) { file ->
      ocrCalls++
      PhotoText(file.name, 3072, 4096, listOf(
        TextLine("02 7896014400287 POLPA BRASFRUT 100G 1 UN 2,98 2,98", 740, 500, 1500, 545, .8f),
        TextLine(discountText, 736, 557, 1100, 602, .8f),
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

  @Test fun foundDiscountsAreAppliedAndTheRestProrated() = runTest {
    val r = receipt()
    val out = reader.read(r)
    assertEquals(Verdict.PARTIAL, out.result.verdict)
    assertTrue(out.applied)
    val lines = dao.lines(chave).associateBy { it.lineNo }
    assertEquals(30L to "photo", lines[2]!!.discountCents to lines[2]!!.discountSource)
    assertEquals(50L, lines.values.sumOf { it.discountCents })
    assertTrue(lines.values.filter { it.lineNo != 2 }.all { it.discountSource == "prorated" })
    // A re-parse keeps what the photo said (ReceiptParser, D49).
    ReceiptParser(db, tmp.root, log = { _, _ -> }).parse(r)
    assertEquals(30L to "photo", dao.lines(chave).single { it.lineNo == 2 }.let { it.discountCents to it.discountSource })
  }

  @Test fun onlyAPartialReadingAsksThePerson() = runTest {
    val r = receipt()
    // R$ 0,30 of R$ 0,50 found: the rest is prorated, and a person has to look (D111).
    reader.read(r)
    assertEquals(30L, db.lists().photoDiscountChecks().first().single().cents)
    // All R$ 0,50 found: the page's total has checked the reading, no card.
    discountText = "Desconto: 0,50"
    File(store.dir(chave), r.photos.single() + PhotoDiscountReader.CACHE_SUFFIX).delete()
    dao.deleteLines(chave)
    ReceiptParser(db, tmp.root, log = { _, _ -> }).parse(r)
    assertEquals(Verdict.COMPLETE, reader.read(r).result.verdict)
    assertTrue(db.lists().photoDiscountChecks().first().isEmpty())
  }

  @Test fun theOcrIsCachedNextToThePhoto() = runTest {
    val r = receipt()
    reader.read(r); reader.read(r)
    assertEquals(1, ocrCalls)
    assertTrue(File(store.dir(chave), r.photos.single() + PhotoDiscountReader.CACHE_SUFFIX).isFile)
  }

  @Test fun aDiscountSetByHandIsKept() = runTest {
    val r = receipt()
    val lines = dao.lines(chave)
    dao.deleteLines(chave)
    dao.insertLines(lines.map { if (it.lineNo == 2) it.copy(discountCents = 5, discountSource = "manual") else it })
    reader.read(r)
    val after = dao.lines(chave).single { it.lineNo == 2 }
    assertEquals(5L to "manual", after.discountCents to after.discountSource)
    assertEquals(50L, dao.lines(chave).sumOf { it.discountCents })
  }

  @Test fun moreThanTheReceiptsDiscountChangesNothing() = runTest {
    val r = receipt()
    val before = dao.lines(chave)
    discountText = "Desconto: 0,90"
    val out = reader.read(r)
    assertEquals(Verdict.CONFLICT, out.result.verdict)
    assertFalse(out.applied)
    assertEquals(before, dao.lines(chave))
  }
}
