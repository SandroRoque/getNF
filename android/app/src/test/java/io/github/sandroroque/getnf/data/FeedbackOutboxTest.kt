package io.github.sandroroque.getnf.data

import io.github.sandroroque.getnf.sync.SupabaseException
import java.io.IOException
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reports wait on the phone until they're sent (D152). */
class FeedbackOutboxTest {
  private val dir = Files.createTempDirectory("feedback").toFile()
  private val outbox = FeedbackOutbox(dir, "Caderneta 0.1.0 (3) · Android 16 · Test phone") { "00000000-0000-0000-0000-00000000f001" }

  @Test fun aReportCarriesTheVersionAndTheInstallButNotTheAccount() {
    outbox.add(FeedbackOutbox.Kind.Comment, "A câmera não sai do lugar", at = 1_000)
    val sent = mutableListOf<JsonObject>()
    assertEquals(1, outbox.send { sent += it })
    val row = sent.single()
    assertEquals(setOf("kind", "body", "about", "install_id", "written_at"), row.keys)
    assertEquals("comentario", row["kind"]!!.jsonPrimitive.content)
    assertEquals("A câmera não sai do lugar", row["body"]!!.jsonPrimitive.content)
    assertEquals("Caderneta 0.1.0 (3) · Android 16 · Test phone", row["about"]!!.jsonPrimitive.content)
    assertEquals("1970-01-01T00:00:01Z", row["written_at"]!!.jsonPrimitive.content)
    assertTrue("a sent report is gone", outbox.pending().isEmpty())
  }

  @Test fun offlineTheyWaitInOrder() {
    outbox.add(FeedbackOutbox.Kind.Crash, "primeiro", at = 1_000)
    outbox.add(FeedbackOutbox.Kind.Refused, "segundo", at = 2_000)
    var tries = 0
    try {
      outbox.send { tries++; throw IOException("no network") }
    } catch (_: IOException) {}
    assertEquals("stops at the first failure", 1, tries)
    assertEquals(2, outbox.pending().size)
    val bodies = mutableListOf<String>()
    outbox.send { bodies += it["body"]!!.jsonPrimitive.content }
    assertEquals(listOf("primeiro", "segundo"), bodies)
  }

  @Test fun aRefusedReportIsDroppedAndTheRestGo() {
    outbox.add(FeedbackOutbox.Kind.Comment, "demais", at = 1_000)
    outbox.add(FeedbackOutbox.Kind.Comment, "normal", at = 2_000)
    val refused = mutableListOf<String>()
    val sent = outbox.send(onRefused = { refused += it }) {
      if (it["body"]!!.jsonPrimitive.content == "demais") throw SupabaseException(400, "violates check constraint", "23514")
    }
    assertEquals(1, sent)
    assertEquals(listOf("violates check constraint"), refused)
    assertFalse(outbox.pending().any())
  }

  @Test fun overTheServersLimitTheyWaitForLater() {
    outbox.add(FeedbackOutbox.Kind.Crash, "primeiro", at = 1_000)
    outbox.add(FeedbackOutbox.Kind.Comment, "segundo", at = 2_000)
    var tries = 0
    val refused = mutableListOf<String>()
    try {
      outbox.send(onRefused = { refused += it }) { tries++; throw SupabaseException(400, "too many reports this hour", FeedbackOutbox.OVER_LIMIT) }
      throw AssertionError("the limit must reach the worker, to back off")
    } catch (_: IOException) {}
    assertEquals("the rest would be turned away too", 1, tries)
    assertEquals(emptyList<String>(), refused)
    assertEquals(2, outbox.pending().size)
    val bodies = mutableListOf<String>()
    assertEquals(2, outbox.send { bodies += it["body"]!!.jsonPrimitive.content })
    assertEquals(listOf("primeiro", "segundo"), bodies)
  }

  @Test fun aLongReportIsCutToTheTablesLimit() {
    outbox.add(FeedbackOutbox.Kind.Crash, "x".repeat(40_000))
    outbox.send { assertEquals(FeedbackOutbox.MAX_BODY, it["body"]!!.jsonPrimitive.content.length) }
  }
}
