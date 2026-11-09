package io.github.sandroroque.getnf.data

import io.github.sandroroque.getnf.sync.SupabaseException
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Reports for the developer (D152): a comment, a crash (D140) or rows the server refused (D145), sent by the person,
 * to the project's `feedback` table, signed in or not. Each waits here as a file until it's sent, so a report written
 * offline goes when there's a network (`FeedbackWorker`). What goes: the text, the app's [about] line (version and
 * phone) and [installId], a random id of this install; never the account.
 */
class FeedbackOutbox(private val dir: File, val about: String, private val installId: () -> String) {
  enum class Kind(val wire: String) { Comment("comentario"), Crash("travamento"), Refused("sincronizacao") }

  fun add(kind: Kind, body: String, at: Long = System.currentTimeMillis()) {
    dir.mkdirs()
    val row = buildJsonObject {
      put("kind", kind.wire)
      put("body", body.take(MAX_BODY))
      put("about", about.take(300))
      put("install_id", installId())
      put("written_at", java.time.Instant.ofEpochMilli(at).toString())
    }
    val temp = File(dir, "$at-${System.nanoTime()}.part")
    temp.writeText(row.toString())
    check(temp.renameTo(File(dir, temp.name.removeSuffix(".part") + ".json"))) { "could not save the report" }
  }

  fun pending(): List<File> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

  /**
   * Sends every waiting report through [post], oldest first; each sent is deleted. A network failure stops it (the
   * rest wait for the next try, and the [IOException] goes up for the worker to back off). So does the server's
   * flood limit (D176): it's about the hour or the day, not the report, and anyone with the app's key can fill the
   * hourly one, so the report waits. A report the server refuses for itself (a row it won't take) is dropped, since
   * sending it again won't change the answer.
   */
  fun send(onRefused: (String) -> Unit = {}, post: (JsonObject) -> Unit): Int {
    var sent = 0
    for (f in pending()) {
      val row = runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull()
      if (row == null) { f.delete(); continue }
      try {
        post(row)
        sent++
      } catch (e: SupabaseException) {
        if (e.code == OVER_LIMIT) throw IOException("the server is taking no reports for now: ${e.message}", e)
        onRefused(e.message ?: "refused")
      }
      f.delete()
    }
    return sent
  }

  companion object {
    /** The table's limit. */
    const val MAX_BODY = 30_000
    /** What `feedback_limits` raises (the migration of D152), as the Data API passes it on with its 400. */
    const val OVER_LIMIT = "P0001"
  }
}
