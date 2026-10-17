package io.github.sandroroque.getnf.data

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The stage 2 read-only copy of the server (D46), on synthetic data. */
class ServerMirrorTest {
  @get:Rule val tmp = TemporaryFolder()
  private val db = testDatabase()
  private val dao = db.catalog()
  private var ids = 0
  private val mirror = ServerMirror(db, now = { 42 }, newId = { "id${ids++}" })
  private val store by lazy { ReceiptStore(tmp.root, db) }

  private val local = "42260611222333000181651090000012341123456789"
  private val serverOnly = "42260611222333000181651090000099991123456780"
  private val gtin = "7890000000017"

  private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

  private fun snapshot(
    products: List<ServerProduct> = listOf(
      ServerProduct("sp1", "gtin:$gtin", "CAFE 500G", "sc2", listOf("st1"), 0.5, "kg", "parsed"),
      ServerProduct("sp2", "store:11222333000181:243", "BANANA KG", "sc2", emptyList(), 0.25, "kg", "manual"),
      ServerProduct("sp3", "store:11222333000181:77", "SACOLA", null, emptyList(), null, null, null),
    ),
    categories: List<ServerCategory> = listOf(
      ServerCategory("sc2", "food.produce", "Hortifrúti", 2, "sc1"), // child first: order must not matter
      ServerCategory("sc1", "food", "Comida", 1, null),
    ),
    receipts: List<ServerReceipt> = listOf(receipt("sr1", local), receipt("sr2", serverOnly)),
    items: List<ServerItem> = listOf(
      ServerItem("sr1", 1, "sp1", "CAFE 500G", gtin, 1.0, "UN", 19.99, 19.99, 0.5, "prorated"),
      ServerItem("sr1", 2, "sp2", "BANANA KG", "243", 1.355, "KG", 5.49, 7.44, 0.0, "prorated"),
      ServerItem("sr1", 3, "sp3", "SACOLA", "77", 1.0, "UN", 0.1, 0.1, 0.0, null),
      ServerItem("sr2", 1, "sp1", "CAFE 500G", gtin, 1.0, "UN", 19.99, 19.99, 0.0, "prorated"),
    ),
  ) = ServerSnapshot(categories, listOf(ServerTag("st1", "organico", "Orgânico")), products, receipts, items)

  private fun receipt(id: String, chave: String, issuedAt: String = "2026-02-01T02:00:00Z") = ServerReceipt(
    id, chave, "qr", 1, null, null, "page.html", emptyList(), "Mercado", "11222333000181", at(issuedAt),
    27.53, 0.5, 27.03, 1, "",
  )

  @Test fun mirrorsCatalogAndLinesOfLocalReceipts() = runTest {
    store.open(local, "qr")
    assertEquals(3, mirror.apply(snapshot()))

    val categories = dao.categories().associateBy { it.seedPath }
    assertEquals(categories["food"]!!.id, categories["food.produce"]!!.parentId)
    val items = dao.storeItems().associateBy { it.code }
    assertEquals(Triple("gtin", "", 500_000L), Triple(items[gtin]!!.codeKind, items[gtin]!!.storeCnpj, items[gtin]!!.parsedSizeE6))
    assertEquals("11222333000181", items["243"]!!.storeCnpj)
    val products = dao.products().associateBy { it.id }
    val banana = products[items["243"]!!.productId]!!
    assertEquals(Triple(250_000L, "kg", "manual"), Triple(banana.sizeE6, banana.sizeUnit, banana.sizeSource))
    assertNull(items["243"]!!.parsedSizeE6)
    assertEquals(categories["food.produce"]!!.id, banana.categoryId)
    assertNull(products[items["77"]!!.productId]!!.categoryId)
    assertEquals(listOf(ProductTagRow(items[gtin]!!.productId, dao.tags().single().id)), dao.productTags())

    val lines = dao.lines()
    assertEquals(listOf(1, 2, 3), lines.map { it.lineNo })
    assertEquals(13_550L, lines[1].qtyE4)
    assertEquals(54_900L, lines[1].unitPriceE4)
    assertEquals(listOf(1999L, 744L, 10L), lines.map { it.grossCents })
    assertEquals(50L, lines[0].discountCents)
    assertEquals("prorated", lines[2].discountSource)
    val parsed = dao.parsed().single()
    assertEquals(Triple(2753L, 50L, 2703L), Triple(parsed.grossCents, parsed.discountCents, parsed.paidCents))
    assertNull(parsed.parseError)
    assertEquals("Mercado", dao.issuers().single().name)
  }

