package io.github.sandroroque.getnf.sync

import io.github.sandroroque.getnf.data.CategoryRow
import io.github.sandroroque.getnf.data.ProductRow
import io.github.sandroroque.getnf.data.ReceiptParser
import io.github.sandroroque.getnf.data.ReceiptStore
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

  @Test fun aLineThePageNoLongerHasIsRemovedFromTheServer() = runTest {
    receiptWithPage()
    engine.sync(server)
    val last = dao.lines(chave).last()
    // As if a re-read page lost the last line.
    db.catalog().deleteLines(chave)
    db.catalog().insertLines(server.rows("receipt_line").map { it.str("line_no")!!.toInt() }.filter { it != last.lineNo }
      .map { n -> last.copy(lineNo = n, dirty = true) })
    engine.sync(server)
    assertTrue(server.rows("receipt_line").none { it.str("line_no")!!.toInt() == last.lineNo })
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

    fun put(table: String, key: String, row: JsonObject) {
      // Like the server's defaults: the caderneta, and who wrote a scan mark.
      val owned = JsonObject(row + ("ledger_id" to JsonPrimitive(ledgerId)) +
        (if (table == "receipt_scan" && "user_id" !in row) mapOf("user_id" to JsonPrimitive(userId)) else emptyMap()))
      val k = key.split(",").joinToString("|") { owned.str(it)!! }
      val old = tables.getOrPut(table) { mutableMapOf() }[k]
      clock = clock.plusMillis(1)
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
      pushed += rows
      rows.forEach { put(table, onConflict, it) }
    }

    override fun delete(table: String, filters: List<Pair<String, String>>) {
      val chave = filters.single { it.first == "chave" }.second.removePrefix("eq.")
      val keep = filters.single { it.first == "line_no" }.second.removePrefix("not.in.(").removeSuffix(")").split(",").filter { it.isNotEmpty() }
      tables[table]?.entries?.removeIf { (_, r) -> r.str("chave") == chave && r.str("line_no") !in keep }
    }

    override fun select(table: String, filters: List<Pair<String, String>>): List<JsonObject> {
      val since = filters.firstOrNull { it.first == "updated_at" }?.second?.removePrefix("gt.")?.let(Instant::parse)
      val limit = filters.firstOrNull { it.first == "limit" }?.second?.toInt() ?: Int.MAX_VALUE
      return rows(table).filter { since == null || java.time.OffsetDateTime.parse(it.str("updated_at")).toInstant() > since }
        .sortedBy { it.str("updated_at") }.take(limit)
    }

    override fun upload(path: String, bytes: ByteArray, type: String) { objects[path] = bytes }
    override fun download(path: String, target: File) = target.writeBytes(objects.getValue(path))
  }
}
