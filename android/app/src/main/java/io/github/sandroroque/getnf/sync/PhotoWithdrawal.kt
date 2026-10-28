package io.github.sandroroque.getnf.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turning "Enviar fotos" off takes back what was sent (D103, D105): every photo this person uploaded, in every
 * caderneta they're in and from any of their phones, its archive copy and mask in Storage, then its rows. Storage
 * first, so a run that stops halfway still finds the paths next time. Photos other members sent aren't touched.
 */
object PhotoWithdrawal {
  /** Returns how many photos were removed. */
  fun withdraw(client: SupabaseClient, session: SupabaseSession): Int {
    val mine = listOf("created_by" to "eq.${session.userId}")
    val rows = client.select(session, "receipt_photo", mine)
    val paths = rows.flatMap { row ->
      listOf("object_path", "mask_path").mapNotNull { key -> row[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content }
    }
    client.deleteObjects(session, paths)
    if (rows.isNotEmpty()) client.delete(session, "receipt_photo", mine)
    return rows.size
  }
}
