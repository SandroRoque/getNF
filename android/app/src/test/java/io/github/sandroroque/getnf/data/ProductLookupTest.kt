package io.github.sandroroque.getnf.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The product lookup (camera plan, step 1): which codes are products, and what the person paid in each caderneta. */
class ProductLookupTest {
  private val day = 86_400_000L
  private val now = 1_790_000_000_000L

  /** [body] with its check digit: made-up codes, never a real product's. */
  private fun gtin(body: String): String {
    var sum = 0
    var w = 3
    for (i in body.length - 1 downTo 0) { sum += (body[i] - '0') * w; w = 4 - w }
    return body + (10 - sum % 10) % 10
  }

  private val coffee = gtin("789000000001")

  /** Made-up receipts in [db]: each purchase is a receipt of its own at a store, [daysAgo] before [now]. */
  private class Fixture(val db: ReceiptDatabase) {
    private var n = 0
    suspend fun product(id: String, name: String?, category: String? = null) {
      category?.let { db.catalog().insertCategory(CategoryRow(it, null, it.replaceFirstChar(Char::uppercase), null, 1)) }
      db.catalog().insertProduct(ProductRow(id, name, category, null, null, null, createdAt = 0))
    }
    suspend fun item(id: String, code: String, product: String, description: String, kind: String = "gtin") =
      db.catalog().insertStoreItem(StoreItemRow(id, kind, code, if (kind == "gtin") "" else "11", description, null, null, product))
    suspend fun buy(item: String, store: String, at: Long, priceCents: Long, unit: String = "UN", qtyE4: Long = 10_000) {
      val chave = "4226%040d".format(++n)
      db.receipts().insert(ReceiptRow(chave, null, at, null, null, null, 1, null, null, null))
      db.catalog().putIssuer(IssuerRow(store, "Loja $store"))
      db.catalog().putParsed(ReceiptParsedRow(chave, store, at, null, null, null, 1, null))
      db.catalog().insertLines(listOf(ReceiptLineRow(chave, 1, "X", "x", qtyE4, unit, priceCents * 100, priceCents * qtyE4 / 10_000, item,
        discountCents = 0, discountSource = "html")))
    }
  }

  private fun lookup(vararg cadernetas: Pair<String?, ReceiptDatabase>) = ProductLookup { codes ->
    cadernetas.map { (ledger, db) -> ledger to db.spending().gtinLines(codes) }
  }

  @Test fun tellsProductsFromScaleLabelsAndStoreCodes() {
    assertEquals(CodeKind.PRODUCT, Gtin.kind(coffee))
    assertEquals(CodeKind.PRODUCT, Gtin.kind(gtin("03600029145"))) // a UPC-A, 12 digits
    assertEquals(CodeKind.SCALE_LABEL, Gtin.kind(gtin("200123400599")))
    assertEquals(CodeKind.STORE_CODE, Gtin.kind(gtin("0012345")))
    assertEquals(CodeKind.NOT_A_PRODUCT, Gtin.kind(coffee.dropLast(1) + ((coffee.last() - '0' + 1) % 10)))
    assertEquals(CodeKind.NOT_A_PRODUCT, Gtin.kind("ABC123"))
    assertEquals(CodeKind.NOT_A_PRODUCT, Gtin.kind("12345"))
  }

  @Test fun spellingsOnlyAddOrDropZeros() {
    val upc = gtin("03600029145")
    assertEquals(setOf(upc, "0$upc", "00$upc"), Gtin.variants(upc).toSet())
    assertEquals(setOf(upc, "0$upc", "00$upc"), Gtin.variants("0$upc").toSet())
    assertEquals(setOf(coffee, "0$coffee"), Gtin.variants(coffee).toSet())
  }

