package io.github.sandroroque.getnf.data

import io.github.sandroroque.getnf.parse.Derive
import io.github.sandroroque.getnf.parse.NfcePage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Round2ArithmeticTest {
  @get:Rule val tmp = TemporaryFolder()
  @Test fun allocationsConserveCentsAndStayInsideLineTotals() {
    val rng = java.util.Random(20261104)
    repeat(1000) {
      val weights = List(1+rng.nextInt(30)) { rng.nextInt(10000).toLong() }
      val total = (rng.nextDouble()*weights.sum()).toLong()
      val shares = Derive.allocateCents(total,weights)
      assertEquals(total,shares.sum())
      assertTrue(shares.zip(weights).all { (s,w) -> s in 0..w })
    }
  }
  @Test fun localMonthBoundaryAndZeroQuantityStayFinite() {
    assertEquals("2026-01",Period.month(Instant.parse("2026-02-01T02:59:59Z").toEpochMilli()).key)
    assertEquals("2026-02",Period.month(Instant.parse("2026-02-01T03:00:00Z").toEpochMilli()).key)
    val l = SpendingLine("synthetic",1,1767272400000,null,null,0,0,"manual","p",null,0,"UN",0,null,null)
    assertTrue(Ipci.compute(listOf(l)).all { it.index.isFinite() && it.change.isFinite() && it.reading==null })
  }
  @Ignore("T-13: reproduces the audit's finding; un-ignore with the fix") @Test fun surchargeMustReachSpending() = runTest {
    val db=testDatabase();val dir=File(tmp.root,"receipts");val store=ReceiptStore(dir,db)
    val html=javaClass.classLoader!!.getResource("sc-detalhes-server-html.html")!!.readText()
    val chave=NfcePage.parse(html).chave!!
    // Existing fixture pays 30,64. Add a valid 1,00 surcharge and adjust payable to 31,64.
    val changed=html.replace("30,64", "31,64").replace("<label>Valor a pagar", "<label>Acréscimo</label><span>1,00</span><label>Valor a pagar")
    val parsed=NfcePage.parse(changed)
    assertEquals(100L,parsed.totals.surchargeCents)
    store.open(chave,"https://example.invalid");store.savePage(chave,"https://example.invalid",changed)
    ReceiptParser(db,dir,log={_,_->}).parse(store.get(chave)!!)
    assertEquals(db.catalog().parsed(chave)!!.paidCents,db.spending().lines().first().sumOf { it.netCents })
  }
}
