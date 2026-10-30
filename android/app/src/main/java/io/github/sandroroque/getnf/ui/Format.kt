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
  fun date(millis: Long): String = day(java.time.Instant.ofEpochMilli(millis).atOffset(java.time.ZoneOffset.ofHours(-3)).toLocalDate())

  /**
   * Something that happened on the phone (a backup, a review), in its own time zone: "27 out 2026, 04:00". Never the
   * phone's language, which may not be Portuguese.
   */
  fun dateTime(millis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val t = java.time.Instant.ofEpochMilli(millis).atZone(zone)
    return "${day(t.toLocalDate())}, ${time(millis, zone)}"
  }

  /** "16:05", on the phone's clock. */
  fun time(millis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
    java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

  private fun day(d: java.time.LocalDate): String {
    val month = d.month.getDisplayName(java.time.format.TextStyle.SHORT, BR).trimEnd('.')
    return if (d.year == java.time.LocalDate.now().year) "${d.dayOfMonth} $month" else "${d.dayOfMonth} $month ${d.year}"
  }

  /** 6400 → "0,64", 10000 → "1", 13550 → "1,355". */
  fun e4(value: Long): String {
    val n = NumberFormat.getNumberInstance(BR).apply { maximumFractionDigits = 4; minimumFractionDigits = 0 }
    return n.format(BigDecimal.valueOf(value, 4))
  }
}
