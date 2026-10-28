package io.github.sandroroque.getnf.data

import android.util.Log
import io.github.sandroroque.getnf.discounts.PhotoText
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
 * What the phone knows about one receipt, assembled from the local database (D45). Photos and the saved page are files
 * in `receipts/<chave>/`. Until D45 this was the `receipt.json` manifest itself (D11); it stays serializable so
 * [ReceiptStore.load] can import those manifests.
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
  /** Photos confirmed in the server's append-only receipt_photos collection (D42), by local file name. */
  val uploadedPhotos: List<String> = emptyList(),
  /** Scanned on this phone, not only adopted from the caderneta (D104): this phone's scan mark goes up with it. */
  val scannedHere: Boolean = true,
) {
  val pendingPhotos: List<String>
    get() = photos.filter { it !in uploadedPhotos }
  val needsUpload: Boolean
    get() = uploadedVersion != version || pendingPhotos.isNotEmpty()
}

class ReceiptStore(
  private val root: File,
  database: ReceiptDatabase,
  private val now: () -> Long = System::currentTimeMillis,
  private val warn: (String, Throwable?) -> Unit = { message, error -> Log.w(TAG, message, error) },
) {
  private val dao = database.receipts()
  private val mutex = Mutex()
  private val json = Json { ignoreUnknownKeys = true }
  private val _receipts = MutableStateFlow<List<Receipt>>(emptyList())

  /** All receipts, newest first. Call [load] once at startup. */
  val receipts: StateFlow<List<Receipt>> = _receipts.asStateFlow()

  fun dir(chave: String): File = File(root, chave)

  /** Imports any pre-D45 manifests, blanks the buyer from pages saved before D104, then publishes. */
  suspend fun load() = io { mutex.withLock { importManifests(); blankBuyers(); publish() } }

  /**
   * Pages saved before D104 still name the buyer; they'd go up like that if their receipt changed. Rewritten in place,
   * only when something is blanked (the page reads the same, BuyerRedactionTest).
   */
  private suspend fun blankBuyers() {
    for (row in dao.receipts()) {
      val page = row.pageFile?.let { File(dir(row.chave), it) }?.takeIf { it.isFile } ?: continue
      runCatching {
        val html = page.readText(Charsets.UTF_8)
        val clean = io.github.sandroroque.getnf.parse.BuyerRedaction.blank(html)
        if (clean != html) {
          val temp = File(page.path + ".tmp")
          temp.writeText(clean, Charsets.UTF_8)
          check(temp.renameTo(page)) { "could not replace ${page.name}" }
        }
      }.onFailure { warn("blanking the buyer of ${row.chave}", it) }
    }
  }

  suspend fun get(chave: String): Receipt? = io { mutex.withLock { read(chave) } }

  /** Returns the receipt for [chave], creating it on the first scan. */
  suspend fun open(chave: String, qrUrl: String?): Receipt = io {
    mutex.withLock {
      read(chave)?.let { known ->
        // Scanning a receipt another member brought (D104) makes it this phone's scan too.
        if (known.scannedHere) known
        else known.copy(scannedHere = true, version = known.version + 1).also { dao.save(it.row()); publish() }
      }
        ?: Receipt(chave = chave, qrUrl = qrUrl, capturedAt = now(), version = 1).also {
          dir(chave).mkdirs()
          dao.save(it.row())
          publish()
        }
    }
  }

  /** A fresh file to hand to the camera. Call [addPhoto] once it's been written. */
  fun newPhotoFile(chave: String): File = File(dir(chave), "photo-${now()}.jpg")

  suspend fun addPhoto(chave: String, file: File) = io {
    mutex.withLock {
      val old = read(chave) ?: error("no receipt $chave")
      dao.save(old.row().copy(version = old.version + 1), listOf(PhotoRow(chave, file.name, old.photos.size, uploaded = false, segment = null)))
      publish()
    }
  }

  /** The page as the receipt, without who bought it (D104). */
  suspend fun savePage(chave: String, url: String, html: String) {
    io { File(dir(chave), PAGE_FILE).writeText(io.github.sandroroque.getnf.parse.BuyerRedaction.blank(html), Charsets.UTF_8) }
    update(chave) { it.copy(pageUrl = url, pageFile = PAGE_FILE, pageFetchedAt = now()) }
  }

  /** A page another member fetched (D104), already saved as [PAGE_FILE]. Not a local change: nothing to push. */
  suspend fun adoptPage(chave: String, url: String?, fetchedAt: Long?) =
    update(chave, bump = false) { it.copy(pageUrl = url, pageFile = PAGE_FILE, pageFetchedAt = fetchedAt) }

  suspend fun markUploaded(chave: String, version: Long, remoteId: String) =
    update(chave, bump = false) { it.copy(uploadedVersion = version, remoteId = remoteId, uploadError = null) }

  /** Not a local change: doesn't bump [Receipt.version]. */
  suspend fun markPhotoUploaded(chave: String, photo: String) = io {
    mutex.withLock { dao.markPhotoUploaded(chave, photo); publish() }
  }

  suspend fun markUploadError(chave: String, message: String) =
    update(chave, bump = false) { it.copy(uploadError = message) }

  /** Idempotent batch import: the database transaction is the commit point; existing evidence is never replaced. */
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
      dao.save(
        old.copy(qrUrl = old.qrUrl ?: session.qrUrl, version = old.version + 1, scannedHere = true).row(),
        session.segments.mapIndexed { i, segment ->
          PhotoRow(key, segment.photo, old.photos.size + i, uploaded = false, segment = json.encodeToString(segment))
        },
        listOf(CaptureSessionRow(key, session.id, old.captureSessions.size)),
      )
      committed = true
      // The OCR each photo came with (D99): optional, and not evidence; copied after the photos are in.
      for (segment in session.segments) {
        val name = segment.photo + PhotoDiscountReader.CACHE_SUFFIX
        runCatching { File(source, name).takeIf { it.isFile }?.copyTo(File(destination, name), overwrite = true) }
      }
      notePhotoRead(key)
      publish()
      read(key)!!
    } finally { temporary.forEach { it.delete() }; if (!committed) created.forEach { it.delete() } }
  } }

  /**
   * Reads what a receipt's photos say while it waits for its page (D99): its issue date, from the photos' OCR (cached
   * next to each; [ocr] reads a photo that has none yet). Every receipt without a page and not yet read.
   */
  suspend fun readPendingPhotos(ocr: (File) -> PhotoText) = io {
    val done = dao.photoReadChaves().toSet()
    for (r in receipts.value.filter { it.pageFile == null && it.chave !in done && it.photos.isNotEmpty() }) {
      for (photo in r.photos) {
        val file = File(dir(r.chave), photo); val cache = File(file.path + PhotoDiscountReader.CACHE_SUFFIX)
        if (file.isFile && !cache.isFile) runCatching { cache.writeText(json.encodeToString(ocr(file))) }
      }
      mutex.withLock { notePhotoRead(r.chave) }
    }
  }

  private suspend fun notePhotoRead(chave: String) {
    val texts = dir(chave).listFiles { f -> f.name.endsWith(PhotoDiscountReader.CACHE_SUFFIX) }.orEmpty()
      .mapNotNull { runCatching { json.decodeFromString<PhotoText>(it.readText()) }.getOrNull() }
    dao.putPhotoRead(PhotoReadRow(chave, PhotoRead.issueDate(chave, texts)))
  }

  /**
   * A receipt copied from another caderneta on this phone (D104): its page, photos, masks and their OCR, as a scan of
   * this phone's, to upload here. Returns false if this caderneta already has it (its photos are then added).
   */
  suspend fun importCopy(source: Receipt, sourceDir: File): Boolean = io { mutex.withLock {
    val old = read(source.chave)
    val target = dir(source.chave).apply { mkdirs() }
    val newPhotos = source.photos.filter { old == null || it !in old.photos }
    val files = sourceDir.listFiles().orEmpty().filter { f ->
      f.isFile && (f.name == source.pageFile && old?.pageFile == null || newPhotos.any { f.name.startsWith(it) } ||
        source.captureSegments.any { it.photo in newPhotos && it.mask == f.name })
    }
    for (f in files) f.copyTo(File(target, f.name), overwrite = true)
    val base = old ?: source.copy(photos = emptyList(), uploadedPhotos = emptyList(), version = 0, uploadedVersion = null,
      remoteId = null, uploadError = null, captureSessions = emptyList(), captureSegments = emptyList())
    val merged = base.copy(
      pageUrl = base.pageUrl ?: source.pageUrl, pageFile = base.pageFile ?: source.pageFile,
      pageFetchedAt = base.pageFetchedAt ?: source.pageFetchedAt, version = base.version + 1, scannedHere = true,
    )
    dao.save(merged.row(), newPhotos.mapIndexed { i, name ->
      PhotoRow(source.chave, name, (old?.photos?.size ?: 0) + i, uploaded = false,
        segment = source.captureSegments.find { it.photo == name }?.let { json.encodeToString(it) })
    })
    publish()
    old == null
  } }

  /**
   * Creates a receipt that only the server had (D46), with its already-downloaded [photos] pending for receipt_photos.
   * The receipt itself counts as uploaded. A receipt the phone already has is left alone.
   */
  suspend fun adoptRemote(receipt: Receipt, photos: List<String>) = io {
    mutex.withLock {
      if (read(receipt.chave) != null) return@withLock
      dao.save(receipt.row(), photos.mapIndexed { i, name -> PhotoRow(receipt.chave, name, i, uploaded = false, segment = null) })
      publish()
    }
  }

  /** Changes receipt-level fields only; photos have their own calls. */
  private suspend fun update(chave: String, bump: Boolean = true, change: (Receipt) -> Receipt) = io {
    mutex.withLock {
      val old = read(chave) ?: error("no receipt $chave")
      val new = change(old).let { if (bump) it.copy(version = old.version + 1) else it }
      dao.save(new.row())
      publish()
    }
  }

  private suspend fun read(chave: String): Receipt? =
    dao.receipt(chave)?.let { assemble(it, dao.photos(chave), dao.sessions(chave)) }

  private suspend fun publish() {
    val photos = dao.photos().groupBy { it.receipt }
    val sessions = dao.sessions().groupBy { it.receipt }
    _receipts.value = dao.receipts().map { assemble(it, photos[it.chave].orEmpty(), sessions[it.chave].orEmpty()) }
      .sortedByDescending { it.capturedAt }
  }

  private fun assemble(row: ReceiptRow, photos: List<PhotoRow>, sessions: List<CaptureSessionRow>) = Receipt(
    chave = row.chave, qrUrl = row.qrUrl, capturedAt = row.capturedAt, photos = photos.map { it.localName },
    pageUrl = row.pageUrl, pageFile = row.pageFile, pageFetchedAt = row.pageFetchedAt, version = row.version,
    uploadedVersion = row.uploadedVersion, remoteId = row.remoteId, uploadError = row.uploadError,
    captureSessions = sessions.map { it.sessionId },
    captureSegments = photos.mapNotNull { p -> p.segment?.let { json.decodeFromString<CaptureSegment>(it) } },
    uploadedPhotos = photos.filter { it.uploaded }.map { it.localName },
    scannedHere = row.scannedHere,
  )

  private fun Receipt.row() =
    ReceiptRow(chave, qrUrl, capturedAt, pageUrl, pageFile, pageFetchedAt, version, uploadedVersion, remoteId, uploadError, scannedHere)

  /**
   * One-time import of the pre-D45 `receipt.json` manifests, keeping versions and upload state so nothing is re-sent.
   * A manifest is renamed once its receipt is in the database, never deleted: it stays as a fallback, and can't bring
   * back a receipt deleted later. A crash between insert and rename just renames on the next load.
   */
  private suspend fun importManifests() {
    for (dir in root.listFiles().orEmpty().filter { it.isDirectory }) {
      val manifest = File(dir, MANIFEST).takeIf { it.isFile } ?: continue
      try {
        val r = json.decodeFromString<Receipt>(manifest.readText())
        require(r.chave == dir.name) { "manifest chave doesn't match its directory" }
        if (dao.receipt(r.chave) == null) dao.save(
          r.row(),
          r.photos.mapIndexed { i, name ->
            PhotoRow(r.chave, name, i, name in r.uploadedPhotos, r.captureSegments.find { it.photo == name }?.let { json.encodeToString(it) })
          },
          r.captureSessions.mapIndexed { i, id -> CaptureSessionRow(r.chave, id, i) },
        )
        if (!manifest.renameTo(File(dir, MIGRATED_MANIFEST))) warn("could not rename the imported manifest of ${dir.name}", null)
      } catch (e: Exception) {
        warn("skipping unreadable manifest ${dir.name}", e)
      }
    }
  }

  private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

  companion object {
    const val MAX_PHOTOS = 100
    const val MANIFEST = "receipt.json"
    const val MIGRATED_MANIFEST = "receipt.json.migrated"
    const val PAGE_FILE = "page.html"
    private const val TAG = "ReceiptStore"
  }
}
