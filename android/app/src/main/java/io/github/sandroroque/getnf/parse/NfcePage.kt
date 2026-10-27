package io.github.sandroroque.getnf.parse

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * Parser for the national NFC-e consultation page (`NFCe_Detalhes.aspx`, docs/sefaz-sc.md), ported from
 * server/pb_hooks/lib/nfce_page.js (D48). Accepts both the server's HTML and the WebView's DOM serialisation.
 * Golden files made by tools/nfce-golden.mjs from the JS parser keep the two in step.
 *
 * Money is Long cents and quantities Long at scale 4, read exactly from the page's decimal text.
 */

data class ParsedItem(
  /** The receipt's own item number (nItem). */
  val line: Int,
  val description: String?,
  val code: String?,
  val qtyE4: Long?,
  val unit: String?,
  val unitPriceE4: Long?,
  val totalCents: Long?,
)

data class ParsedStore(val name: String, val cnpj: String, val address: String)

data class ParsedTotals(
  val itemsCount: Long?,
  /** Filled in from the items when the page omits it (pages without a discount). */
  val grossCents: Long,
  /** 0 when the page omits it. */
  val discountCents: Long,
  val surchargeCents: Long?,
  val payableCents: Long?,
  val changeCents: Long?,
  /** Every label and value of the totals block, as printed. */
  val raw: Map<String, String>,
)

data class ParsedPayment(val method: String, val amountCents: Long?)

/**
 * The taxes the receipt says are in its price (Lei 12.741/2012, IBPT's estimate). Stores word it freely; any part may
 * be missing. [totalCents] is SEFAZ's own field when the page has it, else the parts' sum.
 */
data class ParsedTaxes(val federalCents: Long?, val stateCents: Long?, val municipalCents: Long?, val totalCents: Long?)

data class ParsedReceipt(
  val chave: String?,
  val store: ParsedStore?,
  val number: String?,
  val series: String?,
  /** Epoch millis. */
  val issuedAt: Long?,
  val authorizationProtocol: String?,
  val authorizedAt: Long?,
  val items: List<ParsedItem>,
  val totals: ParsedTotals,
  val payments: List<ParsedPayment>,
  val taxesEstimate: ParsedTaxes?,
  val warnings: List<String>,
)

class NfceParseException(message: String) : Exception(message)

object NfcePage {
  /** Same as the JS parser's VERSION: both read a page the same way. 2: taxes in any store's wording, with a total. */
  const val VERSION = 2

  // UTC offsets by UF code (first 2 digits of the chave). No DST in Brazil since 2019.
  private val UF_OFFSET = mapOf("11" to -4, "12" to -5, "13" to -4, "14" to -4, "50" to -4, "51" to -4)
  private val ENTITIES = mapOf("nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")

