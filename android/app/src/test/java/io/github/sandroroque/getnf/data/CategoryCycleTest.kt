package io.github.sandroroque.getnf.data

import io.github.sandroroque.getnf.ui.catalog.flatten
import io.github.sandroroque.getnf.ui.catalog.pathOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** A category parent cycle in the data (T-32, D187): every walk up or down the tree ends, and the cycle stays visible. */
class CategoryCycleTest {
  private fun node(id: String, parent: String?) = CategoryNode(id, id.uppercase(), parent, 1, null, null, 0)
  // a ↔ b in a cycle, c under a; r a root with s under it.
  private val tree = listOf(node("a", "b"), node("b", "a"), node("c", "a"), node("r", null), node("s", "r"))

  @Test(timeout = 2_000) fun chainUpStopsAtARepeat() {
    assertEquals(listOf("c", "a", "b"), chainUp("c") { id -> tree.single { it.id == id }.parentId })
    assertEquals(listOf("s", "r"), chainUp("s") { id -> tree.single { it.id == id }.parentId })
    assertEquals(emptyList<String>(), chainUp<String>(null) { it })
  }

  /** The review's repro (M3): Início and Gastos compute slices for every line. */
  @Test(timeout = 2_000) fun theReportsFinishOverACycle() {
    val line = SpendingLine("c", 1, 1_760_000_000_000, null, null, 100, 0, "prorated", "p", "a", 10_000, "UN", 10_000, null, null)
    val spending = Spending(listOf(line), tree.associate { it.id to it.parentId })
    val slices = spending.slices(Period.month(line.issuedAt!!), null)
    assertEquals(100L, slices.sumOf { it.netCents })
  }

  @Test(timeout = 2_000) fun theTreeShowsTheCycleAtTheTopSoItCanBeMovedOut() {
    val flat = flatten(tree)
    assertEquals(tree.map { it.id }.sorted(), flat.map { it.first.id }.sorted())
    assertEquals(listOf("R" to 0, "S" to 1), flat.take(2).map { it.first.label to it.second })
    assertEquals("B › A › C", pathOf(tree, "c"))
  }
}
