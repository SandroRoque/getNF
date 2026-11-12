package io.github.sandroroque.getnf.data

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Audit 2026-11-09, area "parsing". Synthetic page derived from the repo's sanitized fixture (its fake chave).
 * `defect_*` FAILS today and states the expected behaviour.
 */
class Review1109ParsingReceiptParserTest {
  @get:Rule val tmp = TemporaryFolder()
  private val db = testDatabase()
  private val store by lazy { ReceiptStore(tmp.root, db, now = { 1_000L }) }
  private val parser by lazy { ReceiptParser(db, tmp.root, now = { 7 }, log = { _, _ -> }) }
  private val chave = "42260111222333000181651090000000011123456784"
  private val html = javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText()

  /**
   * The phone's clock was a day ahead when SEFAZ blocked it, then the clock is corrected. Entries "from the future"
   * pass `t - at < WINDOW_MS` (negative), so the budget stays "blocked" for the whole skew plus an hour.
   */
  @Test fun defect_lookupBudgetEntriesFromTheFutureNeverAgeOut() {
    var saved = ""
    var clock = 10 * 24 * 3_600_000L + 24 * 3_600_000L // a day ahead
    val budget = LookupBudget({ saved }, { saved = it }, now = { clock })
    budget.markBlocked("wifi")
    clock -= 24 * 3_600_000L // clock corrected
    clock += 2 * 3_600_000L // two real hours later: SEFAZ's block (about an hour) is long over
    val s = budget.status("wifi")
    assertTrue(
      "two hours after a block, the budget should be free again; status=$s (nextFreeAt is ${(s.nextFreeAt ?: 0) - clock} ms away)",
      !s.blocked && s.remaining == LookupBudget.LIMIT,
    )
  }
}
