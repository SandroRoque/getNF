package io.github.sandroroque.getnf.data

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
  private val db = testDatabase()
  private fun store() = ReceiptStore(tmp.root, db, now = { clock++ })

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

  private fun segment(photo: String, session: String) =
    CaptureSegment(photo, "$photo.mask.png", listOf(listOf(0.0, 0.0), listOf(1.0, 1.0)), listOf(listOf(0.1, 0.4)), top = 0.1, score = 1.5, capturedAt = 7, sessionId = session)

  /** A pre-D45 manifest as the old store wrote it. */
  private fun legacy(chave: String = this.chave) = Receipt(
    chave = chave, qrUrl = "qr", capturedAt = 500, photos = listOf("photo-1.jpg", "a.jpg", "b.jpg"),
    pageUrl = "https://x", pageFile = ReceiptStore.PAGE_FILE, pageFetchedAt = 600, version = 5, uploadedVersion = 5,
    remoteId = "r1", captureSessions = listOf("s1"), captureSegments = listOf(segment("a.jpg", "s1"), segment("b.jpg", "s1")),
    uploadedPhotos = listOf("photo-1.jpg", "a.jpg"),
  )
  private fun writeManifest(r: Receipt, text: String = Json.encodeToString(r)) =
    File(tmp.root, r.chave).apply { mkdirs() }.resolve(ReceiptStore.MANIFEST).writeText(text)

  @Test
  fun legacyManifestIsImportedWithUploadStateAndRenamed() = runTest {
    val old = legacy(); writeManifest(old)
    val store = store().apply { load() }
    val imported = store.receipts.value.single()
    assertEquals(old, imported)
    // Only the never-confirmed photo is pending; the receipt itself isn't re-sent.
    assertEquals(listOf("b.jpg"), imported.pendingPhotos)
    assertEquals(imported.version, imported.uploadedVersion)
    val dir = store.dir(chave)
    assertFalse(File(dir, ReceiptStore.MANIFEST).exists())
    assertTrue(File(dir, ReceiptStore.MIGRATED_MANIFEST).isFile)
  }

  @Test
  fun importIsIdempotentAndNeverOverwritesTheDatabase() = runTest {
    writeManifest(legacy())
    store().load()
    store().load()
    assertEquals(legacy(), store().apply { load() }.receipts.value.single())
    // Crash between insert and rename: the manifest is back, maybe stale. The database wins; the file is just renamed.
    writeManifest(legacy().copy(version = 99, photos = emptyList()))
    val again = store().apply { load() }.receipts.value.single()
    assertEquals(legacy(), again)
    assertFalse(File(store().dir(chave), ReceiptStore.MANIFEST).exists())
  }

  @Test
  fun unreadableManifestIsSkippedAndTheRestImported() = runTest {
    val other = "42260611222333000181651090000099991123456780"
    writeManifest(legacy())
    writeManifest(legacy(other), text = "{ not json")
    val warnings = mutableListOf<String>()
    val store = ReceiptStore(tmp.root, db, now = { clock++ }, warn = { message, _ -> warnings += message })
    store.load()
    assertEquals(listOf(chave), store.receipts.value.map { it.chave })
    assertEquals(1, warnings.size)
    assertTrue(File(store.dir(other), ReceiptStore.MANIFEST).isFile)
  }

  @Test
  fun changesAfterImportKeepEvidenceAndBumpVersion() = runTest {
    writeManifest(legacy())
    val store = store().apply { load() }
    store.addPhoto(chave, store.newPhotoFile(chave))
    store.markPhotoUploaded(chave, "b.jpg")
    val r = store.get(chave)!!
    assertEquals(4, r.photos.size)
    assertEquals(6L, r.version)
    assertEquals(legacy().captureSegments, r.captureSegments)
    assertEquals(listOf(r.photos.last()), r.pendingPhotos)
  }

  @Test
  fun getUnknownIsNull() = runTest { assertNull(store().get(chave)) }
}
