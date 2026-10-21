package io.github.sandroroque.getnf.data

import io.github.sandroroque.getnf.parse.NfcePage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Parsing saved pages on the phone (D49), and how that sits with the server copy (D46), on the synthetic fixture. */
class ReceiptParserTest {
  @get:Rule val tmp = TemporaryFolder()
  private val db = testDatabase()
  private val dao = db.catalog()
  private var ids = 0
  private val newId = { "id${ids++}" }
  private var clock = 1_000L
  // A clock that moves, so fetching a page again changes its pageFetchedAt.
  private val store by lazy { ReceiptStore(tmp.root, db, now = { clock++ }) }
  private val parser by lazy { ReceiptParser(db, tmp.root, now = { 7 }, newId = newId, log = { _, _ -> }) }
  private val mirror by lazy { ServerMirror(db, now = { 7 }, newId = newId) }

  // The fixture's own chave, so the page matches its receipt.
  private val chave = "42260111222333000181651090000000011123456784"
  private val cnpj = "11222333000181"
  private val html = javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText()

  private suspend fun saved(page: String = html): Receipt {
    store.open(chave, "qr")
    store.savePage(chave, "https://x", page)
    return store.get(chave)!!
  }

  @Test fun aSavedPageBecomesHeaderLinesAndStoreItems() = runTest {
    val r = saved()
    assertTrue(parser.needsParse(r))
    assertEquals(ReceiptParser.Outcome.Parsed(8, emptyList()), parser.parse(r))
    assertFalse(parser.needsParse(r))

    val p = dao.parsed(chave)!!
    assertEquals(listOf(cnpj, 3114L, 50L, 3064L), listOf(p.issuerCnpj, p.grossCents, p.discountCents, p.paidCents))
    assertEquals(1768482000000, p.issuedAt)
    assertEquals(ReceiptParsedRow.SOURCE_PHONE, p.source)
    assertEquals(r.pageFetchedAt, p.pageFetchedAt)
    assertNull(p.parseError)
    assertEquals("SUPERMERCADO EXEMPLO LTDA", dao.issuers().single().name)

    val lines = dao.lines(chave)
    assertEquals(8, lines.size)
    assertEquals(50L, lines.sumOf { it.discountCents })
    val apple = lines.first()
    assertEquals(listOf<Any?>("MACA NAC KG", "243", 6400L, "KG", 87800L, 562L), listOf(apple.description, apple.code, apple.qtyE4, apple.unit, apple.unitPriceE4, apple.grossCents))
    val items = dao.storeItems().associateBy { it.code }
    assertEquals(Triple("store", cnpj, null), Triple(items["243"]!!.codeKind, items["243"]!!.storeCnpj, items["243"]!!.parsedSizeE6))
    assertEquals(Triple("gtin", "", 100_000L), Triple(items["7896014400287"]!!.codeKind, items["7896014400287"]!!.storeCnpj, items["7896014400287"]!!.parsedSizeE6))
    // Every new code gets its own uncategorized product.
    assertEquals(dao.storeItems().size, dao.products().size)
    assertTrue(dao.products().all { it.categoryId == null })
  }

  @Test fun aKnownCodeKeepsItsProductAndCategory() = runTest {
    val r = saved()
    // The server already knows the apple, categorized.
    mirror.apply(ServerSnapshot(
      listOf(ServerCategory("sc1", "food", "Comida", 1, null)), emptyList(),
      listOf(ServerProduct("sp1", "store:$cnpj:243", "MACA NAC KG", "sc1", emptyList(), null, null, null)), emptyList(), emptyList(),
    ))
    val appleProduct = dao.storeItems().single().productId
    parser.parse(r)
    val apple = dao.storeItems().single { it.code == "243" }
    assertEquals(appleProduct, apple.productId)
    assertEquals("food", dao.categories().single { it.id == dao.products().single { p -> p.id == appleProduct }.categoryId }.seedPath)
  }

  @Test fun photoAndManualDiscountsSurviveAReparse() = runTest {
    val r = saved()
    parser.parse(r)
    val lines = dao.lines(chave)
    dao.deleteLines(chave)
    dao.insertLines(lines.map {
      when (it.lineNo) { 1 -> it.copy(discountCents = 20, discountSource = "photo", discountReview = "confirmed"); 2 -> it.copy(discountCents = 0, discountSource = "manual"); else -> it }
    })
    // The page is fetched again.
    store.savePage(chave, "https://x", html)
    val again = store.get(chave)!!
    assertTrue(parser.needsParse(again))
    parser.parse(again)
    val after = dao.lines(chave).associateBy { it.lineNo }
    assertEquals(Triple(20L, "photo", "confirmed"), Triple(after[1]!!.discountCents, after[1]!!.discountSource, after[1]!!.discountReview))
    assertEquals(0L to "manual", after[2]!!.discountCents to after[2]!!.discountSource)
    // The other 30 cents are prorated over the remaining lines.
    assertEquals(50L, after.values.sumOf { it.discountCents })
    assertTrue(after.values.filter { it.lineNo > 2 }.all { it.discountSource == "prorated" })
  }

