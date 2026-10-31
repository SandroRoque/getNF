package io.github.sandroroque.getnf.data

import android.content.Context
import io.github.sandroroque.getnf.parse.Derive
import kotlin.math.roundToLong

/*
 * Looking a product up by its barcode (camera plan, step 1): what the person paid for it, in every caderneta on this
 * phone. There's no GTIN registry: only what the person bought turns up. A GTIN store item is the same row at every
 * store (Derive.codeKey), so one code finds every purchase of it.
 */

/** What a code read from a product is. */
enum class CodeKind {
  /** A GTIN: the same product at every store. */
  PRODUCT,
  /** A GTIN-13 starting with 2: the store's scale label, whose code changes with every weighing. */
  SCALE_LABEL,
  /** A GTIN-8 starting with 0 or 2: a code the store made for itself. */
  STORE_CODE,
  /** Not a GTIN at all (wrong length, a bad check digit, letters). */
  NOT_A_PRODUCT,
}

object Gtin {
  fun kind(raw: String): CodeKind {
    val code = raw.trim()
    if (!Regex("\\d{8}|\\d{12,14}").matches(code) || !checkDigitOk(code)) return CodeKind.NOT_A_PRODUCT
    return when {
      code.length == 13 && code[0] == '2' -> CodeKind.SCALE_LABEL
      code.length == 8 && (code[0] == '0' || code[0] == '2') -> CodeKind.STORE_CODE
      Derive.isGtin(code) -> CodeKind.PRODUCT
      else -> CodeKind.NOT_A_PRODUCT
    }
  }

  /**
   * The spellings of one GTIN that a receipt may have printed: the scanner reads a UPC-A as 12 digits, a receipt may
   * write it as 13 or 14 with zeros in front, and the other way round. Only zeros are added or taken away.
   */
  fun variants(code: String): List<String> {
    val full = code.trim().padStart(14, '0')
    return listOf(8, 12, 13, 14).filter { n -> full.take(14 - n).all { it == '0' } }.map { full.takeLast(it) }
  }

  private fun checkDigitOk(code: String): Boolean {
    var sum = 0
    var w = 3
    for (i in code.length - 2 downTo 0) { sum += (code[i] - '0') * w; w = 4 - w }
    return (10 - sum % 10) % 10 == code.last() - '0'
  }
}

/** One purchase of a store item found by its GTIN, with its product's name and category. */
data class GtinLine(
  val chave: String,
  val lineNo: Int,
  val issuedAt: Long?,
  val storeCnpj: String?,
  val storeName: String?,
  val qtyE4: Long,
  val unit: String?,
  val unitPriceE4: Long,
  val grossCents: Long,
  val discountCents: Long,
  val code: String,
  val description: String,
  val productId: String,
  val productName: String?,
  val categoryLabel: String?,
)

/** A store's latest shelf price for the product. */
data class StorePrice(val storeCnpj: String?, val storeName: String?, val cents: Long, val at: Long)

/**
 * The product in one caderneta: its name and category there, the latest purchase, the latest price at each store
 * (cheapest first), and the average shelf price over the last [ProductSighting.AVERAGE_DAYS] days. Prices are per
 * [basis]: `un`, or `kg`/`l` for the rare GTIN sold by weight or volume; purchases in another basis are left out.
 */
data class ProductSighting(
  /** The caderneta's id; null for this phone's caderneta without an account. */
  val ledger: String?,
  val productId: String,
  val name: String,
  val categoryLabel: String?,
  val basis: String,
  val last: StorePrice,
  val byStore: List<StorePrice>,
  val averageCents: Long?,
  val purchases: Int,
) {
  companion object {
    const val AVERAGE_DAYS = 90
    private const val DAY_MS = 86_400_000L

    /** Null when no line has a date (its page hasn't been read: nothing to say about prices yet). */
    fun of(ledger: String?, lines: List<GtinLine>, now: Long): ProductSighting? {
      val dated = lines.filter { it.issuedAt != null }.sortedByDescending { it.issuedAt }
      val newest = dated.firstOrNull() ?: return null
      val basis = basisOf(newest)
      val same = dated.filter { basisOf(it) == basis }
      fun price(l: GtinLine) = StorePrice(l.storeCnpj, l.storeName, (l.unitPriceE4 / 100.0).roundToLong(), l.issuedAt!!)
      val byStore = same.groupBy { it.storeCnpj ?: it.storeName }.values.map { price(it.first()) }
        .sortedWith(compareBy({ it.cents }, { -it.at }))
      val recent = same.filter { now - it.issuedAt!! <= AVERAGE_DAYS * DAY_MS }
      val average = recent.takeIf { it.isNotEmpty() }?.map { it.unitPriceE4 / 100.0 }?.average()?.roundToLong()
      return ProductSighting(
        ledger, newest.productId, newest.productName?.takeIf { it.isNotBlank() } ?: newest.description,
        newest.categoryLabel, basis, price(newest), byStore, average, same.size,
      )
    }

    private fun basisOf(l: GtinLine) = Derive.unitKind(l.unit) ?: "un"
  }
}

/** What a scanned code turned up. */
sealed interface LookupResult {
  val code: String

  /** Bought before, in one or more cadernetas: the open one first, then the most recent purchase first. */
  data class Found(override val code: String, val sightings: List<ProductSighting>) : LookupResult
  /** A product's GTIN that no receipt on this phone has. */
  data class NeverBought(override val code: String) : LookupResult
  /** A scale label (D19): its code changes with every weighing, so it can't be looked up. */
  data class ScaleLabel(override val code: String) : LookupResult
  /** A store's own code or not a product code: nothing to look up. */
  data class NotAProduct(override val code: String) : LookupResult
}

/**
 * Looks a scanned code up in every caderneta [search] reaches. [search] takes the code's spellings and returns each
 * caderneta's matching lines, the open caderneta first; [ProductLookup.onPhone] makes the real one.
 */
class ProductLookup(private val search: suspend (codes: List<String>) -> List<Pair<String?, List<GtinLine>>>) {
  suspend fun find(raw: String, now: Long = System.currentTimeMillis()): LookupResult {
    val code = raw.trim()
    return when (Gtin.kind(code)) {
      CodeKind.SCALE_LABEL -> LookupResult.ScaleLabel(code)
      CodeKind.STORE_CODE, CodeKind.NOT_A_PRODUCT -> LookupResult.NotAProduct(code)
      CodeKind.PRODUCT -> {
        val found = search(Gtin.variants(code))
        val sightings = found.mapIndexedNotNull { i, (ledger, lines) -> ProductSighting.of(ledger, lines, now)?.let { i to it } }
          .sortedWith(compareBy({ it.first != 0 }, { -it.second.last.at })).map { it.second }
        if (sightings.isEmpty()) LookupResult.NeverBought(code) else LookupResult.Found(code, sightings)
      }
    }
  }

  companion object {
    /**
     * The open caderneta's [database], then every other caderneta with files on this phone (LocalCadernetas), each
     * opened and closed in turn. In the demo, only the demo: its receipts are invented and don't mix with real ones.
     */
    fun onPhone(context: Context, settings: AccountSettings, database: ReceiptDatabase): ProductLookup = ProductLookup { codes ->
      val open = settings.ledgerId()
      val here = listOf(open to database.spending().gtinLines(codes))
      if (settings.demo) return@ProductLookup here
      val local = LocalCadernetas(context, settings)
      val openFile = settings.localFiles().database
      here + local.others(open).filter { settings.filesFor(it).database != openFile }.map { id ->
        id to local.withDatabase(id) { it.spending().gtinLines(codes) }
      }
    }
  }
}
