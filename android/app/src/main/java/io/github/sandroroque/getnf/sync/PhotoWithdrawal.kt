package io.github.sandroroque.getnf.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turning "Enviar fotos" off takes back what was sent (D103, D105): every photo this person uploaded, in every
 * caderneta they're in and from any of their phones, its archive copy and mask in Storage, then its rows. Storage
 * first, so a run that stops halfway still finds the paths next time; and only the rows of the objects just removed,
 * so a photo another of their phones sends meanwhile keeps its row for the next run (T-11). Photos other members sent
 * aren't touched.
 */
object PhotoWithdrawal {
  /** A read stops at the server's 1,000 rows. */
  private const val PAGE = 1000
  /** Rows deleted per request: each is named by its whole key, and the address has a length limit. */
  private const val DELETE_CHUNK = 40

  /** Returns how many photos were removed. */
  fun withdraw(client: SupabaseClient, session: SupabaseSession): Int {
    val rows = mine(client, session)
    client.deleteObjects(session, paths(rows))
    for (chunk in rows.chunked(DELETE_CHUNK)) {
      val keys = chunk.joinToString(",", "(", ")") { r ->
        "and(ledger_id.eq.${r.value("ledger_id")},chave.eq.${r.value("chave")},local_name.eq.${quoted(r.value("local_name"))})"
      }
      client.delete(session, "receipt_photo", listOf("created_by" to "eq.${session.userId}", "or" to keys))
    }
    return rows.size
  }

  /** Every photo row this person sent, in [ledger] or in every caderneta, page by page (T-11). */
  fun mine(client: SupabaseClient, session: SupabaseSession, ledger: String? = null): List<JsonObject> {
    val found = mutableListOf<JsonObject>()
    while (true) {
      val page = client.select(session, "receipt_photo", listOfNotNull(
        "select" to "ledger_id,chave,local_name,object_path,mask_path",
        "created_by" to "eq.${session.userId}",
        ledger?.let { "ledger_id" to "eq.$it" },
        "order" to "ledger_id,chave,local_name", "limit" to "$PAGE", "offset" to "${found.size}",
      ))
      found += page
      if (page.size < PAGE) return found
    }
  }

  /** The objects [rows] point to: archive copies and masks. */
  fun paths(rows: List<JsonObject>): List<String> = rows.flatMap { row ->
    listOf("object_path", "mask_path").mapNotNull { key -> row[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content }
  }

  private fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content

  /** A value inside a PostgREST filter, quoted so commas and parentheses in it are its own. */
  private fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
