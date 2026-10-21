package io.github.sandroroque.getnf.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `manifest.json` of a backup (D47). Counts are shown before a restore; hashes are checked before anything changes. */
@Serializable
data class BackupManifest(
  val format: Int = FORMAT,
  val appVersion: String,
  /** Room schema version of `db/getnf.db`; an app can only restore its own version or older ones. */
  val schemaVersion: Int,
  val createdAt: Long,
  val receipts: Int,
  val photos: Int,
  val lines: Int,
  val products: Int,
  /** Archive path -> SHA-256 (hex), for every file but the manifest. */
  val files: Map<String, String>,
) {
  companion object { const val FORMAT = 1 }
}

class BackupException(message: String) : IOException(message)

/**
 * The backup zip: `db/getnf.db`, `receipts/<chave>/<file>` and `manifest.json`. Plain java.util.zip, so it's tested
 * on the JVM; BackupService feeds it from the device.
 */
object BackupArchive {
  const val MANIFEST = "manifest.json"
  const val DATABASE = "db/getnf.db"
  private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

  /** Writes [files] (archive path -> file) and then the manifest built by [manifest] from their hashes. */
  fun write(out: OutputStream, files: Map<String, File>, progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    manifest: (hashes: Map<String, String>) -> BackupManifest): BackupManifest {
    val hashes = LinkedHashMap<String, String>()
    ZipOutputStream(out.buffered()).use { zip ->
      // Photos are already JPEG: compressing harder costs time for nothing.
      zip.setLevel(Deflater.BEST_SPEED)
      files.entries.forEachIndexed { i, (path, file) ->
        require(safe(path)) { "unsafe archive path $path" }
        zip.putNextEntry(ZipEntry(path))
        hashes[path] = file.inputStream().use { copyHashing(it, zip) }
        zip.closeEntry()
        progress(i + 1, files.size)
      }
      val m = manifest(hashes)
      zip.putNextEntry(ZipEntry(MANIFEST))
      zip.write(json.encodeToString(m).toByteArray(Charsets.UTF_8))
      zip.closeEntry()
      return m
    }
  }

  /** Reads and checks the whole archive without extracting anything. Throws [BackupException] if it can't be restored. */
  fun inspect(zip: File, maxSchema: Int): BackupManifest = ZipFile(zip).use { z ->
    val m = manifestOf(z, maxSchema)
    for ((path, hash) in m.files) {
      val entry = z.getEntry(path) ?: throw BackupException("missing $path")
      if (z.getInputStream(entry).use { copyHashing(it, Discard) } != hash) throw BackupException("corrupt $path")
    }
    m
  }

  /** Extracts a checked archive into [target], verifying every hash again on the way. */
  fun extract(zip: File, target: File, maxSchema: Int): BackupManifest = ZipFile(zip).use { z ->
    val m = manifestOf(z, maxSchema)
    target.deleteRecursively()
    for ((path, hash) in m.files) {
      val out = File(target, path).apply { parentFile!!.mkdirs() }
      val entry = z.getEntry(path) ?: throw BackupException("missing $path")
      if (out.outputStream().use { o -> z.getInputStream(entry).use { copyHashing(it, o) } } != hash) throw BackupException("corrupt $path")
    }
    m
  }

  private fun manifestOf(z: ZipFile, maxSchema: Int): BackupManifest {
    val entry = z.getEntry(MANIFEST) ?: throw BackupException("not a getNF backup (no manifest)")
    val m = try {
      json.decodeFromString<BackupManifest>(z.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) })
    } catch (e: Exception) {
      throw BackupException("unreadable manifest: ${e.message}")
    }
    if (m.format != BackupManifest.FORMAT) throw BackupException("unknown backup format ${m.format}")
    if (m.schemaVersion > maxSchema) throw BackupException("backup is from a newer app (database v${m.schemaVersion}); update the app first")
    if (DATABASE !in m.files) throw BackupException("backup has no database")
    m.files.keys.firstOrNull { !safe(it) }?.let { throw BackupException("unsafe path $it") }
    return m
  }

  /** Only `db/getnf.db` and files directly inside one receipt's directory: nothing can land elsewhere. */
  private fun safe(path: String) = path == DATABASE || Regex("receipts/[0-9]{44}/[^/\\\\]+").matches(path) &&
    path.split('/').none { it == "." || it == ".." }

  // OutputStream.nullOutputStream() needs API 33.
  private object Discard : OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
  }

  private fun copyHashing(input: InputStream, out: OutputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(input, digest).copyTo(out)
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}

/**
 * A restore staged in `restore/staged` (by BackupService) and applied on the next start, before the database opens
 * (D47). Every step is skipped when already done, so a start interrupted halfway finishes the swap on the next one.
 * The replaced data stays in `restore/previous` until [cleanUp], after the restored database opened.
 */
class PendingRestore(private val filesDir: File, private val database: File) {
  private val root = File(filesDir, "restore")
  val staged = File(root, "staged")
  private val ready = File(root, "READY")
  private val previous = File(root, "previous")

  fun markReady() { check(File(staged, BackupArchive.DATABASE).isFile) { "nothing staged" }; ready.writeText("1") }

  /** Returns true when a restore was applied. */
  fun applyIfReady(): Boolean {
    if (!ready.isFile) return false
    val receipts = File(filesDir, "receipts")
    previous.mkdirs()
    // Current data out of the way. Once previous holds it, never again: by then the live paths hold restored data.
    if (!File(previous, "getnf.db").exists() && !File(previous, "getnf.db.none").exists()) {
      for (suffix in listOf("-wal", "-shm", "-journal")) File(database.path + suffix).let { if (it.exists()) move(it, File(previous, it.name)) }
      if (database.exists()) move(database, File(previous, "getnf.db")) else File(previous, "getnf.db.none").writeText("")
    }
    if (!File(previous, "receipts").exists() && !File(previous, "receipts.none").exists()) {
      if (receipts.exists()) move(receipts, File(previous, "receipts")) else File(previous, "receipts.none").writeText("")
    }
    // previous holds the original data now. Whatever sits at a live path while its staged copy still exists was made
    // by a start whose swap failed (an empty database Room created, say), so it goes.
    File(staged, BackupArchive.DATABASE).let {
      if (it.exists()) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(database.path + suffix).delete()
        database.parentFile!!.mkdirs()
        move(it, database)
      }
    }
    File(staged, "receipts").let { if (it.exists()) { receipts.deleteRecursively(); move(it, receipts) } else receipts.mkdirs() }
    ready.delete()
    staged.deleteRecursively()
    return true
  }

  /** The restored database opened: the replaced data can go. */
  fun cleanUp() { if (!ready.exists()) previous.deleteRecursively() }

  private fun move(from: File, to: File) {
    to.parentFile!!.mkdirs()
    if (!from.renameTo(to)) throw IOException("could not move ${from.name}")
  }
}
