package io.github.sandroroque.getnf.sync

import io.github.sandroroque.getnf.data.CategoryRow
import io.github.sandroroque.getnf.data.ProductRow
import io.github.sandroroque.getnf.data.ReceiptLineRow
import io.github.sandroroque.getnf.data.ReceiptParser
import io.github.sandroroque.getnf.data.ReceiptStore
import io.github.sandroroque.getnf.data.SyncBaseRow
import io.github.sandroroque.getnf.data.syncJson
import io.github.sandroroque.getnf.data.syncKey
import io.github.sandroroque.getnf.data.testDatabase
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Ignore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The sync's rules (D70) against an in-memory stand-in for the Supabase Data API: upserts merge by key, every write
 * stamps updated_at, selects filter by it. Postgres-side rules (RLS, the user-choice trigger) are covered by
 * supabase/tests; here it's what the phone sends and how it applies what comes back.
 */
class SyncEngineTest {
  @get:Rule val tmp = TemporaryFolder()
  private val db = testDatabase()
  private val dao = db.catalog()
  private var ids = 0
  private val store by lazy { ReceiptStore(File(tmp.root, "receipts"), db) }
  private val parser by lazy { ReceiptParser(db, File(tmp.root, "receipts"), now = { 7 }, log = { _, _ -> }) }
  private val archived = mutableListOf<String>()
  private val engine by lazy { SyncEngine(db, store, { photo, _ -> archived += photo.name; byteArrayOf(1, 2, 3) }, contribute = { true }, log = {}) }
  private val server = FakeServer()

  private val chave = "42260111222333000181651090000000011123456784"
  private val html = javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText()

  private suspend fun receiptWithPage() {
    store.open(chave, "qr")
    store.savePage(chave, "https://x", html)
    parser.parse(store.get(chave)!!)
  }

  @Test fun theFirstSyncSendsEverythingAndAdoptsTheServersCategoryIds() = runTest {
    // As after MIGRATION_3_4: the phone knew "food.produce.fruit" under its own id, with the apple in it.
    dao.insertCategory(CategoryRow("old-food", "food", "Alimentos", null, 1))
    dao.insertCategory(CategoryRow("old-fruit", "food.produce.fruit", "Frutas", "old-food", 1))
    receiptWithPage()
    val apple = dao.storeItems().single { it.code == "243" }
    dao.updateProduct(dao.products().single { it.id == apple.productId }.copy(categoryId = "old-fruit", categorySource = "import",
      categoryReview = "automatic", dirty = true))
    store.addPhoto(chave, store.newPhotoFile(chave).apply { writeBytes(byteArrayOf(9)) })

    val report = engine.sync(server)

    assertTrue(report.errors.toString(), report.errors.isEmpty())
    // The phone now uses the server's ids, and the old ones are gone.
    val fruit = dao.categories().single { it.seedPath == "food.produce.fruit" }
    assertEquals(server.id("food.produce.fruit"), fruit.id)
    assertEquals(server.id("food.produce"), fruit.parentId)
    assertTrue(dao.categories().none { it.id.startsWith("old-") })
    assertEquals(fruit.id, dao.products().single { it.id == apple.productId }.categoryId)
    // The server has the receipt with its header, its page, every line, the store items, the products and the photo.
    val receipt = server.rows("receipt").single()
    assertEquals("11222333000181", receipt.str("issuer_cnpj"))
    assertEquals("ledger-1/$chave/page.html", receipt.str("page_path"))
    assertTrue(server.objects.containsKey("ledger-1/$chave/page.html"))
    assertEquals(dao.lines(chave).size, server.rows("receipt_line").size)
    assertEquals(dao.storeItems().size, server.rows("store_item").size)
    // Each store item sent is a job for the categorizer: the worker comes back for its answers (D121).
    assertEquals(dao.storeItems().size, report.storeItems)
    assertTrue(db.sync().awaitingCategory() > 0)
    assertEquals(fruit.id, server.rows("product").single { it.str("id") == apple.productId }.str("category_id"))
    assertEquals(1, server.rows("receipt_photo").size)
    assertTrue(server.objects.keys.any { it.endsWith(".webp") })
    // And nothing is left to push.
    val sync = db.sync()
    assertTrue(sync.dirtyLines().isEmpty() && sync.dirtyProducts().isEmpty() && sync.dirtyStoreItems().isEmpty() && sync.dirtyParsed().isEmpty())
    assertFalse(store.get(chave)!!.needsUpload)

    // A second run sends nothing new.
    val writes = server.writes
    assertEquals(0, engine.sync(server).storeItems)
    assertEquals(writes, server.writes)
  }

