package io.github.sandroroque.getnf.data

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Export, wipe, restore and compare, on a real file database and synthetic receipts (D47). */
@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
  @get:Rule val tmp = TemporaryFolder()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val chave = "42260611222333000181651090000012341123456789"

  private fun open(file: File) = Room.databaseBuilder(context, ReceiptDatabase::class.java, file.path).build()

  private suspend fun snapshot(db: ReceiptDatabase, receipts: File) = listOf(
    db.receipts().receipts(), db.receipts().photos(), db.receipts().sessions(), db.catalog().lines(), db.catalog().products(),
    db.catalog().storeItems(), db.catalog().categories(), db.catalog().parsed(),
    receipts.walkTopDown().filter { it.isFile }.map { it.relativeTo(receipts).path to it.readBytes().toList() }.sortedBy { it.first }.toList(),
  )

  @Test fun exportedDataComesBackIdentical() = runBlocking {
    val files = tmp.newFolder("files"); val dbFile = File(tmp.root, "databases/getnf.db").apply { parentFile!!.mkdirs() }
    val receiptsRoot = File(files, "receipts")
    val db = open(dbFile)
    val store = ReceiptStore(receiptsRoot, db)
    store.open(chave, "qr")
    store.addPhoto(chave, store.newPhotoFile(chave).apply { writeBytes(ByteArray(2048) { it.toByte() }) })
    store.savePage(chave, "https://x", "<html>página</html>")
    store.markUploaded(chave, store.get(chave)!!.version, "r1")
    val catalog = db.catalog()
    catalog.insertCategory(CategoryRow("c", "food", "Comida", null, 1))
    catalog.insertProduct(ProductRow("p", null, "c", null, null, null, createdAt = 1, categorySource = "user", dirty = true))
    catalog.insertStoreItem(StoreItemRow("s", "gtin", "7890000000017", "", "CAFE", null, null, "p"))
    catalog.putIssuer(IssuerRow("11222333000181", "M"))
    catalog.putParsed(ReceiptParsedRow(chave, "11222333000181", 1_000, 100, 0, 100, 1, null))
    catalog.insertLines(listOf(ReceiptLineRow(chave, 1, "CAFE", "7890000000017", 10_000, "UN", 10_000, 100, "s", discountCents = 0, discountSource = "prorated")))
    val before = snapshot(db, receiptsRoot)

    val service = BackupService(context, db, receiptsRoot, dbFile, files)
    val zip = File(tmp.root, "backup.zip")
    val m = service.export(Uri.fromFile(zip)) { _, _ -> }
    assertEquals(listOf(1, 1, 1, 1), listOf(m.receipts, m.photos, m.lines, m.products))
    db.close()

    // Wipe: as if the phone were lost and the app reinstalled.
    receiptsRoot.deleteRecursively(); dbFile.parentFile!!.listFiles()!!.forEach { it.delete() }
    assertEquals(m, service.inspect(Uri.fromFile(zip)))
    service.stage()
    PendingRestore(files, dbFile).applyIfReady()

    val restored = open(dbFile)
    assertEquals(before, snapshot(restored, receiptsRoot))
    restored.close()
  }
  /** T-19 (D191): a page fetched again while the archive is written. */
  @Test fun reviewRound2ConcurrentPageWriteMustProduceCoherentBackup() = runBlocking {
    val files = tmp.newFolder("concurrent-files")
    val dbFile = File(tmp.root, "concurrent-db/getnf.db").apply { parentFile!!.mkdirs() }
    val root = File(files, "receipts")
    val db = open(dbFile)
    try {
      val store = ReceiptStore(root, db)
      store.open(chave, "synthetic-qr")
      val beforeUrl = "https://example.invalid/before"
      val afterUrl = "https://example.invalid/after"
      val beforeHtml = "<html>synthetic before 10.00</html>"
      val afterHtml = "<html>synthetic after 20.00</html>"
      store.savePage(chave, beforeUrl, beforeHtml)
      val service = BackupService(context, db, root, dbFile, files, store)
      val zip = File(tmp.root, "concurrent.zip")
      var wrote = false
      service.export(Uri.fromFile(zip)) { done, _ ->
        // Real writer at a deterministic boundary: snapshot has been archived, page has not.
        if (done == 1) {
          runBlocking { store.savePage(chave, afterUrl, afterHtml) }
          wrote = true
        }
      }
      check(wrote)
      BackupArchive.inspect(zip, ReceiptDatabase.VERSION) // passes integrity hashes
      val extracted = File(tmp.root, "concurrent-extracted")
      BackupArchive.extract(zip, extracted, ReceiptDatabase.VERSION)
      val restored = open(File(extracted, BackupArchive.DATABASE))
      try {
        val row = restored.receipts().receipts().single()
        val page = File(extracted, "receipts/$chave/${row.pageFile}").readText()
        val observed = row.pageUrl to page
        org.junit.Assert.assertTrue("backup must contain one coherent state; observed $observed",
          observed == (beforeUrl to beforeHtml) || observed == (afterUrl to afterHtml))
      } finally { restored.close() }
    } finally { db.close() }
  }

  /** T-18 (D191): a photo the database names but the phone lacks is reported, not silently left out. */
  @Test fun reviewRound2ExportMustNotPromiseMissingPhoto() = runBlocking {
    val files = tmp.newFolder("missing-files")
    val dbFile = File(tmp.root, "missing-db/getnf.db").apply { parentFile!!.mkdirs() }
    val receipts = File(files, "receipts")
    val db = open(dbFile)
    try {
      val store = ReceiptStore(receipts, db)
      store.open(chave, "qr")
      val photo = store.newPhotoFile(chave).apply { writeBytes(byteArrayOf(1,2,3)) }
      store.addPhoto(chave, photo)
      check(photo.delete()) // simulate a missing source file, never a real receipt
      val service = BackupService(context, db, receipts, dbFile, files)
      val zip = File(tmp.root, "missing-photo.zip")
      val m = service.export(Uri.fromFile(zip)) { _, _ -> }
      assertEquals("export must report the missing photo, not claim a complete backup", listOf("receipts/$chave/${photo.name}"), m.missing)
      assertEquals(m, BackupArchive.inspect(zip, ReceiptDatabase.VERSION))
    } finally { db.close() }
  }

  /** T-16 (D193): a real export and restore, then a sync with another caderneta: reported, nothing sent. */
  @Test fun reviewRound2RestoredArchiveMustSendReceiptToNewLedger() = runBlocking {
    val files = tmp.newFolder("files"); val dbFile = File(tmp.root, "databases/getnf.db").apply { parentFile!!.mkdirs() }
    val receiptsRoot = File(files, "receipts")
    val db = open(dbFile)
    val store = ReceiptStore(receiptsRoot, db)
    store.open(chave, "qr")
    store.addPhoto(chave, store.newPhotoFile(chave).apply { writeBytes(ByteArray(2048) { it.toByte() }) })
    store.savePage(chave, "https://x", "<html>página</html>")
    store.markUploaded(chave, store.get(chave)!!.version, "r1")
    val catalog = db.catalog()
    catalog.insertCategory(CategoryRow("c", "food", "Comida", null, 1))
    catalog.insertProduct(ProductRow("p", null, "c", null, null, null, createdAt = 1, categorySource = "user", dirty = true))
    catalog.insertStoreItem(StoreItemRow("s", "gtin", "7890000000017", "", "CAFE", null, null, "p"))
    catalog.putIssuer(IssuerRow("11222333000181", "M"))
    catalog.putParsed(ReceiptParsedRow(chave, "11222333000181", 1_000, 100, 0, 100, 1, null))
    catalog.insertLines(listOf(ReceiptLineRow(chave, 1, "CAFE", "7890000000017", 10_000, "UN", 10_000, 100, "s", discountCents = 0, discountSource = "prorated")))
    val before = snapshot(db, receiptsRoot)

    val service = BackupService(context, db, receiptsRoot, dbFile, files)
    val zip = File(tmp.root, "backup.zip")
    val m = service.export(Uri.fromFile(zip)) { _, _ -> }
    assertEquals(listOf(1, 1, 1, 1), listOf(m.receipts, m.photos, m.lines, m.products))
    db.close()

    // Wipe: as if the phone were lost and the app reinstalled.
    receiptsRoot.deleteRecursively(); dbFile.parentFile!!.listFiles()!!.forEach { it.delete() }
    assertEquals(m, service.inspect(Uri.fromFile(zip)))
    service.stage()
    PendingRestore(files, dbFile).applyIfReady()

    val restored = open(dbFile)
    assertEquals(before, snapshot(restored, receiptsRoot))
    var receiptsSent = 0
    val remote = object : io.github.sandroroque.getnf.sync.SyncRemote {
      override val ledgerId = "new-ledger"
      override val userId = "new-user"
      override fun rpc(function: String, args: kotlinx.serialization.json.JsonObject) = kotlinx.serialization.json.JsonNull
      override fun select(table: String, filters: List<Pair<String,String>>) = emptyList<kotlinx.serialization.json.JsonObject>()
      override fun upsert(table: String, rows: List<kotlinx.serialization.json.JsonObject>, onConflict: String, ignoreDuplicates: Boolean) { if (table == "receipt") receiptsSent += rows.size }
      override fun delete(table: String, filters: List<Pair<String,String>>) {}
      override fun upload(path: String, bytes: ByteArray, type: String) {}
      override fun download(path: String, target: File) { error("no remote objects") }
      override fun deleteObjects(paths: List<String>) {}
    }
    val restoredStore = ReceiptStore(receiptsRoot, restored).apply { load() }
    // No caderneta recorded, like a database from before D193: its sent receipt (markUploaded) isn't in the destination.
    val report = io.github.sandroroque.getnf.sync.SyncEngine(restored, restoredStore, { _, _ -> byteArrayOf() }, log = {}).sync(remote)
    org.junit.Assert.assertTrue("a restored archive of another caderneta is reported", report.foreign)
    assertEquals("and sends nothing to it", 0, receiptsSent)
    restored.close()
  }
}
