package io.github.sandroroque.getnf.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The backup archive and the restart swap (D47), with plain files. */
class BackupTest {
  @get:Rule val tmp = TemporaryFolder()
  private val chave = "42260611222333000181651090000012341123456789"

  private fun archive(schema: Int = 2): File {
    val src = tmp.newFolder()
    val db = File(src, "db").apply { writeText("database") }
    val photo = File(src, "a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val page = File(src, "page.html").apply { writeText("<html/>") }
    val zip = tmp.newFile()
    zip.outputStream().use { out ->
      BackupArchive.write(out, linkedMapOf(BackupArchive.DATABASE to db, "receipts/$chave/a.jpg" to photo, "receipts/$chave/page.html" to page)) {
        BackupManifest(appVersion = "1.0", schemaVersion = schema, createdAt = 5, receipts = 1, photos = 1, lines = 0, products = 0, files = it)
      }
    }
    return zip
  }

  private fun assertRejected(zip: File, maxSchema: Int = 2) {
    try { BackupArchive.inspect(zip, maxSchema); fail("accepted") } catch (_: BackupException) {}
  }

  /** Rewrites [zip], changing one entry's bytes (or dropping it when [bytes] is null). */
  private fun tamper(zip: File, path: String, bytes: ByteArray?): File {
    val out = tmp.newFile()
    ZipFile(zip).use { z ->
      ZipOutputStream(out.outputStream()).use { o ->
        for (e in z.entries()) {
          val data = if (e.name == path) bytes ?: continue else z.getInputStream(e).readBytes()
          o.putNextEntry(ZipEntry(e.name)); o.write(data); o.closeEntry()
        }
      }
    }
    return out
  }

  @Test fun roundTripExtractsEveryFileIntact() {
    val zip = archive()
    val m = BackupArchive.inspect(zip, 2)
    assertEquals(3, m.files.size)
    val target = tmp.newFolder()
    BackupArchive.extract(zip, target, 2)
    assertEquals("database", File(target, BackupArchive.DATABASE).readText())
    assertEquals(listOf<Byte>(1, 2, 3), File(target, "receipts/$chave/a.jpg").readBytes().toList())
  }

  @Test fun corruptMissingNewerOrForeignArchivesAreRejected() {
    val zip = archive()
    assertRejected(tamper(zip, "receipts/$chave/a.jpg", byteArrayOf(9)))
    assertRejected(tamper(zip, "receipts/$chave/a.jpg", null))
    assertRejected(tamper(zip, BackupArchive.MANIFEST, null))
    assertRejected(tamper(zip, BackupArchive.MANIFEST, "{".toByteArray()))
    assertRejected(archive(schema = 3))
    // An older schema is fine: Room migrates it when the restored database opens.
    BackupArchive.inspect(archive(schema = 1), 2)
  }

  @Test fun pathsOutsideReceiptDirectoriesAreRefused() {
    val evil = File(tmp.root, "x").apply { writeText("x") }
    for (path in listOf("../outside", "receipts/$chave/../../x", "/abs", "receipts/123/a.jpg")) {
      try {
        BackupArchive.write(tmp.newFile().outputStream(), mapOf(path to evil)) { error("unreachable") }
        fail("wrote $path")
      } catch (_: IllegalArgumentException) {}
    }
  }

  private fun live(files: File, db: File) {
    File(files, "receipts/$chave").mkdirs(); File(files, "receipts/$chave/old.jpg").writeText("old")
    db.parentFile!!.mkdirs(); db.writeText("old db"); File(db.path + "-wal").writeText("old wal")
  }

  @Test fun stagedRestoreIsSwappedInAndThePreviousDataKeptUntilCleanUp() {
    val files = tmp.newFolder("files"); val db = File(tmp.root, "databases/getnf.db")
    live(files, db)
    val restore = PendingRestore(files, db)
    BackupArchive.extract(archive(), restore.staged, 2)
    assertFalse(restore.applyIfReady()) // not marked ready: nothing happens
    restore.markReady()
    assertTrue(restore.applyIfReady())
    assertEquals("database", db.readText())
    assertFalse(File(db.path + "-wal").exists())
    assertEquals(listOf("a.jpg", "page.html"), File(files, "receipts/$chave").list()!!.sorted())
    assertEquals("old", File(files, "restore/previous/receipts/$chave/old.jpg").readText())
    assertFalse(restore.applyIfReady())
    restore.cleanUp()
    assertFalse(File(files, "restore/previous").exists())
  }

  @Test fun anInterruptedSwapFinishesOnTheNextStart() {
    val files = tmp.newFolder("files"); val db = File(tmp.root, "databases/getnf.db")
    live(files, db)
    val restore = PendingRestore(files, db)
    BackupArchive.extract(archive(), restore.staged, 2)
    restore.markReady()
    // Simulate a crash right after the current data moved aside: the live paths are empty.
    File(files, "restore/previous").mkdirs()
    check(db.renameTo(File(files, "restore/previous/getnf.db")))
    check(File(files, "receipts").renameTo(File(files, "restore/previous/receipts")))
    assertTrue(restore.applyIfReady())
    assertEquals("database", db.readText())
    assertEquals("old db", File(files, "restore/previous/getnf.db").readText())
    assertEquals(listOf("a.jpg", "page.html"), File(files, "receipts/$chave").list()!!.sorted())
  }

  @Test fun restoreOntoAFreshInstall() {
    val files = tmp.newFolder("files"); val db = File(tmp.root, "databases/getnf.db")
    val restore = PendingRestore(files, db)
    BackupArchive.extract(archive(), restore.staged, 2)
    restore.markReady()
    assertTrue(restore.applyIfReady())
    assertEquals("database", db.readText())
    restore.cleanUp()
    assertFalse(File(files, "restore").listFiles().orEmpty().any { it.name == "previous" })
  }
}
