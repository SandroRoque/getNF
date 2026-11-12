package io.github.sandroroque.getnf.sync

import io.github.sandroroque.getnf.data.CategoryEventRow
import io.github.sandroroque.getnf.data.ReceiptStore
import io.github.sandroroque.getnf.data.editsTree
import io.github.sandroroque.getnf.data.testDatabase
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * One refusal that escapes SyncEngine.sync fails the whole run at the same step on every later run, so nothing is
 * pulled again (the 2026-11-09 audit, D177). Each case here must leave the run going.
 */
class SyncWedgeTest {
  @get:Rule val tmp = TemporaryFolder()
  private val db = testDatabase()
  private val store by lazy { ReceiptStore(File(tmp.root, "receipts"), db) }
  private val engine by lazy { SyncEngine(db, store, { _, _ -> byteArrayOf(1) }, log = {}) }

  /** Just enough of the Data API: every select returns the table's rows (one page), upserts are recorded. */
  private class MiniServer : SyncRemote {
    override val ledgerId = "ledger-1"
    override val userId = "me"
    val tables = mutableMapOf<String, MutableList<JsonObject>>()
    val refuseUpsert = mutableMapOf<String, Int>()
    val objects = mutableMapOf<String, ByteArray>()
    override fun rpc(function: String, args: JsonObject): JsonElement = JsonNull
    override fun upsert(table: String, rows: List<JsonObject>, onConflict: String, ignoreDuplicates: Boolean) {
      refuseUpsert[table]?.let { throw SupabaseException(it, "POST /rest/v1/$table: $it new row violates row-level security policy") }
      tables.getOrPut(table) { mutableListOf() } += rows
    }
    override fun delete(table: String, filters: List<Pair<String, String>>) {}
    override fun select(table: String, filters: List<Pair<String, String>>): List<JsonObject> =
      if (filters.any { it.first == "or" || it.first == "select" }) emptyList() else tables[table].orEmpty()
    override fun upload(path: String, bytes: ByteArray, type: String) { objects[path] = bytes }
    override fun deleteObjects(paths: List<String>) { paths.forEach(objects::remove) }
    // Supabase Storage answers a missing object with HTTP 400 {"statusCode":"404","error":"not_found"} (checked on the
    // local stack: GET /storage/v1/object/authenticated/receipts/<missing> -> 400).
    override fun download(path: String, target: File) {
      target.writeBytes(objects[path] ?: throw SupabaseException(400, "download $path: 400"))
    }
  }

  private fun product(id: String) = buildJsonObject {
    put("id", id); put("name", "Produto $id"); put("created_at", "2026-11-01T10:00:00+00:00"); put("updated_at", "2026-11-01T10:00:00+00:00")
  }

  private fun member() = buildJsonObject { put("user_id", "me"); put("display_name", "Eu"); put("role", "member") }

  @Test fun aPlainMembersTreeEditMustNotStopEverySync() = runTest {
    // A member (not a manager, D104) renamed a category on the phone: the editor doesn't check the role.
    db.categoryEvents().insert(CategoryEventRow("ev-1", "rename", "cat-1", oldLabel = "a", newLabel = "b", at = 1))
    val server = MiniServer().apply {
      refuseUpsert["category_event"] = 403 // managers_log: RLS 42501, which PostgREST answers 403
      tables["product"] = mutableListOf(product("p-from-another-phone"))
      tables["ledger_member"] = mutableListOf(member())
    }
    for (run in 1..2) {
      try { engine.sync(server) } catch (e: SupabaseException) {
        fail("run $run: one refused tree event must be parked like a refused row, not fail the whole sync (T-29); " +
          "it threw ${e.status}")
      }
    }
    assertEquals("Produto p-from-another-phone", db.sync().product("p-from-another-phone")?.name)
    // It shows in "Não sincronizado", and the phone now knows the person is a plain member: no more tree edits.
    assertEquals(listOf("ev-1"), db.sync().parked("category_event").map { it.rowKey })
    assertEquals("member", db.sync().myRole().first())
    assertFalse(editsTree(db.sync().myRole().first()))
    // "Tentar de novo" (unparkAll) sends it again; once the server takes it, it's no longer dirty.
    db.sync().unparkAll()
    server.refuseUpsert.remove("category_event")
    engine.sync(server)
    assertEquals(emptyList<CategoryEventRow>(), db.categoryEvents().dirty())
    assertEquals(1, server.tables["category_event"]?.size)
  }

  @Test fun editsTreeFollowsTheRole() {
    assertTrue(editsTree(null)); assertTrue(editsTree("owner")); assertTrue(editsTree("manager")); assertFalse(editsTree("member"))
  }

  @Test fun aReceiptWhosePageObjectIsMissingMustNotStopEverySync() = runTest {
    val chave = "42000000000000000000650000000000011000000000"
    val server = MiniServer().apply {
      // A receipt row whose page_path names an object Storage doesn't have.
      tables["receipt"] = mutableListOf(buildJsonObject {
        put("chave", chave); put("captured_at", "2026-11-01T10:00:00+00:00"); put("page_path", "ledger-1/$chave/page.html")
        put("updated_at", "2026-11-01T10:00:00+00:00")
      })
      tables["product"] = mutableListOf(product("p-1"))
      tables["ledger_member"] = mutableListOf(member())
    }
    val failures = mutableListOf<Int>()
    for (run in 1..2) {
      try { engine.sync(server) } catch (e: SupabaseException) { failures += e.status }
    }
    assertEquals("a receipt whose page can't be downloaded must not fail every sync (T-28); statuses thrown per run: " +
      "$failures", emptyList<Int>(), failures)
    assertEquals("Produto p-1", db.sync().product("p-1")?.name)
    // The receipt came in without its page, waiting for it; once Storage has the object, the next run fetches it.
    assertNull(store.get(chave)!!.pageFile)
    server.objects["ledger-1/$chave/page.html"] = "<html/>".toByteArray()
    engine.sync(server)
    assertEquals(ReceiptStore.PAGE_FILE, store.get(chave)!!.pageFile)
    assertEquals("<html/>", File(store.dir(chave), ReceiptStore.PAGE_FILE).readText())
  }
}
