package io.github.sandroroque.getnf.data

import android.content.Context
import androidx.core.content.edit

/** Switches for developing the app, shown in debug builds only (D99). */
class DevSettings(context: Context) {
  private val prefs = context.getSharedPreferences("dev", Context.MODE_PRIVATE)

  /**
   * Testing the capture: scans of receipts already known end without offering their SEFAZ page, and no reminder comes,
   * so the lookups the shared IP allows aren't spent on them (docs/sefaz-sc.md).
   */
  var testingCapture: Boolean
    get() = prefs.getBoolean(TESTING_CAPTURE, false)
    set(value) = prefs.edit { putBoolean(TESTING_CAPTURE, value) }

  private companion object { const val TESTING_CAPTURE = "testingCapture" }
}