  private val ENTITY = Regex("&(#x[0-9a-f]+|#\\d+|[a-z]+);", RegexOption.IGNORE_CASE)
  private val TAG = Regex("<[^>]*>")
  private val SPACE = Regex("[\\s\\u00a0]+")
  private val NUMBER = Regex("-?[\\d.]*\\d(?:,\\d+)?")
  private val DIGITS = Regex("(\\d+)")
  private val ITEM_ROW = Regex("<tr[^>]*id=\"Item \\+ (\\d+)\"[^>]*>([\\s\\S]*?)</tr>")
  private val PAIR = Regex("<label([^>]*)>([\\s\\S]*?)</label>\\s*<span[^>]*>([\\s\\S]*?)</span>")
  private val STORE = Regex(
    "<div[^>]*class=\"txtTopo\"[^>]*>([\\s\\S]*?)</div>\\s*<div[^>]*class=\"text\"[^>]*>([\\s\\S]*?)</div>\\s*" +
      "<div[^>]*class=\"text\"[^>]*>([\\s\\S]*?)</div>",
  )
  private val SCRIPT_STYLE = Regex("<script[\\s\\S]*?</script>|<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE)
  private val CHAVE = Regex("class=\"chave\"[^>]*>([\\d\\s]+)<")
  private val INFO = Regex("Número:\\s*(\\d+)\\s*Série:\\s*(\\d+)\\s*Emissão:\\s*(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})")
  private val PROTOCOL = Regex("Protocolo de Autorização:\\s*(\\d+)\\s+(\\d{2}/\\d{2}/\\d{4})\\s+às\\s+(\\d{2}:\\d{2}:\\d{2})")
  private val TAX_TOTAL = Regex("Tributos Totais Incidentes\\s*\\(Lei Federal 12\\.741/2012\\)\\s*R\\$\\s*(\\d[\\d.]*,\\d{2})", RegexOption.IGNORE_CASE)
  private val TAX_CONTEXT = Regex("\\btrib|IBPT|12\\.741", RegexOption.IGNORE_CASE)
  private val DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

  private enum class Total { ITEMS_COUNT, GROSS, DISCOUNT, SURCHARGE, PAYABLE, CHANGE }
  private val TOTAL_LABELS = listOf(
    Regex("qtd\\. total de itens", RegexOption.IGNORE_CASE) to Total.ITEMS_COUNT,
    Regex("valor total", RegexOption.IGNORE_CASE) to Total.GROSS,
    Regex("desconto", RegexOption.IGNORE_CASE) to Total.DISCOUNT,
    Regex("acr[ée]scimo|outras despesas", RegexOption.IGNORE_CASE) to Total.SURCHARGE,
    Regex("valor a pagar", RegexOption.IGNORE_CASE) to Total.PAYABLE,
    Regex("troco", RegexOption.IGNORE_CASE) to Total.CHANGE,
  )

  private fun decode(s: String) = ENTITY.replace(s) { m ->
    val e = m.groupValues[1]
    when {
      e.startsWith("#x", ignoreCase = true) -> String(Character.toChars(e.substring(2).toInt(16)))
      e.startsWith("#") -> String(Character.toChars(e.substring(1).toInt()))
      else -> ENTITIES[e.lowercase()] ?: m.value
    }
  }

  /** Visible text of an HTML fragment, whitespace collapsed. */
  fun text(html: String) = SPACE.replace(decode(TAG.replace(html, " ")), " ").trim()

  /** "1.234,56" → 1234.56; null if there's no number. */
  fun decimal(s: String?): BigDecimal? {
    val m = NUMBER.find(s ?: return null) ?: return null
    return BigDecimal(m.value.replace(".", "").replace(",", "."))
  }

  private fun scaled(s: String?, scale: Int) = decimal(s)?.setScale(scale, RoundingMode.HALF_UP)?.unscaledValue()?.toLong()
  fun cents(s: String?) = scaled(s, 2)
  fun e4(s: String?) = scaled(s, 4)

  /** Text after the last ':' ("Qtde.: 0,64" → "0,64"). */
  private fun afterLabel(s: String) = s.substring(s.lastIndexOf(':') + 1).trim()

  private fun spanByClass(html: String, cls: String): String? =
    Regex("<span[^>]*class=\"" + Regex.escape(cls) + "(?:\\s[^\"]*)?\"[^>]*>([\\s\\S]*?)</span>").find(html)?.let { text(it.groupValues[1]) }

  private fun parseItems(html: String) = ITEM_ROW.findAll(html).map { m ->
    val r = m.groupValues[2]
    ParsedItem(
      line = m.groupValues[1].toInt(),
      description = spanByClass(r, "txtTit"),
      code = DIGITS.find(spanByClass(r, "RCod") ?: "")?.value,
      qtyE4 = e4(afterLabel(spanByClass(r, "Rqtd") ?: "")),
      unit = afterLabel(spanByClass(r, "RUN") ?: "").ifEmpty { null },
      unitPriceE4 = e4(afterLabel(spanByClass(r, "RvlUnit") ?: "")),
      totalCents = cents(spanByClass(r, "valor")),
    )
  }.toList()

  private class TotalsBlock(val values: Map<Total, String>, val raw: Map<String, String>, val payments: List<ParsedPayment>)

  private fun parseTotals(html: String): TotalsBlock {
    val start = html.indexOf("id=\"totalNota\"")
    val end = if (start < 0) -1 else html.indexOf("id=\"infos\"", start)
    val block = if (start < 0) "" else if (end < 0) html.substring(start) else html.substring(start, end)
    val values = linkedMapOf<Total, String>()
    val raw = linkedMapOf<String, String>()
    val payments = mutableListOf<ParsedPayment>()
    for (m in PAIR.findAll(block)) {
      val label = text(m.groupValues[2])
      val value = text(m.groupValues[3])
      if (m.groupValues[1].contains("class=\"tx\"")) { payments += ParsedPayment(label, cents(value)); continue }
      if (label.contains("forma de pagamento", ignoreCase = true)) continue
      raw[label] = value
      TOTAL_LABELS.firstOrNull { it.first.containsMatchIn(label) }?.let { values[it.second] = value }
    }
    return TotalsBlock(values, raw, payments)
  }

  private fun parseStore(html: String) = STORE.find(html)?.let {
    ParsedStore(text(it.groupValues[1]), text(it.groupValues[2]).replace(Regex("\\D"), ""), text(it.groupValues[3]))
  }

  private fun epochMillis(date: String, time: String, offsetHours: Int) =
    LocalDateTime.parse("$date $time", DATE_TIME).toInstant(ZoneOffset.ofHours(offsetHours)).toEpochMilli()

  /** Writes cents like the JS parser's warnings print reais: 31.15, 31.1, 30. */
  private fun reais(cents: Long) = BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString()

  /**
   * The taxes, from wherever the page states them: SEFAZ's "Tributos Totais Incidentes" total, and the store's own
   * text, which words each part its own way ("FEDERAL R$ 37,70 ESTADUAL R$ 42,63", "FEDERAIS: R$ 28,07 - ESTADUAIS:
   * R$ 35,46", "Trib aprox R$: 47,95 Federal e 28,88 Estadual"). Parts are read only near a mention of taxes, so a
   * "Lei Federal" elsewhere isn't one.
   */
  fun parseTaxes(all: String): ParsedTaxes? {
    val total = TAX_TOTAL.find(all)?.let { cents(it.groupValues[1]) }
    fun part(text: String, label: String): Long? =
      Regex("$label\\w*\\s*:?\\s*R\\$\\s*:?\\s*(\\d[\\d.]*,\\d{2})", RegexOption.IGNORE_CASE).find(text)?.let { cents(it.groupValues[1]) }
        ?: Regex("(\\d[\\d.]*,\\d{2})\\s*$label", RegexOption.IGNORE_CASE).find(text)?.let { cents(it.groupValues[1]) }
    for (m in TAX_CONTEXT.findAll(all)) {
      val near = all.substring(maxOf(0, m.range.first - 160), minOf(all.length, m.range.last + 200))
      val federal = part(near, "FEDERA"); val state = part(near, "ESTADUA"); val municipal = part(near, "MUNICIPA")
      if (federal != null || state != null) return ParsedTaxes(federal, state, municipal, total ?: listOfNotNull(federal, state, municipal).sum())
    }
    return total?.let { ParsedTaxes(null, null, null, it) }
  }

  fun parse(html: String): ParsedReceipt {
    val items = parseItems(html)
    if (items.isEmpty()) throw NfceParseException("no items found: not an NFC-e details page?")

    val all = text(SCRIPT_STYLE.replace(html, " "))
    val chave = CHAVE.find(html)?.groupValues?.get(1)?.replace(Regex("\\D"), "")
    val offset = chave?.let { UF_OFFSET[it.take(2)] } ?: -3

    val info = INFO.find(all)?.groupValues
    val prot = PROTOCOL.find(all)?.groupValues
    val block = parseTotals(html)
    val v = block.values

    // Pages without a discount omit "Valor total" and "Descontos": fill them in so consumers don't have to.
    val itemsCents = items.sumOf { it.totalCents ?: 0 }
    val totals = ParsedTotals(
      itemsCount = decimal(v[Total.ITEMS_COUNT])?.toLong(),
      grossCents = v[Total.GROSS]?.let { cents(it) } ?: itemsCents,
      discountCents = v[Total.DISCOUNT]?.let { cents(it) } ?: 0,
      surchargeCents = cents(v[Total.SURCHARGE]),
      payableCents = cents(v[Total.PAYABLE]),
      changeCents = cents(v[Total.CHANGE]),
      raw = block.raw,
    )

    val warnings = mutableListOf<String>()
    if (totals.itemsCount != null && totals.itemsCount != items.size.toLong())
      warnings += "page says ${totals.itemsCount} items, parsed ${items.size}"
    if (totals.grossCents != itemsCents) warnings += "items sum to ${reais(itemsCents)}, page total is ${reais(totals.grossCents)}"
    if (totals.payableCents != null && totals.grossCents - totals.discountCents + (totals.surchargeCents ?: 0) != totals.payableCents)
      warnings += "gross - discount + surcharge != payable (${reais(totals.payableCents)})"

    return ParsedReceipt(
      chave = chave,
      store = parseStore(html),
      number = info?.get(1),
      series = info?.get(2),
      issuedAt = info?.let { epochMillis(it[3], it[4], offset) },
      authorizationProtocol = prot?.get(1),
      authorizedAt = prot?.let { epochMillis(it[2], it[3], offset) },
      items = items,
      totals = totals,
      payments = block.payments,
      taxesEstimate = parseTaxes(all),
      warnings = warnings,
    )
  }
}
