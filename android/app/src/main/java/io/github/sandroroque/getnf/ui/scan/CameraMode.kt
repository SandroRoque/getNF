package io.github.sandroroque.getnf.ui.scan

import io.github.sandroroque.getnf.data.Chave
import io.github.sandroroque.getnf.data.CodeKind
import io.github.sandroroque.getnf.data.Gtin

/**
 * What one camera is looking at (camera plan, step 2): a receipt or a product.
 *
 * - [UNDECIDED], at open: the receipt capture runs as it always has, and a product's barcode is looked for too.
 * - [RECEIPT], once the receipt's NFC-e QR code is read: product barcodes are ignored (one lying beside the receipt
 *   must not interrupt it) and the capture is exactly the receipt capture.
 * - [PRODUCT], once a product's barcode is confirmed near the middle of the frame while undecided: the receipt capture
 *   stops (what it started is dropped: it was the packaging) and OCR isn't needed, so frames come faster. An NFC-e QR
 *   code switches to [RECEIPT]; no product barcode for [PRODUCT_TIMEOUT_MS] goes back to [UNDECIDED].
 */
internal enum class CameraMode { UNDECIDED, RECEIPT, PRODUCT }

/** A code the scanner read: its text, whether it's a QR code, and its corners in upright frame pixels. */
internal data class ScannedCode(val raw: String, val qr: Boolean, val corners: List<DocumentPoint>)

/**
 * The decision on one frame. [product]: the product barcode being shown, in [CameraMode.PRODUCT]. [newProduct]: it
 * differs from the last one announced, so the lookup and its sheet should open (the same code read again doesn't).
 * [dropReceipt]: the receipt capture's attempt so far should be dropped.
 */
internal data class ModeStep(val mode: CameraMode, val product: String?, val newProduct: Boolean, val dropReceipt: Boolean)

/** [productOnly]: the camera opened to look a product up (the Catálogo's shortcut): no receipt is ever captured. */
internal class CameraModeDecider(private val productOnly: Boolean = false) {
  var mode = if (productOnly) CameraMode.PRODUCT else CameraMode.UNDECIDED
    private set
  private var product: String? = null
  private var announced: String? = null
  private var productSeenAt = 0L
  /** The centred product codes of the last [WINDOW] frames, newest last: a code counts once it's confirmed there. */
  private val recent = ArrayDeque<Set<String>>()

  /**
   * The code read in at least [CONFIRM] of the last [WINDOW] frames, and more often than any other. A shaky or glaring
   * view makes the scanner misread a barcode now and then into another valid-looking code (seen on the phone: five
   * different misreads of one box in 60 frames, none twice in a row); the real code comes back frame after frame.
   */
  private fun confirmed(): String? {
    val counts = recent.flatten().groupingBy { it }.eachCount()
    val best = counts.maxByOrNull { it.value } ?: return null
    return best.key.takeIf { best.value >= CONFIRM && counts.count { it.value == best.value } == 1 }
  }

  fun offer(atMs: Long, width: Int, height: Int, codes: List<ScannedCode>): ModeStep {
    if (mode == CameraMode.RECEIPT) return ModeStep(mode, null, false, false)
    if (!productOnly && codes.any { it.qr && Chave.fromQr(it.raw) != null }) {
      mode = CameraMode.RECEIPT; product = null
      return ModeStep(mode, null, false, false)
    }
    recent.addLast(codes.filter { !it.qr && Gtin.kind(it.raw) in LOOKED_UP && centred(it.corners, width, height) }.map { it.raw.trim() }.toSet())
    if (recent.size > WINDOW) recent.removeFirst()
    val sure = confirmed()
    var drop = false
    if (sure != null && sure in recent.last()) {
      if (mode == CameraMode.UNDECIDED) { mode = CameraMode.PRODUCT; drop = true }
      product = sure
      productSeenAt = atMs
    } else if (mode == CameraMode.PRODUCT && product != null && atMs - productSeenAt > PRODUCT_TIMEOUT_MS) {
      if (productOnly) { product = null; announced = null; return ModeStep(mode, null, false, false) }
      mode = CameraMode.UNDECIDED; product = null; announced = null
      return ModeStep(mode, null, false, false)
    }
    val new = mode == CameraMode.PRODUCT && product != announced
    if (new) announced = product
    return ModeStep(mode, product.takeIf { mode == CameraMode.PRODUCT }, new, drop)
  }

  companion object {
    /** A scale label is announced too: the sheet says why it can't be looked up. */
    private val LOOKED_UP = setOf(CodeKind.PRODUCT, CodeKind.SCALE_LABEL)
    const val PRODUCT_TIMEOUT_MS = 2500L
    const val WINDOW = 8
    const val CONFIRM = 3
    /** Near the middle: the code's centre within the middle [CENTRE] of the frame each way. */
    private const val CENTRE = .7

    private fun centre(corners: List<DocumentPoint>) =
      DocumentPoint(corners.sumOf { it.x } / corners.size, corners.sumOf { it.y } / corners.size)

    private fun centred(corners: List<DocumentPoint>, width: Int, height: Int): Boolean {
      if (corners.isEmpty()) return false
      val c = centre(corners)
      val margin = (1 - CENTRE) / 2
      return c.x in width * margin..width * (1 - margin) && c.y in height * margin..height * (1 - margin)
    }

    private fun offCentre(corners: List<DocumentPoint>, width: Int, height: Int): Double {
      val c = centre(corners)
      return kotlin.math.hypot(c.x / width - .5, c.y / height - .5)
    }
  }
}
