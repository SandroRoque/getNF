package io.github.sandroroque.getnf.ui.scan

import android.os.SystemClock
import android.util.Log
import io.github.sandroroque.getnf.BuildConfig

/** Local debug timings only; never logs images, text, QR payloads or receipt identifiers. */
internal object CapturePerf {
  fun now() = SystemClock.elapsedRealtimeNanos()
  fun elapsed(stage: String, since: Long) {
    if (BuildConfig.DEBUG) Log.d("CapturePerf", "$stage=${(now() - since) / 1_000_000}ms")
  }
  inline fun <T> measure(stage: String, block: () -> T): T {
    val start = now()
    try { return block() } finally { elapsed(stage, start) }
  }
}
