package io.github.sandroroque.getnf.upload

import io.github.sandroroque.getnf.data.CaptureSegment
import io.github.sandroroque.getnf.data.Receipt
import io.github.sandroroque.getnf.data.ServerCategory
import io.github.sandroroque.getnf.data.ServerConfig
import io.github.sandroroque.getnf.data.ServerItem
import io.github.sandroroque.getnf.data.ServerProduct
import io.github.sandroroque.getnf.data.ServerReceipt
import io.github.sandroroque.getnf.data.ServerSnapshot
import io.github.sandroroque.getnf.data.ServerTag
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** A response from PocketBase that retrying won't fix (bad login, validation error...). */
class PocketBaseException(val status: Int, message: String) : Exception(message)

/**
 * Minimal client for the getNF collections (server/pb_migrations). Blocking: call it off the main thread.
 * Network failures surface as [IOException]; HTTP errors as [PocketBaseException].
 */
class PocketBaseClient(private val http: OkHttpClient = OkHttpClient(), private val pageSize: Int = 500) {

  fun login(config: ServerConfig): String {
    val body = buildJsonObject {
      put("identity", config.email)
      put("password", config.password)
    }
    val request =
      Request.Builder()
        .url(api(config, "collections/users/auth-with-password"))
        .post(body.toString().toRequestBody(JSON))
        .build()
    return call(request)["token"]!!.jsonPrimitive.content
  }

  /**
   * Creates or updates the server record for [receipt]: chave, URLs, dates and the page. Photos are never sent here:
   * a multipart `photos` field replaces the server's list and deletes the files left out (D36). They go to
   * receipt_photos with [uploadPhoto] instead. A retry after a lost response is harmless. Returns the record id.
   */
  fun upsert(config: ServerConfig, token: String, receipt: Receipt, dir: File): String {
    val id = receipt.remoteId ?: findId(config, token, receipt.chave)
    val form = MultipartBody.Builder().setType(MultipartBody.FORM)
    form.addFormDataPart("chave", receipt.chave)
    receipt.qrUrl?.let { form.addFormDataPart("qr_url", it) }
    receipt.pageUrl?.let { form.addFormDataPart("page_url", it) }
    form.addFormDataPart("captured_at", Instant.ofEpochMilli(receipt.capturedAt).toString())
    receipt.pageFetchedAt?.let { form.addFormDataPart("page_fetched_at", Instant.ofEpochMilli(it).toString()) }
    receipt.pageFile?.let { form.addFormDataPart("page", it, File(dir, it).asRequestBody(HTML)) }

    val url = if (id == null) api(config, "collections/receipts/records") else api(config, "collections/receipts/records/$id")
    val request = Request.Builder().url(url).header("Authorization", token).let {
      val body = form.build()
      if (id == null) it.post(body) else it.patch(body)
    }
    return try {
      call(request.build())["id"]!!.jsonPrimitive.content
    } catch (e: PocketBaseException) {
      // The record we uploaded before was deleted on the server: look it up again / recreate it.
      if (e.status == 404 && receipt.remoteId != null) upsert(config, token, receipt.copy(remoteId = null), dir) else throw e
    }
  }

  /**
   * Adds one photo to the append-only receipt_photos collection, with its capture mask and segment when it has one.
   * Idempotent: if the server already holds this (receipt, local name), e.g. after a lost response, returns that
   * record's id instead of failing.
   */
  fun uploadPhoto(config: ServerConfig, token: String, receiptId: String, dir: File, name: String, segment: CaptureSegment?): String {
    existingPhoto(config, token, receiptId, name)?.let { return it }
    val image = File(dir, name)
    val form = MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("receipt", receiptId)
      .addFormDataPart("local_name", name)
      .addFormDataPart("image", name, image.asRequestBody(JPEG))
      .addFormDataPart("captured_at", Instant.ofEpochMilli(segment?.capturedAt ?: image.lastModified()).toString())
    if (segment != null) {
      form.addFormDataPart("session_id", segment.sessionId)
      form.addFormDataPart("segment", Json.encodeToString(CaptureSegment.serializer(), segment))
      File(dir, segment.mask).takeIf { it.isFile }?.let { form.addFormDataPart("mask", it.name, it.asRequestBody(PNG)) }
    }
    val request = Request.Builder().url(api(config, "collections/receipt_photos/records")).header("Authorization", token).post(form.build()).build()
    return try {
      call(request)["id"]!!.jsonPrimitive.content
    } catch (e: PocketBaseException) {
      // A concurrent or earlier upload won the unique (receipt, local_name) index.
      if (e.status == 400) existingPhoto(config, token, receiptId, name) ?: throw e else throw e
    }
  }

