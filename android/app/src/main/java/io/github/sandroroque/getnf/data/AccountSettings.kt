package io.github.sandroroque.getnf.data

import android.content.Context
import androidx.core.content.edit
import io.github.sandroroque.getnf.BuildConfig
import io.github.sandroroque.getnf.sync.SupabaseSession

/** Which Supabase project to use, and who is signed in. The project's publishable key is public by design. */
data class ProjectConfig(val url: String, val key: String) {
  val isComplete: Boolean get() = url.isNotBlank() && key.isNotBlank()
}

/**
 * Where a caderneta's data lives on this phone (D104): its own database and receipts folder, so cadernetas never mix
 * and joining one starts empty. The phone's original files belong to the first caderneta it opened.
 */
data class LocalFiles(val database: String, val receipts: String) {
  companion object {
    /** The demo caderneta's (D115). */
    val DEMO = LocalFiles("getnf-demo.db", "receipts-demo")

    fun of(open: String?, legacy: String?): LocalFiles =
      if (open == null || open == legacy) LocalFiles(ReceiptDatabase.NAME, "receipts") else LocalFiles("getnf-$open.db", "receipts-$open")
  }
}

/** The signed-in account: kept is the refresh token, never the password (D70). */
data class Account(val userId: String, val email: String, val refreshToken: String, val name: String = "")

/** Kept in app-private SharedPreferences (app data is excluded from Android backups). */
class AccountSettings(context: Context) {
  private val prefs = context.getSharedPreferences("supabase", Context.MODE_PRIVATE)

  fun project(): ProjectConfig = ProjectConfig(
    url = prefs.getString(URL, null) ?: BuildConfig.DEFAULT_SUPABASE_URL,
    key = prefs.getString(KEY, null) ?: BuildConfig.DEFAULT_SUPABASE_KEY,
  )

  /** Another project means another account: signs out. */
  fun setProject(config: ProjectConfig) = prefs.edit {
    if (config != project()) { remove(USER); remove(EMAIL); remove(REFRESH); remove(NAME); remove(LAST_SYNC) }
    putString(URL, config.url.trim().trimEnd('/')); putString(KEY, config.key.trim())
  }

  fun account(): Account? {
    val user = prefs.getString(USER, null) ?: return null
    return Account(user, prefs.getString(EMAIL, null) ?: "", prefs.getString(REFRESH, null) ?: return null, prefs.getString(NAME, null) ?: "")
  }

  /** Each refresh hands out a new refresh token and retires the old one: always keep the latest. */
  fun signedIn(session: SupabaseSession) = prefs.edit {
    if (session.userId != prefs.getString(USER, null)) { remove(LAST_SYNC); remove(NAME) }
    putString(USER, session.userId); putString(EMAIL, session.email); putString(REFRESH, session.refreshToken)
    if (session.name.isNotBlank()) putString(NAME, session.name)
  }

  /** When the last sync finished without an error (D123), for "Sincronizado há 5 min" in Ajustes; 0 for never. */
  var lastSync: Long
    get() = prefs.getLong(LAST_SYNC, 0)
    set(value) = prefs.edit { putLong(LAST_SYNC, value) }

  /** Signing out leaves the open caderneta's data on the phone, and open: nothing is deleted. */
  fun signOut() = prefs.edit { remove(USER); remove(EMAIL); remove(REFRESH); remove(NAME); remove(LAST_SYNC) }

  /** The caderneta this phone has open (D104); null until the first sync picks one. */
  fun ledgerId(): String? = prefs.getString(LEDGER, null)

  /**
   * Opens [id]; takes effect on the next start (the app restarts). The first caderneta ever opened takes the data the
   * phone already had (D104): it keeps the original files.
   */
  // commit, not apply: the app restarts right after, and an asynchronous write would be lost with the process.
  fun openLedger(id: String) = prefs.edit(commit = true) {
    putString(LEDGER, id)
    if (prefs.getString(LEGACY, null) == null) putString(LEGACY, id)
  }

  /** Where the open caderneta's data lives on this phone; the demo's own files while it's open (D115). */
  fun localFiles(): LocalFiles = if (demo) LocalFiles.DEMO else filesFor(ledgerId())

  /**
   * The demo caderneta is open (D115): made-up receipts in files of their own, never synced. Takes effect on the next
   * start, like a caderneta.
   */
  var demo: Boolean
    get() = prefs.getBoolean(DEMO, false)
    // commit: the app restarts right after.
    set(value) { prefs.edit(commit = true) { putBoolean(DEMO, value) } }

  fun filesFor(ledger: String?): LocalFiles = LocalFiles.of(ledger, prefs.getString(LEGACY, null))

  private companion object {
    const val URL = "url"
    const val KEY = "key"
    const val USER = "user"
    const val EMAIL = "email"
    const val REFRESH = "refresh"
    const val LEDGER = "ledger"
    const val LEGACY = "legacy_ledger"
    const val DEMO = "demo"
    const val NAME = "name"
    const val LAST_SYNC = "last_sync"
  }
}
