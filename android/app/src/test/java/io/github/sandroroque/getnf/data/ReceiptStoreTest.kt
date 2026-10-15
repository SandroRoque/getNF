package io.github.sandroroque.getnf.data

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiptStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private val chave = "42260611222333000181651090000012341123456789"
  private var clock = 1_000L
  private fun store() = ReceiptStore(tmp.root, now = { clock++ })

  @Test
  fun openCreatesOnceAndIsListed() = runTest {
    val store = store()
    val first = store.open(chave, "qr")
    val again = store.open(chave, "other")
    assertEquals(first, again)
    assertEquals("qr", again.qrUrl)
    assertEquals(listOf(chave), store.receipts.value.map { it.chave })
    assertTrue(first.needsUpload)
  }

  @Test
  fun photosAndPageArePersisted() = runTest {
    val store = store()
    store.open(chave, "qr")
    val photo = store.newPhotoFile(chave).apply { writeBytes(byteArrayOf(1, 2, 3)) }
    store.addPhoto(chave, photo)
    store.savePage(chave, "https://x/NFCe_Detalhes.aspx", "<html>ç</html>")

    val reloaded = store().apply { load() }.receipts.value.single()
    assertEquals(listOf(photo.name), reloaded.photos)
    assertEquals(ReceiptStore.PAGE_FILE, reloaded.pageFile)
    assertEquals("<html>ç</html>", File(store.dir(chave), ReceiptStore.PAGE_FILE).readText())
    assertEquals(3L, reloaded.version)
  }

  @Test
  fun uploadTracksTheUploadedVersion() = runTest {
    val store = store()
    val r = store.open(chave, "qr")
    store.markUploadError(chave, "offline")
    assertEquals("offline", store.get(chave)!!.uploadError)

    store.markUploaded(chave, r.version, "abc")
    val uploaded = store.get(chave)!!
    assertFalse(uploaded.needsUpload)
    assertNull(uploaded.uploadError)

    // A change after the upload snapshot makes it dirty again.
    store.addPhoto(chave, store.newPhotoFile(chave))
    assertTrue(store.get(chave)!!.needsUpload)
  }

  @Test
  fun photosStayPendingUntilEachIsConfirmed() = runTest {
    val store = store()
    val r = store.open(chave, "qr")
    val a = store.newPhotoFile(chave).apply { writeBytes(byteArrayOf(1)) }; store.addPhoto(chave, a)
    val b = store.newPhotoFile(chave).apply { writeBytes(byteArrayOf(2)) }; store.addPhoto(chave, b)
    store.markUploaded(chave, store.get(chave)!!.version, "abc")
    // Receipt fields are up to date, but photos are confirmed one by one (D42).
    assertTrue(store.get(chave)!!.needsUpload)
    val version = store.get(chave)!!.version
    store.markPhotoUploaded(chave, a.name)
    assertEquals(listOf(b.name), store.get(chave)!!.pendingPhotos)
    store.markPhotoUploaded(chave, b.name); store.markPhotoUploaded(chave, b.name)
    val done = store().apply { load() }.receipts.value.single()
    assertFalse(done.needsUpload)
    assertEquals(listOf(a.name, b.name), done.uploadedPhotos)
    assertEquals(version, done.version)
    assertTrue(r.version < version)
  }

  @Test
  fun getUnknownIsNull() = runTest { assertNull(store().get(chave)) }
}
