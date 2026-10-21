package io.github.sandroroque.getnf.ui

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Brazilian formats for what the database keeps as integers (cents, scale-4 quantities). */
object Format {
  private val BR = Locale.forLanguageTag("pt-BR")

  /** 3064 → "R$ 30,64". */
  fun brl(cents: Long): String = NumberFormat.getCurrencyInstance(BR).format(BigDecimal.valueOf(cents, 2))

  /** 6400 → "0,64", 10000 → "1", 13550 → "1,355". */
  fun e4(value: Long): String {
    val n = NumberFormat.getNumberInstance(BR).apply { maximumFractionDigits = 4; minimumFractionDigits = 0 }
    return n.format(BigDecimal.valueOf(value, 4))
  }
}
