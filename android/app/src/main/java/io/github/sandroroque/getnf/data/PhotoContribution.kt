package io.github.sandroroque.getnf.data

import android.content.Context
import androidx.core.content.edit

/**
 * Whether the user lets receipt photos go up to help improve the app (D103). Off until they say so; the photos that go
 * are those of receipts the app failed to read, with the card slip and CPF blanked (D98). A small sample of the
 * others is meant to join them, how to be decided (docs/capture-backlog.md).
 */
class PhotoContribution(context: Context, private val database: ReceiptDatabase) {
  private val prefs = context.getSharedPreferences("contribution", Context.MODE_PRIVATE)

  /** The setting in Ajustes. */
  var enabled: Boolean
    get() = prefs.getBoolean(ENABLED, false)
    set(value) = prefs.edit { putBoolean(ENABLED, value) }

  /** The one time the app offered it after a failure; whatever the answer, it isn't offered again. */
  var asked: Boolean
    get() = prefs.getBoolean(ASKED, false)
    set(value) = prefs.edit { putBoolean(ASKED, value) }

  /** Whether this receipt's photos go up: the user allowed it, and the app failed with this receipt. */
  suspend fun wants(receipt: Receipt): Boolean {
    if (!enabled || receipt.photos.isEmpty()) return false
    val discount = database.catalog().parsed(receipt.chave)?.discountCents ?: return false
    return isFailure(discount, receipt.photos.size, database.catalog().lines(receipt.chave).map { it.discountSource })
  }

  companion object {
    private const val ENABLED = "photos"
    private const val ASKED = "asked"

    /**
     * The app couldn't read a receipt's discounts from its photos: the receipt has a discount and photos, and no line
     * has a discount read from them or set by hand, and some line still has its prorated share.
     */
    fun isFailure(discountCents: Long, photos: Int, discountSources: List<String>) =
      discountCents > 0 && photos > 0 && "prorated" in discountSources && discountSources.none { it == "photo" || it == "manual" }
  }
}
