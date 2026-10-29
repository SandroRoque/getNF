package io.github.sandroroque.getnf.data

import androidx.room.Dao
import androidx.room.Query
import io.github.sandroroque.getnf.parse.Derive
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow

/**
 * Stage 5: spending and prices, computed on the phone from its own database, so reports work offline. Lines are
 * read once and aggregated here (a household has thousands of lines, not millions), in plain Kotlin that JVM tests
 * can check: totals per period and category must add up to the receipts (roadmap stage 5).
 */
data class SpendingLine(
  val chave: String,
  val lineNo: Int,
  val issuedAt: Long?,
  val storeCnpj: String?,
  val storeName: String?,
  val grossCents: Long,
  val discountCents: Long,
  val discountSource: String,
  val productId: String,
  val categoryId: String?,
  val qtyE4: Long,
  val unit: String?,
  val unitPriceE4: Long,
  /** The product's size by hand, else the one System-1 is sure of (D108), else the parsed one; "no size" gives null. */
  val sizeE6: Long?,
  val sizeUnit: String?,
  /** How the store item is sold, when the parser or System-1 settled it (D108): `weight`, `volume` or `unit`. */
  val soldBy: String? = null,
) {
  /** `kg` or `l` when the quantity is itself a weight or a volume: by the unit's spelling, else by how it's sold. */
  val measure: String? get() = Derive.unitKind(unit) ?: when (soldBy) { "weight" -> "kg"; "volume" -> "l"; else -> null }

  val netCents: Long get() = grossCents - discountCents
}

@Dao
abstract class SpendingDao {
  @Query(
    """
    SELECT l.receipt AS chave, l.lineNo, rp.issuedAt, rp.issuerCnpj AS storeCnpj, i.name AS storeName, l.grossCents,
      l.discountCents, l.discountSource, p.id AS productId, p.categoryId, l.qtyE4, l.unit, l.unitPriceE4,
      CASE WHEN p.sizeSource = 'manual' THEN p.sizeE6 WHEN s.readSizeSource IS NOT NULL THEN s.readSizeE6 ELSE s.parsedSizeE6 END AS sizeE6,
      CASE WHEN p.sizeSource = 'manual' THEN p.sizeUnit WHEN s.readSizeSource IS NOT NULL THEN s.readSizeUnit ELSE s.parsedSizeUnit END AS sizeUnit,
      s.soldBy
    FROM receipt_line l
    JOIN store_item s ON s.id = l.storeItemId
    JOIN product p ON p.id = COALESCE(l.productOverrideId, s.productId)
    LEFT JOIN receipt_parsed rp ON rp.chave = l.receipt
    LEFT JOIN issuer i ON i.cnpj = rp.issuerCnpj
    """,
  )
  abstract fun lines(): Flow<List<SpendingLine>>

  /** Receipts scanned whose page wasn't fetched yet: spending the reports can't show. */
  @Query("SELECT COUNT(*) FROM receipt WHERE pageFile IS NULL") abstract fun waitingForPage(): Flow<Int>
}

/** A month (`2026-10`) or a year (`2026`). */
data class Period(val key: String) : Comparable<Period> {
  val isYear: Boolean get() = key.length == 4
  override fun compareTo(other: Period) = key.compareTo(other.key)

  companion object {
    /** Receipts are dated in store-local time; Santa Catarina is UTC-3 all year (as the old dashboard, D19). */
    private val STORE_TIME = ZoneOffset.ofHours(-3)
    fun month(millis: Long) = Period(Instant.ofEpochMilli(millis).atOffset(STORE_TIME).toLocalDate().toString().take(7))
    fun year(millis: Long) = Period(month(millis).key.take(4))
  }
}

/** One slice of a period: a category (with everything under it), or what has no category. */
data class Slice(val categoryId: String?, val netCents: Long, val discountCents: Long, val lines: Int)

/** What the numbers can't fully say, shown next to them (roadmap stage 5). */
data class Caveats(
  /** Discount that is the receipt's total spread over lines, not read per item (D14, D54). */
  val proratedDiscountCents: Long,
  val uncategorizedCents: Long,
  /** Lines whose receipt has no date: left out of every period. */
  val undatedLines: Int,
)

/**
 * The tree-aware aggregation. [parents] maps a category id to its parent's; a line counts in its category and every
 * ancestor, so a level's slices add up to the level above, and the top level to the period's total.
 */
