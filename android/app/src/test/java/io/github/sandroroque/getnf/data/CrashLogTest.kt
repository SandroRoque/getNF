package io.github.sandroroque.getnf.data

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's record of its crashes (D140). */
class CrashLogTest {
  private val dir = Files.createTempDirectory("crashes").toFile()
  private val prefs = object : CrashLog.Prefs { override var dismissedAt = 0L }
  private val log = CrashLog(dir, "Caderneta 0.0.3 (3) · Android 16 · Test phone", prefs)

  @Test fun aCrashIsOfferedOnceWithWhatHappened() {
    assertNull(log.pending())
    log.record(1_000, "main", IllegalStateException("boom", RuntimeException("the cause")))
    val r = log.pending()!!
    assertEquals(1_000L, r.at)
    assertTrue(r.text.startsWith("Caderneta 0.0.3 (3) · Android 16 · Test phone\n"))
    assertTrue(r.text.contains("thread: main"))
    assertTrue(r.text.contains("IllegalStateException: boom"))
    assertTrue("the cause chain is kept", r.text.contains("Caused by: java.lang.RuntimeException: the cause"))
    log.dismiss(r)
    assertNull(log.pending())
    // A newer crash is offered again.
    log.record(2_000, "worker", OutOfMemoryError())
    assertEquals(2_000L, log.pending()!!.at)
  }

  @Test fun onlyTheNewestFewAreKept() {
    repeat(CrashLog.KEEP + 3) { log.record(1_000L + it, "main", RuntimeException("n$it")) }
    val left = dir.listFiles()!!.map { it.name }.sorted()
    assertEquals((3 until CrashLog.KEEP + 3).map { "${1_000 + it}.txt" }, left)
  }

  @Test fun theHandlerRecordsThenLetsThePreviousOneEndTheProcess() {
    val before = Thread.getDefaultUncaughtExceptionHandler()
    var passedOn: Throwable? = null
    try {
      Thread.setDefaultUncaughtExceptionHandler { _, e -> passedOn = e }
      log.install(now = { 5_000 })
      val error = RuntimeException("uncaught")
      Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
      assertEquals(error, passedOn)
      assertEquals(5_000L, log.pending()!!.at)
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(before)
    }
  }
}
