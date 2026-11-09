package io.github.sandroroque.getnf.sync

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Ignore
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Round 3 (audit): PhotoWithdrawal and LedgerService.deleteMe select the person's photo rows once (PostgREST caps a
 * response at 1,000 rows), delete those objects, then delete or let the server delete ALL their rows. Real local
 * Supabase only (disposable stack); synthetic 1-byte objects.
 */
class Round3WithdrawalTest {
  private val keys = System.getenv("GETNF_SUPABASE_LOCAL")?.split(",")
  private val url = System.getenv("GETNF_SUPABASE_URL") ?: "http://127.0.0.1:54321"
  private val total = 1001

  private fun newUser(): Pair<String, String> {
    val email = "r3w-${UUID.randomUUID()}@example.test"; val password = UUID.randomUUID().toString()
    val body = buildJsonObject { put("email", email); put("password", password); put("email_confirm", true) }
    val request = Request.Builder().url("$url/auth/v1/admin/users").header("apikey", keys!![1])
      .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    OkHttpClient().newCall(request).execute().use { check(it.isSuccessful) { "creating a user: ${it.code}" } }
    return email to password
  }

  private fun seed(client: SupabaseClient, session: SupabaseSession, ledger: String): String {
    val chave = "1".repeat(44)
    client.upsert(session, "receipt", listOf(buildJsonObject { put("ledger_id", ledger); put("chave", chave); put("captured_at", "2026-01-01T00:00:00Z") }), "ledger_id,chave")
    val rows = (0 until total).map { i ->
      buildJsonObject {
        put("ledger_id", ledger); put("chave", chave); put("local_name", "p$i"); put("position", i)
        put("object_path", "$ledger/$chave/p$i.webp")
      }
    }
    rows.chunked(500).forEach { client.upsert(session, "receipt_photo", it, "ledger_id,chave,local_name") }
    for (i in 0 until total) client.upload(session, "$ledger/$chave/p$i.webp", byteArrayOf(1).toRequestBody("application/octet-stream".toMediaType()))
    return chave
  }

  @Ignore("T-11: reproduces the audit's finding; un-ignore with the fix") @Test fun reviewRound3WithdrawingMoreThanAThousandPhotosMustLeaveNoObject() {
    assumeTrue("set GETNF_SUPABASE_LOCAL (disposable stack only)", keys != null)
    val client = SupabaseClient(url, keys!![0])
    val session = newUser().let { (e, p) -> client.signIn(e, p) }
    val ledger = Ledgers.pick(Ledgers.mine(client, session), null, session.userId)!!
    seed(client, session, ledger)
    assertEquals(total, client.listObjects(session, "$ledger/").size)
    val removed = PhotoWithdrawal.withdraw(client, session)
    val rows = client.select(session, "receipt_photo", listOf("created_by" to "eq.${session.userId}")).size
    val objects = client.listObjects(session, "$ledger/").size
    println("R3 withdraw: reported removed=$removed; photo rows left=$rows (a read is capped at 1000, so this may undercount); objects left=$objects")
    assertEquals("every uploaded object must be gone after the withdrawal", 0, objects)
  }

  @Ignore("T-11: reproduces the audit's finding; un-ignore with the fix") @Test fun reviewRound3DeleteMeWithMoreThanAThousandPhotosInASharedCadernetaMustLeaveNoObject() {
    assumeTrue("set GETNF_SUPABASE_LOCAL (disposable stack only)", keys != null)
    val client = SupabaseClient(url, keys!![0])
    val ana = newUser().let { (e, p) -> client.signIn(e, p) }
    val bia = newUser().let { (e, p) -> client.signIn(e, p) }
    val casa = client.rpc(ana, "create_ledger", buildJsonObject { put("name", "Casa") }).jsonPrimitive.content
    client.rpc(bia, "join_ledger", buildJsonObject { put("code", client.rpc(ana, "create_invite", buildJsonObject { put("lid", casa) }).jsonPrimitive.content) })
    seed(client, bia, casa)
    // LedgerService.deleteMe, lines for a caderneta Bia shares with Ana (copied: it needs an Android Context):
    val files = client.select(bia, "receipt_photo", listOf("ledger_id" to "eq.$casa", "created_by" to "eq.${bia.userId}"))
      .flatMap { row -> listOfNotNull(row["object_path"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content, row["mask_path"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content) }
    client.deleteObjects(bia, files)
    client.rpc(bia, "delete_me")
    val left = client.listObjects(ana, "$casa/").size
    println("R3 deleteMe: paths the client knew=${files.size}; objects of the departed person still in the shared caderneta=$left")
    assertEquals("a deleted account must not leave its photos in the shared caderneta", 0, left)
  }
}