  @Test fun findsTheLastPriceThePriceAtEachStoreAndTheRecentAverage() = runTest {
    val db = testDatabase()
    Fixture(db).apply {
      product("p1", "Café torrado 500 g", "cafe")
      item("s1", coffee, "p1", "CAFE TORRADO 500G")
      buy("s1", "A", now - 200 * day, 1590) // outside the average
      buy("s1", "A", now - 60 * day, 1890)
      buy("s1", "B", now - 30 * day, 1790)
      buy("s1", "A", now - 2 * day, 1990)
    }
    val r = lookup("pessoal" to db).find(coffee, now) as LookupResult.Found
    val s = r.sightings.single()
    assertEquals("pessoal", s.ledger)
    assertEquals("Café torrado 500 g", s.name)
    assertEquals("Cafe", s.categoryLabel)
    assertEquals(1990L to now - 2 * day, s.last.cents to s.last.at)
    assertEquals("Loja A", s.last.storeName)
    // The latest price at each store, cheapest first: B's 17,90 beats A's latest 19,90 (not A's old 15,90).
    assertEquals(listOf("B" to 1790L, "A" to 1990L), s.byStore.map { it.storeCnpj to it.cents })
    assertEquals((1890L + 1790 + 1990) / 3, s.averageCents)
    assertEquals(4, s.purchases)
    assertEquals("un", s.basis)
  }

  @Test fun aUpcReadAsTwelveDigitsFindsTheReceiptsThirteen() = runTest {
    val db = testDatabase()
    val upc = gtin("03600029145")
    Fixture(db).apply { product("p1", null); item("s1", "0$upc", "p1", "ADOCANTE 100ML"); buy("s1", "A", now - day, 899) }
    val s = (lookup(null to db).find(upc, now) as LookupResult.Found).sightings.single()
    assertEquals("the store's description when the product has no name", "ADOCANTE 100ML", s.name)
    assertNull(s.ledger)
    assertNull(s.categoryLabel)
  }

  @Test fun everyCadernetaCountsTheOpenOneFirst() = runTest {
    val open = testDatabase()
    val other = testDatabase()
    Fixture(open).apply { product("p1", "Café"); item("s1", coffee, "p1", "CAFE"); buy("s1", "A", now - 50 * day, 1800) }
    Fixture(other).apply { product("q1", "Café do escritório"); item("t1", coffee, "q1", "CAFE"); buy("t1", "C", now - day, 1700) }
    val empty = testDatabase()
    val r = lookup("casa" to open, "vazia" to empty, "escritorio" to other).find(coffee, now) as LookupResult.Found
    assertEquals(listOf("casa", "escritorio"), r.sightings.map { it.ledger })
    assertEquals("Café do escritório", r.sightings[1].name)
  }

  @Test fun neverBoughtScaleLabelsAndOtherCodes() = runTest {
    val db = testDatabase()
    Fixture(db).apply {
      product("p1", "Banana")
      // A store code that happens to match digits is never a GTIN hit.
      item("s1", coffee, "p1", "BANANA KG", kind = "store")
      buy("s1", "A", now - day, 599, unit = "KG")
    }
    assertTrue(lookup("x" to db).find(coffee, now) is LookupResult.NeverBought)
    var searched = false
    val spy = ProductLookup { searched = true; emptyList() }
    assertTrue(spy.find(gtin("200123400599"), now) is LookupResult.ScaleLabel)
    assertTrue(spy.find(gtin("0012345"), now) is LookupResult.NotAProduct)
    assertTrue(spy.find("https://sat.sef.sc.gov.br/nfce/consulta?p=1", now) is LookupResult.NotAProduct)
    assertFalse("only a product's GTIN is looked up", searched)
  }

  @Test fun pricesStayInOneBasis() = runTest {
    val db = testDatabase()
    Fixture(db).apply {
      product("p1", "Queijo")
      item("s1", coffee, "p1", "QUEIJO")
      buy("s1", "A", now - 10 * day, 1200) // a unit
      buy("s1", "A", now - day, 4990, unit = "KG", qtyE4 = 4_000) // per kg, newest
    }
    val s = (lookup("x" to db).find(coffee, now) as LookupResult.Found).sightings.single()
    assertEquals("kg", s.basis)
    assertEquals(1, s.purchases)
    assertEquals(4990L, s.averageCents)
  }

  @Test fun aDeletedStoreItemOrProductIsNotFound() = runTest {
    val db = testDatabase()
    Fixture(db).apply { product("p1", "Café"); item("s1", coffee, "p1", "CAFE"); buy("s1", "A", now - day, 1800) }
    db.catalog().updateProduct(db.edit().productNow("p1")!!.copy(deleted = true))
    assertTrue(lookup("x" to db).find(coffee, now) is LookupResult.NeverBought)
  }
}
