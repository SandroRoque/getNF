package io.github.sandroroque.getnf.data

import io.github.sandroroque.getnf.sync.PhotoArchiver
import io.github.sandroroque.getnf.sync.SupabaseException
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * During the closed test (D161), photos and pages for the developer: every capture's photos (saved, started over or
 * left), the SEFAZ page once it's read, and the pages of states the app doesn't read yet. Only in builds made for the
 * test ([testBuild]), only after the person said yes ([enabled]), signed in or not: they go under the install's random
 * id, never the account. Photos leave the phone as their archive copy, grey, with the card slip, a CPF and the operator
 * blanked (D98); pages without the buyer (D104).
 *
 * Each capture or page waits in [dir] as a folder (an item) until it's sent, the photos as the original JPEGs until
 * the worker makes their copies. Unrelated to D103's opt-in, which puts photos in the caderneta's own folder.
 */
class TestContribution(
  private val prefs: Prefs,
  private val dir: File,
  /** BuildConfig.TEST_PERIOD: the build was made for the closed test. */
  val testBuild: Boolean,
  private val about: String,
  val installId: () -> String,
) {
  /** Where the answer is kept: SharedPreferences in the app, a map in tests. */
  interface Prefs {
    var enabled: Boolean?
    var withdraw: Boolean
  }

  private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

  /** The person's answer: null until asked. Saying no (or turning it off) drops what's waiting and takes back what went. */
  var enabled: Boolean?
    get() = prefs.enabled
    set(value) {
      val was = enabled == true
      prefs.enabled = value
      if (was && value != true) prefs.withdraw = true
      if (value == true) prefs.withdraw = false
      if (value != true) dir.deleteRecursively()
    }

  /** Whether the app asks: a test build, and the person hasn't answered. */
  val shouldAsk get() = testBuild && enabled == null

  /** Collecting now. */
  val active get() = testBuild && enabled == true

  /** What was sent waits to be deleted from the server, done by the worker. */
  var withdrawPending: Boolean
    get() = prefs.withdraw
    set(value) { prefs.withdraw = value }

  enum class Kind(val wire: String) {
    /** The capture finished: its photos became a receipt. */
    Saved("salva"),
    /** The person started over (R4, D150) or the attempt dropped after losing the paper (D149). */
    Restarted("recomecada"),
    /** The person left the capture. */
    Left("abandonada"),
    /** The receipt's SEFAZ page, read. */
    Page("pagina"),
    /** A portal the app doesn't read yet (another state): the last page it showed. */
    OtherState("outro_estado"),
  }

  @Serializable
  data class Meta(
    val kind: String,
    val chave: String? = null,
    val url: String? = null,
    val about: String,
    val writtenAt: String,
    val photos: List<Photo> = emptyList(),
  )

  /** A photo as `pNN.webp`, with what the capture recorded about it (its outline and rows), for the photo reader. */
  @Serializable
  data class Photo(val name: String, val polygon: List<List<Double>>, val bands: List<List<Double>>, val top: Double? = null,
                   val bottom: Double? = null, val score: Double, val capturedAt: Long)

  /** A capture's photos, copied from [source] (the session's folder) before it goes. Nothing if inactive or no photos. */
  fun addCapture(kind: Kind, session: CaptureSessionManifest, source: File) {
    if (!active || session.segments.isEmpty()) return
    val photos = session.segments.take(MAX_PHOTOS).mapIndexedNotNull { i, s ->
      val from = File(source, s.photo).takeIf { it.isFile } ?: return@mapIndexedNotNull null
      Triple("p%02d".format(i), from, s)
    }
    if (photos.isEmpty()) return
    write(kind, session.chave, session.qrUrl,
      photos.map { (name, _, s) -> Photo("$name.webp", s.polygon, s.bands, s.top, s.bottom, s.score, s.capturedAt) }) { item ->
      for ((name, from, s) in photos) {
        from.copyTo(File(item, "$name.jpg"))
        File(item, "$name.segment.json").writeText(json.encodeToString(CaptureSegment.serializer(), s))
      }
    }
  }

  /** A page: [html] already without the buyer for a receipt's page (ReceiptStore.savePage); blanked again here. */
  fun addPage(kind: Kind, chave: String?, url: String, html: String) {
    if (!active) return
    write(kind, chave, url, emptyList()) { item ->
      File(item, PAGE).writeText(io.github.sandroroque.getnf.parse.BuyerRedaction.blank(html), Charsets.UTF_8)
    }
  }

  private fun write(kind: Kind, chave: String?, url: String?, photos: List<Photo>, files: (File) -> Unit) {
    val id = UUID.randomUUID().toString()
    val temp = File(dir, "$id.part")
    temp.mkdirs()
    try {
      files(temp)
      val meta = Meta(kind.wire, chave, url, about, java.time.Instant.now().toString(), photos)
      File(temp, META).writeText(json.encodeToString(Meta.serializer(), meta))
      check(temp.renameTo(File(dir, id))) { "could not keep $id" }
    } catch (e: Exception) {
      temp.deleteRecursively()
      throw e
    }
  }

  fun pending(): List<File> = dir.listFiles { f -> f.isDirectory && !f.name.endsWith(".part") }.orEmpty().sortedBy { it.lastModified() }

  /**
   * Sends every waiting item through [post] (query, file, type), oldest first. Each photo becomes its archive copy
   * first; one that can't be made (the photo can't be read to blank it) is dropped, as in D98. Each file is deleted
   * once sent, meta.json last, then the item. A network failure or a limit stops it ([IOException], for the worker to
   * try again later); a file the server refuses is dropped.
   */
  fun send(archiver: PhotoArchiver, onRefused: (String) -> Unit = {}, post: (Map<String, String>, File, String) -> Unit): Int {
    var sent = 0
    for (item in pending()) {
      for (jpg in item.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().sortedBy { it.name }) {
        val base = jpg.name.removeSuffix(".jpg")
        val segmentFile = File(item, "$base.segment.json")
        val segment = runCatching { json.decodeFromString(CaptureSegment.serializer(), segmentFile.readText()) }.getOrNull()
        runCatching { archiver.archive(jpg, segment) }
          .onSuccess { bytes -> File(item, "$base.webp.part").apply { writeBytes(bytes); renameTo(File(item, "$base.webp")) } }
        jpg.delete(); segmentFile.delete()
      }
      val files = item.listFiles { f -> f.name.endsWith(".webp") || f.name == PAGE }.orEmpty().sortedBy { it.name } +
        listOfNotNull(File(item, META).takeIf { it.isFile })
      for (f in files) {
        val type = when { f.name.endsWith(".webp") -> "image/webp"; f.name == PAGE -> "text/html"; else -> "application/json" }
        try {
          post(mapOf("install" to installId(), "item" to item.name, "file" to f.name), f, type)
          sent++
        } catch (e: SupabaseException) {
          onRefused("${f.name}: ${e.message}")
        }
        f.delete()
      }
      item.deleteRecursively()
    }
    return sent
  }

  companion object {
    private const val META = "meta.json"
    private const val PAGE = "page.html"
    /** The server's names go up to p99. */
    private const val MAX_PHOTOS = 100
  }
}
