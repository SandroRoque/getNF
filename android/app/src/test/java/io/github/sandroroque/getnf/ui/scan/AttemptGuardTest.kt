package io.github.sandroroque.getnf.ui.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R1 and R2 (D149): a provisional attempt drops after a while without the paper; a proven one never does. */
class AttemptGuardTest {
  @Test fun aStartedProvisionalAttemptDropsAfterThePaperIsGoneAWhile() {
    val g = AttemptGuard()
    assertFalse(g.offer(0, paperSeen = true, proven = false, started = true))
    assertFalse(g.offer(2000, paperSeen = false, proven = false, started = true))
    assertFalse(g.offer(2500, paperSeen = false, proven = false, started = true))
    assertTrue(g.offer(2600, paperSeen = false, proven = false, started = true))
  }

  @Test fun thePaperBackInTimeKeepsIt() {
    val g = AttemptGuard()
    g.offer(0, paperSeen = true, proven = false, started = true)
    g.offer(2000, paperSeen = false, proven = false, started = true)
    g.offer(2400, paperSeen = true, proven = false, started = true)
    assertFalse(g.offer(4800, paperSeen = false, proven = false, started = true))
  }

  @Test fun aProvenOrUnstartedAttemptNeverDrops() {
    val proven = AttemptGuard()
    proven.offer(0, paperSeen = true, proven = true, started = true)
    assertFalse(proven.offer(60_000, paperSeen = false, proven = true, started = true))
    val empty = AttemptGuard()
    assertFalse(empty.offer(60_000, paperSeen = false, proven = false, started = false))
    // Started later: the wait counts from then.
    assertFalse(empty.offer(61_000, paperSeen = false, proven = false, started = true))
    assertTrue(empty.offer(63_600, paperSeen = false, proven = false, started = true))
  }
}