  @Test fun aPullNeverOverwritesALocalChangeNotYetPushed() = runTest {
    receiptWithPage()
    engine.sync(server)
    val product = dao.products().first()
    // The server changes the product's name while the phone changed it too, and the phone's push fails this time.
    server.patch("product", product.id) { put("name", "server name") }
    dao.updateProduct(product.copy(name = "phone name", dirty = true))
    server.failUpserts = true
    runCatching { engine.sync(server) }
    assertEquals("phone name", dao.products().single { it.id == product.id }.name)
    // Next time the phone's value goes up, and stays.
    server.failUpserts = false
    engine.sync(server)
    assertEquals("phone name", server.rows("product").single { it.str("id") == product.id }.str("name"))
    assertEquals("phone name", dao.products().single { it.id == product.id }.name)
  }

  @Test fun aChangeMadeOnTheServerReachesThePhone() = runTest {
    receiptWithPage()
    engine.sync(server)
    val product = dao.products().first()
    val veg = server.id("food.produce.vegetables")
    server.patch("product", product.id) { put("category_id", veg); put("category_source", "jev"); put("category_review", "automatic") }
    engine.sync(server)
    val now = dao.products().single { it.id == product.id }
    assertEquals(veg, now.categoryId)
    assertEquals("jev", now.categorySource)
    assertFalse(now.dirty)

    // A suggestion (4b) comes down too, and the phone never sends it back.
    val other = dao.products().first { it.id != product.id }
    server.patch("product", other.id) { put("suggestion_category_id", veg); put("suggestion_confidence", .4) }
    engine.sync(server)
    assertEquals(veg, dao.products().single { it.id == other.id }.suggestionCategoryId)
    dao.updateProduct(dao.products().single { it.id == other.id }.copy(name = "x", dirty = true))
    engine.sync(server)
    assertTrue(server.pushed.none { "suggestion_category_id" in it })

    // How it's sold (D110): Jev's unsure answer comes down and never goes back; the user's answer goes up.
    val item = dao.storeItems().first()
    server.patch("store_item", item.id) { put("sold_by_suggestion", "unit"); put("sold_by_suggestion_confidence", .6) }
    engine.sync(server)
    assertEquals("unit", dao.storeItems().single { it.id == item.id }.soldBySuggestion)
    dao.updateStoreItem(dao.storeItems().single { it.id == item.id }.copy(soldByUser = "weight", dirty = true))
    engine.sync(server)
    assertTrue(server.pushed.any { it["sold_by_user"].toString() == "\"weight\"" })
    assertTrue(server.pushed.none { "sold_by_suggestion" in it })
  }

  @Test fun treeEditsGoUpWithTheirLogOnce() = runTest {
    receiptWithPage()
    engine.sync(server)
    val editor = io.github.sandroroque.getnf.data.CatalogEditor(db)
    val dried = editor.createCategory(server.id("food.produce"), "Frutas secas", "Dried fruit, nuts")
    editor.splitCategory(server.id("food.produce.fruit"), listOf("Frescas", "Secas"))
    engine.sync(server)
    assertEquals("Dried fruit, nuts", server.rows("category").single { it.str("id") == dried }.str("description"))
    assertEquals(2, server.rows("category_event").size)
    assertEquals(setOf("create", "split"), server.rows("category_event").map { it.str("op") }.toSet())
    assertTrue(db.categoryEvents().dirty().isEmpty())
    val writes = server.writes
    engine.sync(server)
    assertEquals(writes, server.writes)
  }

  /** The fixture's page without its last item, as a re-read that lost a line. */
  private val htmlWithoutLastLine = Regex("""<tr id="Item \+ 8".*?</tr>""", RegexOption.DOT_MATCHES_ALL).replace(html, "")

  @Test fun aLineThePageNoLongerHasIsRemovedFromTheServer() = runTest {
    receiptWithPage()
    engine.sync(server)
    store.savePage(chave, "https://x", htmlWithoutLastLine)
    parser.parse(store.get(chave)!!)
    engine.sync(server)
    assertEquals((1..7).toSet(), server.rows("receipt_line").map { it.str("line_no")!!.toInt() }.toSet())
    assertTrue(db.sync().removedLines().isEmpty())
  }

  // ---- T-1, T-2, T-3 (D164) ----

  private fun issuer(i: Int) = buildJsonObject { put("cnpj", "%014d".format(i)); put("name", "Loja $i") }

  @Test fun aPullGetsEveryRowHoweverManyShareAMinuteOrATimestamp() = runTest {
    // 2,501 rows written within a second, then 1,500 more stamped with one instant: pages split both runs.
    repeat(2501) { server.put("issuer", "ledger_id,cnpj", issuer(it)) }
    server.tick = 0
    repeat(1500) { server.put("issuer", "ledger_id,cnpj", issuer(10_000 + it)) }
    engine.sync(server)
    assertEquals(4001, dao.issuers().size)
    // A second run repeats the last minute (harmless) and neither loops nor loses anything.
    engine.sync(server)
    assertEquals(4001, dao.issuers().size)
    // One more row in the same instant still arrives.
    server.put("issuer", "ledger_id,cnpj", issuer(20_000))
    engine.sync(server)
    assertEquals(4002, dao.issuers().size)
  }

