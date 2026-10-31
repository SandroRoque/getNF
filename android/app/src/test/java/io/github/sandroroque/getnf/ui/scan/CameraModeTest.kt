package io.github.sandroroque.getnf.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One camera for receipts and products (camera plan, step 2): what it decides it's looking at. */
class CameraModeTest {
  private val w = 1440
  private val h = 1920
  // A made-up receipt (valid check digit) and made-up product codes.
  private val qr = ScannedCode("https://sat.sef.sc.gov.br/nfce/consulta?p=42260111222333000181651090000000011123456784|2|1|1|x", true, box(720.0, 1700.0))
  private val coffee = "7890000000017"
  private val tea = "7890000000024"

  private fun box(x: Double, y: Double) = listOf(DocumentPoint(x - 100, y - 40), DocumentPoint(x + 100, y - 40), DocumentPoint(x + 100, y + 40), DocumentPoint(x - 100, y + 40))
  private fun product(code: String, x: Double = 720.0, y: Double = 960.0) = ScannedCode(code, false, box(x, y))

  /** [codes] in three frames in a row, ending at [at] (what confirms a barcode): the last step, announced by any. */
  private fun CameraModeDecider.shown(at: Long, codes: List<ScannedCode>): ModeStep {
    val steps = listOf(offer(at - 2, w, h, codes), offer(at - 1, w, h, codes), offer(at, w, h, codes))
    return steps.last().copy(newProduct = steps.any { it.newProduct }, dropReceipt = steps.any { it.dropReceipt })
  }

  @Test fun aCentredBarcodeMakesItAProductAndDropsTheReceiptAttempt() {
    val d = CameraModeDecider()
    assertEquals(CameraMode.UNDECIDED, d.offer(0, w, h, emptyList()).mode)
    val s = d.shown(200, listOf(product(coffee)))
    assertEquals(CameraMode.PRODUCT, s.mode)
    assertEquals(coffee, s.product)
    assertTrue(s.newProduct)
    assertTrue("whatever the receipt capture took was the packaging", s.dropReceipt)
  }

  @Test fun theSameCodeAgainDoesntReopenTheSheetAnotherOneDoes() {
    val d = CameraModeDecider()
    d.shown(0, listOf(product(coffee)))
    val again = d.shown(200, listOf(product(coffee)))
    assertFalse(again.newProduct)
    assertFalse(again.dropReceipt)
    // Between reads, frames without the code keep the product while it's recent.
    assertEquals(coffee, d.offer(1500, w, h, emptyList()).product)
    // Another product: once it's read more often than the first in the last frames.
    repeat(2) { d.offer(1600L + it, w, h, listOf(product(tea))) }
    val other = d.shown(1700, listOf(product(tea)))
    assertTrue(other.newProduct)
    assertEquals(tea, other.product)
  }

  @Test fun aBarcodeAtTheEdgeOrAStoreCodeIsIgnored() {
    val d = CameraModeDecider()
    assertEquals(CameraMode.UNDECIDED, d.shown(0, listOf(product(coffee, x = 60.0))).mode)
    assertEquals(CameraMode.UNDECIDED, d.shown(0, listOf(product(coffee, y = 1880.0))).mode)
    assertEquals(CameraMode.UNDECIDED, d.shown(0, listOf(product("00123457"))).mode) // a store's GTIN-8
    assertEquals(CameraMode.UNDECIDED, d.shown(0, listOf(product("7890000000018"))).mode) // bad check digit
  }

  @Test fun aScaleLabelIsAnnouncedToSayWhyItCantBeLookedUp() {
    val s = CameraModeDecider().shown(0, listOf(product("2001234005999")))
    assertEquals(CameraMode.PRODUCT, s.mode)
    assertEquals("2001234005999", s.product)
  }

  @Test fun theReceiptsQrLocksTheReceiptAndProductsAreIgnored() {
    val d = CameraModeDecider()
    assertEquals(CameraMode.RECEIPT, d.offer(0, w, h, listOf(qr)).mode)
    val s = d.shown(200, listOf(product(coffee)))
    assertEquals(CameraMode.RECEIPT, s.mode)
    assertNull(s.product)
    assertFalse(s.dropReceipt)
  }

  @Test fun aQrCodeThatIsntAReceiptDecidesNothing() {
    val d = CameraModeDecider()
    assertEquals(CameraMode.UNDECIDED, d.offer(0, w, h, listOf(ScannedCode("https://example.com/menu", true, box(720.0, 960.0)))).mode)
  }

  @Test fun fromAProductAReceiptsQrSwitchesToTheReceipt() {
    val d = CameraModeDecider()
    d.shown(0, listOf(product(coffee)))
    val s = d.offer(300, w, h, listOf(qr))
    assertEquals(CameraMode.RECEIPT, s.mode)
    assertNull(s.product)
  }

  @Test fun noBarcodeForAWhileGoesBackToUndecided() {
    val d = CameraModeDecider()
    d.shown(0, listOf(product(coffee)))
    assertEquals(CameraMode.PRODUCT, d.offer(CameraModeDecider.PRODUCT_TIMEOUT_MS, w, h, emptyList()).mode)
    val s = d.offer(CameraModeDecider.PRODUCT_TIMEOUT_MS + 1, w, h, emptyList())
    assertEquals(CameraMode.UNDECIDED, s.mode)
    assertNull(s.product)
    // Back to the same product later: announced again, since its sheet was left.
    assertTrue(d.shown(CameraModeDecider.PRODUCT_TIMEOUT_MS + 500, listOf(product(coffee))).newProduct)
  }

  @Test fun openedForAProductItNeverTakesAReceipt() {
    val d = CameraModeDecider(productOnly = true)
    val start = d.offer(0, w, h, emptyList())
    assertEquals(CameraMode.PRODUCT, start.mode)
    assertNull(start.product)
    assertFalse(start.newProduct)
    assertEquals("a receipt's QR code doesn't start a capture", CameraMode.PRODUCT, d.offer(100, w, h, listOf(qr)).mode)
    assertTrue(d.shown(200, listOf(product(coffee))).newProduct)
    // Gone a while: still a product camera, and the same code is announced again when it's back.
    val gone = d.offer(200 + CameraModeDecider.PRODUCT_TIMEOUT_MS + 1, w, h, emptyList())
    assertEquals(CameraMode.PRODUCT to null, gone.mode to gone.product)
    assertTrue(d.shown(4000, listOf(product(coffee))).newProduct)
  }

  @Test fun misreadsOfAShakyBarcodeAreNeverAnnounced() {
    // The phone's session of 2026-10-31 03:22 (D133): one box, its code misread now and then into others.
    val real = "7896714292687"
    val frames = listOf(listOf(real), listOf("7296714892687", real), listOf(real), listOf(real, "3290770898687"), listOf("3299780898687"),
      listOf(real), listOf("3890770298687"), emptyList(), emptyList(), listOf(real)) +
      List(12) { emptyList<String>() } + listOf(listOf("3290770898687"), emptyList(), listOf("3290770898687"), listOf(real), listOf(real),
      listOf(real)) + List(5) { emptyList<String>() } + listOf(listOf("3290770898687"), emptyList(), listOf(real), emptyList(),
      listOf("126511292687"), emptyList(), listOf(real), listOf(real))
    val d = CameraModeDecider()
    val announced = frames.mapIndexedNotNull { i, codes ->
      d.offer(i * 230L, w, h, codes.map { product(it) }).takeIf { it.newProduct }?.product
    }
    // Announced again only after it went unread for a while (the glare): never a misread.
    assertEquals(setOf(real), announced.toSet())
  }
}
