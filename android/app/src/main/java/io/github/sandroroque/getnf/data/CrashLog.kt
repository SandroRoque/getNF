package io.github.sandroroque.getnf.data

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The app's own record of its crashes (D140): an uncaught exception is written to [dir] before the process dies, and the
 * next start offers to send it. Play's Android vitals counts crashes of Play installs; this one says what happened on a
 * given phone, debug builds included, and the person decides whether it leaves the phone (they send it themselves).
 *
 * One file per crash, named by its time; the newest [KEEP] stay (`adb pull` the folder to read them). [dismissedAt]
 * remembers the newest one already offered, so a report is offered once.
 */
class CrashLog(private val dir: File, val about: String, private val prefs: Prefs) {
  /** Where "dismissed up to" is kept: the app's SharedPreferences, a map in tests. */
  interface Prefs { var dismissedAt: Long }

  class Report(val at: Long, val text: String)

  /** Wraps the current default handler: write the report, then let the previous handler end the process as before. */
  fun install(now: () -> Long = System::currentTimeMillis) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      runCatching { record(now(), thread.name, error) }
      previous?.uncaughtException(thread, error)
    }
  }

  fun record(at: Long, thread: String, error: Throwable) {
    dir.mkdirs()
    File(dir, "$at.txt").writeText(format(at, thread, error))
    dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().sortedByDescending { it.name.removeSuffix(".txt").toLongOrNull() ?: 0 }
      .drop(KEEP).forEach { it.delete() }
  }

  /** The newest crash not yet offered, or null. */
  fun pending(): Report? {
    val newest = dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
      .mapNotNull { f -> f.name.removeSuffix(".txt").toLongOrNull()?.let { it to f } }.maxByOrNull { it.first } ?: return null
    if (newest.first <= prefs.dismissedAt) return null
    return Report(newest.first, runCatching { newest.second.readText() }.getOrElse { return null })
  }

  fun dismiss(report: Report) { prefs.dismissedAt = maxOf(prefs.dismissedAt, report.at) }

  internal fun format(at: Long, thread: String, error: Throwable): String {
    val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    return "$about\nquando: ${java.time.Instant.ofEpochMilli(at)}\nthread: $thread\n\n" + trace.take(MAX_TRACE)
  }

  companion object {
    const val KEEP = 5
    /** Enough for the cause chain; a stack overflow's trace is cut. */
    private const val MAX_TRACE = 24_000
  }
}
