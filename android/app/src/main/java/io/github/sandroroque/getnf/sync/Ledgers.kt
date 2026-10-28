package io.github.sandroroque.getnf.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A caderneta the signed-in person belongs to (D104), with their role in it. */
data class Ledger(val id: String, val role: String)

/** Which caderneta a phone works in, from the ones its person belongs to (D104). */
object Ledgers {
  fun mine(client: SupabaseClient, session: SupabaseSession): List<Ledger> =
    client.select(session, "ledger_member", listOf("user_id" to "eq.${session.userId}", "order" to "joined_at.asc"))
      .map { Ledger(it.text("ledger_id"), it.text("role")) }

  /**
   * The [current] one while the person still belongs to it; else the one their login became (same id, D104), else
   * the first they joined. Null if they belong to none (all left), which leaves the phone working on its own.
   */
  fun pick(mine: List<Ledger>, current: String?, userId: String): String? =
    mine.find { it.id == current }?.id ?: mine.find { it.id == userId }?.id ?: mine.firstOrNull()?.id

  private fun JsonObject.text(name: String) = this[name]!!.jsonPrimitive.content
}
