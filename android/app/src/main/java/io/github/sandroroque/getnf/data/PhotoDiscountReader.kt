package io.github.sandroroque.getnf.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.sandroroque.getnf.discounts.PhotoDiscounts
import io.github.sandroroque.getnf.discounts.PhotoText
import io.github.sandroroque.getnf.parse.Derive
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Reads a receipt's per-item discounts from its photos and applies them (D54). OCR text is cached next to each photo
 * (`<photo>.ocr1.json`), so reading again is quick. Discounts found become `photo` (unreviewed); the rest of the
 * receipt's discount is prorated over the other lines. A discount set by hand is never replaced. On a conflict, or
 * when nothing is found, the lines stay as they were. It runs by itself once a page is read ([readIfPending], D101).
 */
class PhotoDiscountReader(
  private val database: ReceiptDatabase,
  private val root: File,
  /** Blocking OCR of one photo; PhotoOcr on the device. */
  private val ocr: (File) -> PhotoText,
) {
  private val dao = database.catalog()
  private val json = Json { ignoreUnknownKeys = true }

  data class Outcome(val result: PhotoDiscounts.Result, val applied: Boolean, val photos: Int)

  suspend fun read(receipt: Receipt): Outcome {
    val parsed = requireNotNull(dao.parsed(receipt.chave)) { "receipt not read yet" }
    val lines = dao.lines(receipt.chave)
    val items = lines.map { PhotoDiscounts.Item(it.lineNo, it.code, it.description, it.grossCents) }
    val texts = withContext(Dispatchers.Default) { receipt.photos.map { text(receipt.chave, it) } }
    val result = PhotoDiscounts.read(texts, items, parsed.discountCents ?: 0)
    val apply = result.verdict == PhotoDiscounts.Verdict.COMPLETE || result.verdict == PhotoDiscounts.Verdict.PARTIAL
    if (apply) write(receipt.chave, parsed.discountCents ?: 0, result.discounts)
    return Outcome(result, apply, texts.size)
  }

  /**
   * Reads the photos' discounts by itself (D101) when there's something to find and nothing found yet: the receipt has
   * a discount and photos, and every line's share is still the prorated one. Null when there's nothing to do.
   */
  suspend fun readIfPending(receipt: Receipt): Outcome? {
    if (receipt.photos.isEmpty() || receipt.pageFile == null) return null
    val parsed = dao.parsed(receipt.chave) ?: return null
    if ((parsed.discountCents ?: 0) <= 0) return null
    val lines = dao.lines(receipt.chave)
    if (lines.isEmpty() || lines.any { it.discountSource == "photo" || it.discountSource == "manual" }) return null
    return read(receipt)
  }

  private fun text(chave: String, photo: String): PhotoText {
    val file = File(File(root, chave), photo)
    val cache = File(file.path + CACHE_SUFFIX)
    runCatching { return json.decodeFromString<PhotoText>(cache.readText()) }
    return ocr(file).also { runCatching { cache.writeText(json.encodeToString(it)) } }
  }

  private suspend fun write(chave: String, totalCents: Long, found: List<PhotoDiscounts.Found>) =
    database.useWriterConnection { it.immediateTransaction {
      val lines = dao.lines(chave)
      val read = found.associate { it.lineNo to it.cents }
      // Known: what a person set, then what the photos say. Everything else shares what's left.
      val known = lines.withIndex().mapNotNull { (i, l) ->
        when {
          l.discountSource == "manual" -> i to l.discountCents
          l.lineNo in read -> i to read.getValue(l.lineNo)
          else -> null
        }
      }.toMap()
      val discounts = Derive.lineDiscounts(totalCents, lines.map { it.grossCents }, known)
      dao.deleteLines(chave)
      dao.insertLines(lines.mapIndexed { i, l ->
        when {
          l.discountSource == "manual" -> l
          l.lineNo in read -> l.copy(discountCents = discounts[i].cents, discountSource = "photo", discountReview = "unreviewed", dirty = true)
          else -> l.copy(discountCents = discounts[i].cents, discountSource = discounts[i].source.value, discountReview = "unreviewed", dirty = true)
        }
      })
    } }

  companion object { const val CACHE_SUFFIX = ".ocr1.json" }
}