class Spending(lines: List<SpendingLine>, private val parents: Map<String, String?>, private val yearly: Boolean = false) {
  private val dated = lines.filter { it.issuedAt != null }
  val undatedLines: Int = lines.size - dated.size

  fun periodOf(line: SpendingLine): Period = if (yearly) Period.year(line.issuedAt!!) else Period.month(line.issuedAt!!)

  /** Every period with spending, oldest first. Gaps (months with no receipts) are not zero spending, just no data. */
  val periods: List<Period> = dated.map(::periodOf).distinct().sorted()

  fun linesIn(period: Period) = dated.filter { periodOf(it) == period }

  fun total(period: Period): Long = linesIn(period).sumOf { it.netCents }

  /** The path from a category up to the root, itself first. */
  fun ancestry(categoryId: String?): List<String> =
    generateSequence(categoryId) { parents[it] }.takeWhile { it in parents }.toList()

  /**
   * Slices of [period] directly under [parentId] (null: the top level). Lines of [parentId] itself, not further
   * down, come back as a slice with [parentId]'s own id ("only here"), and at the top level the uncategorized ones as a
   * slice with a null id. Largest first.
   */
  fun slices(period: Period, parentId: String?): List<Slice> {
    val groups = linkedMapOf<String?, MutableList<SpendingLine>>()
    for (line in linesIn(period)) {
      val path = ancestry(line.categoryId).reversed() // root first; empty when uncategorized (or the category is gone)
      val key = if (parentId == null) path.firstOrNull() else {
        val at = path.indexOf(parentId)
        if (at < 0) continue
        path.getOrNull(at + 1) ?: parentId
      }
      groups.getOrPut(key) { mutableListOf() } += line
    }
    return groups.map { (id, ls) -> Slice(id, ls.sumOf { it.netCents }, ls.sumOf { it.discountCents }, ls.size) }
      .sortedByDescending { it.netCents }
  }

  /** The lines of [period] in [categoryId] or under it (null: all of them). */
  fun linesUnder(period: Period, categoryId: String?) =
    linesIn(period).filter { categoryId == null || categoryId in ancestry(it.categoryId) }

  /** Each period's spending per top-level category, for the stacked bars. */
  fun stacks(): Map<Period, List<Slice>> = periods.associateWith { slices(it, null) }

  fun caveats(period: Period): Caveats {
    val ls = linesIn(period)
    return Caveats(
      proratedDiscountCents = ls.filter { it.discountSource == "prorated" }.sumOf { it.discountCents },
      uncategorizedCents = ls.filter { it.categoryId == null || it.categoryId !in parents }.sumOf { it.netCents },
      undatedLines = undatedLines,
    )
  }
}

/** A product's spending in a period, for the list under the chart. */
data class ProductSpend(val productId: String, val netCents: Long, val lines: Int, val qty: Map<String, Long>)

fun productsIn(lines: List<SpendingLine>): List<ProductSpend> = lines.groupBy { it.productId }.map { (id, ls) ->
  // Quantities add up only within a unit (D19): 1,2 kg and 3 UN stay apart.
  ProductSpend(id, ls.sumOf { it.netCents }, ls.size, ls.groupBy { it.unit.orEmpty().uppercase() }.mapValues { (_, u) -> u.sumOf { it.qtyE4 } })
}.sortedByDescending { it.netCents }

/**
 * One purchase's price, made comparable: per kg or litre when the line is sold by weight or the size is known, else per
 * unit. Uses the shelf price (unit price before discounts), like a price tag.
 */
data class PricePoint(val at: Long, val storeName: String?, val cents: Long, val basis: String)

fun pricePoint(line: SpendingLine): PricePoint? {
  val at = line.issuedAt ?: return null
  val measure = line.measure
  // unitPriceE4 is centavos × 100 (scale 4 on reais).
  val perUnitCents = line.unitPriceE4 / 100.0
  return when {
    measure != null -> PricePoint(at, line.storeName, Math.round(perUnitCents), measure)
    line.sizeE6 != null && line.sizeE6 > 0 && line.sizeUnit != null ->
      PricePoint(at, line.storeName, Math.round(perUnitCents * 1_000_000 / line.sizeE6), line.sizeUnit)
    else -> PricePoint(at, line.storeName, Math.round(perUnitCents), "un")
  }
}
