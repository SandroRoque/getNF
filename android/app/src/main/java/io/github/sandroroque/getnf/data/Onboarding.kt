package io.github.sandroroque.getnf.data

import android.content.Context
import androidx.core.content.edit

/** What a new user has already been shown (D115): the welcome, the scan's how-to, the first steps on Início. */
class Onboarding(context: Context) {
  private val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

  var welcomeSeen: Boolean
    get() = prefs.getBoolean("welcome", false)
    set(value) = prefs.edit { putBoolean("welcome", value) }

  var scanIntroSeen: Boolean
    get() = prefs.getBoolean("scan_intro", false)
    set(value) = prefs.edit { putBoolean("scan_intro", value) }

  var firstStepsHidden: Boolean
    get() = prefs.getBoolean("first_steps_hidden", false)
    set(value) = prefs.edit { putBoolean("first_steps_hidden", value) }
}
