package io.github.sandroroque.getnf.data

import io.github.sandroroque.getnf.discounts.PhotoText
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What a receipt's photos tell before its page is fetched (D99). The access key carries the year and month of issue
 * (its digits 3–6, AAMM), so a date read by OCR counts only if it agrees with them: a misread date is dropped, not
 * shown. The date printed most often wins (a receipt prints it for the issue, the authorization and the footer).
 */
object PhotoRead {
  private val DATE = Regex("(\\d{2})\\s?/\\s?(\\d{2})\\s?/\\s?(\\d{4})")

  /** The issue date (local midnight in Brazil, epoch millis), or null. */
  fun issueDate(chave: String, texts: List<PhotoText>): Long? {
    val (year, month) = keyMonth(chave) ?: return null
    val dates = texts.flatMap { it.lines }.flatMap { l -> DATE.findAll(l.text).mapNotNull { m ->
      val (d, mo, y) = m.destructured
      runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()?.takeIf { it.year == year && it.monthValue == month }
    }.toList() }
    val date = dates.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
    return date.atStartOfDay().toInstant(ZoneOffset.ofHours(-3)).toEpochMilli()
  }

  /** The issue year and month the access key carries. */
  fun keyMonth(chave: String): Pair<Int, Int>? {
    if (chave.length != 44 || !chave.all(Char::isDigit)) return null
    val month = chave.substring(4, 6).toInt()
    return if (month in 1..12) (2000 + chave.substring(2, 4).toInt()) to month else null
  }
}
