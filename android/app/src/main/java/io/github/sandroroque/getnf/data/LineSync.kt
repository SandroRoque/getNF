package io.github.sandroroque.getnf.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How a receipt line travels to the server, shared by the sync and the parser (D182, review L1). [SyncBaseRow] keeps
 * what the server last held of each line under [syncKey]. A push sends the line's extracted columns (the page's
 * reading, NOT NULL online) and only the [LINE_CORRECTIONS] that differ from the base, so a phone that hasn't pulled
 * another phone's correction never sends its stale copy of it.
 */
const val LINE_TABLE = "receipt_line"

/** What a person or a photo says about a line, as opposed to what the page says. */
val LINE_CORRECTIONS = setOf("product_override_id", "discount_cents", "discount_source", "discount_review")

val ReceiptLineRow.syncKey: String get() = lineSyncKey(receipt, lineNo)

fun lineSyncKey(chave: String, lineNo: Int) = "$chave/$lineNo"

fun ReceiptLineRow.syncJson(): JsonObject = buildJsonObject {
  put("chave", receipt); put("line_no", lineNo); put("description", description); put("code", code)
  put("qty_e4", qtyE4); put("unit", unit); put("unit_price_e4", unitPriceE4); put("gross_cents", grossCents)
  put("store_item_id", storeItemId); put("product_override_id", productOverrideId); put("discount_cents", discountCents)
  put("discount_source", discountSource); put("discount_review", discountReview); put("orphaned", orphaned)
  put("surcharge_cents", surchargeCents)
}
