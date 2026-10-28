package io.github.sandroroque.getnf.sync

import io.github.sandroroque.getnf.data.ReceiptParser
import io.github.sandroroque.getnf.data.ReceiptStore
import io.github.sandroroque.getnf.data.testDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The sync against a real local Supabase (`npx supabase start`): the schema, RLS, PostgREST's upserts and filters,
 * Storage. Skipped unless GETNF_SUPABASE_LOCAL is set to the local project's "publishable,secret" keys, as printed by
 * `npx supabase status`. Each run signs up a throwaway user, so runs don't interfere.
 */
class SupabaseLocalTest {
  @get:Rule val tmp = TemporaryFolder()
  private val keys = System.getenv("GETNF_SUPABASE_LOCAL")?.split(",")
  private val url = System.getenv("GETNF_SUPABASE_URL") ?: "http://127.0.0.1:54321"
  private val chave = "42260111222333000181651090000000011123456784"
  private val html = javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText()

  private fun newUser(): Pair<String, String> {
    // A fixed account instead (to look at the result in the app): GETNF_SUPABASE_USER=email,password, created beforehand.
    System.getenv("GETNF_SUPABASE_USER")?.split(",", limit = 2)?.let { return it[0] to it[1] }
    val email = "sync-${UUID.randomUUID()}@example.test"; val password = UUID.randomUUID().toString()
    val body = buildJsonObject { put("email", email); put("password", password); put("email_confirm", true) }
    val request = Request.Builder().url("$url/auth/v1/admin/users").header("apikey", keys!![1])
      .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    OkHttpClient().newCall(request).execute().use { check(it.isSuccessful) { "creating a user: ${it.code} ${it.body.string()}" } }
    return email to password
  }

  private class Phone(root: File) {
    val db = testDatabase()
    val store = ReceiptStore(File(root, "receipts"), db)
    val parser = ReceiptParser(db, File(root, "receipts"), log = { _, _ -> })
    val engine = SyncEngine(db, store, { _, _ -> byteArrayOf(82, 73, 70, 70) }, contribute = { true }, log = {})
  }

  @Test fun aReceiptGoesUpFromOnePhoneAndDownToAnother() = runTest {
    assumeTrue("set GETNF_SUPABASE_LOCAL to run against a local Supabase", keys != null)
    val (email, password) = newUser()
    val client = SupabaseClient(url, keys!![0])
    val session = client.signIn(email, password)
    val ledger = Ledgers.pick(Ledgers.mine(client, session), null, session.userId)!!

    val a = Phone(tmp.newFolder("a"))
    a.store.open(chave, "qr"); a.store.savePage(chave, "https://x", html)
    a.parser.parse(a.store.get(chave)!!)
    a.store.addPhoto(chave, a.store.newPhotoFile(chave).apply { writeBytes(byteArrayOf(1)) })
    val report = a.engine.sync(SupabaseRemote(client, session, ledger))
    assertEquals(emptyList<String>(), report.errors)
    val lines = a.db.catalog().lines(chave)
    val server = client.select(session, "receipt_line", listOf("chave" to "eq.$chave"))
    assertEquals(lines.size, server.size)
    assertEquals(lines.sumOf { it.grossCents }, server.sumOf { it["gross_cents"]!!.jsonPrimitive.content.toLong() })
    assertEquals(1, client.select(session, "receipt_photo", emptyList()).size)
    // Categories came from the server's seed, with its ids.
    assertEquals(client.select(session, "category", listOf("ledger_id" to "eq.$ledger")).map { it["id"]!!.jsonPrimitive.content }.toSet(),
      a.db.catalog().categories().map { it.id }.toSet())

    // A re-read page that lost a line removes it on the server too.
    val last = lines.last()
    a.db.catalog().deleteLines(chave); a.db.catalog().insertLines(lines.dropLast(1).map { it.copy(dirty = true) })
    a.engine.sync(SupabaseRemote(client, session, ledger))
    assertEquals(lines.size - 1, client.select(session, "receipt_line", listOf("chave" to "eq.$chave")).size)

    // A second phone signed in to the same account gets the receipt, its page, and the same catalog.
    val b = Phone(tmp.newFolder("b"))
    b.store.load()
    b.engine.sync(SupabaseRemote(client, client.refresh(session.refreshToken), ledger))
    val adopted = b.store.get(chave)!!
    assertEquals(html, File(b.store.dir(chave), ReceiptStore.PAGE_FILE).readText())
    assertFalse(adopted.needsUpload)
    assertEquals(lines.size - 1, b.db.catalog().lines(chave).size)
    assertEquals(a.db.catalog().products().map { it.id }.toSet(), b.db.catalog().products().map { it.id }.toSet())
    assertEquals(last.lineNo !in b.db.catalog().lines(chave).map { it.lineNo }, true)
  }

  @Test fun twoPeopleInOneCadernetaReadTheSameReceiptOfflineWithoutDuplicates() = runTest {
    assumeTrue("set GETNF_SUPABASE_LOCAL to run against a local Supabase", keys != null)
    val client = SupabaseClient(url, keys!![0])
    val ana = newUser().let { (e, p) -> client.signIn(e, p) }
    val bia = newUser().let { (e, p) -> client.signIn(e, p) }
    // Ana makes "Casa" and invites Bia (D104).
    val casa = client.rpc(ana, "create_ledger", buildJsonObject { put("name", "Casa") }).jsonPrimitive.content
    val code = client.rpc(ana, "create_invite", buildJsonObject { put("lid", casa) }).jsonPrimitive.content
    client.rpc(bia, "join_ledger", buildJsonObject { put("code", code) })
    assertEquals(casa, Ledgers.pick(Ledgers.mine(client, bia), casa, bia.userId))

    // Both scan the same receipt and read its page offline, then sync.
    val phones = listOf(Phone(tmp.newFolder("ana")) to ana, Phone(tmp.newFolder("bia")) to bia)
    for ((p, _) in phones) { p.store.open(chave, "qr"); p.store.savePage(chave, "https://x", html); p.parser.parse(p.store.get(chave)!!) }
    for ((p, s) in phones) assertEquals(emptyList<String>(), p.engine.sync(SupabaseRemote(client, s, casa)).errors)

    val byLedger = listOf("ledger_id" to "eq.$casa")
    assertEquals(phones[0].first.db.catalog().storeItems().size, client.select(ana, "store_item", byLedger).size)
    assertEquals(phones[0].first.db.catalog().products().size, client.select(ana, "product", byLedger).size)
    assertEquals(1, client.select(ana, "receipt", byLedger).size)
    // Each scan is marked, once per person.
    assertEquals(setOf(ana.userId, bia.userId), client.select(bia, "receipt_scan", byLedger).map { it["user_id"]!!.jsonPrimitive.content }.toSet())
  }
}