  /** Both phones synced with the receipt; returns the id of a product both hold. */
  private suspend fun twoPhonesWithTheReceipt(a: Phone, b: Phone): String {
    a.store.open(chave, "qr"); a.store.savePage(chave, "https://x", html); a.parser.parse(a.store.get(chave)!!)
    a.engine.sync(server)
    b.engine.sync(server)
    return b.db.catalog().products().first().id.also { id -> assertTrue(a.db.catalog().products().any { it.id == id }) }
  }

  @Test fun editsOfDifferentFieldsOnTwoPhonesBothStay() = runTest {
    for (aFirst in listOf(true, false)) {
      server.tables.clear()
      val a = Phone("a$aFirst"); val b = Phone("b$aFirst")
      val id = twoPhonesWithTheReceipt(a, b)
      // Offline, A names the product and B gives it a size.
      val ca = a.db.catalog(); val cb = b.db.catalog()
      ca.updateProduct(ca.products().single { it.id == id }.copy(name = "Nome de A", dirty = true))
      cb.updateProduct(cb.products().single { it.id == id }.copy(sizeE6 = 500_000, sizeUnit = "kg", sizeSource = "manual", dirty = true))
      val order = if (aFirst) listOf(a, b, a) else listOf(b, a, b)
      for (p in order) p.engine.sync(server)
      for (p in listOf(a, b)) {
        val product = p.db.catalog().products().single { it.id == id }
        assertEquals("aFirst=$aFirst", "Nome de A", product.name)
        assertEquals("aFirst=$aFirst", 500_000L, product.sizeE6)
        assertFalse(product.dirty)
      }
      val online = server.rows("product").single { it.str("id") == id }
      assertEquals("Nome de A", online.str("name"))
      assertEquals("500000", online.str("size_e6"))
    }
  }

  @Test fun theSameFieldEditedOnTwoPhonesEndsWithTheLastToSync() = runTest {
    val a = Phone("a"); val b = Phone("b")
    val id = twoPhonesWithTheReceipt(a, b)
    a.db.catalog().updateProduct(a.db.catalog().products().single { it.id == id }.copy(name = "de A", dirty = true))
    b.db.catalog().updateProduct(b.db.catalog().products().single { it.id == id }.copy(name = "de B", dirty = true))
    a.engine.sync(server); b.engine.sync(server); a.engine.sync(server)
    for (p in listOf(a, b)) assertEquals("de B", p.db.catalog().products().single { it.id == id }.name)
    assertEquals("de B", server.rows("product").single { it.str("id") == id }.str("name"))
  }

  @Test fun aPushSendsOnlyTheFieldsThePhoneChanged() = runTest {
    receiptWithPage()
    engine.sync(server)
    val product = dao.products().first()
    server.pushed.clear()
    dao.updateProduct(product.copy(name = "novo", dirty = true))
    engine.sync(server)
    assertEquals(setOf("id", "name", "ledger_id"), server.pushed.single { it.str("id") == product.id }.keys)
    // Changed back to what the server has, it needs no request at all.
    server.pushed.clear()
    dao.updateProduct(dao.products().single { it.id == product.id }.copy(name = "novo", dirty = true))
    engine.sync(server)
    assertTrue(server.pushed.none { it.str("id") == product.id })
    assertFalse(dao.products().single { it.id == product.id }.dirty)
  }

  @Test fun aLineRemovedOnOnePhoneGoesFromTheOther() = runTest {
    val a = Phone("a"); val b = Phone("b")
    twoPhonesWithTheReceipt(a, b)
    assertEquals(8, b.db.catalog().lines(chave).size)
    // B is offline for a while: more than the pull's minute of overlap passes on the server.
    repeat(70_000) { server.put("issuer", "ledger_id,cnpj", issuer(1)) }
    a.store.savePage(chave, "https://x", htmlWithoutLastLine); a.parser.parse(a.store.get(chave)!!)
    a.engine.sync(server)
    b.engine.sync(server)
    assertEquals((1..7).toList(), b.db.catalog().lines(chave).map { it.lineNo })
    // Sending again changes nothing.
    val writes = server.writes
    a.engine.sync(server); b.engine.sync(server)
    assertEquals(writes, server.writes)
    assertEquals(7, server.rows("receipt_line").size)
  }

