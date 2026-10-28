package io.github.sandroroque.getnf.data

import android.content.Context
import androidx.core.content.edit
import io.github.sandroroque.getnf.BuildConfig
import io.github.sandroroque.getnf.sync.SupabaseSession

/** Which Supabase project to use, and who is signed in. The project's publishable key is public by design. */
data class ProjectConfig(val url: String, val key: String) {
  val isComplete: Boolean get() = url.isNotBlank() && key.isNotBlank()
}

/** The signed-in account: kept is the refresh token, never the password (D70). */
data class Account(val userId: String, val email: String, val refreshToken: String)

/** Kept in app-private SharedPreferences (app data is excluded from Android backups). */
class AccountSettings(context: Context) {
  private val prefs = context.getSharedPreferences("supabase", Context.MODE_PRIVATE)

  fun project(): ProjectConfig = ProjectConfig(
    url = prefs.getString(URL, null) ?: BuildConfig.DEFAULT_SUPABASE_URL,
    key = prefs.getString(KEY, null) ?: BuildConfig.DEFAULT_SUPABASE_KEY,
  )

  /** Another project means another account: signs out. */
  fun setProject(config: ProjectConfig) = prefs.edit {
    if (config != project()) { remove(USER); remove(EMAIL); remove(REFRESH); remove(LEDGER) }
    putString(URL, config.url.trim().trimEnd('/')); putString(KEY, config.key.trim())
  }

  fun account(): Account? {
    val user = prefs.getString(USER, null) ?: return null
    return Account(user, prefs.getString(EMAIL, null) ?: "", prefs.getString(REFRESH, null) ?: return null)
  }

  /** Each refresh hands out a new refresh token and retires the old one: always keep the latest. */
  fun signedIn(session: SupabaseSession) = prefs.edit {
    putString(USER, session.userId); putString(EMAIL, session.email); putString(REFRESH, session.refreshToken)
  }

  fun signOut() = prefs.edit { remove(USER); remove(EMAIL); remove(REFRESH); remove(LEDGER) }

  /** The caderneta this phone has open (D104); null until the first sync picks one. */
  fun ledgerId(): String? = prefs.getString(LEDGER, null)

  fun openLedger(id: String) = prefs.edit { putString(LEDGER, id) }

  private companion object {
    const val URL = "url"
    const val KEY = "key"
    const val USER = "user"
    const val EMAIL = "email"
    const val REFRESH = "refresh"
    const val LEDGER = "ledger"
  }
}
