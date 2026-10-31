package io.github.sandroroque.getnf.ui.scan

/**
 * When a receipt attempt drops by itself (D149). An attempt is provisional until there's proof it's a receipt: its
 * NFC-e QR code read inside the paper (R2; then nothing drops without the person deciding). A provisional attempt that
 * has started (rows assembled or a photo taken) and loses the paper for [lossMs] is dropped and the capture starts
 * afresh (R1), the way the product camera goes back after a while without a barcode (D131): whatever it was looking
 * at, packaging or a sheet of paper, is gone, and its rows mustn't hold the next attempt back.
 *
 * Fed one frame at a time with the frame's own time, so a replay decides exactly as the phone did.
 */
internal class AttemptGuard(private val lossMs: Long = LOSS_MS) {
  private var seenAt: Long? = null

  /** True when the attempt must be dropped on this frame. */
  fun offer(atMs: Long, paperSeen: Boolean, proven: Boolean, started: Boolean): Boolean {
    if (proven || !started || paperSeen) { seenAt = atMs; return false }
    val since = seenAt ?: atMs.also { seenAt = it }
    return atMs - since > lossMs
  }

  fun reset() { seenAt = null }

  companion object {
    /** The product camera's own wait (CameraModeDecider.PRODUCT_TIMEOUT_MS): one rhythm for "it's gone". */
    const val LOSS_MS = 2500L
  }
}
