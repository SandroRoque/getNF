package io.github.sandroroque.getnf.sync

import android.util.Log
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.sandroroque.getnf.data.CaptureSegment
import io.github.sandroroque.getnf.data.CategoryRow
import io.github.sandroroque.getnf.data.IssuerRow
import io.github.sandroroque.getnf.data.LinkSuggestionRow
import io.github.sandroroque.getnf.data.WorkerReportRow
import io.github.sandroroque.getnf.data.ProductRow
import io.github.sandroroque.getnf.data.ProductTagRow
import io.github.sandroroque.getnf.data.Receipt
import io.github.sandroroque.getnf.data.ReceiptDatabase
import io.github.sandroroque.getnf.data.ReceiptLineRow
import io.github.sandroroque.getnf.data.ReceiptParsedRow
import io.github.sandroroque.getnf.data.ReceiptStore
import io.github.sandroroque.getnf.data.StoreItemRow
import io.github.sandroroque.getnf.data.SyncBaseRow
import io.github.sandroroque.getnf.data.LINE_CORRECTIONS
import io.github.sandroroque.getnf.data.lineSyncKey
import io.github.sandroroque.getnf.data.syncJson
import io.github.sandroroque.getnf.data.syncKey
import io.github.sandroroque.getnf.data.SyncParkedRow
import io.github.sandroroque.getnf.data.MemberRow
import io.github.sandroroque.getnf.data.ReceiptScanRow
import io.github.sandroroque.getnf.data.SyncStateRow
import io.github.sandroroque.getnf.data.TagRow
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The server side of a sync: one caderneta (D104) of the Supabase project, as the signed-in user (SupabaseRemote), or a
 * fake in tests. Every row the phone sends carries [ledgerId]; every pull asks for that caderneta's rows only.
 */
interface SyncRemote {
  val ledgerId: String
  /** The signed-in person. */
  val userId: String
  fun rpc(function: String, args: JsonObject = JsonObject(emptyMap())): JsonElement
  fun upsert(table: String, rows: List<JsonObject>, onConflict: String, ignoreDuplicates: Boolean = false)
  fun delete(table: String, filters: List<Pair<String, String>>)
  fun select(table: String, filters: List<Pair<String, String>>): List<JsonObject>
  fun upload(path: String, bytes: ByteArray, type: String)
  fun download(path: String, target: File)
  /** Removes Storage objects; one already gone is no error. */
  fun deleteObjects(paths: List<String>)
}

/** Turns a receipt photo into its small archive copy (D61). */
fun interface PhotoArchiver {
  fun archive(photo: File, segment: CaptureSegment?): ByteArray
}

/**
 * Keeps the phone's database and the Supabase project in step (D70). One run:
 * 1. the server brings the user's categories and tags up to the taxonomy, and the phone pulls them, adopting the
 *    server's ids for seeded ones it knew under its own;
 * 2. the phone pushes what it changed (dirty rows, receipts with a new version, photos not uploaded yet), parents
 *    before children so foreign keys hold;
 * 3. the phone pulls what changed on the server since the last run, never overwriting a row it changed meanwhile.
 *
 * A pushed row stops being dirty only if it's still exactly what was pushed: a parse that ran in between keeps it
 * dirty for the next run. Pulls overlap the previous cursor by a minute (rows can commit out of timestamp order);
 * applying a row twice is harmless. Within a run, pages follow (updated_at, key), so no row is skipped however many
 * share a minute or a timestamp (D164).
 *
 * Catalog rows (categories, tags, products, store items) merge field by field (D164): the phone keeps what the server
 * last held of each ([SyncBaseRow]), pushes only the fields it changed since, and a pull into a row it changed takes
 * the server's other fields. The same field changed on two phones ends with the one that pushes last. Receipt lines
 * merge that way too (D182): a phone sends a changed line's extracted columns and only the corrections it changed, so
 * one that re-reads a page never sends its stale copy of another phone's correction. Lines a re-read
 * page dropped are removed on the server one by one ([LineRemovedRow]); a phone that pulls changed lines of a receipt
 * drops the ones the server no longer has.
 */
