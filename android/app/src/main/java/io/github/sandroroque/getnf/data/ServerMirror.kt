package io.github.sandroroque.getnf.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import java.util.UUID
import kotlin.math.roundToLong

/** What the server holds, as read by PocketBaseClient.fetchSnapshot. Server ids are PocketBase record ids. */
data class ServerSnapshot(
  val categories: List<ServerCategory>,
  val tags: List<ServerTag>,
  val products: List<ServerProduct>,
  val receipts: List<ServerReceipt>,
  val items: List<ServerItem>,
)
data class ServerCategory(val id: String, val path: String, val label: String, val order: Int, val parentId: String?)
data class ServerTag(val id: String, val slug: String, val description: String)
data class ServerProduct(
  val id: String,
  /** `gtin:<code>` or `store:<cnpj>:<code>` (server/pb_hooks/lib/derive.js). */
  val key: String,
  val description: String,
  val categoryId: String?,
  val tagIds: List<String>,
  val sizeQty: Double?,
  val sizeUnit: String?,
  val sizeSource: String?,
)
data class ServerReceipt(
  val id: String,
  val chave: String,
  val qrUrl: String?,
  val capturedAt: Long?,
  val pageUrl: String?,
  val pageFetchedAt: Long?,
  /** Stored file name of the page, if any. */
  val page: String?,
  /** Legacy `receipts.photos` file names (D42). */
  val photos: List<String>,
  val storeName: String?,
  val storeCnpj: String?,
  val issuedAt: Long?,
  val totalGross: Double?,
  val discount: Double?,
  val totalPaid: Double?,
  val parserVersion: Int?,
  val parseError: String?,
  /** How many records it has in the append-only receipt_photos. */
  val evidencePhotos: Int = 0,
)
data class ServerItem(
  val receiptId: String,
  val line: Int,
  val productId: String?,
  val description: String,
  val code: String,
  val qty: Double,
  val unit: String?,
  val unitPrice: Double,
  val gross: Double,
  val discount: Double,
  val discountSource: String?,
)

/**
 * The copy of the server's catalog and parsed lines (D46). The server is still where products are categorized, so
 * each refresh makes the local catalog match it, in one transaction. Local ids stay stable across refreshes:
 * categories match by path, tags by slug, store items by code, lines by (chave, line).
 *
 * Since D49 the phone parses its own pages: receipts ReceiptParser has written are left alone, and store items their
 * lines use are kept even when the server doesn't know them yet. The server still decides a known store item's
 * product, category, tags and manual size.
 */
