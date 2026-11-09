package io.github.sandroroque.getnf.data

import io.github.sandroroque.getnf.sync.PhotoArchiver
import io.github.sandroroque.getnf.sync.SupabaseException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Ignore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The closed test's photos and pages wait on the phone until they're sent (D161). */
class TestContributionTest {
  private val dir = Files.createTempDirectory("contributions").toFile()
  private val prefs = object : TestContribution.Prefs { override var enabled: Boolean? = null; override var withdraw = false }
  private val install = "00000000-0000-0000-0000-00000000f001"
  private fun contribution(testBuild: Boolean = true) = TestContribution(prefs, dir, testBuild, "Caderneta 0.1.1 (4)") { install }
  /** The copy is the original's bytes reversed: enough to see which photo it came from. */
  private val archiver = PhotoArchiver { photo, _ -> photo.readBytes().reversedArray() }

  private data class Sent(val query: Map<String, String>, val name: String, val type: String, val bytes: ByteArray)

  private fun session(vararg photos: String): Pair<CaptureSessionManifest, File> {
    val source = Files.createTempDirectory("session").toFile()
    val segments = photos.mapIndexed { i, name ->
      File(source, name).writeBytes(byteArrayOf(i.toByte(), 1, 2))
      CaptureSegment(name, "$name.mask", listOf(listOf(.1, .1), listOf(.9, .1), listOf(.9, .9)), emptyList(), score = .8, capturedAt = 1000L + i, sessionId = "s")
    }
    return CaptureSessionManifest("s", chave = "4" + "2".repeat(43), qrUrl = "https://sat.sef.sc.gov.br/x", segments = segments) to source
  }

  private fun TestContribution.sendAll(): List<Sent> {
    val sent = mutableListOf<Sent>()
    send(archiver) { query, file, type -> sent += Sent(query, file.name, type, file.readBytes()) }
    return sent
  }

  @Test fun nothingIsKeptBeforeTheAnswerOrOutsideATestBuild() {
    val (s, source) = session("a.jpg")
    contribution().addCapture(TestContribution.Kind.Saved, s, source)
    assertTrue(contribution().shouldAsk)
    prefs.enabled = true
    contribution(testBuild = false).addCapture(TestContribution.Kind.Saved, s, source)
    assertFalse(contribution(testBuild = false).shouldAsk)
    assertTrue(contribution().pending().isEmpty())
  }

