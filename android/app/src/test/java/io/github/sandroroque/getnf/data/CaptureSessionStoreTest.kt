package io.github.sandroroque.getnf.data

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class CaptureSessionStoreTest {
  @get:Rule val tmp = TemporaryFolder()
  private val key = "42260611222333000181651090000012341123456789"
  private fun segment(id: String) = CaptureSegment("capture-$id.jpg", "capture-$id.mask.png", emptyList(), listOf(listOf(0.0, 100.0)), 0.0, 100.0, 1.5, 1, id)
  private fun prepare(sessions: CaptureSessionStore, committing: Boolean = false, withQr: Boolean = true): CaptureSessionManifest {
    var session = sessions.create()
    val segment = segment(session.id)
    File(sessions.dir(session.id), segment.photo).writeText("jpeg-test-bytes")
    File(sessions.dir(session.id), segment.mask).writeText("mask-test-bytes")
    session = session.copy(chave = if (withQr) key else null, qrUrl = "https://example.invalid/", committing = committing, segments = listOf(segment))
    sessions.write(session)
    return session
  }
  @Test fun importPreservesExistingPhotosAndIsIdempotent() = runTest {
    val receipts = ReceiptStore(tmp.newFolder("receipts"), testDatabase())
    receipts.open(key, "old-url")
    val old = File(receipts.dir(key), "old.jpg").apply { writeText("existing") }
    receipts.addPhoto(key, old)
    val sessions = CaptureSessionStore(tmp.newFolder("sessions")); val session = prepare(sessions, true)
    val first = receipts.importCapture(session, sessions.dir(session.id))
    val again = receipts.importCapture(session, sessions.dir(session.id))
    assertEquals(first, again)
    assertEquals(listOf("old.jpg", session.segments.single().photo), again.photos)
    assertEquals("existing", old.readText())
    assertEquals("old-url", again.qrUrl)
    assertEquals(1, again.captureSessions.size)
  }
  @Test fun processRecoveryKeepsOnlyCodeForUnfinishedCapture() = runTest {
    val root = tmp.newFolder("sessions"); val sessions = CaptureSessionStore(root)
    val session = prepare(sessions)
    val receipts = ReceiptStore(tmp.newFolder("receipts"), testDatabase())
    CaptureSessionStore(root).recover(receipts)
    assertNotNull(receipts.get(key)); assertTrue(receipts.get(key)!!.photos.isEmpty())
    assertFalse(sessions.dir(session.id).exists())
  }
  @Test fun processRecoveryWithoutQrDiscardsAllImages() = runTest {
    val root = tmp.newFolder("sessions"); val sessions = CaptureSessionStore(root)
    val session = prepare(sessions, withQr = false)
    val receipts = ReceiptStore(tmp.newFolder("receipts"), testDatabase())
    CaptureSessionStore(root).recover(receipts)
    assertTrue(receipts.receipts.value.isEmpty()); assertFalse(sessions.dir(session.id).exists())
  }
  @Test fun interruptedCommitCanFinishWithoutDuplicatingImages() = runTest {
    val root = tmp.newFolder("sessions"); val sessions = CaptureSessionStore(root)
    val session = prepare(sessions, committing = true)
    val receipts = ReceiptStore(tmp.newFolder("receipts"), testDatabase())
    receipts.importCapture(session, sessions.dir(session.id)) // Simulate crash after receipt commit, before temp cleanup.
    CaptureSessionStore(root).recover(receipts)
    assertEquals(1, receipts.get(key)!!.photos.size)
    assertFalse(sessions.dir(session.id).exists())
  }
  @Test fun missingSourceDoesNotPublishPartialBatchOrDeleteOldPhotos() = runTest {
    val sessions = CaptureSessionStore(tmp.newFolder("sessions")); val session = prepare(sessions, true)
    File(sessions.dir(session.id), session.segments.single().mask).delete()
    val receipts = ReceiptStore(tmp.newFolder("receipts"), testDatabase())
    val before = receipts.open(key, "existing")
    try { receipts.importCapture(session, sessions.dir(session.id)); fail("Expected a missing-file error") } catch (_: java.io.IOException) { }
    assertEquals(before, receipts.get(key))
    assertFalse(File(receipts.dir(key), session.segments.single().photo).exists())
  }
  @Test fun damagedCommitIsDeferredWithoutBlockingExistingReceipts() = runTest {
    val root = tmp.newFolder("sessions"); val sessions = CaptureSessionStore(root)
    val session = prepare(sessions, committing = true)
    File(sessions.dir(session.id), session.segments.single().mask).delete()
    val receipts = ReceiptStore(tmp.newFolder("receipts"), testDatabase()); val before = receipts.open(key, "existing")
    assertEquals(1, CaptureSessionStore(root).recover(receipts))
    assertEquals(before, receipts.get(key)); assertTrue(sessions.dir(session.id).exists())
  }
  @Test fun quotaRejectsTheEntireBatchWithoutChangingExistingPhotos() = runTest {
    val sessions = CaptureSessionStore(tmp.newFolder("sessions")); val session = prepare(sessions, true)
    val receipts = ReceiptStore(tmp.newFolder("receipts"), testDatabase()); receipts.open(key, null)
    repeat(ReceiptStore.MAX_PHOTOS) { i ->
      val file = File(receipts.dir(key), "old-$i.jpg").apply { writeText("existing") }
      receipts.addPhoto(key, file)
    }
    val before = receipts.get(key)
    try { receipts.importCapture(session, sessions.dir(session.id)); fail("Expected quota rejection") } catch (_: IllegalArgumentException) { }
    assertEquals(before, receipts.get(key)); assertEquals("existing", File(receipts.dir(key), "old-0.jpg").readText())
  }
  @Test fun activeSessionIsNeverCleanedByStartupRecovery() = runTest {
    val sessions = CaptureSessionStore(tmp.newFolder("sessions")); val session = prepare(sessions)
    val receipts = ReceiptStore(tmp.newFolder("receipts"), testDatabase())
    sessions.recover(receipts)
    assertTrue(sessions.dir(session.id).exists()); assertNull(receipts.get(key))
  }
}
