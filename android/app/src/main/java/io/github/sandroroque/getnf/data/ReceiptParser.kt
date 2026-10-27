package io.github.sandroroque.getnf.data

import android.util.Log
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.sandroroque.getnf.parse.Derive
import io.github.sandroroque.getnf.parse.NfcePage
import io.github.sandroroque.getnf.parse.ParsedReceipt
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Reads a receipt's saved page into its header, lines and store items, on the phone (D49), with the same rules as the
 * server's ingest.js. A store item already known by its code keeps its product, and so its category; an unknown code
 * gets a new uncategorized product. Discounts read from a photo or set by hand survive a re-parse, matched by line
 * number. A page that can't be read keeps the receipt and its earlier lines, with the error recorded. Everything it
 * writes is marked dirty, for the next sync to push (D70).
 */
class ReceiptParser(
  private val database: ReceiptDatabase,
  private val root: File,
  private val now: () -> Long = System::currentTimeMillis,
  private val newId: () -> String = { UUID.randomUUID().toString() },
  private val log: (String, Throwable?) -> Unit = { message, error -> Log.i(TAG, message, error) },
  // Run on the IO dispatcher. A test swaps it to hold the read open.
  private val readPage: suspend (File) -> String = { it.readText(Charsets.UTF_8) },
) {
  private val dao = database.catalog()
  // One parse at a time: startup, a saved page and the upload worker can all ask at once.
  private val mutex = Mutex()

  sealed interface Outcome {
    data class Parsed(val lines: Int, val warnings: List<String>) : Outcome
    data class Failed(val error: String) : Outcome
  }

  /** Not parsed on the phone yet, parsed by an older parser, or the page was fetched again since. */
  suspend fun needsParse(receipt: Receipt): Boolean {
    if (receipt.pageFile == null) return false
    val parsed = dao.parsed(receipt.chave) ?: return true
    return parsed.source != ReceiptParsedRow.SOURCE_PHONE || parsed.parserVersion != NfcePage.VERSION ||
      parsed.pageFetchedAt != receipt.pageFetchedAt
  }

  /**
   * Parses every receipt that [needsParse]. Returns how many were parsed (successfully or not). Checked under the lock,
   * so startup and the upload worker running at once don't both read the same page.
   */
  suspend fun parsePending(receipts: List<Receipt>): Int {
    var n = 0
    for (r in receipts) mutex.withLock { if (needsParse(r)) { parseLocked(r); n++ } }
    return n
  }

  suspend fun parse(receipt: Receipt): Outcome = mutex.withLock { parseLocked(receipt) }

  private suspend fun parseLocked(receipt: Receipt): Outcome {
    val pageFile = requireNotNull(receipt.pageFile) { "no page for ${receipt.chave}" }
    val parsed = try {
      withContext(Dispatchers.IO) { NfcePage.parse(readPage(File(File(root, receipt.chave), pageFile))) }
    } catch (e: CancellationException) {
      // The caller went away (say, the upload worker was replaced): not something wrong with the page.
      throw e
    } catch (e: Exception) {
      val error = e.message ?: e.toString()
      record(receipt, error)
      log("could not read the page of ${receipt.chave}: $error", null)
      return Outcome.Failed(error)
    }
    if (parsed.chave != null && parsed.chave != receipt.chave) {
      val error = "page is for another receipt (${parsed.chave})"
      record(receipt, error)
      return Outcome.Failed(error)
    }
    val lines = write(receipt, parsed)
    log("parsed ${receipt.chave}: $lines lines, ${parsed.warnings.size} warning(s)", null)
    return Outcome.Parsed(lines, parsed.warnings)
  }

  /** The page couldn't be read: keep whatever was there (header, lines) and record why. */
  private suspend fun record(receipt: Receipt, error: String) = database.useWriterConnection { it.immediateTransaction {
    val old = dao.parsed(receipt.chave)
    dao.putParsed(
      (old ?: ReceiptParsedRow(receipt.chave, null, null, null, null, null, null, null)).copy(
        parserVersion = NfcePage.VERSION, parseError = error, source = ReceiptParsedRow.SOURCE_PHONE, pageFetchedAt = receipt.pageFetchedAt,
        dirty = true,
      ),
    )
  } }

  private suspend fun write(receipt: Receipt, p: ParsedReceipt): Int = database.useWriterConnection { it.immediateTransaction {
    val chave = receipt.chave
    val storeCnpj = p.store?.cnpj ?: ""
    p.store?.takeIf { it.cnpj.isNotEmpty() }?.let { dao.putIssuer(IssuerRow(it.cnpj, it.name, dirty = true)) }

    // What a person or a photo said about a line outlives the page's re-reading.
    val old = dao.lines(chave).associateBy { it.lineNo }
    val kept = old.filterValues { it.discountSource == "photo" || it.discountSource == "manual" }
    dao.deleteLines(chave)

    val lineCents = p.items.map { it.totalCents ?: 0 }
    val known = p.items.withIndex().mapNotNull { (i, item) -> kept[item.line]?.let { i to it.discountCents } }.toMap()
    val discounts = Derive.lineDiscounts(p.totals.discountCents, lineCents, known)

    val rows = p.items.mapIndexed { i, item ->
      val code = item.code ?: ""
      val key = Derive.codeKey(code, storeCnpj)
      val size = Derive.parseSize(item.description)
      val description = item.description ?: ""
      val existing = dao.storeItem(key.kind, key.code, key.storeCnpj)
      val storeItemId = if (existing != null) {
        // Keep the latest wording, like the server.
        val updated = existing.copy(description = description, parsedSizeE6 = size?.e6, parsedSizeUnit = size?.unit)
        if (updated != existing) dao.updateStoreItem(updated.copy(dirty = true))
        existing.id
      } else {
        val product = ProductRow(newId(), name = null, categoryId = null, sizeE6 = null, sizeUnit = null, sizeSource = null, createdAt = now(), dirty = true)
        dao.insertProduct(product)
        val item = StoreItemRow(newId(), key.kind, key.code, key.storeCnpj, description, size?.e6, size?.unit, product.id, dirty = true)
        dao.insertStoreItem(item)
        item.id
      }
      val previous = old[item.line]
      ReceiptLineRow(
        chave, item.line, description, code, item.qtyE4 ?: 0, item.unit, item.unitPriceE4 ?: 0, lineCents[i], storeItemId,
        productOverrideId = previous?.productOverrideId,
        discountCents = discounts[i].cents,
        discountSource = if (i in known) kept.getValue(item.line).discountSource else discounts[i].source.value,
        discountReview = if (i in known) kept.getValue(item.line).discountReview else "unreviewed",
        dirty = true,
      )
    }
    dao.insertLines(rows)

    val t = p.totals
    dao.putParsed(ReceiptParsedRow(
      chave, storeCnpj.ifEmpty { null }, p.issuedAt, t.grossCents, t.discountCents,
      paidCents = t.payableCents ?: (t.grossCents - t.discountCents),
      parserVersion = NfcePage.VERSION,
      // The server's wording, so both sides read the same.
      parseError = if (p.warnings.isEmpty()) null else "warnings: " + p.warnings.joinToString("; "),
      source = ReceiptParsedRow.SOURCE_PHONE, pageFetchedAt = receipt.pageFetchedAt, dirty = true,
      payments = Json.encodeToString(p.payments.map { buildJsonObject { put("method", it.method); put("cents", it.amountCents) } }),
      changeCents = t.changeCents,
      taxFederalCents = p.taxesEstimate?.federalCents, taxStateCents = p.taxesEstimate?.stateCents,
      taxMunicipalCents = p.taxesEstimate?.municipalCents, taxTotalCents = p.taxesEstimate?.totalCents,
    ))
    dao.deleteUnusedIssuers()
    rows.size
  } }

  private companion object { const val TAG = "ReceiptParser" }
}
