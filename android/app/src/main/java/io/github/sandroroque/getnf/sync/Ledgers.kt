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

  /**
   * The caderneta a run syncs the phone's open files with (D104), null for none: with nothing [open] yet, the one
   * [pick] gives (the caller opens it); otherwise the open one, while the signed-in person belongs to it. One they
   * don't belong to (they left, or another person signed in) is never swapped for another of theirs: the files on the
   * phone are that caderneta's, and wait for the person to open another in Ajustes.
   */
  fun toSync(mine: List<Ledger>, open: String?, userId: String): String? =
    if (open == null) pick(mine, null, userId) else open.takeIf { mine.any { m -> m.id == open } }

  /** The cadernetas besides [open] that a run also syncs: the person's own that this phone has files for ([hasFiles]). */
  fun others(mine: List<Ledger>, open: String?, hasFiles: (String) -> Boolean): List<String> =
    mine.map { it.id }.filter { it != open && hasFiles(it) }

  private fun JsonObject.text(name: String) = this[name]!!.jsonPrimitive.content
}