class ServerMirror(
  private val database: ReceiptDatabase,
  private val now: () -> Long = System::currentTimeMillis,
  private val newId: () -> String = { UUID.randomUUID().toString() },
) {
  private val dao = database.catalog()

  /**
   * Mirrors [snapshot]. Receipts the phone doesn't have are skipped here: ReceiptStore.adoptRemote creates them
   * first, with their files. Returns how many lines were written.
   */
  suspend fun apply(snapshot: ServerSnapshot): Int = database.useWriterConnection { it.immediateTransaction {
    val local = database.receipts().receipts().map { it.chave }.toSet()
    val phoneParsed = dao.parsed().filter { it.source == ReceiptParsedRow.SOURCE_PHONE }.map { it.chave }.toSet()

    // Categories, parents first (a path's parent is always shorter).
    val oldCategories = dao.categories().associateBy { it.seedPath }
    val serverPaths = snapshot.categories.associate { it.id to it.path }
    val categoryIds = HashMap<String, String>() // server id -> local id
    val byPath = HashMap<String, String>()
    for (c in snapshot.categories.sortedBy { it.path.count { ch -> ch == '.' } }) {
      val id = oldCategories[c.path]?.id ?: newId()
      val row = CategoryRow(id, c.path, c.label, c.parentId?.let { serverPaths[it] }?.let { byPath[it] }, c.order)
      if (dao.updateCategory(row) == 0) dao.insertCategory(row)
      categoryIds[c.id] = id; byPath[c.path] = id
    }

    val oldTags = dao.tags().associateBy { it.slug }
    val tagIds = snapshot.tags.associate { t ->
      val row = TagRow(oldTags[t.slug]?.id ?: newId(), t.slug, t.description)
      if (dao.updateTag(row) == 0) dao.insertTag(row)
      t.id to row.id
    }

    // Products: one store item and one product per server product, today 1:1.
    val oldItems = dao.storeItems().associateBy { Triple(it.codeKind, it.code, it.storeCnpj) }
    val oldProducts = dao.products().associateBy { it.id }
    val storeItemIds = HashMap<String, String>() // server product id -> store item id
    val productTags = mutableListOf<ProductTagRow>()
    for (p in snapshot.products) {
      val key = parseKey(p.key) ?: continue
      val existing = oldItems[key]
      val productId = existing?.productId ?: newId()
      // "Manual" without a quantity is a size removed by hand: no size, and the description's reading doesn't count.
      val manual = p.sizeSource == "manual"
      val manualSize = manual && p.sizeQty != null && p.sizeQty > 0
      val product = ProductRow(
        productId, name = null, categoryId = p.categoryId?.let { categoryIds[it] },
        sizeE6 = if (manualSize) e6(p.sizeQty!!) else null, sizeUnit = if (manualSize) p.sizeUnit?.ifEmpty { null } else null,
        sizeSource = if (manual) "manual" else null, createdAt = oldProducts[productId]?.createdAt ?: now(),
      )
      if (dao.updateProduct(product) == 0) dao.insertProduct(product)
      val parsed = p.sizeSource == "parsed" && p.sizeQty != null
      // A manual size hides the server's parsed one; the phone's own reading (ReceiptParser) stays.
      val item = StoreItemRow(
        existing?.id ?: newId(), key.first, key.second, key.third, p.description,
        parsedSizeE6 = if (parsed) e6(p.sizeQty!!) else existing?.parsedSizeE6,
        parsedSizeUnit = if (parsed) p.sizeUnit else existing?.parsedSizeUnit, productId = productId,
      )
      if (dao.updateStoreItem(item) == 0) dao.insertStoreItem(item)
      storeItemIds[p.id] = item.id
      p.tagIds.mapNotNullTo(productTags) { tagIds[it]?.let { tag -> ProductTagRow(productId, tag) } }
    }
    dao.deleteProductTags()
    dao.insertProductTags(productTags.distinct())

    // Receipts' parsed header and lines.
    val chaves = snapshot.receipts.associate { it.id to it.chave }
    val items = snapshot.items.groupBy { it.receiptId }
    var written = 0
    for (r in snapshot.receipts) {
      if (r.chave !in local || r.chave in phoneParsed) continue
      dao.deleteLines(r.chave)
      if (!r.storeCnpj.isNullOrEmpty()) dao.putIssuer(IssuerRow(r.storeCnpj, r.storeName.orEmpty()))
      dao.putParsed(ReceiptParsedRow(
        r.chave, r.storeCnpj?.ifEmpty { null }, r.issuedAt, r.totalGross?.let(::cents), r.discount?.let(::cents),
        r.totalPaid?.let(::cents), r.parserVersion, r.parseError?.ifEmpty { null },
      ))
      val lines = items[r.id].orEmpty().mapNotNull { i ->
        val storeItem = i.productId?.let { storeItemIds[it] } ?: return@mapNotNull null
        ReceiptLineRow(
          r.chave, i.line, i.description, i.code, e4(i.qty), i.unit, e4(i.unitPrice), cents(i.gross), storeItem,
          discountCents = cents(i.discount), discountSource = i.discountSource ?: "prorated",
        )
      }
      dao.insertLines(lines)
      written += lines.size
    }
    // The server no longer has it: drop the mirror's copy. Receipts stay; they're ReceiptStore's. So does the phone's parse.
    val serverChaves = chaves.values.toSet()
    for (chave in local - serverChaves - phoneParsed) { dao.deleteLines(chave); dao.deleteParsed(chave) }
    dao.deleteUnusedIssuers()
    val keptItems = storeItemIds.values.toSet() + dao.storeItemsInUse()
    dao.deleteStoreItems(dao.storeItems().map { it.id }.filter { it !in keptItems })
    val keptProducts = dao.storeItems().map { it.productId }.toSet()
    dao.deleteProducts(dao.products().map { it.id }.filter { it !in keptProducts })
    val keptTags = tagIds.values.toSet()
    dao.deleteTags(dao.tags().map { it.id }.filter { it !in keptTags })
    val keptCategories = categoryIds.values.toSet()
    dao.deleteCategories(dao.categories().map { it.id }.filter { it !in keptCategories })
    written
  } }

  companion object {
    /** `gtin:<code>` or `store:<cnpj>:<code>` to (kind, code, cnpj). */
    fun parseKey(key: String): Triple<String, String, String>? {
      val parts = key.split(':')
      return when {
        parts.size == 2 && parts[0] == "gtin" -> Triple("gtin", parts[1], "")
        parts.size == 3 && parts[0] == "store" -> Triple("store", parts[2], parts[1])
        else -> null
      }
    }
    fun cents(reais: Double): Long = (reais * 100).roundToLong()
    fun e4(x: Double): Long = (x * 10_000).roundToLong()
    fun e6(x: Double): Long = (x * 1_000_000).roundToLong()
  }
}
