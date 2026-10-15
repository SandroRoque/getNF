package io.github.sandroroque.getnf.data

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CaptureSegment(
  val photo: String,
  val mask: String,
  val polygon: List<List<Double>>,
  val bands: List<List<Double>>,
  val top: Double? = null,
  val bottom: Double? = null,
  val score: Double,
  val capturedAt: Long,
  val sessionId: String,
)

@Serializable
data class CaptureSessionManifest(
  val id: String,
  val chave: String? = null,
  val qrUrl: String? = null,
  val committing: Boolean = false,
  val segments: List<CaptureSegment> = emptyList(),
)

/** Images are provisional until the complete batch is imported into ReceiptStore. All calls run off main. */
class CaptureSessionStore(private val root: File) {
  private val json = Json { ignoreUnknownKeys = true }
  private val active = ConcurrentHashMap.newKeySet<String>()

  fun create(): CaptureSessionManifest {
    val session = CaptureSessionManifest(UUID.randomUUID().toString())
    active.add(session.id)
    dir(session.id).mkdirs()
    write(session)
    return session
  }
  fun dir(id: String) = File(root, id)
  fun write(session: CaptureSessionManifest) {
    val temp = File(dir(session.id), "session.json.tmp")
    java.io.FileOutputStream(temp).use { output ->
      output.write(json.encodeToString(session).toByteArray(Charsets.UTF_8)); output.fd.sync()
    }
    check(temp.renameTo(File(dir(session.id), "session.json"))) { "Could not persist capture session" }
  }
  fun remove(session: CaptureSessionManifest) {
    check(dir(session.id).deleteRecursively()) { "Could not remove temporary capture images" }
    active.remove(session.id)
  }
  suspend fun recover(receipts: ReceiptStore): Int {
    var deferred = 0
    for (directory in root.listFiles().orEmpty().filter { it.isDirectory && it.name !in active }) {
      val source = File(directory, "session.json")
      val session = runCatching { json.decodeFromString<CaptureSessionManifest>(source.readText()) }.getOrNull()
      if (session == null) { directory.deleteRecursively(); continue }
      try {
        if (session.chave != null) {
          if (session.committing) receipts.importCapture(session, directory)
          else receipts.open(session.chave, session.qrUrl)
        }
        remove(session)
      } catch (_: Exception) {
        // A damaged batch must not hide existing receipts or destroy the only recovery evidence.
        deferred++
      }
    }
    return deferred
  }
}