  /** Everything ServerMirror copies (D46): the catalog, receipts' parsed headers and every line. */
  fun fetchSnapshot(config: ServerConfig, token: String): ServerSnapshot {
    val evidence = list(config, token, "receipt_photos", "receipt").groupingBy { it.str("receipt") }.eachCount()
    return ServerSnapshot(
    categories = list(config, token, "categories", "id,path,label,order,parent").map {
      ServerCategory(it.str("id")!!, it.str("path")!!, it.str("label").orEmpty(), it.num("order")?.toInt() ?: 0, it.str("parent"))
    },
    tags = list(config, token, "tags", "id,slug,description").map { ServerTag(it.str("id")!!, it.str("slug")!!, it.str("description").orEmpty()) },
    products = list(config, token, "products", "id,key,description,category,tags,size_qty,size_unit,size_source").map {
      ServerProduct(
        it.str("id")!!, it.str("key")!!, it.str("description").orEmpty(), it.str("category"),
        (it["tags"] as? JsonArray)?.map { t -> t.jsonPrimitive.content }.orEmpty(),
        it.num("size_qty")?.takeIf { q -> q > 0 }, it.str("size_unit"), it.str("size_source"),
      )
    },
    receipts = list(
      config, token, "receipts",
      "id,chave,qr_url,captured_at,page_url,page_fetched_at,page,photos,store_name,store_cnpj,issued_at,total_gross,discount,total_paid,parser_version,parse_error",
    ).map {
      ServerReceipt(
        it.str("id")!!, it.str("chave")!!, it.str("qr_url"), it.time("captured_at"), it.str("page_url"), it.time("page_fetched_at"),
        it.str("page"), (it["photos"] as? JsonArray)?.map { p -> p.jsonPrimitive.content }.orEmpty(),
        it.str("store_name"), it.str("store_cnpj"), it.time("issued_at"), it.num("total_gross"), it.num("discount"),
        it.num("total_paid"), it.num("parser_version")?.toInt(), it.str("parse_error"), evidence[it.str("id")] ?: 0,
      )
    },
    items = list(
      config, token, "receipt_items", "receipt,line,product,description,code,qty,unit,unit_price,gross,discount,discount_source",
    ).map {
      ServerItem(
        it.str("receipt")!!, it.num("line")!!.toInt(), it.str("product"), it.str("description").orEmpty(), it.str("code").orEmpty(),
        it.num("qty") ?: 0.0, it.str("unit"), it.num("unit_price") ?: 0.0, it.num("gross") ?: 0.0, it.num("discount") ?: 0.0,
        it.str("discount_source"),
      )
    },
  )
  }

  /** Downloads one stored file of a record to [target], via a temporary file so a cut download leaves nothing. */
  fun download(config: ServerConfig, token: String, collection: String, recordId: String, name: String, target: File) {
    // Protected file fields need a short-lived file token; public ones ignore it.
    val fileToken = call(Request.Builder().url(api(config, "files/token")).header("Authorization", token).post(ByteArray(0).toRequestBody()).build())["token"]!!.jsonPrimitive.content
    val url = api(config, "files/$collection/$recordId/$name").newBuilder().addQueryParameter("token", fileToken).build()
    http.newCall(Request.Builder().url(url).header("Authorization", token).build()).execute().use { response ->
      if (!response.isSuccessful) throw PocketBaseException(response.code, "HTTP ${response.code} downloading $name")
      val temp = File(target.parentFile, "${target.name}.tmp")
      temp.outputStream().use { response.body.byteStream().copyTo(it) }
      if (!temp.renameTo(target)) throw IOException("could not save $name")
    }
  }

  private fun list(config: ServerConfig, token: String, collection: String, fields: String): List<JsonObject> {
    val all = mutableListOf<JsonObject>()
    var page = 1
    while (true) {
      val url = api(config, "collections/$collection/records").newBuilder()
        .addQueryParameter("page", page.toString()).addQueryParameter("perPage", pageSize.toString())
        .addQueryParameter("skipTotal", "1").addQueryParameter("fields", fields).build()
      val items = call(Request.Builder().url(url).header("Authorization", token).build())["items"]!!.jsonArray.map { it.jsonObject }
      all += items
      if (items.size < pageSize) return all
      page++
    }
  }

  private fun JsonObject.str(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.ifEmpty { null }
  private fun JsonObject.num(name: String): Double? = str(name)?.toDoubleOrNull()
  /** PocketBase dates: "2026-01-15 13:00:00.000Z"; empty when unset. */
  private fun JsonObject.time(name: String): Long? = str(name)?.let { Instant.parse(it.replace(' ', 'T')).toEpochMilli() }

  private fun existingPhoto(config: ServerConfig, token: String, receiptId: String, name: String): String? {
    val url = api(config, "collections/receipt_photos/records").newBuilder()
      .addQueryParameter("filter", "receipt='$receiptId' && local_name='$name'")
      .addQueryParameter("fields", "id").addQueryParameter("perPage", "1").build()
    val items = call(Request.Builder().url(url).header("Authorization", token).build())["items"]!!.jsonArray
    return items.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
  }

  private fun findId(config: ServerConfig, token: String, chave: String): String? {
    val url =
      api(config, "collections/receipts/records")
        .newBuilder()
        .addQueryParameter("filter", "chave='$chave'")
        .addQueryParameter("fields", "id")
        .addQueryParameter("perPage", "1")
        .build()
    val items = call(Request.Builder().url(url).header("Authorization", token).build())["items"]!!.jsonArray
    return items.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
  }

  private fun call(request: Request): JsonObject =
    http.newCall(request).execute().use { response ->
      val text = response.body.string()
      if (!response.isSuccessful) {
        val message = runCatching { Json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
        throw PocketBaseException(response.code, "HTTP ${response.code}: ${message ?: text.take(200)}")
      }
      Json.parseToJsonElement(text).jsonObject
    }

  private fun api(config: ServerConfig, path: String): HttpUrl = "${config.url.trimEnd('/')}/api/$path".toHttpUrl()

  private companion object {
    val JSON = "application/json".toMediaType()
    val JPEG = "image/jpeg".toMediaType()
    val PNG = "image/png".toMediaType()
    val HTML = "text/html; charset=utf-8".toMediaType()
  }
}