  @Test fun aLineCorrectedBeforeItsRemovalArrivedComesBackEverywhere() = runTest {
    val a = Phone("a"); val b = Phone("b")
    twoPhonesWithTheReceipt(a, b)
    // B corrects the last line's discount by hand while A's re-read drops that line.
    val last = b.db.catalog().lines(chave).last()
    b.db.catalog().deleteLines(chave)
    b.db.catalog().insertLines(a.db.catalog().lines(chave).map { l ->
      if (l.lineNo == last.lineNo) l.copy(discountCents = 20, discountSource = "manual", discountReview = "confirmed", dirty = true) else l
    })
    a.store.savePage(chave, "https://x", htmlWithoutLastLine); a.parser.parse(a.store.get(chave)!!)
    a.engine.sync(server)
    b.engine.sync(server)
    a.engine.sync(server)
    // The person's correction is kept, and both phones and the server agree.
    for (p in listOf(a, b)) assertEquals(8, p.db.catalog().lines(chave).size)
    assertEquals(20L, a.db.catalog().lines(chave).single { it.lineNo == last.lineNo }.discountCents)
    assertEquals(8, server.rows("receipt_line").size)
  }


  /**
   * Review 2026-11-11, L1 (D182). A phone that re-reads its pages (an app update with a newer parser, or Room 14 → 15's
   * ReparseSurcharged) before it has pulled another phone's correction must not write its stale copy over it.
   */
  @Test fun aStalePhonesReReadMustNotUndoAnotherPhonesLineCorrection() = runTest {
    val a = Phone("a"); val b = Phone("b")
    twoPhonesWithTheReceipt(a, b)
    staleReRead(corrector = a, rereader = b)
  }

  /** The mirror: the phone that scanned the receipt is the stale one. */
  @Test fun theScanningPhonesStaleReReadKeepsTheOthersCorrectionToo() = runTest {
    val a = Phone("a"); val b = Phone("b")
    twoPhonesWithTheReceipt(a, b)
    staleReRead(corrector = b, rereader = a)
  }

  /** The re-read does change a line (its parser is newer): that field goes up, and the correction still stays. */
  @Test fun aReReadThatChangesALineSendsOnlyWhatItChanged() = runTest {
    val a = Phone("a"); val b = Phone("b")
    twoPhonesWithTheReceipt(a, b)
    staleReRead(corrector = a, rereader = b) { line ->
      // What an older parser had read and the server held when B last synced: another surcharge share.
      val old = line.copy(surchargeCents = line.surchargeCents + 999)
      val rest = b.db.catalog().lines(chave).filter { it.lineNo != old.lineNo }
      b.db.catalog().deleteLines(chave); b.db.catalog().insertLines(rest + old)
      b.db.sync().putBase(SyncBaseRow("receipt_line", old.syncKey, old.syncJson().toString()))
      server.put("receipt_line", "ledger_id,chave,line_no", buildJsonObject { put("chave", chave); put("line_no", old.lineNo); put("surcharge_cents", old.surchargeCents) })
      line.surchargeCents
    }
  }

  /**
   * Both phones hold the receipt ([twoPhonesWithTheReceipt]); [corrector] then corrects one line by hand and syncs while
   * [rereader] stays offline. [olderParser], given that line on [rereader], may make it look as an older parser read
   * it and return what the re-read will put back; null when the re-read changes nothing. Then [rereader] re-reads
   * and syncs, and the correction must stand everywhere.
   */
  private suspend fun staleReRead(corrector: Phone, rereader: Phone, olderParser: (suspend (ReceiptLineRow) -> Long)? = null) {
    // Each reads the adopted page itself, as the worker does after a sync, and everything settles.
    for (p in listOf(corrector, rereader)) p.parser.parse(p.store.get(chave)!!)
    repeat(2) { for (p in listOf(corrector, rereader)) p.engine.sync(server) }
    val first = rereader.db.catalog().lines(chave).first()
    val surcharge = olderParser?.invoke(first)
    // The corrector says by hand what the line's product is and what its discount was, and syncs. The other hasn't.
    val cc = corrector.db.catalog()
    val other = cc.products().first { p -> cc.storeItems().none { it.id == first.storeItemId && it.productId == p.id } }.id
    val corrected = cc.lines(chave).map { l ->
      if (l.lineNo == first.lineNo) l.copy(productOverrideId = other, discountSource = "manual", discountReview = "confirmed", dirty = true) else l
    }
    cc.deleteLines(chave); cc.insertLines(corrected)
    corrector.engine.sync(server)
    assertEquals(other, server.rows("receipt_line").single { it.str("line_no") == "${first.lineNo}" }.str("product_override_id"))
    // The other phone's app updates to a newer parser: at start it reads its saved pages again, then syncs.
    rereader.parser.parse(rereader.store.get(chave)!!)
    val report = rereader.engine.sync(server)
    assertTrue(report.errors.toString(), report.errors.isEmpty())
    corrector.engine.sync(server)
    val online = server.rows("receipt_line").single { it.str("line_no") == "${first.lineNo}" }
    assertEquals("the server still has the corrected product for the line", other, online.str("product_override_id"))
    assertEquals("the server still has the discount as typed", "manual", online.str("discount_source"))
    for (p in listOf(corrector, rereader)) {
      val line = p.db.catalog().lines(chave).single { it.lineNo == first.lineNo }
      assertEquals("both phones end with the correction", other, line.productOverrideId)
      assertEquals("manual", line.discountSource)
      if (surcharge != null) assertEquals("the newer reading went up", surcharge, line.surchargeCents)
    }
    if (surcharge != null) assertEquals("$surcharge", online.str("surcharge_cents"))
  }