class SyncEngine(
  private val database: ReceiptDatabase,
  private val store: ReceiptStore,
  private val archiver: PhotoArchiver,
  /** Whether a receipt's photos may go up (D103): nothing is uploaded unless the user allowed it and the app failed with it. */
  private val contribute: suspend (Receipt) -> Boolean = { false },
  private val log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) {
  private val dao = database.sync()
  private val mutex = Mutex()
  private val json = Json { ignoreUnknownKeys = true }

  /** [storeItems]: how many of [pushed] were store items, each a job for the categorizer online (D74, D121). */
  /** [foreign]: this database belongs to another caderneta, so nothing was sent or taken (T-16, D193). */
  data class Report(val pushed: Int, val pulled: Int, val photos: Int, val errors: List<String>, val storeItems: Int = 0, val foreign: Boolean = false)

  suspend fun sync(remote: SyncRemote): Report = mutex.withLock {
    if (!belongsTo(remote)) return@withLock Report(0, 0, 0, listOf("this database belongs to another caderneta: not synced"), foreign = true)
    val errors = mutableListOf<String>()
    io { remote.rpc("sync_taxonomy", buildJsonObject { put("lid", remote.ledgerId) }) }
    var pulled = pull(remote, listOf(CATEGORY, TAG_TABLE), errors)
    seedBases()
    val (pushed, photos) = push(remote, errors)
    retryPages(remote, errors)
    pulled += pull(remote, TABLES, errors)
    reconcileLines(remote)
    pullMembers(remote)
    Report(pushed, pulled, photos, errors, storeItemsPushed).also { log("sync: $it") }
  }

  /**
   * Whether this database is [remote]'s caderneta's (T-16, D193). Its rows carry what that caderneta's server already
   * has ("sent, version 3"), which means nothing anywhere else: a backup restored into another caderneta would send
   * nothing, then only what's edited later, half a catalog. So the first sync records the caderneta, and a database
   * that has one syncs with no other; Ajustes says so ([FOREIGN]).
   * A database from before the record: if it sent receipts and none of the latest are in [remote]'s caderneta, it was
   * another's.
   */
  private suspend fun belongsTo(remote: SyncRemote): Boolean {
    val recorded = dao.state(LEDGER)
    val ours = when {
      recorded != null -> recorded == remote.ledgerId
      else -> {
        val sent = database.receipts().receipts().filter { it.remoteId != null }.sortedByDescending { it.capturedAt }.take(20).map { it.chave }
        sent.isEmpty() || io {
          remote.select("receipt", listOf("select" to "chave", "ledger_id" to "eq.${remote.ledgerId}", "chave" to "in.(${sent.joinToString(",")})"))
        }.isNotEmpty()
      }
    }
    if (ours) { if (recorded == null) dao.putState(SyncStateRow(LEDGER, remote.ledgerId)); dao.deleteStates(listOf(FOREIGN)) }
    else dao.putState(SyncStateRow(FOREIGN, recorded ?: ""))
    return ours
  }

  // ---- push ----

  private var storeItemsPushed = 0

  private suspend fun push(remote: SyncRemote, errors: MutableList<String>): Pair<Int, Int> {
    storeItemsPushed = 0
    var n = 0
    n += pushDeletions(remote, errors)
    n += pushRows(remote, errors, "issuer", "ledger_id,cnpj", dao.dirtyIssuers(), { it.cnpj }, ::issuerJson, { dao.issuer(it.cnpj) }) { dao.upsertIssuer(it.copy(dirty = false)) }
    n += pushReceipts(remote, errors)
    n += pushRows(remote, errors, "category", BY_ID, dao.dirtyCategories(), { it.id }, ::categoryJson, { dao.category(it.id) }, merges = true) { dao.upsertCategory(it.copy(dirty = false)) }
    n += pushRows(remote, errors, "tag", BY_ID, dao.dirtyTags(), { it.id }, ::tagJson, { dao.tag(it.id) }, merges = true) { dao.upsertTag(it.copy(dirty = false)) }
    n += pushRows(remote, errors, "product", BY_ID, dao.dirtyProducts(), { it.id }, ::productJson, { dao.product(it.id) }, merges = true) { dao.upsertProduct(it.copy(dirty = false)) }
    storeItemsPushed = pushRows(remote, errors, "store_item", BY_ID, dao.dirtyStoreItems(), { it.id }, ::storeItemJson, { dao.storeItem(it.id) }, merges = true) { dao.upsertStoreItem(it.copy(dirty = false)) }
    n += storeItemsPushed
    n += pushRows(remote, errors, "product_tag", "ledger_id,product_id,tag_id", dao.dirtyProductTags(), { "${it.productId}/${it.tagId}" },
      ::productTagJson, { dao.productTag(it.productId, it.tagId) }) { dao.upsertProductTag(it.copy(dirty = false)) }
    n += pushLines(remote, errors)
    n += pushEvents(remote, errors)
    n += pushRows(remote, errors, "link_suggestion", BY_ID, dao.dirtyLinkSuggestions(), { it.id }, ::linkJson, { dao.linkSuggestion(it.id) }) {
      dao.putLinkSuggestion(it.copy(dirty = false))
    }
    return n to pushPhotos(remote, errors)
  }

  /**
   * Upserts [rows] in one request. If the server refuses it (a 4xx), they go one by one, and each it refuses is parked
   * (D104): not sent again until it changes, so one bad row neither holds back the rest nor fails every sync.
   * A table that [merges] (D164) sends only the fields that differ from the row's base, plus its id; one request per
   * set of fields, since PostgREST takes the same keys in every row of a request.
   */
  private suspend fun <T> pushRows(
    remote: SyncRemote, errors: MutableList<String>, table: String, key: String, rows: List<T>, keyOf: (T) -> String,
    toJson: (T) -> JsonObject, current: suspend (T) -> T?, merges: Boolean = false, clean: suspend (T) -> Unit,
  ): Int {
    if (rows.isEmpty()) return 0
    val parkedRows = dao.parked(table)
    // A field upsert refused for a NOT NULL column (review L8) is tried again each run until the server, given the
    // defaults of D183, takes it.
    val parked = parkedRows.filterNot { merges && NOT_NULL in it.error }.associate { it.rowKey to it.payload }
    val ready = rows.map { it to toJson(it) }.filter { (row, json) -> parked[keyOf(row)] != json.toString() }
    if (ready.isEmpty()) return 0
    val bases = if (!merges) emptyMap() else ready.map { keyOf(it.first) }.chunked(500)
      .flatMap { dao.bases(table, it) }.associate { it.rowKey to json.parseToJsonElement(it.payload) as JsonObject }
    // What goes up for each row; a row changed back to what the server has needs no request.
    val payloads = ready.associate { (row, json) ->
      val base = bases[keyOf(row)]
      row to if (base == null) json else JsonObject(json.filter { (field, value) -> field == "id" || base[field] != value })
    }
    val requests = ready.filter { (row, _) -> payloads.getValue(row).size > 1 || bases[keyOf(row)] == null }
    val requested = requests.mapTo(HashSet()) { it.first }
    val sent = try {
      for (group in requests.groupBy { payloads.getValue(it.first).keys }.values)
        io { remote.upsert(table, group.map { owned(remote, payloads.getValue(it.first)) }, key) }
      ready
    } catch (e: SupabaseException) {
      if (e.status == 401) throw e
      ready.filter { (row, json) ->
        try {
          if (row in requested) io { remote.upsert(table, listOf(owned(remote, payloads.getValue(row))), key) }
          true
        } catch (e: SupabaseException) {
          if (e.status == 401) throw e
          park(table, keyOf(row), json.toString(), e, errors); false
        }
      }
    }
    transaction {
      for ((row, json) in sent) {
        if (current(row) == row) clean(row)
        // The server now has the fields sent, and the base already had the others.
        if (merges) dao.putBase(SyncBaseRow(table, keyOf(row), json.toString()))
      }
      if (parkedRows.isNotEmpty()) dao.unpark(table, sent.map { keyOf(it.first) })
    }
    return sent.count { it.first in requested }
  }

  /** Rows from before D164 have no base: an unchanged one is what the server held. */
  private suspend fun seedBases() = transaction {
    for (c in dao.categoriesWithoutBase()) dao.putBase(SyncBaseRow(CATEGORY, c.id, categoryJson(c).toString()))
    for (t in dao.tagsWithoutBase()) dao.putBase(SyncBaseRow(TAG_TABLE, t.id, tagJson(t).toString()))
    for (p in dao.productsWithoutBase()) dao.putBase(SyncBaseRow("product", p.id, productJson(p).toString()))
    for (s in dao.storeItemsWithoutBase()) dao.putBase(SyncBaseRow("store_item", s.id, storeItemJson(s).toString()))
    for (l in dao.linesWithoutBase()) dao.putBase(SyncBaseRow(LINES, l.syncKey, lineJson(l).toString()))
  }

  private suspend fun park(table: String, key: String, payload: String, e: SupabaseException, errors: MutableList<String>) {
    errors += "$table $key: ${e.message}"
    dao.park(SyncParkedRow(table, key, payload, e.message ?: "rejected", System.currentTimeMillis()))
  }

  private fun owned(remote: SyncRemote, o: JsonObject) = JsonObject(o + ("ledger_id" to JsonPrimitive(remote.ledgerId)))

  /**
   * Receipts deleted here (T-50, D194), first: a receipt scanned again since goes up after this as the new copy it is.
   * The server removes the rows, keeps a tombstone and names the Storage objects that held the page and photos; those
   * are removed next, each set kept in sync_state until it's done ([DELETED_FILES]), so a run that stops in between
   * leaves nothing behind online.
   */
  private suspend fun pushDeletions(remote: SyncRemote, errors: MutableList<String>): Int {
    var n = 0
    for (name in dao.stateNames(ReceiptStore.DELETED)) {
      val chave = name.removePrefix(ReceiptStore.DELETED)
      val at = dao.state(name)?.toLongOrNull() ?: run { dao.deleteStates(listOf(name)); continue }
      try {
        val files = io {
          remote.rpc("delete_receipt", buildJsonObject { put("lid", remote.ledgerId); put("receipt_chave", chave); put("at", iso(at)) })
        }.let { it as? JsonArray }.orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
        transaction {
          if (files.isNotEmpty()) dao.putState(SyncStateRow(DELETED_FILES + chave, JsonArray(files.map(::JsonPrimitive)).toString()))
          dao.deleteStates(listOf(name))
        }
        n++
      } catch (e: SupabaseException) {
        if (e.status == 401) throw e
        errors += "receipt $chave: not deleted: ${e.message}"
      }
    }
    for (name in dao.stateNames(DELETED_FILES)) {
      val paths = dao.state(name)?.let { json.parseToJsonElement(it) as? JsonArray }.orEmpty().map { it.jsonPrimitive.content }
      try {
        if (paths.isNotEmpty()) io { remote.deleteObjects(paths) }
        dao.deleteStates(listOf(name))
      } catch (e: SupabaseException) {
        if (e.status == 401) throw e
        errors += "${name.removePrefix(DELETED_FILES)}: files not deleted: ${e.message}"
      }
    }
    return n
  }

  /** The receipt row with its parsed header; the page goes to storage when the receipt itself changed. */
  private suspend fun pushReceipts(remote: SyncRemote, errors: MutableList<String>): Int {
    val dirtyHeaders = dao.dirtyParsed().associateBy { it.chave }
    val parked = dao.parked(RECEIPT).associate { it.rowKey to it.payload }
    var n = 0
    for (r in store.receipts.value) {
      val changed = r.uploadedVersion != r.version
      val header = dirtyHeaders[r.chave] ?: if (changed) dao.parsed(r.chave) else null
      if (!changed && header == null) continue
      val pagePath = pagePath(remote, r)
      val row = receiptJson(r, header, pagePath)
      val attempt = "${r.version} $row"
      if (parked[r.chave] == attempt) continue
      try {
        if (changed && r.pageFile != null) {
          val page = File(store.dir(r.chave), r.pageFile)
          if (page.isFile) io { remote.upload(pagePath!!, page.readBytes(), "text/html; charset=utf-8") }
        }
        io { remote.upsert(RECEIPT, listOf(owned(remote, row)), "ledger_id,chave") }
        // This phone's scan mark (D104); the server fills in who. Another member's mark is theirs.
        if (changed && r.scannedHere) io {
          remote.upsert("receipt_scan", listOf(owned(remote, buildJsonObject { put("chave", r.chave); put("scanned_at", iso(r.capturedAt)) })),
            "ledger_id,chave,user_id", ignoreDuplicates = true)
        }
        if (changed) store.markUploaded(r.chave, r.version, REMOTE_ID)
        dirtyHeaders[r.chave]?.let { pushed -> transaction { if (dao.parsed(pushed.chave) == pushed) dao.upsertParsed(pushed.copy(dirty = false)) } }
        if (r.chave in parked) dao.unpark(RECEIPT, listOf(r.chave))
        n++
      } catch (e: SupabaseException) {
        if (e.status == 401) throw e
        // Bad data for this one receipt: record it, don't send it again until it changes, carry on with the rest.
        park(RECEIPT, r.chave, attempt, e, errors)
        store.markUploadError(r.chave, e.message ?: "rejected")
      }
    }
    return n
  }

  /**
   * Per receipt: the lines this phone removed (a re-read page can drop a line, D164), then its dirty lines. Only the
   * removed ones go, never "whatever this phone lacks", which would take lines another phone's newer read added. The
   * removals go first, so a phone that pulls the lines this upsert touches also sees them gone; with no line to
   * upsert, the receipt row is sent again to the same end. Per receipt, so one that can't go up (its receipt failed)
   * doesn't hold back the others. A line with no base goes whole; one with a base as its extracted columns and the
   * corrections that differ from it (D182), after an insert-if-missing of the whole line.
   */
  private suspend fun pushLines(remote: SyncRemote, errors: MutableList<String>): Int {
    var n = 0
    val parked = dao.parked(LINES).associate { it.rowKey to it.payload }
    val dirty = dao.dirtyLines().groupBy { it.receipt }
    val removed = dao.removedLines().groupBy({ it.receipt }, { it.lineNo })
    for (chave in dirty.keys + removed.keys) {
      val rows = dirty[chave].orEmpty()
      val gone = removed[chave].orEmpty()
      val payload = rows.map(::lineJson).toString() + gone
      if (parked[chave] == payload) continue
      val bases = rows.map { it.syncKey }.chunked(500).flatMap { dao.bases(LINES, it) }
        .associate { it.rowKey to json.parseToJsonElement(it.payload) as JsonObject }
      // A line with no base goes whole. One with a base sends nothing if it's what the server held, else its extracted
      // columns and the corrections that differ from the base.
      val whole = rows.filter { bases[it.syncKey] == null }.map(::lineJson)
      val changed = rows.mapNotNull { row ->
        val base = bases[row.syncKey] ?: return@mapNotNull null
        val all = lineJson(row)
        if (all.all { (field, value) -> base[field] == value }) return@mapNotNull null
        row to JsonObject(all.filter { (field, value) -> field !in LINE_CORRECTIONS || base[field] != value })
      }
      // Fields alone can't make a row another phone has removed meanwhile: such a line is first put back whole, as
      // this phone has it, and an insert that finds the row there changes nothing (a correction outlives a removal, D182).
      val ifMissing = changed.map { lineJson(it.first) }
      val sends = whole + changed.map { it.second }
      try {
        if (gone.isNotEmpty()) io {
          remote.delete(LINES, listOf("ledger_id" to "eq.${remote.ledgerId}", "chave" to "eq.$chave", "line_no" to "in.(${gone.joinToString(",")})"))
        }
        if (ifMissing.isNotEmpty()) io { remote.upsert(LINES, ifMissing.map { owned(remote, it) }, "ledger_id,chave,line_no", ignoreDuplicates = true) }
        // PostgREST takes the same keys in every row of a request.
        for (group in sends.groupBy { it.keys }.values) io { remote.upsert(LINES, group.map { owned(remote, it) }, "ledger_id,chave,line_no") }
        if (sends.isEmpty() && gone.isNotEmpty()) store.get(chave)?.takeIf { it.uploadedVersion != null }?.let { r ->
          io { remote.upsert(RECEIPT, listOf(owned(remote, receiptJson(r, dao.parsed(chave), pagePath(remote, r)))), "ledger_id,chave") }
        }
        transaction {
          for (row in rows) {
            if (dao.line(row.receipt, row.lineNo) == row) dao.upsertLine(row.copy(dirty = false))
            // The server now has the fields sent, and the base already had the others.
            dao.putBase(SyncBaseRow(LINES, row.syncKey, lineJson(row).toString()))
          }
          dao.clearRemovedLines(chave, gone)
          dao.deleteBases(LINES, gone.map { lineSyncKey(chave, it) })
        }
        if (chave in parked) dao.unpark(LINES, listOf(chave))
        n += sends.size + gone.size
      } catch (e: SupabaseException) {
        if (e.status == 401) throw e
        park(LINES, chave, payload, e, errors)
      }
    }
    return n
  }

  private fun pagePath(remote: SyncRemote, r: Receipt) = r.pageFile?.let { "${remote.ledgerId}/${r.chave}/page.html" }

  /**
   * Tree edits (D75), after the categories they name. Append-only on the server: a resend changes nothing. One the
   * server refuses (a plain member's, T-29) is parked like a refused row, so it shows in Ajustes and the run goes on;
   * an event never changes, so it waits there until "Tentar de novo".
   */
  private suspend fun pushEvents(remote: SyncRemote, errors: MutableList<String>): Int {
    val parked = dao.parked(EVENTS).mapTo(HashSet()) { it.rowKey }
    val events = database.categoryEvents().dirty().filter { it.id !in parked }
    if (events.isEmpty()) return 0
    fun send(batch: List<io.github.sandroroque.getnf.data.CategoryEventRow>) =
      remote.upsert(EVENTS, batch.map { owned(remote, eventJson(it)) }, "id", ignoreDuplicates = true)
    val sent = try {
      io { send(events) }
      events
    } catch (e: SupabaseException) {
      if (e.status == 401) throw e
      events.filter { event ->
        try { io { send(listOf(event)) }; true } catch (e: SupabaseException) {
          if (e.status == 401) throw e
          park(EVENTS, event.id, eventJson(event).toString(), e, errors); false
        }
      }
    }
    database.categoryEvents().pushed(sent.map { it.id })
    return sent.size
  }

  /** Each photo's archive copy (D61) and mask, then its row; confirmed one by one, so a run resumes where it stopped. */
  private suspend fun pushPhotos(remote: SyncRemote, errors: MutableList<String>): Int {
    var n = 0
    for (r in store.receipts.value) {
      if (r.uploadedVersion != r.version) continue // the receipt row isn't up yet: its photos would have no parent
      if (!contribute(r)) continue
      for ((position, name) in r.photos.withIndex()) {
        if (name in r.uploadedPhotos) continue
        val file = File(store.dir(r.chave), name)
        if (!file.isFile) { errors += "${r.chave}/$name: missing"; continue }
        try {
          val segment = r.captureSegments.find { it.photo == name }
          val archived = io { archiver.archive(file, segment) }
          val objectPath = "${remote.ledgerId}/${r.chave}/${name.substringBeforeLast('.')}.webp"
          io { remote.upload(objectPath, archived, "image/webp") }
          val maskPath = segment?.mask?.let { mask ->
            File(store.dir(r.chave), mask).takeIf { it.isFile }?.let { m ->
              "${remote.ledgerId}/${r.chave}/$mask".also { path -> io { remote.upload(path, m.readBytes(), "image/png") } }
            }
          }
          io { remote.upsert("receipt_photo", listOf(owned(remote, photoJson(r.chave, name, position, segment, file, objectPath, maskPath, archived.size))), "ledger_id,chave,local_name") }
          store.markPhotoUploaded(r.chave, name)
          n++
        } catch (e: SupabaseException) {
          errors += "${r.chave}/$name: ${e.message}"
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
          throw e
        } catch (e: Exception) {
          // The copy couldn't be made (the photo couldn't be read to blank what's private, D98): it waits for the
          // next run rather than going up as it is.
          errors += "${r.chave}/$name: could not prepare its copy: ${e.message}"
        }
      }
    }
    return n
  }

  // ---- pull ----

  /**
   * Each table from a minute before the last row seen, then page after page in (updated_at, key) order, each page
   * starting after the last row of the one before (D164): a page boundary inside a run of equal timestamps neither
   * skips nor repeats a row.
   */
  private suspend fun pull(remote: SyncRemote, tables: List<String>, errors: MutableList<String>): Int {
    var n = 0
    for (table in tables) {
      val cursorName = "cursor:$table"
      val since = dao.state(cursorName)?.let { instant(it).minusSeconds(OVERLAP_SECONDS).toString() }
      val order = listOf("updated_at") + KEYS.getValue(table)
      var last: JsonObject? = null
      while (true) {
        val filters = buildList {
          add("ledger_id" to "eq.${remote.ledgerId}")
          if (last != null) add("or" to after(last!!, order)) else if (since != null) add("updated_at" to "gt.$since")
          add("order" to order.joinToString(",") { "$it.asc" }); add("limit" to PAGE.toString())
        }
        val rows = io { remote.select(table, filters) }
        if (rows.isEmpty()) break
        apply(remote, table, rows, errors)
        n += rows.size
        last = rows.last()
        transaction {
          // Written with the cursor that moves past them: once it has, the overlap won't bring these rows back (T-12).
          if (table == LINES || table == RECEIPT) dao.putStates(rows.map { SyncStateRow(RECONCILE + it.string("chave")!!, "") }.distinct())
          dao.putState(SyncStateRow(cursorName, last.string("updated_at")!!))
        }
        if (rows.size < PAGE) break
      }
    }
    return n
  }


  /**
   * PostgREST's filter for rows after [row] in the order of [columns]: (a > x) or (a = x and b > y) or ... Values are
   * quoted, since a key may hold a comma or a parenthesis.
   */
  private fun after(row: JsonObject, columns: List<String>): String {
    fun quoted(column: String) = "\"" + row.string(column)!!.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val terms = columns.indices.map { i ->
      val parts = columns.take(i).map { "$it.eq.${quoted(it)}" } + "${columns[i]}.gt.${quoted(columns[i])}"
      parts.singleOrNull() ?: "and(${parts.joinToString(",")})"
    }
    return "(${terms.joinToString(",")})"
  }

  /**
   * Lines another phone removed (D164). For each receipt whose row or lines a pull brought (kept in sync_state as
   * `reconcile:<chave>` until done, so a run that stops halfway leaves them for the next, T-12) and that has lines
   * here, the line numbers the server holds; a line the phone has and the server doesn't goes, unless the phone
   * changed it meanwhile: then it goes up again next run, so a correction made before the removal arrived isn't lost.
   */
  private suspend fun reconcileLines(remote: SyncRemote) {
    val pending = dao.stateNames(RECONCILE).map { it.removePrefix(RECONCILE) }
    val (local, nothingHere) = pending.partition { dao.lineNumbers(it).isNotEmpty() }
    nothingHere.chunked(500).forEach { chunk -> dao.deleteStates(chunk.map { RECONCILE + it }) }
    for (chunk in local.chunked(50)) {
      val onServer = mutableSetOf<Pair<String, Int>>()
      var last: JsonObject? = null
      while (true) {
        val rows = io {
          remote.select(LINES, buildList {
            add("select" to "chave,line_no"); add("ledger_id" to "eq.${remote.ledgerId}")
            add("chave" to "in.(${chunk.joinToString(",") { "\"$it\"" }})")
            last?.let { add("or" to after(it, LINE_KEY)) }
            add("order" to "chave.asc,line_no.asc"); add("limit" to PAGE.toString())
          })
        }
        rows.mapTo(onServer) { it.string("chave")!! to it.long("line_no")!!.toInt() }
        if (rows.size < PAGE) break
        last = rows.last()
      }
      transaction {
        for (chave in chunk) for (no in dao.lineNumbers(chave)) {
          if ((chave to no) in onServer) continue
          // No row online to merge into: a line that goes up again goes whole (D182).
          dao.deleteBases(LINES, listOf(lineSyncKey(chave, no)))
          if (dao.line(chave, no)?.dirty == false) dao.deleteLine(chave, no)
        }
        dao.deleteStates(chunk.map { RECONCILE + it })
      }
    }
  }

  /** The whole member list each run (it's small, and a member who left must disappear, D104). */
  private suspend fun pullMembers(remote: SyncRemote) {
    val members = io { remote.select("ledger_member", listOf("ledger_id" to "eq.${remote.ledgerId}")) }
      .map { MemberRow(it.string("user_id")!!, it.string("display_name").orEmpty(), it.string("user_id") == remote.userId, it.string("role")) }
    transaction { dao.clearMembers(); dao.putMembers(members) }
  }

  private suspend fun apply(remote: SyncRemote, table: String, rows: List<JsonObject>, errors: MutableList<String>) = when (table) {
    CATEGORY -> applyCategories(rows)
    TAG_TABLE -> applyTags(rows)
    "issuer" -> transaction { for (o in rows) { val cnpj = o.string("cnpj")!!; if (dao.issuer(cnpj)?.dirty != true) dao.upsertIssuer(IssuerRow(cnpj, o.string("name")!!)) } }
    "receipt" -> applyReceipts(remote, rows, errors)
    "product" -> transaction {
      for (o in rows) merged("product", o, ::product, { dao.product(it.id) }, { it.dirty }, ::productJson) { it.copy(dirty = true) }?.let { dao.upsertProduct(it) }
    }
    "store_item" -> transaction {
      for (o in rows) merged("store_item", o, ::storeItem, { dao.storeItem(it.id) }, { it.dirty }, ::storeItemJson) { it.copy(dirty = true) }?.let { dao.upsertStoreItem(it) }
    }
    "product_tag" -> transaction {
      for (o in rows) { val t = productTag(o); if (dao.productTag(t.productId, t.tagId)?.dirty != true) dao.upsertProductTag(t) }
    }
    "receipt_line" -> transaction {
      for (o in rows) {
        val l = line(o)
        // Only for receipts the phone has (all of them, once receipts were pulled).
        if (store.receipts.value.none { it.chave == l.receipt }) continue
        merged(LINES, o, ::line, { dao.line(it.receipt, it.lineNo) }, { it.dirty }, ::lineJson, l.syncKey) { it.copy(dirty = true) }
          ?.let { dao.upsertLine(it) }
      }
    }
    "link_suggestion" -> transaction {
      for (o in rows) {
        val id = o.string("id")!!
        if (dao.linkSuggestion(id)?.dirty != true) dao.putLinkSuggestion(LinkSuggestionRow(id, o.string("store_item_id")!!,
          o.string("product_id")!!, o.double("confidence"), o.string("reason"), o.string("status") ?: "pending"))
      }
    }
    "receipt_scan" -> dao.putScans(rows.mapNotNull { o -> o.string("user_id")?.let { ReceiptScanRow(o.string("chave")!!, it) } })
    "worker_report" -> transaction {
      for (o in rows) dao.putWorkerReport(WorkerReportRow(o.long("id")!!, o.millis("at") ?: 0, o["report"].toString()))
    }
    else -> error("no puller for $table")
  }

  /**
   * Parents may arrive after children, so rows go in without their parent first and get it in a second pass. A seeded
   * category the phone holds under another id hands its products and children to the server's id and goes.
   */
  private suspend fun applyCategories(rows: List<JsonObject>) = transaction {
    val parents = mutableMapOf<String, String?>()
    for (o in rows) {
      val c = merged(CATEGORY, o, ::category, { dao.category(it.id) }, { it.dirty }, ::categoryJson) { it.copy(dirty = true) } ?: continue
      parents[c.id] = c.parentId
      // A row the phone changed is its own already; only a clean one can be a seed known under another id.
      val seeded = if (c.dirty) null else c.seedPath?.let { dao.categoryBySeed(it) }?.takeIf { it.id != c.id }
      if (seeded != null) dao.releaseSeedPath(seeded.id)
      dao.upsertCategory(c.copy(parentId = c.parentId?.takeIf { dao.category(it) != null }))
      if (seeded != null) {
        dao.moveProductsToCategory(seeded.id, c.id); dao.moveChildren(seeded.id, c.id); dao.deleteCategory(seeded.id)
      }
    }
    for ((id, parent) in parents) dao.category(id)?.takeIf { it.parentId != parent }?.let { dao.upsertCategory(it.copy(parentId = parent)) }
  }

  private suspend fun applyTags(rows: List<JsonObject>) = transaction {
    for (o in rows) {
      val t = merged(TAG_TABLE, o, ::tag, { dao.tag(it.id) }, { it.dirty }, ::tagJson) { it.copy(dirty = true) } ?: continue
      if (t.dirty) { dao.upsertTag(t); continue }
      val known = dao.tagBySlug(t.slug)?.takeIf { it.id != t.id }
      if (known != null) dao.releaseSlug(known.id)
      dao.upsertTag(t)
      if (known != null) { dao.moveProductTags(known.id, t.id); dao.deleteTag(known.id) }
    }
  }

  /**
   * A pulled catalog row (D164), to write; null to leave the phone's copy as it is. A row the phone hasn't changed is
   * the server's. One it changed keeps the fields it changed since the base and takes the server's for the rest,
   * staying dirty if that differs from the server; without a base (changed before D164), the phone's copy stands.
   * Either way the server's row becomes the base.
   */
  private suspend fun <T> merged(
    table: String, o: JsonObject, fromJson: (JsonObject) -> T, local: suspend (T) -> T?, dirty: (T) -> Boolean,
    toJson: (T) -> JsonObject, key: String = o.string("id")!!, markDirty: (T) -> T,
  ): T? {
    val pulled = fromJson(o)
    val server = toJson(pulled)
    val base = dao.base(table, key)?.let { json.parseToJsonElement(it.payload) as JsonObject }
    dao.putBase(SyncBaseRow(table, key, server.toString()))
    val mine = local(pulled)?.takeIf(dirty) ?: return pulled
    if (base == null) return null
    val changed = toJson(mine).filter { (field, value) -> base[field] != value }
    val row = fromJson(JsonObject(o + changed))
    return if (toJson(row) == server) row else markDirty(row)
  }

  /**
   * A receipt the phone doesn't have (saved from elsewhere) is adopted with its page; the parser then reads it here.
   * For known receipts the phone's own parse stands, unless the phone never parsed them. A page Storage won't give is
   * left for later ([fetchPage]): the receipt comes in without it, and the rest of the pull goes on (T-28).
   */
  private suspend fun applyReceipts(remote: SyncRemote, rows: List<JsonObject>, errors: MutableList<String>) {
    for (o in rows) {
      val chave = o.string("chave")!!
      val known = store.receipts.value.find { it.chave == chave }
      if (o.bool("deleted") == true) {
        // Deleted in the caderneta (D194): the copy here goes too, unless it was scanned here after the deletion,
        // which then goes up and brings the receipt back.
        val deletedAt = o.millis("deleted_at")
        if (known != null && (deletedAt == null || known.capturedAt <= deletedAt)) store.delete(chave, byUser = false)
        continue
      }
      val pagePath = o.string("page_path")
      if (known == null) {
        val page = pagePath != null && fetchPage(remote, chave, pagePath, errors)
        store.adoptRemote(
          Receipt(
            chave = chave, qrUrl = o.string("qr_url"), capturedAt = o.millis("captured_at") ?: 0, pageUrl = o.string("page_url"),
            pageFile = if (page) ReceiptStore.PAGE_FILE else null, pageFetchedAt = o.millis("page_fetched_at"),
            version = 1, uploadedVersion = 1, remoteId = REMOTE_ID, scannedHere = false,
          ),
          emptyList(),
        )
      } else if (known.pageFile == null && pagePath != null) {
        // Another member fetched the page of a receipt this phone was waiting on (D104): no lookup needed here.
        if (fetchPage(remote, chave, pagePath, errors)) store.adoptPage(chave, o.string("page_url"), o.millis("page_fetched_at"))
      }
      val local = dao.parsed(chave)
      if (local == null || (local.source != ReceiptParsedRow.SOURCE_PHONE && !local.dirty)) {
        o.string("issuer_cnpj")?.let { cnpj -> if (dao.issuer(cnpj) == null) dao.upsertIssuer(IssuerRow(cnpj, cnpj)) }
        dao.upsertParsed(ReceiptParsedRow(
          chave, o.string("issuer_cnpj"), o.millis("issued_at"), o.long("gross_cents"), o.long("discount_cents"),
          o.long("paid_cents"), o.long("parser_version")?.toInt(), o.string("parse_error"), source = ReceiptParsedRow.SOURCE_SERVER,
          payments = o["payments"]?.takeIf { it !is JsonNull }?.toString(), changeCents = o.long("change_cents"),
          taxFederalCents = o.long("tax_federal_cents"), taxStateCents = o.long("tax_state_cents"),
          taxMunicipalCents = o.long("tax_municipal_cents"), taxTotalCents = o.long("tax_total_cents"),
        ))
      }
    }
  }

  /**
   * Downloads a receipt's page from Storage. One that fails (Storage answers a missing object with a 400, T-28) is
   * recorded as `page:<chave>` in sync_state and tried again each run by [retryPages], instead of failing the run: a
   * row whose object is gone would otherwise stop every sync at the same receipt.
   */
  private suspend fun fetchPage(remote: SyncRemote, chave: String, path: String, errors: MutableList<String>): Boolean = try {
    io { remote.download(path, File(store.dir(chave).apply { mkdirs() }, ReceiptStore.PAGE_FILE)) }
    dao.deleteStates(listOf(PAGE_PENDING + chave))
    true
  } catch (e: SupabaseException) {
    if (e.status == 401) throw e
    errors += "receipt $chave: page not downloaded: ${e.message}"
    dao.putState(SyncStateRow(PAGE_PENDING + chave, path))
    false
  } catch (e: java.io.IOException) {
    // Transient (T-17): kept pending for the next run, and the run stops so the worker backs off and retries.
    dao.putState(SyncStateRow(PAGE_PENDING + chave, path))
    throw e
  }

  /** Pages an earlier run couldn't download; one the phone got meanwhile (a lookup of its own) is no longer wanted. */
  private suspend fun retryPages(remote: SyncRemote, errors: MutableList<String>) {
    for (name in dao.stateNames(PAGE_PENDING)) {
      val chave = name.removePrefix(PAGE_PENDING)
      val r = store.get(chave)
      val path = dao.state(name)
      if (r == null || r.pageFile != null || path == null) { dao.deleteStates(listOf(name)); continue }
      if (fetchPage(remote, chave, path, errors)) store.adoptPage(chave, r.pageUrl, r.pageFetchedAt)
    }
  }

  // ---- JSON, both ways. Money and sizes are exact integers; times are ISO instants on the server, millis here. ----

  private fun issuerJson(r: IssuerRow) = buildJsonObject { put("cnpj", r.cnpj); put("name", r.name) }

  private fun receiptJson(r: Receipt, h: ReceiptParsedRow?, pagePath: String?) = buildJsonObject {
    put("chave", r.chave); put("qr_url", r.qrUrl); put("captured_at", iso(r.capturedAt)); put("page_url", r.pageUrl)
    put("page_path", pagePath); put("page_fetched_at", r.pageFetchedAt?.let(::iso))
    if (h != null) {
      put("issuer_cnpj", h.issuerCnpj); put("issued_at", h.issuedAt?.let(::iso)); put("gross_cents", h.grossCents)
      put("discount_cents", h.discountCents); put("paid_cents", h.paidCents); put("parser_version", h.parserVersion)
      put("parse_error", h.parseError)
      put("payments", h.payments?.let(Json::parseToJsonElement) ?: JsonNull); put("change_cents", h.changeCents)
      put("tax_federal_cents", h.taxFederalCents); put("tax_state_cents", h.taxStateCents)
      put("tax_municipal_cents", h.taxMunicipalCents); put("tax_total_cents", h.taxTotalCents)
    }
  }

  private fun categoryJson(c: CategoryRow) = buildJsonObject {
    put("id", c.id); put("seed_path", c.seedPath); put("label", c.label); put("parent_id", c.parentId); put("sort", c.sort)
    put("user_modified", c.userModified); put("deleted", c.deleted); put("description", c.description)
  }

  /** Only the user's answer goes up: the suggestion itself is the worker's. */
  private fun linkJson(l: LinkSuggestionRow) = buildJsonObject {
    put("id", l.id); put("store_item_id", l.storeItemId); put("product_id", l.productId); put("status", l.status)
  }

  private fun eventJson(e: io.github.sandroroque.getnf.data.CategoryEventRow) = buildJsonObject {
    put("id", e.id); put("op", e.op); put("category_id", e.categoryId)
    put("target_ids", kotlinx.serialization.json.JsonArray(e.targetIds.split(",").filter { it.isNotEmpty() }.map(::JsonPrimitive)))
    put("old_label", e.oldLabel); put("new_label", e.newLabel); put("old_parent_id", e.oldParentId)
    put("new_parent_id", e.newParentId); put("products", e.products); put("at", iso(e.at))
  }

  private fun category(o: JsonObject) = CategoryRow(
    o.string("id")!!, o.string("seed_path"), o.string("label")!!, o.string("parent_id"), o.long("sort")!!.toInt(),
    o.bool("user_modified") ?: false, o.bool("deleted") ?: false, description = o.string("description"),
    descriptionSuggestion = o.string("description_suggestion"),
  )

  private fun tagJson(t: TagRow) = buildJsonObject {
    put("id", t.id); put("slug", t.slug); put("description", t.description); put("seed", t.seed); put("deleted", t.deleted)
  }

  private fun tag(o: JsonObject) =
    TagRow(o.string("id")!!, o.string("slug")!!, o.string("description")!!, o.bool("seed") ?: true, o.bool("deleted") ?: false)

  /** Suggestions are left out: only the server writes them (4b). */
  private fun productJson(p: ProductRow) = buildJsonObject {
    put("id", p.id); put("name", p.name); put("category_id", p.categoryId); put("category_source", p.categorySource)
    put("category_review", p.categoryReview); put("category_model", p.categoryModel); put("category_confidence", p.categoryConfidence)
    put("category_at", p.categoryAt?.let(::iso)); put("size_e6", p.sizeE6); put("size_unit", p.sizeUnit)
    put("size_source", p.sizeSource); put("archived", p.archived); put("deleted", p.deleted); put("created_at", iso(p.createdAt))
  }

  private fun product(o: JsonObject) = ProductRow(
    o.string("id")!!, o.string("name"), o.string("category_id"), o.long("size_e6"), o.string("size_unit"), o.string("size_source"),
    o.bool("archived") ?: false, o.millis("created_at") ?: 0, o.string("category_source"), o.string("category_review"),
    o.string("category_model"), o.double("category_confidence"), o.millis("category_at"), o.bool("deleted") ?: false,
    suggestionCategoryId = o.string("suggestion_category_id"), suggestionConfidence = o.double("suggestion_confidence"),
    suggestionModel = o.string("suggestion_model"), suggestionAt = o.millis("suggestion_at"),
    sizeSuggestionE6 = o.long("size_suggestion_e6"), sizeSuggestionUnit = o.string("size_suggestion_unit"),
    sizeSuggestionModel = o.string("size_suggestion_model"),
  )

  /** System-1's readings are left out: only the server writes them (D108). The user's answer goes up (D110). */
  private fun storeItemJson(s: StoreItemRow) = buildJsonObject {
    put("id", s.id); put("code_kind", s.codeKind); put("code", s.code); put("store_cnpj", s.storeCnpj)
    put("description", s.description); put("parsed_size_e6", s.parsedSizeE6); put("parsed_size_unit", s.parsedSizeUnit)
    put("product_id", s.productId); put("link_source", s.linkSource); put("link_review", s.linkReview); put("deleted", s.deleted)
    put("sold_by_user", s.soldByUser)
  }

  private fun storeItem(o: JsonObject) = StoreItemRow(
    o.string("id")!!, o.string("code_kind")!!, o.string("code")!!, o.string("store_cnpj") ?: "", o.string("description")!!,
    o.long("parsed_size_e6"), o.string("parsed_size_unit"), o.string("product_id")!!, o.string("link_source") ?: "regex",
    o.string("link_review"), o.bool("deleted") ?: false,
    soldBy = o.string("sold_by"), soldBySource = o.string("sold_by_source"), soldByConfidence = o.double("sold_by_confidence"),
    readSizeE6 = o.long("read_size_e6"), readSizeUnit = o.string("read_size_unit"), readSizeSource = o.string("read_size_source"),
    readSizeConfidence = o.double("read_size_confidence"), readPackCount = o.long("read_pack_count")?.toInt(),
    readModel = o.string("read_model"), soldBySuggestion = o.string("sold_by_suggestion"),
    soldBySuggestionConfidence = o.double("sold_by_suggestion_confidence"), soldByUser = o.string("sold_by_user"),
  )

  private fun productTagJson(t: ProductTagRow) = buildJsonObject {
    put("product_id", t.productId); put("tag_id", t.tagId); put("deleted", t.deleted)
  }

  private fun productTag(o: JsonObject) = ProductTagRow(o.string("product_id")!!, o.string("tag_id")!!, o.bool("deleted") ?: false)

  private fun lineJson(l: ReceiptLineRow) = l.syncJson()

  private fun line(o: JsonObject) = ReceiptLineRow(
    o.string("chave")!!, o.long("line_no")!!.toInt(), o.string("description")!!, o.string("code")!!, o.long("qty_e4")!!,
    o.string("unit"), o.long("unit_price_e4")!!, o.long("gross_cents")!!, o.string("store_item_id")!!,
    o.string("product_override_id"), o.long("discount_cents") ?: 0, o.string("discount_source")!!,
    o.string("discount_review") ?: "unreviewed", o.bool("orphaned") ?: false, surchargeCents = o.long("surcharge_cents") ?: 0,
  )

  private fun photoJson(chave: String, name: String, position: Int, segment: CaptureSegment?, file: File, objectPath: String,
                        maskPath: String?, bytes: Int) = buildJsonObject {
    put("chave", chave); put("local_name", name); put("position", position); put("session_id", segment?.sessionId)
    put("segment", segment?.let { json.parseToJsonElement(json.encodeToString(CaptureSegment.serializer(), it)) } ?: JsonNull)
    put("captured_at", iso(segment?.capturedAt ?: file.lastModified())); put("object_path", objectPath)
    put("mask_path", maskPath); put("bytes", bytes)
  }

  private fun iso(millis: Long) = Instant.ofEpochMilli(millis).toString()
  private fun JsonObject.field(name: String): JsonPrimitive? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }
  private fun JsonObject.string(name: String) = field(name)?.content
  private fun JsonObject.long(name: String) = field(name)?.longOrNull
  private fun JsonObject.double(name: String) = field(name)?.doubleOrNull
  private fun JsonObject.bool(name: String) = field(name)?.booleanOrNull
  private fun JsonObject.millis(name: String) = string(name)?.let { instant(it).toEpochMilli() }

  /** PostgREST writes `2026-10-25T12:00:00.123456+00:00`, which Instant.parse doesn't read on older Androids. */
  private fun instant(t: String): Instant = java.time.OffsetDateTime.parse(t).toInstant()

  private suspend fun <T> transaction(block: suspend () -> T): T = database.useWriterConnection { it.immediateTransaction { block() } }
  private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

  companion object {
    private const val LOG_TAG = "SyncEngine"
    const val REMOTE_ID = "supabase"
    private const val CATEGORY = "category"
    private const val RECEIPT = "receipt"
    private const val LINES = "receipt_line"
    /** Postgres's wording for 23502, as PostgREST passes it on. */
    private const val NOT_NULL = "violates not-null constraint"
    private const val EVENTS = "category_event"
    // Row ids are unique per caderneta (D104).
    private const val BY_ID = "ledger_id,id"
    private const val TAG_TABLE = "tag"
    // Parents before children, so a pulled row's references already exist locally.
    val TABLES = listOf("issuer", "receipt", "receipt_scan", "category", "tag", "product", "store_item", "product_tag", "receipt_line",
      "link_suggestion", "worker_report")
    // Each pulled table's primary key after ledger_id, for paging (D164).
    private val LINE_KEY = listOf("chave", "line_no")
    private val KEYS = mapOf("issuer" to listOf("cnpj"), RECEIPT to listOf("chave"), "receipt_scan" to listOf("chave", "user_id"),
      "product_tag" to listOf("product_id", "tag_id"), LINES to LINE_KEY).withDefault { listOf("id") }
    internal const val PAGE = 1000
    private const val OVERLAP_SECONDS = 60L
    /** sync_state's prefix for a receipt whose lines wait to be reconciled. */
    private const val RECONCILE = "reconcile:"
    /** sync_state's prefix for a receipt whose page is still to be downloaded (T-28); the value is its object path. */
    private const val PAGE_PENDING = "page:"
    /** sync_state's prefix for a deleted receipt's Storage objects still to be removed (D194); the value is a JSON array. */
    private const val DELETED_FILES = "deleted-files:"
    /** The caderneta this database syncs with, recorded at its first sync (D193). */
    const val LEDGER = "ledger"
    /** Present while this database is another caderneta's (D193): its value is that one's id, or "" if unknown. */
    const val FOREIGN = "foreign_ledger"
  }
}
