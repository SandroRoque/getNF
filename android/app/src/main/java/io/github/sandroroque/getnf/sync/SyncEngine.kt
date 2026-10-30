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
 * applying a row twice is harmless.
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

  data class Report(val pushed: Int, val pulled: Int, val photos: Int, val errors: List<String>)

  suspend fun sync(remote: SyncRemote): Report = mutex.withLock {
    val errors = mutableListOf<String>()
    io { remote.rpc("sync_taxonomy", buildJsonObject { put("lid", remote.ledgerId) }) }
    var pulled = pull(remote, listOf(CATEGORY, TAG_TABLE))
    val (pushed, photos) = push(remote, errors)
    pulled += pull(remote, TABLES)
    pullMembers(remote)
    Report(pushed, pulled, photos, errors).also { log("sync: $it") }
  }

  // ---- push ----

  private suspend fun push(remote: SyncRemote, errors: MutableList<String>): Pair<Int, Int> {
    var n = 0
    n += pushRows(remote, errors, "issuer", "ledger_id,cnpj", dao.dirtyIssuers(), { it.cnpj }, ::issuerJson, { dao.issuer(it.cnpj) }) { dao.upsertIssuer(it.copy(dirty = false)) }
    n += pushReceipts(remote, errors)
    n += pushRows(remote, errors, "category", BY_ID, dao.dirtyCategories(), { it.id }, ::categoryJson, { dao.category(it.id) }) { dao.upsertCategory(it.copy(dirty = false)) }
    n += pushRows(remote, errors, "tag", BY_ID, dao.dirtyTags(), { it.id }, ::tagJson, { dao.tag(it.id) }) { dao.upsertTag(it.copy(dirty = false)) }
    n += pushRows(remote, errors, "product", BY_ID, dao.dirtyProducts(), { it.id }, ::productJson, { dao.product(it.id) }) { dao.upsertProduct(it.copy(dirty = false)) }
    n += pushRows(remote, errors, "store_item", BY_ID, dao.dirtyStoreItems(), { it.id }, ::storeItemJson, { dao.storeItem(it.id) }) { dao.upsertStoreItem(it.copy(dirty = false)) }
    n += pushRows(remote, errors, "product_tag", "ledger_id,product_id,tag_id", dao.dirtyProductTags(), { "${it.productId}/${it.tagId}" },
      ::productTagJson, { dao.productTag(it.productId, it.tagId) }) { dao.upsertProductTag(it.copy(dirty = false)) }
    n += pushLines(remote, errors)
    n += pushEvents(remote)
    n += pushRows(remote, errors, "link_suggestion", BY_ID, dao.dirtyLinkSuggestions(), { it.id }, ::linkJson, { dao.linkSuggestion(it.id) }) {
      dao.putLinkSuggestion(it.copy(dirty = false))
    }
    return n to pushPhotos(remote, errors)
  }

  /**
   * Upserts [rows] in one request. If the server refuses it (a 4xx), they go one by one, and each it refuses is parked
   * (D104): not sent again until it changes, so one bad row neither holds back the rest nor fails every sync.
   */
  private suspend fun <T> pushRows(
    remote: SyncRemote, errors: MutableList<String>, table: String, key: String, rows: List<T>, keyOf: (T) -> String,
    toJson: (T) -> JsonObject, current: suspend (T) -> T?, clean: suspend (T) -> Unit,
  ): Int {
    if (rows.isEmpty()) return 0
    val parked = dao.parked(table).associate { it.rowKey to it.payload }
    val ready = rows.map { it to toJson(it) }.filter { (row, json) -> parked[keyOf(row)] != json.toString() }
    if (ready.isEmpty()) return 0
    val sent = try {
      io { remote.upsert(table, ready.map { owned(remote, it.second) }, key) }
      ready
    } catch (e: SupabaseException) {
      if (e.status == 401) throw e
      ready.filter { (row, json) ->
        try {
          io { remote.upsert(table, listOf(owned(remote, json)), key) }; true
        } catch (e: SupabaseException) {
          if (e.status == 401) throw e
          park(table, keyOf(row), json.toString(), e, errors); false
        }
      }
    }
    transaction {
      for ((row, _) in sent) if (current(row) == row) clean(row)
      if (parked.isNotEmpty()) dao.unpark(table, sent.map { keyOf(it.first) })
    }
    return sent.size
  }

  private suspend fun park(table: String, key: String, payload: String, e: SupabaseException, errors: MutableList<String>) {
    errors += "$table $key: ${e.message}"
    dao.park(SyncParkedRow(table, key, payload, e.message ?: "rejected", System.currentTimeMillis()))
  }

  private fun owned(remote: SyncRemote, o: JsonObject) = JsonObject(o + ("ledger_id" to JsonPrimitive(remote.ledgerId)))

  /** The receipt row with its parsed header; the page goes to storage when the receipt itself changed. */
  private suspend fun pushReceipts(remote: SyncRemote, errors: MutableList<String>): Int {
    val dirtyHeaders = dao.dirtyParsed().associateBy { it.chave }
    val parked = dao.parked(RECEIPT).associate { it.rowKey to it.payload }
    var n = 0
    for (r in store.receipts.value) {
      val changed = r.uploadedVersion != r.version
      val header = dirtyHeaders[r.chave] ?: if (changed) dao.parsed(r.chave) else null
      if (!changed && header == null) continue
      val pagePath = r.pageFile?.let { "${remote.ledgerId}/${r.chave}/page.html" }
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
   * A receipt's dirty lines, then the server's lines that the phone no longer has for that receipt (a re-read page
   * can drop a line). Per receipt, so one that can't go up (its receipt failed) doesn't hold back the others.
   */
  private suspend fun pushLines(remote: SyncRemote, errors: MutableList<String>): Int {
    var n = 0
    val parked = dao.parked(LINES).associate { it.rowKey to it.payload }
    for ((chave, rows) in dao.dirtyLines().groupBy { it.receipt }) {
      val payload = rows.map(::lineJson).toString()
      if (parked[chave] == payload) continue
      try {
        io { remote.upsert("receipt_line", rows.map { owned(remote, lineJson(it)) }, "ledger_id,chave,line_no") }
        val kept = dao.lineNumbers(chave)
        io {
          remote.delete("receipt_line", listOf("ledger_id" to "eq.${remote.ledgerId}", "chave" to "eq.$chave",
            "line_no" to "not.in.(${kept.joinToString(",")})"))
        }
        transaction { for (row in rows) if (dao.line(row.receipt, row.lineNo) == row) dao.upsertLine(row.copy(dirty = false)) }
        if (chave in parked) dao.unpark(LINES, listOf(chave))
        n += rows.size
      } catch (e: SupabaseException) {
        if (e.status == 401) throw e
        park(LINES, chave, payload, e, errors)
      }
    }
    return n
  }

  /** Tree edits (D75), after the categories they name. Append-only on the server: a resend changes nothing. */
  private suspend fun pushEvents(remote: SyncRemote): Int {
    val events = database.categoryEvents().dirty()
    if (events.isEmpty()) return 0
    io { remote.upsert("category_event", events.map { owned(remote, eventJson(it)) }, "id", ignoreDuplicates = true) }
    database.categoryEvents().pushed(events.map { it.id })
    return events.size
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

  private suspend fun pull(remote: SyncRemote, tables: List<String>): Int {
    var n = 0
    for (table in tables) {
      val cursorName = "cursor:$table"
      var cursor = dao.state(cursorName)
      while (true) {
        val since = cursor?.let { instant(it).minusSeconds(OVERLAP_SECONDS).toString() }
        val filters = buildList {
          add("ledger_id" to "eq.${remote.ledgerId}")
          if (since != null) add("updated_at" to "gt.$since")
          add("order" to "updated_at.asc"); add("limit" to PAGE.toString())
        }
        val rows = io { remote.select(table, filters) }
        if (rows.isEmpty()) break
        apply(remote, table, rows)
        n += rows.size
        val last = rows.last().string("updated_at")!!
        dao.putState(SyncStateRow(cursorName, last))
        // A full page whose last row is the cursor we started from would loop: only possible with > PAGE rows in the
        // overlap minute, which a phone never produces.
        if (rows.size < PAGE || last == cursor) break
        cursor = last
      }
    }
    return n
  }

  /** The whole member list each run (it's small, and a member who left must disappear, D104). */
  private suspend fun pullMembers(remote: SyncRemote) {
    val members = io { remote.select("ledger_member", listOf("ledger_id" to "eq.${remote.ledgerId}")) }
      .map { MemberRow(it.string("user_id")!!, it.string("display_name").orEmpty(), it.string("user_id") == remote.userId) }
    transaction { dao.clearMembers(); dao.putMembers(members) }
  }

  private suspend fun apply(remote: SyncRemote, table: String, rows: List<JsonObject>) = when (table) {
    CATEGORY -> applyCategories(rows)
    TAG_TABLE -> applyTags(rows)
    "issuer" -> transaction { for (o in rows) { val cnpj = o.string("cnpj")!!; if (dao.issuer(cnpj)?.dirty != true) dao.upsertIssuer(IssuerRow(cnpj, o.string("name")!!)) } }
    "receipt" -> applyReceipts(remote, rows)
    "product" -> transaction { for (o in rows) { val p = product(o); if (dao.product(p.id)?.dirty != true) dao.upsertProduct(p) } }
    "store_item" -> transaction { for (o in rows) { val s = storeItem(o); if (dao.storeItem(s.id)?.dirty != true) dao.upsertStoreItem(s) } }
    "product_tag" -> transaction {
      for (o in rows) { val t = productTag(o); if (dao.productTag(t.productId, t.tagId)?.dirty != true) dao.upsertProductTag(t) }
    }
    "receipt_line" -> transaction {
      for (o in rows) {
        val l = line(o)
        // Only for receipts the phone has (all of them, once receipts were pulled).
        if (store.receipts.value.none { it.chave == l.receipt }) continue
        if (dao.line(l.receipt, l.lineNo)?.dirty != true) dao.upsertLine(l)
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
    val pulled = rows.map(::category)
    for (c in pulled) {
      if (dao.category(c.id)?.dirty == true) continue
      val seeded = c.seedPath?.let { dao.categoryBySeed(it) }?.takeIf { it.id != c.id }
      if (seeded != null) dao.releaseSeedPath(seeded.id)
      dao.upsertCategory(c.copy(parentId = c.parentId?.takeIf { dao.category(it) != null }))
      if (seeded != null) {
        dao.moveProductsToCategory(seeded.id, c.id); dao.moveChildren(seeded.id, c.id); dao.deleteCategory(seeded.id)
      }
    }
    for (c in pulled) if (dao.category(c.id)?.dirty == false) dao.upsertCategory(dao.category(c.id)!!.copy(parentId = c.parentId))
  }

  private suspend fun applyTags(rows: List<JsonObject>) = transaction {
    for (t in rows.map(::tag)) {
      if (dao.tag(t.id)?.dirty == true) continue
      val known = dao.tagBySlug(t.slug)?.takeIf { it.id != t.id }
      if (known != null) dao.releaseSlug(known.id)
      dao.upsertTag(t)
      if (known != null) { dao.moveProductTags(known.id, t.id); dao.deleteTag(known.id) }
    }
  }

  /**
   * A receipt the phone doesn't have (saved from elsewhere) is adopted with its page; the parser then reads it here.
   * For known receipts the phone's own parse stands, unless the phone never parsed them.
   */
  private suspend fun applyReceipts(remote: SyncRemote, rows: List<JsonObject>) {
    for (o in rows) {
      val chave = o.string("chave")!!
      if (o.bool("deleted") == true) continue
      val known = store.receipts.value.find { it.chave == chave }
      val pagePath = o.string("page_path")
      if (known == null) {
        val dir = store.dir(chave).apply { mkdirs() }
        if (pagePath != null) io { remote.download(pagePath, File(dir, ReceiptStore.PAGE_FILE)) }
        store.adoptRemote(
          Receipt(
            chave = chave, qrUrl = o.string("qr_url"), capturedAt = o.millis("captured_at") ?: 0, pageUrl = o.string("page_url"),
            pageFile = pagePath?.let { ReceiptStore.PAGE_FILE }, pageFetchedAt = o.millis("page_fetched_at"),
            version = 1, uploadedVersion = 1, remoteId = REMOTE_ID, scannedHere = false,
          ),
          emptyList(),
        )
      } else if (known.pageFile == null && pagePath != null) {
        // Another member fetched the page of a receipt this phone was waiting on (D104): no lookup needed here.
        io { remote.download(pagePath, File(store.dir(chave).apply { mkdirs() }, ReceiptStore.PAGE_FILE)) }
        store.adoptPage(chave, o.string("page_url"), o.millis("page_fetched_at"))
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

  private fun lineJson(l: ReceiptLineRow) = buildJsonObject {
    put("chave", l.receipt); put("line_no", l.lineNo); put("description", l.description); put("code", l.code)
    put("qty_e4", l.qtyE4); put("unit", l.unit); put("unit_price_e4", l.unitPriceE4); put("gross_cents", l.grossCents)
    put("store_item_id", l.storeItemId); put("product_override_id", l.productOverrideId); put("discount_cents", l.discountCents)
    put("discount_source", l.discountSource); put("discount_review", l.discountReview); put("orphaned", l.orphaned)
  }

  private fun line(o: JsonObject) = ReceiptLineRow(
    o.string("chave")!!, o.long("line_no")!!.toInt(), o.string("description")!!, o.string("code")!!, o.long("qty_e4")!!,
    o.string("unit"), o.long("unit_price_e4")!!, o.long("gross_cents")!!, o.string("store_item_id")!!,
    o.string("product_override_id"), o.long("discount_cents") ?: 0, o.string("discount_source")!!,
    o.string("discount_review") ?: "unreviewed", o.bool("orphaned") ?: false,
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
    // Row ids are unique per caderneta (D104).
    private const val BY_ID = "ledger_id,id"
    private const val TAG_TABLE = "tag"
    // Parents before children, so a pulled row's references already exist locally.
    val TABLES = listOf("issuer", "receipt", "receipt_scan", "category", "tag", "product", "store_item", "product_tag", "receipt_line",
      "link_suggestion", "worker_report")
    private const val PAGE = 1000
    private const val OVERLAP_SECONDS = 60L
  }
}
