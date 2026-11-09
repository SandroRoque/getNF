package io.github.sandroroque.getnf.data

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Two independent local databases; synthetic files only. No remote authorization claim. */
class Round2CopyTest {
  @get:Rule val tmp = TemporaryFolder()
  private val chave = "42260111222333000181651090000000011123456784"

  @Test fun copyAndRepeatKeepSourceAndResetDestinationUploadState() = runTest {
    val a = testDatabase(); val b = testDatabase()
    try {
      val from = ReceiptStore(tmp.newFolder("from"), a)
      val to = ReceiptStore(tmp.newFolder("to"), b)
      from.open(chave, "http://example.invalid/synthetic")
      from.savePage(chave, "http://example.invalid/details", "<html>synthetic 10.00</html>")
      val photo = from.newPhotoFile(chave).apply { writeBytes(byteArrayOf(1,2,3,4)) }
      from.addPhoto(chave, photo)
      from.markUploaded(chave, from.get(chave)!!.version, "source-remote")
      val source = from.get(chave)!!
      assertTrue(to.importCopy(source, from.dir(chave)))
      assertFalse(to.importCopy(source, from.dir(chave)))
      val copied = to.get(chave)!!
      assertEquals(1, b.receipts().receipts().size)
      assertEquals(1, b.receipts().photos().size)
      assertNull(copied.uploadedVersion)
      assertNull(copied.remoteId)
      assertTrue(copied.needsUpload)
      assertEquals(source.photos, copied.pendingPhotos)
      assertArrayEquals(photo.readBytes(), File(to.dir(chave), photo.name).readBytes())
      assertEquals(source, from.get(chave))
      to.savePage(chave, "http://example.invalid/destination", "<html>destination only</html>")
      assertEquals("<html>synthetic 10.00</html>", File(from.dir(chave), ReceiptStore.PAGE_FILE).readText())
    } finally { a.close(); b.close() }
  }

  @Test fun repeatCopyKeepsExistingDestinationPage() = runTest {
    val a = testDatabase(); val b = testDatabase()
    try {
      val from = ReceiptStore(tmp.newFolder("from"), a)
      val to = ReceiptStore(tmp.newFolder("to"), b)
      from.open(chave, "synthetic"); to.open(chave, "synthetic")
      from.savePage(chave, "source", "<html>source</html>")
      to.savePage(chave, "target", "<html>target</html>")
      assertFalse(to.importCopy(from.get(chave)!!, from.dir(chave)))
      assertEquals("target", to.get(chave)!!.pageUrl)
      assertEquals("<html>target</html>", File(to.dir(chave), ReceiptStore.PAGE_FILE).readText())
    } finally { a.close(); b.close() }
  }
}