  @Test fun anUnreadablePageKeepsTheReceiptAndItsLines() = runTest {
    parser.parse(saved())
    store.savePage(chave, "https://x", "<html><div class='cf-turnstile'></div></html>")
    val r = store.get(chave)!!
    val outcome = parser.parse(r)
    assertTrue(outcome is ReceiptParser.Outcome.Failed)
    assertEquals(8, dao.lines(chave).size)
    val p = dao.parsed(chave)!!
    assertTrue(p.parseError!!.contains("no items"))
    assertEquals(3064L, p.paidCents)
    // Recorded as tried with this parser on this page: not retried on every start.
    assertFalse(parser.needsParse(r))
    assertEquals(store.receipts.value.map { it.chave }, listOf(chave))
  }

  @Test fun aPageOfAnotherReceiptIsRefused() = runTest {
    val other = "42260611222333000181651090000012341123456789"
    store.open(other, "qr"); store.savePage(other, "https://x", html)
    assertTrue(parser.parse(store.get(other)!!) is ReceiptParser.Outcome.Failed)
    assertTrue(dao.lines(other).isEmpty())
  }

  @Test fun warningsUseTheServersWording() = runTest {
    parser.parse(saved(html.replace("<span class=\"valor\">5,62</span>", "<span class=\"valor\">5,63</span>")))
    assertEquals("warnings: items sum to 31.15, page total is 31.14", dao.parsed(chave)!!.parseError)
    // Warnings still give lines.
    assertEquals(8, dao.lines(chave).size)
  }

  @Test fun theMirrorLeavesPhoneParsedReceiptsAloneButStillCategorizes() = runTest {
    parser.parse(saved())
    val before = dao.lines(chave)
    val storeItems = dao.storeItems().size
    // The server parsed the same receipt differently (say, an older parser) and categorized the apple.
    mirror.apply(ServerSnapshot(
      listOf(ServerCategory("sc1", "food", "Comida", 1, null)), emptyList(),
      listOf(ServerProduct("sp1", "store:$cnpj:243", "MACA NAC KG", "sc1", emptyList(), null, null, null)),
      listOf(ServerReceipt("sr1", chave, "qr", 1, null, null, "page.html", emptyList(), "X", cnpj, 1, 1.0, 0.0, 1.0, 1, "")),
      listOf(ServerItem("sr1", 1, "sp1", "MACA NAC KG", "243", 1.0, "KG", 1.0, 1.0, 0.0, "prorated")),
    ))
    assertEquals(before, dao.lines(chave))
    assertEquals(3064L, dao.parsed(chave)!!.paidCents)
    // Store items only the phone knows stay, since its lines use them; the apple now has the server's category.
    assertEquals(storeItems, dao.storeItems().size)
    val apple = dao.storeItems().single { it.code == "243" }
    assertEquals("food", dao.categories().single { it.id == dao.products().single { p -> p.id == apple.productId }.categoryId }.seedPath)

    // A refresh where the server doesn't have the receipt at all doesn't drop the phone's parse either.
    mirror.apply(ServerSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
    assertEquals(before, dao.lines(chave))
  }

  @Test fun anOlderParserVersionOrAServerParseIsParsedAgain() = runTest {
    val r = saved()
    parser.parse(r)
    dao.putParsed(dao.parsed(chave)!!.copy(parserVersion = NfcePage.VERSION - 1))
    assertTrue(parser.needsParse(r))
    dao.putParsed(dao.parsed(chave)!!.copy(parserVersion = NfcePage.VERSION, source = ReceiptParsedRow.SOURCE_SERVER))
    assertTrue(parser.needsParse(r))
    assertEquals(1, parser.parsePending(store.receipts.value))
    assertEquals(0, parser.parsePending(store.receipts.value))
    // No page, nothing to parse.
    store.open("42260611222333000181651090000099991123456780", "qr")
    assertEquals(0, parser.parsePending(store.receipts.value))
  }

  @Test fun aCancelledParseIsNotRecordedAsAnError() = runTest {
    val r = saved()
    // As on the phone: the upload worker is replaced while the page is being read on the IO dispatcher.
    val job = launch(start = CoroutineStart.UNDISPATCHED) { parser.parse(r) }
    job.cancel()
    job.join()
    assertTrue(job.isCancelled)
    assertNull(dao.parsed(chave))
    assertTrue(parser.needsParse(r))
  }

  @Test fun startupAndTheWorkerAtOnceParseEachPageOnce() = runTest {
    saved()
    val logged = mutableListOf<String>()
    val p = ReceiptParser(db, tmp.root, newId = newId, log = { m, _ -> logged += m })
    val counts = List(3) { async { p.parsePending(store.receipts.value) } }.awaitAll()
    assertEquals(1, counts.sum())
    assertEquals(1, logged.count { it.startsWith("parsed") })
  }

  @Test fun aSizeRemovedByHandOnTheServerBeatsTheDescription() = runTest {
    parser.parse(saved())
    // POLPA BRASFRUT 100G reads as 100 g; on the server someone removed its size by hand (manual, no quantity).
    mirror.apply(ServerSnapshot(emptyList(), emptyList(),
      listOf(ServerProduct("sp1", "gtin:7896014400287", "POLPA BRASFRUT 100G", null, emptyList(), null, null, "manual")), emptyList(), emptyList()))
    val item = dao.storeItems().single { it.code == "7896014400287" }
    assertEquals(100_000L, item.parsedSizeE6)
    val product = dao.products().single { it.id == item.productId }
    assertEquals(Triple<Long?, String?, String?>(null, null, "manual"), Triple(product.sizeE6, product.sizeUnit, product.sizeSource))
  }
}