  @Test fun refreshIsIdempotentAndKeepsLocalIds() = runTest {
    store.open(local, "qr")
    mirror.apply(snapshot())
    val before = listOf(dao.categories(), dao.tags(), dao.products(), dao.storeItems(), dao.productTags(), dao.lines(), dao.parsed())
    mirror.apply(snapshot())
    assertEquals(before, listOf(dao.categories(), dao.tags(), dao.products(), dao.storeItems(), dao.productTags(), dao.lines(), dao.parsed()))
  }

  @Test fun whatTheServerDroppedLeavesTheCopy() = runTest {
    store.open(local, "qr")
    mirror.apply(snapshot())
    val s = snapshot()
    mirror.apply(s.copy(
      categories = s.categories.filter { it.id == "sc1" },
      products = s.products.filter { it.id != "sp3" },
      items = s.items.filter { it.productId != "sp3" },
      tags = emptyList(),
    ))
    assertEquals(listOf("food"), dao.categories().map { it.seedPath })
    assertEquals(2, dao.products().size)
    assertTrue(dao.products().all { it.categoryId == null })
    assertEquals(listOf(1, 2), dao.lines().map { it.lineNo })
    assertTrue(dao.productTags().isEmpty())

    // The receipt itself is gone from the server: its lines and header go, the local receipt and photos stay.
    mirror.apply(s.copy(receipts = emptyList(), items = emptyList()))
    assertTrue(dao.lines().isEmpty())
    assertTrue(dao.parsed().isEmpty())
    assertTrue(dao.issuers().isEmpty())
    assertNotNull(store.get(local))
  }

  @Test fun aFailedRefreshChangesNothing() = runTest {
    store.open(local, "qr")
    mirror.apply(snapshot())
    val before = listOf(dao.products(), dao.storeItems(), dao.lines())
    val s = snapshot()
    try {
      // Two lines with the same number: the insert fails halfway through.
      mirror.apply(s.copy(products = s.products.map { it.copy(description = "CHANGED") }, items = s.items + s.items.first()))
      fail("expected a constraint failure")
    } catch (_: Exception) {
    }
    assertEquals(before, listOf(dao.products(), dao.storeItems(), dao.lines()))
  }

  @Test fun receiptsOnlyTheServerHasAreLeftToAdoption() = runTest {
    store.open(local, "qr")
    mirror.apply(snapshot())
    assertEquals(listOf(local), dao.parsed().map { it.chave })
    store.adoptRemote(Receipt(serverOnly, "qr", 1, version = 1, uploadedVersion = 1, remoteId = "sr2"), listOf("old.jpg"))
    assertEquals(4, mirror.apply(snapshot()))
    val adopted = store.get(serverOnly)!!
    assertEquals(listOf("old.jpg"), adopted.pendingPhotos)
    assertEquals(adopted.version, adopted.uploadedVersion)
  }

  @Test fun monthlyTotalsUseStoreLocalTimeAndProductCategory() = runTest {
    store.open(local, "qr")
    store.adoptRemote(Receipt(serverOnly, "qr", 1, version = 1, uploadedVersion = 1, remoteId = "sr2"), emptyList())
    // 02:00 UTC on Feb 1st is still January 31st in SC (UTC-3); the other receipt is in February.
    val s = snapshot(receipts = listOf(receipt("sr1", local), receipt("sr2", serverOnly, issuedAt = "2026-02-01T04:00:00Z")))
    mirror.apply(s)
    assertEquals(
      listOf(
        MonthlyCategoryTotal("2026-01", "", 10),
        MonthlyCategoryTotal("2026-01", "food.produce", 1949 + 744),
        MonthlyCategoryTotal("2026-02", "food.produce", 1999),
      ),
      dao.monthlyTotals(),
    )
  }
}
