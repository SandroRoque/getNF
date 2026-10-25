package io.github.sandroroque.getnf.ui

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Brazilian formats for what the database keeps as integers (cents, scale-4 quantities). */
object Format {
  private val BR = Locale.forLanguageTag("pt-BR")

  /** 3064 → "R$ 30,64". */
  fun brl(cents: Long): String = NumberFormat.getCurrencyInstance(BR).format(BigDecimal.valueOf(cents, 2))

  /** "1 nota", "3 notas". */
  fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

  /** "9 out" (this year) or "9 out 2025". Receipts are dated in store-local time, UTC-3 (D19). */
  fun date(millis: Long): String {
    val d = java.time.Instant.ofEpochMilli(millis).atOffset(java.time.ZoneOffset.ofHours(-3)).toLocalDate()
    val month = d.month.getDisplayName(java.time.format.TextStyle.SHORT, BR).trimEnd('.')
    return if (d.year == java.time.LocalDate.now().year) "${d.dayOfMonth} $month" else "${d.dayOfMonth} $month ${d.year}"
  }

  /** 6400 → "0,64", 10000 → "1", 13550 → "1,355". */
  fun e4(value: Long): String {
    val n = NumberFormat.getNumberInstance(BR).apply { maximumFractionDigits = 4; minimumFractionDigits = 0 }
    return n.format(BigDecimal.valueOf(value, 4))
  }
}
