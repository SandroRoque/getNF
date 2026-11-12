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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Round 3 (audit): a restored backup is the old database with its sync marks, syncing as another account into another
 * caderneta. The phone object is reused after its first sync, which is exactly the state BackupService restores (R7).
 * Real local Supabase only (the disposable stack); skipped without GETNF_SUPABASE_LOCAL. Synthetic fixture.
 */
class Round3RestoreTest {
  @get:Rule val tmp = TemporaryFolder()
  private val keys = System.getenv("GETNF_SUPABASE_LOCAL")?.split(",")
  private val url = System.getenv("GETNF_SUPABASE_URL") ?: "http://127.0.0.1:54321"
  private val chave = "42260111222333000181651090000000011123456784"
  private val html = javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText()

  private fun newUser(): Pair<String, String> {
    val email = "r3-${UUID.randomUUID()}@example.test"; val password = UUID.randomUUID().toString()
    val body = buildJsonObject { put("email", email); put("password", password); put("email_confirm", true) }
    val request = Request.Builder().url("$url/auth/v1/admin/users").header("apikey", keys!![1])
      .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    OkHttpClient().newCall(request).execute().use { check(it.isSuccessful) { "creating a user: ${it.code}" } }
    return email to password
  }

  private class Phone(root: File) {
    val db = testDatabase()
    val store = ReceiptStore(File(root, "receipts"), db)
    val parser = ReceiptParser(db, File(root, "receipts"), log = { _, _ -> })
    val engine = SyncEngine(db, store, { _, _ -> byteArrayOf(82, 73, 70, 70) }, contribute = { true }, log = {})
  }

  /** T-16 (D193): against the local stack, a database of A's caderneta sends nothing to B's, then or after an edit. */
  @Test fun reviewRound3RestoredDatabaseMustReachTheCadernetaOfTheNewAccount() = runTest {
    assumeTrue("set GETNF_SUPABASE_LOCAL (disposable stack only)", keys != null)
    val client = SupabaseClient(url, keys!![0])
    val a = newUser().let { (e, p) -> client.signIn(e, p) }
    val b = newUser().let { (e, p) -> client.signIn(e, p) }
    val la = Ledgers.pick(Ledgers.mine(client, a), null, a.userId)!!
    val lb = Ledgers.pick(Ledgers.mine(client, b), null, b.userId)!!
    val phone = Phone(tmp.newFolder("p"))
    phone.store.open(chave, "qr"); phone.store.savePage(chave, "https://x", html); phone.parser.parse(phone.store.get(chave)!!)
    phone.store.addPhoto(chave, phone.store.newPhotoFile(chave).apply { writeBytes(byteArrayOf(1)) })
    assertEquals(emptyList<String>(), phone.engine.sync(SupabaseRemote(client, a, la)).errors)
    val lines = phone.db.catalog().lines(chave)
    println("R3 setup: caderneta A holds ${client.select(a, "receipt_line", listOf("ledger_id" to "eq.$la")).size} lines; local ${lines.size}")

    // --- the restored state syncing as B into B's empty caderneta
    val remoteB = SupabaseRemote(client, b, lb)
    val first = phone.engine.sync(remoteB); val second = phone.engine.sync(remoteB)
    val receiptsInB = client.select(b, "receipt", listOf("ledger_id" to "eq.$lb")).size
    println("R3 restored->B: errors=${first.errors + second.errors} pushed=${first.pushed}/${second.pushed} photos=${first.photos}/${second.photos} receiptsInB=$receiptsInB")

    // --- the person edits what they see after restoring (a product's name) and syncs again
    val product = phone.db.catalog().products().first()
    phone.db.catalog().updateProduct(product.copy(name = "Editado depois de restaurar", dirty = true))
    val third = phone.engine.sync(remoteB)
    val productsInB = client.select(b, "product", listOf("ledger_id" to "eq.$lb"))
    val parked = phone.db.sync().parked()
    println("R3 edit after restore: errors=${third.errors} pushed=${third.pushed} productsInB=${productsInB.size} " +
      "editedInB=${productsInB.any { it["name"]?.jsonPrimitive?.content == "Editado depois de restaurar" }} parkedRows=${parked.size} " +
      "parkedTables=${parked.map { it.tableName }.distinct()} stillDirty=${phone.db.sync().dirtyProducts().size}")
    println("R3 row now in B: ${productsInB.map { r -> r.filterKeys { it in setOf("name", "category_id", "category_source", "deleted") } }}; " +
      "categories in B=${client.select(b, "category", listOf("ledger_id" to "eq.$lb")).size}, store items in B=${client.select(b, "store_item", listOf("ledger_id" to "eq.$lb")).size}, " +
      "local products=${phone.db.catalog().products().size}")
    println("R3 server A untouched: lines=${client.select(a, "receipt_line", listOf("ledger_id" to "eq.$la")).size}")

    assertTrue("another caderneta's database is reported, not synced", first.foreign && second.foreign && third.foreign)
    assertEquals("nothing of it reaches B's caderneta", 0, receiptsInB)
    assertEquals("not even an edit made after restoring (no half catalog)", 0, productsInB.size)
    assertTrue(parked.isEmpty())
  }
}
