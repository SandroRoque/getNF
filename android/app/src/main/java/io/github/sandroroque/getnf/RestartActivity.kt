package io.github.sandroroque.getnf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import kotlin.system.exitProcess

/**
 * Relaunches the app in a fresh process (D47 restore). Android 14+ won't let an alarm open an activity for an app
 * whose process is dead, so this runs in its own `:restart` process, started while the app is still in the
 * foreground: it kills the old process, then opens the app again.
 */
class RestartActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Process.killProcess(intent.getIntExtra(EXTRA_PID, -1))
    startActivity(packageManager.getLaunchIntentForPackage(packageName)!!
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    finish()
    exitProcess(0)
  }

  companion object {
    const val PROCESS_SUFFIX = ":restart"
    private const val EXTRA_PID = "pid"

    /** Never returns. */
    fun restart(context: Context): Nothing {
      context.startActivity(Intent(context, RestartActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_PID, Process.myPid()))
      exitProcess(0)
    }
  }
}
