package io.github.sandroroque.getnf.data

import android.content.Context
import androidx.core.content.edit
import io.github.sandroroque.getnf.BuildConfig

data class ServerConfig(val url: String, val email: String, val password: String) {
  val isComplete: Boolean
    get() = url.isNotBlank() && email.isNotBlank() && password.isNotBlank()
}

/** PocketBase address and app login, kept in app-private SharedPreferences. */
class ServerSettings(context: Context) {
  private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

  fun get(): ServerConfig =
    ServerConfig(
      url = prefs.getString(URL, null) ?: BuildConfig.DEFAULT_SERVER_URL,
      email = prefs.getString(EMAIL, null) ?: BuildConfig.DEFAULT_EMAIL,
      password = prefs.getString(PASSWORD, null) ?: BuildConfig.DEFAULT_PASSWORD,
    )

  fun set(config: ServerConfig) =
    prefs.edit {
      putString(URL, config.url.trim().trimEnd('/'))
      putString(EMAIL, config.email.trim())
      putString(PASSWORD, config.password)
    }

  private companion object {
    const val URL = "url"
    const val EMAIL = "email"
    const val PASSWORD = "password"
  }
}
