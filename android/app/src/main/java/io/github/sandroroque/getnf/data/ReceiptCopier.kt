package io.github.sandroroque.getnf.data

import android.content.Context
import java.io.File

/**
 * Copies receipts scanned on this phone into another caderneta's files on this phone (D104), for someone keeping
 * their receipts when they leave, or any time. The source keeps everything. The copies are read and uploaded when
 * that caderneta is opened.
 */
class ReceiptCopier(private val context: Context, private val settings: AccountSettings) {
  /** Returns how many were new there. */
  suspend fun copy(from: ReceiptStore, receipts: List<Receipt>, toLedger: String): Int {
    val files = settings.filesFor(toLedger)
    val database = ReceiptDatabase.open(context, files.database)
    try {
      val target = ReceiptStore(File(context.filesDir, files.receipts), database).apply { load() }
      return receipts.count { target.importCopy(it, from.dir(it.chave)) }
    } finally {
      database.close()
    }
  }
}
