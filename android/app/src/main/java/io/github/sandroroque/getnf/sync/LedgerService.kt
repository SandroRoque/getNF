package io.github.sandroroque.getnf.sync

import io.github.sandroroque.getnf.data.AccountSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A caderneta as Ajustes shows it (D104). */
data class LedgerInfo(val id: String, val name: String, val role: String, val members: Int)

data class Member(val userId: String, val name: String, val role: String, val you: Boolean)

/**
 * The cadernetas the signed-in person belongs to, and what they can do with them (D104): the database functions of
 * supabase/migrations/20261027000000_ledgers.sql, plus the files they leave for the phone to delete. Network calls;
 * each refreshes the session (and keeps the new refresh token).
 */
class LedgerService(private val settings: AccountSettings) {
  private fun client() = settings.project().let { SupabaseClient(it.url, it.key) }

  private suspend fun <T> signedIn(block: (SupabaseClient, SupabaseSession) -> T): T = withContext(Dispatchers.IO) {
    val account = checkNotNull(settings.account()) { "sem conta" }
    val client = client()
    val session = client.refresh(account.refreshToken).also(settings::signedIn)
    block(client, session)
  }

  suspend fun mine(): List<LedgerInfo> = signedIn { c, s ->
    val roles = Ledgers.mine(c, s).associate { it.id to it.role }
    if (roles.isEmpty()) return@signedIn emptyList()
    val ids = roles.keys.joinToString(",")
    val names = c.select(s, "ledger", listOf("id" to "in.($ids)")).associate { it.text("id") to it.text("name") }
    val counts = c.select(s, "ledger_member", listOf("ledger_id" to "in.($ids)")).groupingBy { it.text("ledger_id") }.eachCount()
    roles.map { (id, role) -> LedgerInfo(id, names[id] ?: "", role, counts[id] ?: 1) }
  }

  suspend fun members(ledger: String): List<Member> = signedIn { c, s ->
    c.select(s, "ledger_member", listOf("ledger_id" to "eq.$ledger", "order" to "joined_at.asc")).map {
      Member(it.text("user_id"), it.text("display_name"), it.text("role"), it.text("user_id") == s.userId)
    }
  }

  suspend fun create(name: String): String = signedIn { c, s -> c.rpc(s, "create_ledger", args { put("name", name) }).jsonPrimitive.content }

  suspend fun rename(ledger: String, name: String) = signedIn { c, s -> c.rpc(s, "rename_ledger", args { put("lid", ledger); put("name", name) }); Unit }

  suspend fun setMyName(ledger: String, name: String) =
    signedIn { c, s -> c.rpc(s, "set_display_name", args { put("lid", ledger); put("name", name) }); Unit }

  /** A single-use code, valid for a week. */
  suspend fun invite(ledger: String, role: String): String =
    signedIn { c, s -> c.rpc(s, "create_invite", args { put("lid", ledger); put("role", role) }).jsonPrimitive.content }

  suspend fun join(code: String): String = signedIn { c, s -> c.rpc(s, "join_ledger", args { put("code", code.trim()) }).jsonPrimitive.content }

  suspend fun setRole(ledger: String, user: String, role: String) =
    signedIn { c, s -> c.rpc(s, "set_role", args { put("lid", ledger); put("uid", user); put("role", role) }); Unit }

  /** Their photos' rows go now; the files right after (the server leaves them to whoever may delete them). */
  suspend fun remove(ledger: String, user: String) = signedIn { c, s ->
    c.deleteObjects(s, paths(c.rpc(s, "remove_member", args { put("lid", ledger); put("uid", user) })))
  }

  suspend fun leave(ledger: String) = signedIn { c, s ->
    // The files go after the rows: `own_uploads` lets a person delete what they uploaded even once they've left.
    c.deleteObjects(s, paths(c.rpc(s, "leave_ledger", args { put("lid", ledger) })))
  }

  /** The caderneta's files first, while still a member, then the caderneta. */
  suspend fun delete(ledger: String) = signedIn { c, s ->
    c.deleteObjects(s, c.listObjects(s, "$ledger/"))
    c.rpc(s, "delete_ledger", args { put("lid", ledger) }); Unit
  }

  /** Cadernetas to hand over before the login can go: owned by this person alone, with other members. */
  suspend fun toHandOver(): List<String> = signedIn { c, s -> c.rpc(s, "ledgers_to_hand_over").jsonArray.map { it.jsonObject.text("name") } }

  /**
   * Leaving the app (D104): the files of cadernetas where this person is the only member, and their own uploads
   * elsewhere, then the login (the server applies the leaving rules everywhere).
   */
  suspend fun deleteMe() = signedIn { c, s ->
    val mine = Ledgers.mine(c, s).map { it.id }
    for (ledger in mine) {
      val alone = c.select(s, "ledger_member", listOf("ledger_id" to "eq.$ledger")).size == 1
      val files = if (alone) c.listObjects(s, "$ledger/")
        else c.select(s, "receipt_photo", listOf("ledger_id" to "eq.$ledger", "created_by" to "eq.${s.userId}"))
          .flatMap { listOfNotNull(it.textOrNull("object_path"), it.textOrNull("mask_path")) }
      c.deleteObjects(s, files)
    }
    c.rpc(s, "delete_me"); Unit
  }

  /**
   * Whether this phone may spend a lookup on [chave] now (D104): true unless another member's phone claimed it in the
   * last few minutes. A receipt the server doesn't know yet is this phone's alone.
   */
  suspend fun claimFetch(ledger: String, chave: String): Boolean =
    signedIn { c, s -> c.rpc(s, "claim_fetch", args { put("lid", ledger); put("chave", chave) }).jsonPrimitive.content == "true" }

  suspend fun releaseFetch(ledger: String, chave: String) =
    signedIn { c, s -> c.rpc(s, "release_fetch", args { put("lid", ledger); put("chave", chave) }); Unit }

  private fun paths(result: JsonElement): List<String> =
    (result as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }

  private fun args(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)
  private fun JsonObject.text(name: String) = this[name]!!.jsonPrimitive.content
  private fun JsonObject.textOrNull(name: String) = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}
