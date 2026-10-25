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
}
