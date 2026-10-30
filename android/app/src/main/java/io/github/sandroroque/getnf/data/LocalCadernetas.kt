package io.github.sandroroque.getnf.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * The other cadernetas this phone holds files for (D104), and bringing their receipts into the open one (D126): the
 * pull side of [ReceiptCopier]. Only receipts scanned on this phone, as for copying out: what other members scanned
 * stays in the caderneta they shared it with.
 */
class LocalCadernetas(private val context: Context, private val settings: AccountSettings) {
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
      val here = store.receipts.value.filter { it.scannedHere }.map { it.chave }.toSet()
      Source(id, database.lists().receipts().first().filter { it.chave in here }).takeIf { it.receipts.isNotEmpty() }
    }
  }

  /** Copies [chaves] from [ledger] into [target] (the open caderneta's store); returns how many were new there. */
  suspend fun copyInto(target: ReceiptStore, ledger: String, chaves: Set<String>): Int = withLedger(ledger) { _, store ->
    store.receipts.value.filter { it.chave in chaves && it.scannedHere }.count { target.importCopy(it, store.dir(it.chave)) }
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