  @Test fun aReceiptOnlyTheServerHasIsAdoptedWithItsPage() = runTest {
    server.objects["ledger-1/$chave/page.html"] = html.toByteArray()
    server.put("receipt", "ledger_id,chave", buildJsonObject {
      put("chave", chave); put("qr_url", "qr"); put("captured_at", "2026-10-01T10:00:00+00:00")
      put("page_path", "ledger-1/$chave/page.html"); put("issuer_cnpj", "11222333000181"); put("paid_cents", 3064)
    })
    engine.sync(server)
    val r = store.get(chave)!!
    assertEquals(ReceiptStore.PAGE_FILE, r.pageFile)
    assertEquals(html, File(store.dir(chave), ReceiptStore.PAGE_FILE).readText())
    assertFalse(r.needsUpload)
    // The phone reads the page itself next (SyncWorker runs the parser after a sync).
    assertTrue(parser.needsParse(r))
    assertNull(store.get(chave)!!.uploadError)
  }

  /** Another phone in the same caderneta: its own database and files, the same server. */
  private inner class Phone(name: String) {
    val db = testDatabase()
    val store = ReceiptStore(File(tmp.root, name), db)
    val parser = ReceiptParser(db, File(tmp.root, name), now = { 7 }, log = { _, _ -> })
    val engine = SyncEngine(db, store, { _, _ -> byteArrayOf(1) }, log = {})
  }

  @Test fun twoPhonesReadingTheSameItemsOfflineMakeTheSameRows() = runTest {
    // Two members scan the same receipt, both offline; each phone reads the page and makes its catalog (D104).
    val a = Phone("a"); val b = Phone("b")
    for (p in listOf(a, b)) {
      p.store.open(chave, "qr"); p.store.savePage(chave, "https://x", html); p.parser.parse(p.store.get(chave)!!)
    }
    val reportA = a.engine.sync(server)
    val reportB = b.engine.sync(server)
    assertTrue(reportA.errors.toString(), reportA.errors.isEmpty())
    assertTrue(reportB.errors.toString(), reportB.errors.isEmpty())
    // One store item and one product per code, not two; both phones hold the same ids.
    assertEquals(a.db.catalog().storeItems().size, server.rows("store_item").size)
    assertEquals(a.db.catalog().products().size, server.rows("product").size)
    assertEquals(a.db.catalog().storeItems().map { it.id }.toSet(), b.db.catalog().storeItems().map { it.id }.toSet())
    assertEquals(1, server.rows("receipt").size)
  }

  @Test fun eachScanLeavesItsMarkButAnAdoptedReceiptDoesnt() = runTest {
    receiptWithPage()
    engine.sync(server)
    assertEquals(chave, server.rows("receipt_scan").single().str("chave"))
    // A phone that only pulled the receipt doesn't claim to have scanned it.
    val other = Phone("other")
    other.engine.sync(server)
    assertFalse(other.store.get(chave)!!.scannedHere)
    assertEquals(1, server.pushed.count { it.containsKey("scanned_at") })
    // Scanning it there too makes it that phone's scan as well.
    other.store.open(chave, "qr")
    assertTrue(other.store.get(chave)!!.scannedHere)
    assertTrue(other.store.get(chave)!!.needsUpload)
  }

  @Test fun aRefusedRowIsParkedAndNotSentAgainUntilItChanges() = runTest {
    receiptWithPage()
    engine.sync(server)
    val (bad, good) = dao.products().take(2)
    dao.updateProduct(bad.copy(name = "bad name", dirty = true))
    dao.updateProduct(good.copy(name = "good name", dirty = true))
    server.refuse["product"] = "bad name"

    val report = engine.sync(server)
    // The good one went up anyway, and the sync carried on.
    assertEquals("good name", server.rows("product").single { it.str("id") == good.id }.str("name"))
    assertEquals(1, report.errors.size)
    assertEquals(bad.id, db.sync().parked().single().rowKey)
    // Not sent again while it's unchanged.
    val pushed = server.pushed.size
    engine.sync(server)
    assertEquals(pushed, server.pushed.size)
    // Changed, it's tried again; accepted, it's no longer parked.
    server.refuse.clear()
    dao.updateProduct(dao.products().single { it.id == bad.id }.copy(name = "fixed name", dirty = true))
    engine.sync(server)
    assertEquals("fixed name", server.rows("product").single { it.str("id") == bad.id }.str("name"))
    assertTrue(db.sync().parked().isEmpty())
  }

