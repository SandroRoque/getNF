package io.github.sandroroque.getnf.sync

import android.content.Context
import android.util.Log
import io.github.sandroroque.getnf.data.AccountSettings
import io.github.sandroroque.getnf.data.PhotoContribution
import io.github.sandroroque.getnf.data.ReceiptDatabase
import io.github.sandroroque.getnf.data.ReceiptParser
import io.github.sandroroque.getnf.data.ReceiptStore
import java.io.File

/**
 * Syncs the cadernetas this phone has files for but doesn't have open (D104): receipts copied into one, or scanned
 * there before switching, go up without opening it, and opening it later finds it current. Each opens its own
 * database just for its sync. A caderneta the person joined but never opened on this phone isn't downloaded here; it
 * arrives when opened.
 */
class OtherLedgers(
  private val context: Context,
  private val settings: AccountSettings,
  private val archiver: PhotoArchiver,
  private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
  /** Ledgers on this phone besides [open] that the person still belongs to. */
  fun onPhone(mine: List<Ledger>, open: String?): List<String> =
    mine.map { it.id }.filter { it != open && context.getDatabasePath(settings.filesFor(it).database).isFile }

  /** Network trouble ([java.io.IOException]) stops the run, for the worker to retry; a refusal skips that caderneta. */
  suspend fun sync(client: SupabaseClient, session: SupabaseSession, mine: List<Ledger>, open: String?) {
    for (ledger in onPhone(mine, open)) {
      val files = settings.filesFor(ledger)
      val database = ReceiptDatabase.open(context, files.database)
      try {
        val dir = File(context.filesDir, files.receipts)
        val store = ReceiptStore(dir, database).apply { load() }
        val engine = SyncEngine(database, store, archiver, contribute = PhotoContribution(context, database)::wants, log = { log("$ledger: $it") })
        val remote = SupabaseRemote(client, session, ledger)
        engine.sync(remote)
        if (ReceiptParser(database, dir).parsePending(store.receipts.value) > 0) engine.sync(remote)
      } catch (e: SupabaseException) {
        if (e.status == 401) throw e
        log("$ledger: refused: ${e.message}")
      } finally {
        database.close()
      }
    }
  }

  private companion object { const val TAG = "OtherLedgers" }
}
