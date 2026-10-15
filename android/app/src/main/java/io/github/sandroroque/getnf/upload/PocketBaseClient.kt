package io.github.sandroroque.getnf.upload

import io.github.sandroroque.getnf.data.CaptureSegment
import io.github.sandroroque.getnf.data.Receipt
import io.github.sandroroque.getnf.data.ServerConfig
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * Minimal client for the `receipts` collection (server/pb_migrations). Blocking: call it off the main thread.
 * Network failures surface as [IOException]; HTTP errors as [PocketBaseException].
 */
class PocketBaseClient(private val http: OkHttpClient = OkHttpClient()) {

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