  @Test fun aPageAnotherMemberFetchedIsAdoptedInsteadOfFetched() = runTest {
    // This phone scanned the receipt and is waiting for its page; another member fetches it (D104).
    store.open(chave, "qr")
    engine.sync(server)
    server.objects["ledger-1/$chave/page.html"] = html.toByteArray()
    server.put("receipt", "ledger_id,chave", JsonObject(server.rows("receipt").single() + buildJsonObject {
      put("page_path", "ledger-1/$chave/page.html"); put("page_url", "https://x"); put("page_fetched_at", "2026-10-02T10:00:00+00:00")
    }))
    engine.sync(server)
    val r = store.get(chave)!!
    assertEquals(ReceiptStore.PAGE_FILE, r.pageFile)
    assertEquals(html, File(store.dir(chave), ReceiptStore.PAGE_FILE).readText())
    // Adopting it isn't a change of this phone's: nothing to send back.
    assertFalse(r.needsUpload)
    assertTrue(parser.needsParse(r))
  }

  @Test fun aReceiptOnlyAnotherMemberScannedSaysWho() = runTest {
    // Bia scanned a receipt in the caderneta; this phone (Ana's) only pulls it (D104).
    server.objects["ledger-1/$chave/page.html"] = html.toByteArray()
    server.put("receipt", "ledger_id,chave", buildJsonObject {
      put("chave", chave); put("qr_url", "qr"); put("captured_at", "2026-10-01T10:00:00+00:00"); put("page_path", "ledger-1/$chave/page.html")
    })
    server.put("receipt_scan", "ledger_id,chave,user_id", buildJsonObject { put("chave", chave); put("user_id", "bia"); put("scanned_at", "2026-10-01T10:00:00+00:00") })
    for ((id, name) in listOf("me" to "Ana", "bia" to "Bia")) server.put("ledger_member", "ledger_id,user_id", buildJsonObject {
      put("user_id", id); put("display_name", name); put("role", "member")
    })
    engine.sync(server)
    val lists = db.lists()
    assertEquals("Bia", lists.scannedBy(chave).first())
    assertEquals("Bia", lists.receipts().first().single().scannedBy)

    // Once Bia leaves, her mark goes with her.
    server.tables.getValue("ledger_member").entries.removeIf { it.value.str("user_id") == "bia" }
    engine.sync(server)
    assertNull(lists.scannedBy(chave).first())

    // A receipt Ana scanned too is hers: no mark.
    server.put("ledger_member", "ledger_id,user_id", buildJsonObject { put("user_id", "bia"); put("display_name", "Bia"); put("role", "member") })
    engine.sync(server)
    store.open(chave, "qr")
    assertNull(lists.scannedBy(chave).first())
  }

  @Test fun reviewRound2RetryAfterEveryRemoteBoundaryPreservesReceipt() = runTest {
    suspend fun attempt(cut: Int): Int {
      val phone = Phone("fault-$cut")
      val remote = FakeServer()
      phone.store.open(chave, "qr")
      phone.store.savePage(chave, "https://example.invalid", html)
      phone.parser.parse(phone.store.get(chave)!!)
      val expected = phone.db.catalog().lines(chave).sumOf { it.grossCents - it.discountCents }
      var calls = 0
      fun boundary() { if (++calls == cut) throw java.io.IOException("synthetic interruption $cut") }
      val failing = object : SyncRemote by remote {
        override fun rpc(function: String, args: JsonObject) = remote.rpc(function, args).also { boundary() }
        override fun upsert(table: String, rows: List<JsonObject>, onConflict: String, ignoreDuplicates: Boolean) {
          remote.upsert(table, rows, onConflict, ignoreDuplicates); boundary()
        }
        override fun delete(table: String, filters: List<Pair<String,String>>) { remote.delete(table, filters); boundary() }
        override fun select(table: String, filters: List<Pair<String,String>>) = remote.select(table, filters).also { boundary() }
        override fun upload(path: String, bytes: ByteArray, type: String) { remote.upload(path, bytes, type); boundary() }
        override fun download(path: String, target: File) { remote.download(path, target); boundary() }
      }
      try {
        val error = runCatching { phone.engine.sync(failing) }.exceptionOrNull()
        if (cut > 0) assertTrue("cut $cut reached", error is java.io.IOException)
        phone.engine.sync(remote)
        phone.engine.sync(remote)
        assertEquals("one receipt after cut $cut", 1, remote.rows("receipt").size)
        assertEquals("all lines after cut $cut", phone.db.catalog().lines(chave).size, remote.rows("receipt_line").size)
        assertEquals(expected, remote.rows("receipt_line").sumOf { it.str("gross_cents")!!.toLong() - it.str("discount_cents")!!.toLong() })
        assertFalse("pending upload after cut $cut", phone.store.get(chave)!!.needsUpload)
      } finally { phone.db.close() }
      return calls
    }
    val boundaries = attempt(0)
    assertTrue(boundaries > 10)
    for (cut in 1..boundaries) attempt(cut)
    println("ROUND2: retried after each of $boundaries remote boundaries; receipt and cents conserved")
  }

