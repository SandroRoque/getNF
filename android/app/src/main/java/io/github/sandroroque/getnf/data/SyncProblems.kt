package io.github.sandroroque.getnf.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rows the server refused (`sync_parked`, D104), as Ajustes shows them (D145): what each is, in words, and the text to
 * send the developer. A refused row stays on the phone, unsynced, until it's sent again ("Tentar de novo" unparks every
 * one and syncs) or it changes; nothing is dropped, since the fix is nearly always on the server's side.
 */
object SyncProblems {
  /** [title]: what it is, in words ("Produto: Café torrado", "Nota de 10/2026"). */
  class Item(val title: String, val error: String, val at: Long)

  private val KINDS = mapOf(
    "issuer" to "Loja", "receipt" to "Nota", "receipt_line" to "Itens da nota", "category" to "Categoria",
    "tag" to "Marcador", "product" to "Produto", "store_item" to "Item de loja", "product_tag" to "Marcador de um produto",
    "link_suggestion" to "Sugestão", "category_event" to "Mudança nas categorias",
  )

  fun describe(row: SyncParkedRow): Item {
    val o = runCatching { Json.parseToJsonElement(row.payload) as? JsonObject }.getOrNull()
    fun field(name: String) = runCatching { o?.get(name)?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
    // A receipt and its lines are keyed by the chave, whose digits 3–6 are the year and month it was issued.
    val chave = (field("chave") ?: row.rowKey).takeIf { it.length == 44 && it.all(Char::isDigit) }
    val kind = KINDS[row.tableName] ?: row.tableName
    val name = field("name") ?: field("label") ?: field("description") ?: field("new_label") ?: field("old_label")
    val title = when {
      name != null -> "$kind: $name"
      chave != null -> "$kind de ${chave.substring(4, 6)}/20${chave.substring(2, 4)}"
      else -> kind
    }
    return Item(title, row.error, row.at)
  }

  /** For the developer: each row's table, key, error and what was sent, after the app's [about] line. */
  fun report(about: String, rows: List<SyncParkedRow>): String =
    "$about\n${rows.size} linha(s) recusada(s) pelo servidor\n\n" +
      rows.joinToString("\n\n") { "${it.tableName} ${it.rowKey} (${java.time.Instant.ofEpochMilli(it.at)})\nerro: ${it.error}\nenviado: ${it.payload.take(2_000)}" }
}
