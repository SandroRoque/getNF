package io.github.sandroroque.getnf.sync

import io.github.sandroroque.getnf.data.CategoryRow
import io.github.sandroroque.getnf.data.ProductRow
import io.github.sandroroque.getnf.data.ReceiptParser
import io.github.sandroroque.getnf.data.ReceiptStore
import io.github.sandroroque.getnf.data.testDatabase
import java.io.File
import java.time.Instant
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
  private val parser by lazy { ReceiptParser(db, File(tmp.root, "receipts"), now = { 7 }, newId = { "local${ids++}" }, log = { _, _ -> }) }
  private val archived = mutableListOf<String>()
  private val engine by lazy { SyncEngine(db, store, { photo, _ -> archived += photo.name; byteArrayOf(1, 2, 3) }, log = {}) }
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
    assertEquals("user-1/$chave/page.html", receipt.str("page_path"))
    assertTrue(server.objects.containsKey("user-1/$chave/page.html"))
    assertEquals(dao.lines(chave).size, server.rows("receipt_line").size)
    assertEquals(dao.storeItems().size, server.rows("store_item").size)
    assertEquals(fruit.id, server.rows("product").single { it.str("id") == apple.productId }.str("category_id"))
    assertEquals(1, server.rows("receipt_photo").size)
    assertTrue(server.objects.keys.any { it.endsWith(".webp") })
    // And nothing is left to push.
    val sync = db.sync()
    assertTrue(sync.dirtyLines().isEmpty() && sync.dirtyProducts().isEmpty() && sync.dirtyStoreItems().isEmpty() && sync.dirtyParsed().isEmpty())
    assertFalse(store.get(chave)!!.needsUpload)

    // A second run sends nothing new.
    val writes = server.writes
    engine.sync(server)
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
    server.objects["user-1/$chave/page.html"] = html.toByteArray()
    server.put("receipt", "user_id,chave", buildJsonObject {
      put("chave", chave); put("qr_url", "qr"); put("captured_at", "2026-10-01T10:00:00+00:00")
      put("page_path", "user-1/$chave/page.html"); put("issuer_cnpj", "11222333000181"); put("paid_cents", 3064)
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

  private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content

  /** The Data API as the user "user-1": rows keyed like the real tables, updated_at stamped on every write. */
  private class FakeServer : SyncRemote {
    override val userId = "user-1"
    val tables = mutableMapOf<String, MutableMap<String, JsonObject>>()
    val objects = mutableMapOf<String, ByteArray>()
    var writes = 0
    /** Every row the phone sent, as it sent it. */
    val pushed = mutableListOf<JsonObject>()
    var failUpserts = false
    private var clock = Instant.parse("2026-10-25T12:00:00Z")
    private val seed = listOf("food" to null, "food.produce" to "food", "food.produce.fruit" to "food.produce", "food.produce.vegetables" to "food.produce")
    private val keys = mapOf("receipt" to "user_id,chave", "receipt_line" to "user_id,chave,line_no", "issuer" to "user_id,cnpj",
      "product_tag" to "user_id,product_id,tag_id", "receipt_photo" to "user_id,chave,local_name")

    fun id(path: String) = "srv-$path"
    private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content
    fun rows(table: String) = tables[table].orEmpty().values.toList()

    override fun rpc(function: String) {
      if (rows("category").isNotEmpty()) return
      for ((i, node) in seed.withIndex()) put("category", "id", buildJsonObject {
        put("id", id(node.first)); put("seed_path", node.first); put("label", node.first); put("parent_id", node.second?.let(::id))
        put("sort", i); put("user_modified", false); put("deleted", false)
      })
    }

    fun put(table: String, key: String, row: JsonObject) {
      val withUser = JsonObject(row + ("user_id" to JsonPrimitive(userId)))
      val k = key.split(",").joinToString("|") { withUser.str(it)!! }
      val old = tables.getOrPut(table) { mutableMapOf() }[k]
      clock = clock.plusMillis(1)
      tables.getValue(table)[k] = JsonObject((old ?: emptyMap()) + withUser + ("updated_at" to JsonPrimitive(clock.toString().replace("Z", "+00:00"))))
      writes++
    }

    fun patch(table: String, id: String, change: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
      val row = rows(table).single { it.str("id") == id }
      put(table, "id", JsonObject(row + buildJsonObject(change)))
    }

    override fun upsert(table: String, rows: List<JsonObject>, onConflict: String) {
      if (failUpserts) throw java.io.IOException("offline")
      pushed += rows
      rows.forEach { put(table, keys[table] ?: onConflict, it) }
    }

    override fun delete(table: String, filters: List<Pair<String, String>>) {
      val chave = filters.single { it.first == "chave" }.second.removePrefix("eq.")
      val keep = filters.single { it.first == "line_no" }.second.removePrefix("not.in.(").removeSuffix(")").split(",").filter { it.isNotEmpty() }
      tables[table]?.entries?.removeIf { (_, r) -> r.str("chave") == chave && r.str("line_no") !in keep }
    }

    override fun select(table: String, filters: List<Pair<String, String>>): List<JsonObject> {
      val since = filters.firstOrNull { it.first == "updated_at" }?.second?.removePrefix("gt.")?.let(Instant::parse)
      val limit = filters.single { it.first == "limit" }.second.toInt()
      return rows(table).filter { since == null || java.time.OffsetDateTime.parse(it.str("updated_at")).toInstant() > since }
        .sortedBy { it.str("updated_at") }.take(limit)
    }

    override fun upload(path: String, bytes: ByteArray, type: String) { objects[path] = bytes }
    override fun download(path: String, target: File) = target.writeBytes(objects.getValue(path))
  }
}
