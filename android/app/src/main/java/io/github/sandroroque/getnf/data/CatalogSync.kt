package io.github.sandroroque.getnf.data

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How products and store items travel to the server, shared by the sync and the parser, as [ReceiptLineRow.syncJson]
 * is for lines.
 *
 * A row the parser makes has an id that comes from the code (D104), so another phone reading the same item makes the
 * same row, and one of them may already be in the caderneta with a name or a category someone gave it. The parser
 * therefore keeps the row as it made it as the row's base ([SyncBaseRow]), marked [MADE_HERE] (D211): until the
 * server's copy is seen, "what this phone changed" is what differs from that, and only that is sent.
 */
const val PRODUCT_TABLE = "product"
const val STORE_ITEM_TABLE = "store_item"

/** In a base: the row as this phone made it, not yet what the server held. No column has this name. */
const val MADE_HERE = "_made_here"

/** [row] as a base for a row this phone has just made. */
fun madeHere(row: JsonObject): String = JsonObject(row + (MADE_HERE to JsonPrimitive(true))).toString()

/** Suggestions are left out: only the server writes them (4b). */
fun ProductRow.syncJson(): JsonObject = buildJsonObject {
  put("id", id); put("name", name); put("category_id", categoryId); put("category_source", categorySource)
  put("category_review", categoryReview); put("category_model", categoryModel); put("category_confidence", categoryConfidence)
  put("category_at", categoryAt?.let(::iso)); put("size_e6", sizeE6); put("size_unit", sizeUnit)
  put("size_source", sizeSource); put("archived", archived); put("deleted", deleted); put("created_at", iso(createdAt))
}

/** System-1's readings are left out: only the server writes them (D108). The user's answer goes up (D110). */
fun StoreItemRow.syncJson(): JsonObject = buildJsonObject {
  put("id", id); put("code_kind", codeKind); put("code", code); put("store_cnpj", storeCnpj)
  put("description", description); put("parsed_size_e6", parsedSizeE6); put("parsed_size_unit", parsedSizeUnit)
  put("product_id", productId); put("link_source", linkSource); put("link_review", linkReview); put("deleted", deleted)
  put("sold_by_user", soldByUser)
}

private fun iso(millis: Long) = Instant.ofEpochMilli(millis).toString()