  @Test fun aCaptureGoesAsItsCopiesThenTheMetaLast() {
    prefs.enabled = true
    val c = contribution()
    val (s, source) = session("a.jpg", "b.jpg")
    c.addCapture(TestContribution.Kind.Left, s, source)
    source.deleteRecursively() // the session's folder goes right after; the item has its own copies
    val sent = c.sendAll()
    assertEquals(listOf("p00.webp", "p01.webp", "meta.json"), sent.map { it.name })
    assertEquals(listOf("image/webp", "image/webp", "application/json"), sent.map { it.type })
    assertEquals(setOf(install), sent.map { it.query["install"] }.toSet())
    assertEquals("one item for the capture", 1, sent.map { it.query["item"] }.toSet().size)
    assertEquals(listOf<Byte>(2, 1, 1), sent[1].bytes.toList())
    val meta = Json.parseToJsonElement(sent.last().bytes.decodeToString()).jsonObject
    assertEquals("abandonada", meta["kind"]!!.jsonPrimitive.content)
    assertEquals(s.chave, meta["chave"]!!.jsonPrimitive.content)
    assertEquals(listOf("p00.webp", "p01.webp"), meta["photos"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    assertTrue(c.pending().isEmpty())
  }

  @Test fun aPhotoThatCantBeBlankedDoesntGo() {
    prefs.enabled = true
    val c = contribution()
    val (s, source) = session("a.jpg", "b.jpg")
    c.addCapture(TestContribution.Kind.Saved, s, source)
    val sent = mutableListOf<String>()
    val picky = PhotoArchiver { photo, _ -> if (photo.readBytes()[0] == 0.toByte()) error("can't read it") else photo.readBytes() }
    c.send(picky) { _, file, _ -> sent += file.name }
    assertEquals(listOf("p01.webp", "meta.json"), sent)
  }

  @Test fun aPageGoesWithoutTheBuyer() {
    prefs.enabled = true
    val c = contribution()
    c.addPage(TestContribution.Kind.OtherState, null, "https://nfce.example/x", "<p>CPF 123.456.789-09</p>")
    val sent = c.sendAll()
    assertEquals(listOf("page.html", "meta.json"), sent.map { it.name })
    assertEquals("<p>CPF ***.***.***-**</p>", sent.first().bytes.decodeToString())
  }

  @Test fun aNetworkFailureKeepsWhatWasntSentAndARefusalDropsIt() {
    prefs.enabled = true
    val c = contribution()
    val (s, source) = session("a.jpg", "b.jpg")
    c.addCapture(TestContribution.Kind.Saved, s, source)
    var calls = 0
    runCatching { c.send(archiver) { _, _, _ -> if (++calls == 2) throw IOException("offline") } }
    assertEquals(listOf("p01.webp", "meta.json"), c.sendAll().map { it.name })
    c.addCapture(TestContribution.Kind.Saved, s, source)
    val refused = mutableListOf<String>()
    c.send(archiver, onRefused = { refused += it }) { _, file, _ -> if (file.name == "p00.webp") throw SupabaseException(400, "no") }
    assertEquals(1, refused.size)
    assertTrue(c.pending().isEmpty())
  }

  @Test fun turningItOffDropsWhatWaitsAndAsksToTakeBackWhatWent() {
    prefs.enabled = true
    val c = contribution()
    val (s, source) = session("a.jpg")
    c.addCapture(TestContribution.Kind.Saved, s, source)
    c.enabled = false
    assertTrue(c.pending().isEmpty())
    assertTrue(c.withdrawPending)
    assertFalse(c.active)
    c.enabled = true
    assertFalse("on again: nothing to take back", c.withdrawPending)
  }

  @Ignore("T-10: reproduces the audit's finding; un-ignore with the fix") @Test fun reviewRound2WithdrawalDuringAnInflightSendMustNotLeaveAnUpload() {
    prefs.enabled = true
    val c = contribution()
    c.addPage(TestContribution.Kind.Page, null, "https://example.invalid", "<p>synthetic</p>")
    val entered = java.util.concurrent.CountDownLatch(1)
    val resume = java.util.concurrent.CountDownLatch(1)
    val remoteFiles = java.util.Collections.synchronizedList(mutableListOf<String>())
    val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
    val sender = Thread {
      try {
        c.send(archiver) { _, file, _ ->
          // A blocking HTTP upload has read its bytes, but the server hasn't persisted them yet.
          file.readBytes()
          entered.countDown()
          check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
          remoteFiles.add(file.name)
        }
      } catch (e: Throwable) { errors.add(e) }
    }
    sender.start()
    try {
      assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
      c.enabled = false
      assertTrue(c.withdrawPending)
      remoteFiles.clear() // the replacement worker's withdrawal finishes before the old POST
      c.withdrawPending = false
    } finally { resume.countDown(); sender.join(6000) }
    assertFalse(sender.isAlive)
    assertFalse(c.active)
    assertTrue("withdrawal finished but old blocking upload persisted ${remoteFiles.size} file(s); errors=${errors.map { it.javaClass.simpleName }}", remoteFiles.isEmpty())
  }

  @Test fun sayingNoAtFirstTakesNothingBack() {
    val c = contribution()
    c.enabled = false
    assertFalse(c.withdrawPending)
    assertFalse(c.shouldAsk)
    assertNull(prefs.enabled?.takeIf { it })
  }
}
