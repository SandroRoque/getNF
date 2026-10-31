package io.github.sandroroque.getnf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Refused rows as Ajustes shows them (D145). */
class SyncProblemsTest {
  // A made-up chave: issued October 2026.
  private val chave = "42261011222333000181651090000000011123456784"

  @Test fun eachRowSaysWhatItIs() {
    assertEquals("Produto: Café torrado 500 g",
      SyncProblems.describe(SyncParkedRow("product", "p1", """{"id":"p1","name":"Café torrado 500 g"}""", "check", 0)).title)
    assertEquals("Item de loja: CAFE TORRADO 500G",
      SyncProblems.describe(SyncParkedRow("store_item", "s1", """{"description":"CAFE TORRADO 500G"}""", "x", 0)).title)
    assertEquals("Nota de 10/2026", SyncProblems.describe(SyncParkedRow("receipt", chave, """{"chave":"$chave"}""", "x", 0)).title)
    // The lines are keyed by the chave, which their payload (a list) doesn't carry as a field.
    assertEquals("Itens da nota de 10/2026", SyncProblems.describe(SyncParkedRow("receipt_line", chave, "[]", "x", 0)).title)
    assertEquals("Marcador", SyncProblems.describe(SyncParkedRow("tag", "t1", "not json", "x", 0)).title)
  }

  @Test fun theReportCarriesWhatWasSentAndWhy() {
    val r = SyncProblems.report("Caderneta 0.0.3 (3)", listOf(SyncParkedRow("product", "p1", """{"id":"p1"}""", "violates check", 0)))
    assertTrue(r.startsWith("Caderneta 0.0.3 (3)\n1 linha(s) recusada(s)"))
    assertTrue(r.contains("product p1") && r.contains("erro: violates check") && r.contains("""enviado: {"id":"p1"}"""))
  }
}
