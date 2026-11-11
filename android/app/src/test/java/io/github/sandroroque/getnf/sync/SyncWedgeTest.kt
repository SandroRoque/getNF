package io.github.sandroroque.getnf.sync

import io.github.sandroroque.getnf.data.ReceiptStore
import io.github.sandroroque.getnf.data.testDatabase
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    // Supabase Storage answers a missing object with HTTP 400 {"statusCode":"404","error":"not_found"} (checked on the
    // local stack: GET /storage/v1/object/authenticated/receipts/<missing> -> 400).
    override fun download(path: String, target: File) {
      target.writeBytes(objects[path] ?: throw SupabaseException(400, "download $path: 400"))
    }
  }

  private fun product(id: String) = buildJsonObject {
    put("id", id); put("name", "Produto $id"); put("created_at", "2026-11-01T10:00:00+00:00"); put("updated_at", "2026-11-01T10:00:00+00:00")
  }

  private fun member() = buildJsonObject { put("user_id", "me"); put("display_name", "Eu") }

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
