package io.github.sandroroque.getnf.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * The other cadernetas this phone holds files for (D104), and bringing their receipts into the open one (D126): the
 * pull side of [ReceiptCopier]. Only receipts this person scanned, here or on another phone (D128), as for copying
 * out: what other members scanned stays in the caderneta they shared it with.
 */
class LocalCadernetas(private val context: Context, private val settings: AccountSettings) {
  private companion object {
    val DECIDED = setOf(REVIEW_CONFIRMED, REVIEW_CORRECTED)
  }

  /** A caderneta on this phone and the receipts scanned here that it holds, newest first. */
  class Source(val ledger: String, val receipts: List<ReceiptListItem>)

  /** The ids of cadernetas with a database on this phone, other than [open] (and never the demo's). */
  fun others(open: String?): List<String> {
    val names = context.databaseList().toSet()
    val legacy = settings.legacyLedger()?.takeIf { ReceiptDatabase.NAME in names }
    val own = names.filter { it.startsWith("getnf-") && it.endsWith(".db") && it != LocalFiles.DEMO.database }
      .map { it.removePrefix("getnf-").removeSuffix(".db") }
    return (listOfNotNull(legacy) + own).distinct().filter { it != open }
  }

  /** Each other caderneta's receipts scanned here; cadernetas without any are left out. */
  suspend fun sources(open: String?): List<Source> = others(open).mapNotNull { id ->
    withLedger(id) { database, store ->
      val mine = database.lists().scannedByMe().toSet()
      Source(id, database.lists().receipts().first().filter { it.chave in mine }).takeIf { it.receipts.isNotEmpty() }
    }
  }

  /** Copies [chaves] from [ledger] into [target] (the open caderneta's store); returns how many were new there. */
  suspend fun copyInto(target: ReceiptStore, ledger: String, chaves: Set<String>): Int = withLedger(ledger) { database, store ->
    val mine = store.receipts.value.map { it.chave }.toSet().intersect(database.lists().scannedByMe().toSet())
    store.receipts.value.filter { it.chave in chaves && it.chave in mine }.count { target.importCopy(it, store.dir(it.chave)) }
  }

  /**
   * After [copyInto] and the open caderneta's parse: the categories the person confirmed or corrected in [ledger] go to
   * the same items in [target] (D127), found by store item, whose id comes from the item's code in every caderneta.
   * A seeded category is found by its taxonomy path; one the person made, by its labels from the top, created where
   * missing. A product already decided by hand in [target] is left alone. Returns how many products got a category.
   */
  suspend fun bringCategories(target: ReceiptDatabase, editor: CatalogEditor, ledger: String, chaves: Set<String>): Int =
    withLedger(ledger) { source, _ ->
      val from = source.edit()
      val to = target.edit()
      val categories = from.categoriesNow().associateBy { it.id }
      val seen = mutableSetOf<String>()
      var count = 0
      for (chave in chaves) for (line in from.lines(chave)) {
        if (!seen.add(line.storeItemId)) continue
        val product = from.storeItemNow(line.storeItemId)?.let { from.productNow(it.productId) } ?: continue
        if (product.deleted || product.categoryReview !in DECIDED) continue
        val category = product.categoryId?.let(categories::get) ?: continue
        val there = to.storeItemNow(line.storeItemId)?.let { to.productNow(it.productId) } ?: continue
        if (there.categoryReview in DECIDED && there.categorySource == SOURCE_USER) continue
        val chain = generateSequence(category) { it.parentId?.let(categories::get) }.toList().reversed()
        editor.setCategory(there.id, categoryIn(to, editor, chain))
        count++
      }
      count
    }

  /** The category in [to] that matches [chain] (root first), making the missing part of a hand-made branch. */
  private suspend fun categoryIn(to: EditDao, editor: CatalogEditor, chain: List<CategoryRow>): String {
    chain.last().seedPath?.let { path -> to.categoriesNow().find { it.seedPath == path }?.let { return it.id } }
    var parent: String? = null
    for (c in chain) {
      val all = to.categoriesNow()
      val match = c.seedPath?.let { path -> all.find { it.seedPath == path } }
        ?: all.find { it.parentId == parent && it.label.trim().equals(c.label.trim(), ignoreCase = true) }
      parent = match?.id ?: editor.createCategory(parent, c.label, c.description)
    }
    return parent!!
  }

  /** Opens [id]'s database alone (no receipt files) for [block], then closes it. */
  internal suspend fun <T> withDatabase(id: String, block: suspend (ReceiptDatabase) -> T): T {
    val database = ReceiptDatabase.open(context, settings.filesFor(id).database)
    try {
      return block(database)
    } finally {
      database.close()
    }
  }

  private suspend fun <T> withLedger(id: String, block: suspend (ReceiptDatabase, ReceiptStore) -> T): T {
    val files = settings.filesFor(id)
    val database = ReceiptDatabase.open(context, files.database)
    try {
      return block(database, ReceiptStore(File(context.filesDir, files.receipts), database).apply { load() })
    } finally {
      database.close()
    }
  }
}
