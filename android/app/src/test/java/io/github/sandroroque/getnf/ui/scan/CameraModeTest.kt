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

  @Test fun aCentredBarcodeMakesItAProductAndDropsTheReceiptAttempt() {
    val d = CameraModeDecider()
    assertEquals(CameraMode.UNDECIDED, d.offer(0, w, h, emptyList()).mode)
    val s = d.offer(200, w, h, listOf(product(coffee)))
    assertEquals(CameraMode.PRODUCT, s.mode)
    assertEquals(coffee, s.product)
    assertTrue(s.newProduct)
    assertTrue("whatever the receipt capture took was the packaging", s.dropReceipt)
  }

  @Test fun theSameCodeAgainDoesntReopenTheSheetAnotherOneDoes() {
    val d = CameraModeDecider()
    d.offer(0, w, h, listOf(product(coffee)))
    val again = d.offer(200, w, h, listOf(product(coffee)))
    assertFalse(again.newProduct)
    assertFalse(again.dropReceipt)
    // Between reads, frames without the code keep the product while it's recent.
    assertEquals(coffee, d.offer(1500, w, h, emptyList()).product)
    val other = d.offer(1700, w, h, listOf(product(tea)))
    assertTrue(other.newProduct)
    assertEquals(tea, other.product)
  }

  @Test fun aBarcodeAtTheEdgeOrAStoreCodeIsIgnored() {
    val d = CameraModeDecider()
    assertEquals(CameraMode.UNDECIDED, d.offer(0, w, h, listOf(product(coffee, x = 60.0))).mode)
    assertEquals(CameraMode.UNDECIDED, d.offer(0, w, h, listOf(product(coffee, y = 1880.0))).mode)
    assertEquals(CameraMode.UNDECIDED, d.offer(0, w, h, listOf(product("00123457"))).mode) // a store's GTIN-8
    assertEquals(CameraMode.UNDECIDED, d.offer(0, w, h, listOf(product("7890000000018"))).mode) // bad check digit
  }

  @Test fun aScaleLabelIsAnnouncedToSayWhyItCantBeLookedUp() {
    val s = CameraModeDecider().offer(0, w, h, listOf(product("2001234005999")))
    assertEquals(CameraMode.PRODUCT, s.mode)
    assertEquals("2001234005999", s.product)
  }

  @Test fun theReceiptsQrLocksTheReceiptAndProductsAreIgnored() {
    val d = CameraModeDecider()
    assertEquals(CameraMode.RECEIPT, d.offer(0, w, h, listOf(qr)).mode)
    val s = d.offer(200, w, h, listOf(product(coffee)))
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
    d.offer(0, w, h, listOf(product(coffee)))
    val s = d.offer(300, w, h, listOf(qr))
    assertEquals(CameraMode.RECEIPT, s.mode)
    assertNull(s.product)
  }

  @Test fun noBarcodeForAWhileGoesBackToUndecided() {
    val d = CameraModeDecider()
    d.offer(0, w, h, listOf(product(coffee)))
    assertEquals(CameraMode.PRODUCT, d.offer(CameraModeDecider.PRODUCT_TIMEOUT_MS, w, h, emptyList()).mode)
    val s = d.offer(CameraModeDecider.PRODUCT_TIMEOUT_MS + 1, w, h, emptyList())
    assertEquals(CameraMode.UNDECIDED, s.mode)
    assertNull(s.product)
    // Back to the same product later: announced again, since its sheet was left.
    assertTrue(d.offer(CameraModeDecider.PRODUCT_TIMEOUT_MS + 500, w, h, listOf(product(coffee))).newProduct)
  }
}
