package io.github.sandroroque.getnf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupBudgetTest {
  private var stored = ""
  private var clock = 1_000_000L
  private val min = 60_000L
  private val budget = LookupBudget(load = { stored }, save = { stored = it }, now = { clock })

  @Test
  fun countsPerNetworkWithinTheWindow() {
    repeat(3) {
      budget.record("wifi")
      clock += min
    }
    budget.record("cellular:1")
    assertEquals(LookupBudget.Status(used = 3, remaining = 2, blocked = false, nextFreeAt = null), budget.status("wifi"))
    assertEquals(4, budget.status("cellular:1").remaining)
    assertEquals(5, budget.status("cellular:2").remaining)
  }

  @Test
  fun fullBudgetFreesUpAsTheOldestLookupAgesOut() {
    val first = clock
    repeat(5) {
      budget.record("wifi")
      clock += 10 * min
    }
    val s = budget.status("wifi")
    assertEquals(0, s.remaining)
    assertEquals(first + LookupBudget.WINDOW_MS, s.nextFreeAt)
    clock = first + LookupBudget.WINDOW_MS // the first lookup is now an hour old
    assertEquals(1, budget.status("wifi").remaining)
  }

  @Test
  fun blockUsesUpTheNetworkForAWindow() {
    budget.record("wifi")
    clock += min
    val blockedAt = clock
    budget.markBlocked("wifi")
    val s = budget.status("wifi")
    assertTrue(s.blocked)
    assertEquals(0, s.remaining)
    assertEquals(blockedAt + LookupBudget.WINDOW_MS, s.nextFreeAt)
    assertEquals(5, budget.status("cellular:1").remaining)
    clock = blockedAt + LookupBudget.WINDOW_MS
    assertFalse(budget.status("wifi").blocked)
  }

  @Test
  fun oldEntriesArePrunedAndGarbageIgnored() {
    stored = "garbage\n1|x\n"
    budget.record("wifi")
    clock += 2 * LookupBudget.WINDOW_MS
    budget.record("wifi")
    assertEquals(1, stored.lines().size)
    assertNull(budget.status("wifi").nextFreeAt)
  }
}
