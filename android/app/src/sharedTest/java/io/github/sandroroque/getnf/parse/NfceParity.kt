package io.github.sandroroque.getnf.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Compares the Kotlin parser and derivation with golden files written from the server's JS by tools/nfce-golden.mjs
 * (D48). Shared by the JVM tests and the on-device ones, since Android's regex engine (ICU) isn't the JVM's.
 */
object NfceParity {
  val FIXTURES = listOf("sc-detalhes-server-html", "sc-detalhes-webview-dom")

  /** Each page in [dir] that has a golden file next to it. */
  fun pairs(dir: java.io.File) = dir.listFiles { f -> f.extension == "html" }.orEmpty().sorted().mapNotNull { html ->
    java.io.File(html.path.removeSuffix(".html") + ".json").takeIf { it.isFile }?.let { html to it }
  }

  /** The golden file's shape, from the Kotlin side. */
  fun golden(p: ParsedReceipt): JsonObject {
    val storeCnpj = p.store?.cnpj ?: ""
    val discounts = Derive.lineDiscounts(p.totals.discountCents, p.items.map { it.totalCents ?: 0 })
    return buildJsonObject {
      put("version", NfcePage.VERSION)
      put("chave", p.chave)
      put("store", p.store?.let { buildJsonObject { put("name", it.name); put("cnpj", it.cnpj); put("address", it.address) } } ?: JsonNull)
      put("number", p.number)
      put("series", p.series)
      put("issuedAt", p.issuedAt)
      put("authorizationProtocol", p.authorizationProtocol)
      put("authorizedAt", p.authorizedAt)
      put("items", buildJsonArray {
        p.items.forEachIndexed { idx, i ->
          val size = Derive.parseSize(i.description)
          add(buildJsonObject {
            put("line", i.line); put("description", i.description); put("code", i.code); put("qtyE4", i.qtyE4)
            put("unit", i.unit); put("unitPriceE4", i.unitPriceE4); put("totalCents", i.totalCents)
            put("serverKey", Derive.codeKey(i.code ?: "", storeCnpj).serverKey)
            put("sizeE6", size?.e6); put("sizeUnit", size?.unit)
            put("discountCents", discounts[idx].cents); put("discountSource", discounts[idx].source.value)
          })
        }
      })
      put("totals", buildJsonObject {
        val t = p.totals
        put("itemsCount", t.itemsCount); put("grossCents", t.grossCents); put("discountCents", t.discountCents)
        put("surchargeCents", t.surchargeCents); put("payableCents", t.payableCents); put("changeCents", t.changeCents)
        put("raw", buildJsonObject { t.raw.forEach { (k, v) -> put(k, v) } })
      })
      put("payments", buildJsonArray { p.payments.forEach { add(buildJsonObject { put("method", it.method); put("amountCents", it.amountCents) }) } })
      put("taxesEstimate", p.taxesEstimate?.let {
        buildJsonObject { put("federalCents", it.federalCents); put("stateCents", it.stateCents); put("municipalCents", it.municipalCents) }
      } ?: JsonNull)
      put("warnings", buildJsonArray { p.warnings.forEach { add(JsonPrimitive(it)) } })
    }
  }

  /** Field paths where [actual] differs from [expected]; "null" and absent count the same. */
  private fun diff(expected: JsonElement, actual: JsonElement, at: String = ""): List<String> = when {
    expected is JsonObject && actual is JsonObject ->
      (expected.keys + actual.keys).flatMap { diff(expected[it] ?: JsonNull, actual[it] ?: JsonNull, "$at.$it") }
    expected is JsonArray && actual is JsonArray ->
      if (expected.size != actual.size) listOf("$at: ${expected.size} entries, got ${actual.size}")
      else expected.indices.flatMap { diff(expected[it], actual[it], "$at[$it]") }
    expected is JsonPrimitive && actual is JsonPrimitive && expected.content == actual.content && expected.isString == actual.isString -> emptyList()
    else -> listOf("$at: expected $expected, got $actual")
  }

  /** Differences from the golden file, as field paths; empty when they match. */
  fun differences(html: String, goldenJson: String) = diff(Json.parseToJsonElement(goldenJson), golden(NfcePage.parse(html)))

}
