package io.github.sandroroque.getnf.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the phone knows about one receipt. Lives in `receipts/<chave>/receipt.json`, next to the
 * photos and the saved page (see docs/decisions.md D11).
 */
@Serializable
data class Receipt(
  val chave: String,
  val qrUrl: String? = null,
  /** Epoch millis of the first scan. */
  val capturedAt: Long,
  /** File names in the receipt directory, in the order they were taken. */
  val photos: List<String> = emptyList(),
  val pageUrl: String? = null,
  val pageFile: String? = null,
  val pageFetchedAt: Long? = null,
  /** Bumped on every local change. */
  val version: Long = 0,
  /** The [version] that was last uploaded successfully. */
  val uploadedVersion: Long? = null,
  val remoteId: String? = null,
  val uploadError: String? = null,
  val captureSessions: List<String> = emptyList(),
  val captureSegments: List<CaptureSegment> = emptyList(),
) {
  val needsUpload: Boolean
    get() = uploadedVersion != version
}

class ReceiptStore(private val root: File, private val now: () -> Long = System::currentTimeMillis) {
  private val mutex = Mutex()
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
  }
  private val _receipts = MutableStateFlow<List<Receipt>>(emptyList())

  /** All receipts, newest first. Call [load] once at startup. */
  val receipts: StateFlow<List<Receipt>> = _receipts.asStateFlow()

  fun dir(chave: String): File = File(root, chave)

  suspend fun load() = io { mutex.withLock { publish() } }

  suspend fun get(chave: String): Receipt? = io { mutex.withLock { read(chave) } }

  /** Returns the receipt for [chave], creating it on the first scan. */
  suspend fun open(chave: String, qrUrl: String?): Receipt = io {
    mutex.withLock {
      read(chave)
        ?: Receipt(chave = chave, qrUrl = qrUrl, capturedAt = now(), version = 1).also {
          dir(chave).mkdirs()
          write(it)
          publish()
        }
    }
  }

  /** A fresh file to hand to the camera. Call [addPhoto] once it's been written. */
  fun newPhotoFile(chave: String): File = File(dir(chave), "photo-${now()}.jpg")

  suspend fun addPhoto(chave: String, file: File) = update(chave) { it.copy(photos = it.photos + file.name) }

  suspend fun savePage(chave: String, url: String, html: String) {
    io { File(dir(chave), PAGE_FILE).writeText(html, Charsets.UTF_8) }
    update(chave) { it.copy(pageUrl = url, pageFile = PAGE_FILE, pageFetchedAt = now()) }
  }

  suspend fun markUploaded(chave: String, version: Long, remoteId: String) =
    update(chave, bump = false) { it.copy(uploadedVersion = version, remoteId = remoteId, uploadError = null) }

  suspend fun markUploadError(chave: String, message: String) =
    update(chave, bump = false) { it.copy(uploadError = message) }

  /** Idempotent batch import: manifest publication is the commit point; existing evidence is never replaced. */
  suspend fun importCapture(session: CaptureSessionManifest, source: File): Receipt = io { mutex.withLock {
    val key = requireNotNull(session.chave)
    val old = read(key) ?: Receipt(key, session.qrUrl, now())
    if (session.id in old.captureSessions) return@withLock old
    require(old.photos.size + session.segments.size <= MAX_PHOTOS) { "Limite de 100 imagens por nota atingido" }
    val destination = dir(key).apply { mkdirs() }
    val created = mutableListOf<File>(); val temporary = mutableListOf<File>()
    var committed = false
    try {
      for (segment in session.segments) for (name in listOf(segment.photo, segment.mask)) {
        require(File(name).name == name) { "Invalid capture filename" }
        require(name !in old.photos && old.captureSegments.none { it.mask == name }) { "Cannot replace existing receipt evidence" }
        val target = File(destination, name)
        val temp = File(destination, "$name.tmp")
        temporary += temp
        File(source, name).copyTo(temp, overwrite = true)
        check(temp.renameTo(target)) { "Could not import captured image" }
        created += target
      }
      val next = old.copy(
        qrUrl = old.qrUrl ?: session.qrUrl,
        photos = old.photos + session.segments.map { it.photo },
        captureSessions = old.captureSessions + session.id,
        captureSegments = old.captureSegments + session.segments,
        version = old.version + 1,
      )
      write(next)
      committed = true
      publish()
      next
    } finally { temporary.forEach { it.delete() }; if (!committed) created.forEach { it.delete() } }
  } }

  private suspend fun update(chave: String, bump: Boolean = true, change: (Receipt) -> Receipt) = io {
    mutex.withLock {
      val old = read(chave) ?: error("no receipt $chave")
      val new = change(old).let { if (bump) it.copy(version = old.version + 1) else it }
      write(new)
      publish()
    }
  }

  private fun read(chave: String): Receipt? =
    File(dir(chave), MANIFEST).takeIf { it.isFile }?.let { json.decodeFromString<Receipt>(it.readText()) }

  // Write-then-rename so a crash never leaves a half-written manifest.
  private fun write(receipt: Receipt) {
    val tmp = File(dir(receipt.chave), "$MANIFEST.tmp")
    java.io.FileOutputStream(tmp).use { output ->
      output.write(json.encodeToString(receipt).toByteArray(Charsets.UTF_8)); output.fd.sync()
    }
    check(tmp.renameTo(File(dir(receipt.chave), MANIFEST))) { "could not save ${receipt.chave}" }
  }

  private fun publish() {
    val all = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { read(it.name) }
    _receipts.value = all.sortedByDescending { it.capturedAt }
  }

  private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

  companion object {
    const val MAX_PHOTOS = 100
    const val MANIFEST = "receipt.json"
    const val PAGE_FILE = "page.html"
  }
}
