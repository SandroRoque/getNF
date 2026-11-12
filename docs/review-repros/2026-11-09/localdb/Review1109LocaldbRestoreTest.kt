package io.github.sandroroque.getnf.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Review 2026-11-09, area localdb. Each test FAILS while the defect it names is present; its message states the
 * expected behaviour. Synthetic data only (the chave is made up).
 */
class Review1109LocaldbRestoreTest {
  @get:Rule val tmp = TemporaryFolder()
  private val chave = "42260611222333000181651090000012341123456789"

  private fun archive(dbBytes: ByteArray, schema: Int = ReceiptDatabase.VERSION): File {
    val src = tmp.newFolder()
    val db = File(src, "db").apply { writeBytes(dbBytes) }
    val photo = File(src, "a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val zip = tmp.newFile()
    zip.outputStream().use { out ->
      BackupArchive.write(out, linkedMapOf(BackupArchive.DATABASE to db, "receipts/$chave/a.jpg" to photo)) {
        BackupManifest(appVersion = "x", schemaVersion = schema, createdAt = 1, receipts = 1, photos = 1, lines = 0, products = 0, files = it)
      }
    }
    return zip
  }

  /**
   * inspect() checks the manifest and hashes only: the database entry is never opened. A file that is not SQLite, or
   * a SQLite file whose user_version is newer than the manifest says, passes inspect and stage; the swap then happens
   * before Room opens it (GetNFApp.kt:99-107), Room throws in the startup coroutine, the app crashes on every start
   * (READY is already gone, so there is no retry and no roll-back), and the original data sits in restore/previous
   * with no way back short of "clear data", which deletes it too.
   */
  @Test fun aBackupWhoseDatabaseIsNotADatabaseIsRefusedBeforeAnythingChanges() {
    val zip = archive("this is not an SQLite file".toByteArray())
    try {
      BackupArchive.inspect(zip, ReceiptDatabase.VERSION)
      fail("inspect must refuse a backup whose db/getnf.db is not an SQLite database of a version <= ${ReceiptDatabase.VERSION} (check the header and PRAGMA user_version), not accept it")
    } catch (_: BackupException) {}
  }

  /** The swap keeps the original in restore/previous, but nothing ever puts it back when the restored data can't open. */
  @Test fun aRestoredDatabaseThatFailsToOpenIsRolledBackOnTheNextStart() {
    val files = tmp.newFolder("files")
    val db = File(tmp.root, "databases/getnf.db").apply { parentFile!!.mkdirs(); writeText("ORIGINAL") }
    val pending = PendingRestore(files, db)
    BackupArchive.extract(archive("unopenable".toByteArray()), pending.staged, ReceiptDatabase.VERSION)
    pending.markReady()
    pending.applyIfReady()
    // Start 1 crashes opening the restored database: cleanUp() is never reached. Start 2:
    assertFalse(PendingRestore(files, db).applyIfReady())
    assertEquals("after a start that never got the restored database open (cleanUp not reached), the next start must " +
      "roll back to restore/previous instead of crashing on the same file forever", "ORIGINAL", db.readText())
  }
}