  @Ignore("T-16: reproduces the audit's finding; un-ignore with the fix") @Test fun reviewRound2RestoredCleanDatabaseMustUploadToNewLedger() = runTest {
    receiptWithPage()
    engine.sync(server)
    assertFalse(store.get(chave)!!.needsUpload)
    // BackupService copies the DB verbatim; restoring preserves these clean flags and cursors.
    // Reopening that restored state against an empty destination must not silently skip its receipt.
    val destination = FakeServer()
    val remote = object : SyncRemote by destination { override val ledgerId = "ledger-2" }
    val restoredEngine = SyncEngine(db, store, { _, _ -> byteArrayOf() }, log = {})
    repeat(2) { restoredEngine.sync(remote) }
    assertEquals("restored receipt must exist in destination", 1, destination.rows("receipt").size)
  }

  @Test fun reviewInterruptedReconciliationMustRetryAfterCursorAdvanced() = runTest {
    receiptWithPage()
    engine.sync(server)
    server.delete("receipt_line", listOf("chave" to "eq.$chave", "line_no" to "eq.8"))
    server.put("receipt", "ledger_id,chave", server.rows("receipt").single())
    // Another receipt changes two minutes later, advancing both durable cursors past this removal.
    val other = "0".repeat(44)
    server.tick = 120_000
    server.put("receipt", "ledger_id,chave", JsonObject(server.rows("receipt").single() +
      buildJsonObject { put("chave", other); put("page_path", kotlinx.serialization.json.JsonNull) }))
    server.put("receipt_line", "ledger_id,chave,line_no", JsonObject(server.rows("receipt_line").first() +
      buildJsonObject { put("chave", other) }))
    val interrupted = object : SyncRemote by server {
      override fun select(table: String, filters: List<Pair<String, String>>): List<JsonObject> {
        if (filters.any { it.first == "select" && it.second == "chave,line_no" })
          throw java.io.IOException("review: network lost during reconciliation")
        return server.select(table, filters)
      }
    }
    assertTrue(runCatching { engine.sync(interrupted) }.isFailure)
    engine.sync(server)
    assertEquals("deleted line must disappear after retry", (1..7).toList(), dao.lines(chave).map { it.lineNo })
  }

  private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content

  /** The Data API for the caderneta "ledger-1": rows keyed like the real tables, updated_at stamped on every write. */
  private class FakeServer : SyncRemote {
    override val ledgerId = "ledger-1"
    override val userId = "me"
    val tables = mutableMapOf<String, MutableMap<String, JsonObject>>()
    val objects = mutableMapOf<String, ByteArray>()
    var writes = 0
    /** Every row the phone sent, as it sent it. */
    val pushed = mutableListOf<JsonObject>()
    var failUpserts = false
    private var clock = Instant.parse("2026-10-25T12:00:00Z")
    /** How far the clock moves per write; 0 stamps rows with one timestamp, as a bulk write in one instant could. */
    var tick = 1L
    private val seed = listOf("food" to null, "food.produce" to "food", "food.produce.fruit" to "food.produce", "food.produce.vegetables" to "food.produce")
    /** Requests the server refuses (a 4xx), by table: a row whose JSON contains this text. */
    val refuse = mutableMapOf<String, String>()

    fun id(path: String) = "srv-$path"
    private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content
    fun rows(table: String) = tables[table].orEmpty().values.toList()

    override fun rpc(function: String, args: JsonObject): kotlinx.serialization.json.JsonElement {
      if (rows("category").isNotEmpty()) return kotlinx.serialization.json.JsonNull
      for ((i, node) in seed.withIndex()) put("category", "ledger_id,id", buildJsonObject {
        put("id", id(node.first)); put("seed_path", node.first); put("label", node.first); put("parent_id", node.second?.let(::id))
        put("sort", i); put("user_modified", false); put("deleted", false)
      })
      return kotlinx.serialization.json.JsonNull
    }

    // Like the server's defaults: the caderneta, and who wrote a scan mark.
    private fun owned(table: String, row: JsonObject) = JsonObject(row + ("ledger_id" to JsonPrimitive(ledgerId)) +
      (if (table == "receipt_scan" && "user_id" !in row) mapOf("user_id" to JsonPrimitive(userId)) else emptyMap()))

    /** NOT NULL columns with no default, as in the migrations. */
    private val required = mapOf("receipt_line" to listOf("chave", "line_no", "description", "code", "qty_e4", "unit_price_e4", "gross_cents", "store_item_id"))

    fun has(table: String, key: String, row: JsonObject): Boolean {
      val owned = owned(table, row)
      return tables[table]?.containsKey(key.split(",").joinToString("|") { owned.str(it)!! }) == true
    }

