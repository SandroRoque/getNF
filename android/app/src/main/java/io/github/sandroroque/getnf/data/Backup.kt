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
  /** Files the database names that the phone didn't have when it was made (T-18): the copy goes without them. */
  val missing: List<String> = emptyList(),
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
    z.getEntry(DATABASE)?.let { e -> z.getInputStream(e).use { checkDatabase(it, maxSchema) } }
    for ((path, hash) in m.files) {
      val entry = z.getEntry(path) ?: throw BackupException("missing $path")
      if (z.getInputStream(entry).use { copyHashing(it, Discard) } != hash) throw BackupException("corrupt $path")
    }
    m
  }

  /** Extracts a checked archive into [target], verifying every hash again on the way. */
  fun extract(zip: File, target: File, maxSchema: Int): BackupManifest = ZipFile(zip).use { z ->
    val m = manifestOf(z, maxSchema)
    z.getEntry(DATABASE)?.let { e -> z.getInputStream(e).use { checkDatabase(it, maxSchema) } }
    target.deleteRecursively()
    for ((path, hash) in m.files) {
      val out = File(target, path).apply { parentFile!!.mkdirs() }
      val entry = z.getEntry(path) ?: throw BackupException("missing $path")
      if (out.outputStream().use { o -> z.getInputStream(entry).use { copyHashing(it, o) } } != hash) throw BackupException("corrupt $path")
    }
    m
  }

  /**
   * The database entry is an SQLite file of a version this app opens (T-36, D188): its header, and the schema version
   * Room keeps in it (`PRAGMA user_version`, at byte 60). Hashes only say it's what was exported, and a database Room
   * can't open would otherwise be swapped in and crash every start.
   */
  private fun checkDatabase(input: InputStream, maxSchema: Int) {
    val header = ByteArray(64)
    var read = 0
    while (read < header.size) { val n = input.read(header, read, header.size - read); if (n < 0) break; read += n }
    if (read < header.size || !header.copyOf(16).contentEquals(SQLITE_MAGIC)) throw BackupException("the backup's database isn't a database")
    val version = (60 until 64).fold(0) { v, i -> (v shl 8) or (header[i].toInt() and 0xff) }
    if (version <= 0 || version > maxSchema) throw BackupException("the backup's database is version $version; this app opens up to $maxSchema")
  }

  private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

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
class PendingRestore(filesDir: File, private val database: File, private val receipts: File = File(filesDir, "receipts")) {
  private val root = File(filesDir, "restore")
  val staged = File(root, "staged")
  private val ready = File(root, "READY")
  private val previous = File(root, "previous")

  /** Before staging: no earlier marker or half-staged files (another caderneta's, or the demo's) can mix in. */
  fun clear() { ready.delete(); staged.deleteRecursively() }

  /** The marker names the database it was staged for (D184): a start that opens other files leaves it alone. */
  fun markReady() { check(File(staged, BackupArchive.DATABASE).isFile) { "nothing staged" }; ready.writeText(target) }

  /** Returns true when a restore was applied. */
  fun applyIfReady(): Boolean {
    if (!ready.isFile || !forThisTarget()) return false
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

  /**
   * The restored data didn't open (T-36, D188): what it replaced goes back, and true. False when there's nothing to put
   * back (no restore was applied since the last [cleanUp]).
   */
  fun rollBack(): Boolean {
    if (ready.exists() || !previous.isDirectory) return false
    val db = File(previous, "getnf.db")
    if (!db.exists() && !File(previous, "getnf.db.none").exists()) return false
    for (suffix in listOf("", "-wal", "-shm", "-journal")) File(database.path + suffix).delete()
    for (suffix in listOf("-wal", "-shm", "-journal")) File(previous, database.name + suffix).let { if (it.exists()) move(it, File(database.path + suffix)) }
    if (db.exists()) move(db, database)
    val oldReceipts = File(previous, "receipts")
    if (oldReceipts.exists() || File(previous, "receipts.none").exists()) {
      receipts.deleteRecursively()
      if (oldReceipts.exists()) move(oldReceipts, receipts)
    }
    previous.deleteRecursively()
    return true
  }

  private val target get() = database.absoluteFile.normalize().path

  // "1" is what markers said before D184; only Ajustes' restore staged then, always for the open caderneta.
  private fun forThisTarget() = ready.readText().trim().let { it == target || it == "1" }

  private fun move(from: File, to: File) {
    to.parentFile!!.mkdirs()
    if (!from.renameTo(to)) throw IOException("could not move ${from.name}")
  }
}