    fun put(table: String, key: String, row: JsonObject) {
      val owned = owned(table, row)
      val k = key.split(",").joinToString("|") { owned.str(it)!! }
      val old = tables.getOrPut(table) { mutableMapOf() }[k]
      clock = clock.plusMillis(tick)
      tables.getValue(table)[k] = JsonObject((old ?: emptyMap()) + owned + ("updated_at" to JsonPrimitive(clock.toString().replace("Z", "+00:00"))))
      writes++
    }

    fun patch(table: String, id: String, change: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
      val row = rows(table).single { it.str("id") == id }
      put(table, "ledger_id,id", JsonObject(row + buildJsonObject(change)))
    }

    override fun upsert(table: String, rows: List<JsonObject>, onConflict: String, ignoreDuplicates: Boolean) {
      if (failUpserts) throw java.io.IOException("offline")
      refuse[table]?.let { bad -> if (rows.any { bad in it.toString() }) throw SupabaseException(400, "refused") }
      // Postgres checks NOT NULL on the row an upsert proposes, before it finds the conflict.
      if (!ignoreDuplicates) required[table]?.let { columns ->
        rows.firstOrNull { row -> columns.any { it !in row } }?.let { throw SupabaseException(400, "23502 not null: $it") }
      }
      pushed += rows
      // ignore-duplicates: a row already there is left as it is.
      rows.forEach { if (!ignoreDuplicates || !has(table, onConflict, it)) put(table, onConflict, it) }
    }

    override fun delete(table: String, filters: List<Pair<String, String>>) {
      tables[table]?.values?.removeIf { r -> filters.all { (k, v) -> k == "ledger_id" || matches(r, "$k.$v") } }
    }

    /** The PostgREST filters the phone uses: eq, gt, in, not.in, and and/or trees of them; ordered by typed values. */
    override fun select(table: String, filters: List<Pair<String, String>>): List<JsonObject> {
      val order = filters.firstOrNull { it.first == "order" }?.second?.split(",")?.map { it.removeSuffix(".asc") } ?: listOf("updated_at")
      val limit = filters.firstOrNull { it.first == "limit" }?.second?.toInt() ?: Int.MAX_VALUE
      val columns = filters.firstOrNull { it.first == "select" }?.second?.split(",")
      return rows(table)
        .filter { r -> filters.all { (k, v) -> k in setOf("select", "order", "limit", "ledger_id") || matches(r, if (k == "or") "or$v" else "$k.$v") } }
        .sortedWith { a, b -> order.firstNotNullOfOrNull { c -> compare(a.str(c), b.str(c)).takeIf { it != 0 } } ?: 0 }
        .take(limit)
        .map { r -> if (columns == null) r else JsonObject(r.filterKeys { it in columns }) }
    }

    private fun compare(a: String?, b: String?): Int {
      if (a == null || b == null) return compareValues(a, b)
      a.toLongOrNull()?.let { x -> b.toLongOrNull()?.let { y -> return x.compareTo(y) } }
      val instants = runCatching { java.time.OffsetDateTime.parse(a).toInstant().compareTo(java.time.OffsetDateTime.parse(b).toInstant()) }
      return instants.getOrElse { a.compareTo(b) }
    }

    private fun matches(r: JsonObject, expr: String): Boolean {
      for (logic in listOf("or", "and")) if (expr.startsWith("$logic(")) {
        val parts = split(expr.removePrefix(logic).removePrefix("(").removeSuffix(")"))
        return if (logic == "or") parts.any { matches(r, it) } else parts.all { matches(r, it) }
      }
      val column = expr.substringBefore('.')
      var rest = expr.substringAfter('.')
      val negated = rest.startsWith("not.").also { if (it) rest = rest.removePrefix("not.") }
      val op = rest.substringBefore('.')
      val value = rest.substringAfter('.')
      val have = r.str(column)
      val result = when (op) {
        "eq" -> compare(have, unquote(value)) == 0
        "gt" -> have != null && compare(have, unquote(value)) > 0
        "in" -> have in split(value.removePrefix("(").removeSuffix(")")).map(::unquote)
        else -> error("filter $op")
      }
      return result != negated
    }

    /** Splits on the commas outside parentheses and quotes. */
    private fun split(s: String): List<String> {
      val out = mutableListOf<String>(); val cur = StringBuilder(); var depth = 0; var quoted = false; var escaped = false
      for (c in s) {
        when {
          escaped -> escaped = false
          c == '\\' -> escaped = true
          c == '"' -> quoted = !quoted
          !quoted && c == '(' -> depth++
          !quoted && c == ')' -> depth--
          !quoted && depth == 0 && c == ',' -> { out += cur.toString(); cur.clear(); continue }
        }
        cur.append(c)
      }
      if (cur.isNotEmpty()) out += cur.toString()
      return out
    }

    private fun unquote(v: String) = if (v.startsWith('"')) v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\") else v

    override fun upload(path: String, bytes: ByteArray, type: String) { objects[path] = bytes }
    override fun download(path: String, target: File) = target.writeBytes(objects.getValue(path))
  }
}
