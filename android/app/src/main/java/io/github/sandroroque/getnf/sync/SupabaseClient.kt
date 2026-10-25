package io.github.sandroroque.getnf.sync

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** A response from Supabase that retrying won't fix (bad login, a rejected row...). */
class SupabaseException(val status: Int, message: String) : Exception(message)

/** A signed-in user. The refresh token is what's kept; access tokens last an hour and are renewed each sync. */
data class SupabaseSession(val userId: String, val email: String, val accessToken: String, val refreshToken: String)

/**
 * Minimal client for the project's Auth, Data API (PostgREST) and Storage, over plain HTTP (D70). Blocking: call it
 * off the main thread. Network failures surface as [IOException]; HTTP errors as [SupabaseException].
 * [url] is the project URL, [key] its publishable key (safe in the app; Row Level Security does the protecting).
 */
class SupabaseClient(val url: String, private val key: String, private val http: OkHttpClient = OkHttpClient()) {
  private val base = url.trimEnd('/').toHttpUrl()

  fun signIn(email: String, password: String): SupabaseSession =
    session(auth("password", buildJsonObject { put("email", email); put("password", password) }))

  fun refresh(refreshToken: String): SupabaseSession =
    session(auth("refresh_token", buildJsonObject { put("refresh_token", refreshToken) }))

  private fun auth(grant: String, body: JsonObject): JsonObject {
    val target = base.newBuilder().addPathSegments("auth/v1/token").addQueryParameter("grant_type", grant).build()
    return call(Request.Builder().url(target).header("apikey", key).post(body.toString().toRequestBody(JSON)).build()).jsonObject
  }

  private fun session(o: JsonObject): SupabaseSession {
    val user = o["user"]!!.jsonObject
    return SupabaseSession(
      userId = user["id"]!!.jsonPrimitive.content, email = user["email"]?.jsonPrimitive?.content ?: "",
      accessToken = o["access_token"]!!.jsonPrimitive.content, refreshToken = o["refresh_token"]!!.jsonPrimitive.content,
    )
  }

  /** Inserts or updates [rows] by the [onConflict] key; columns left out of a row keep their value. */
  fun upsert(session: SupabaseSession, table: String, rows: List<JsonObject>, onConflict: String) {
    if (rows.isEmpty()) return
    for (chunk in rows.chunked(500)) {
      val target = rest(table).addQueryParameter("on_conflict", onConflict).build()
      call(authorized(session, target).header("Prefer", "resolution=merge-duplicates,return=minimal")
        .post(JsonArray(chunk).toString().toRequestBody(JSON)).build())
    }
  }

  /** Rows matching PostgREST [filters] (`column` to `op.value`), ordered and paged by the caller's filters. */
  fun select(session: SupabaseSession, table: String, filters: List<Pair<String, String>>): List<JsonObject> {
    val target = rest(table).addQueryParameter("select", "*").apply { filters.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
    return call(authorized(session, target).get().build()).jsonArray.map { it.jsonObject }
  }

  fun delete(session: SupabaseSession, table: String, filters: List<Pair<String, String>>) {
    val target = rest(table).apply { filters.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
    call(authorized(session, target).header("Prefer", "return=minimal").delete().build())
  }

  fun rpc(session: SupabaseSession, function: String) {
    val target = base.newBuilder().addPathSegments("rest/v1/rpc").addPathSegment(function).build()
    call(authorized(session, target).post("{}".toRequestBody(JSON)).build())
  }

  /** Writes (or replaces) an object in the private `receipts` bucket. [path] starts with the user's id. */
  fun upload(session: SupabaseSession, path: String, body: RequestBody) {
    val target = base.newBuilder().addPathSegments("storage/v1/object/$BUCKET").addPathSegments(path).build()
    call(authorized(session, target).header("x-upsert", "true").post(body).build())
  }

  fun upload(session: SupabaseSession, path: String, file: File, type: String) = upload(session, path, file.asRequestBody(type.toMediaType()))

  fun download(session: SupabaseSession, path: String, target: File) {
    val url = base.newBuilder().addPathSegments("storage/v1/object/authenticated/$BUCKET").addPathSegments(path).build()
    http.newCall(authorized(session, url).get().build()).execute().use { r ->
      if (!r.isSuccessful) throw SupabaseException(r.code, "download $path: ${r.code}")
      val temp = File(target.parentFile, target.name + ".part")
      temp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
      check(temp.renameTo(target)) { "could not save $target" }
    }
  }

  private fun rest(table: String): HttpUrl.Builder = base.newBuilder().addPathSegments("rest/v1").addPathSegment(table)

  private fun authorized(session: SupabaseSession, target: HttpUrl) =
    Request.Builder().url(target).header("apikey", key).header("Authorization", "Bearer ${session.accessToken}")

  private fun call(request: Request): JsonElement = http.newCall(request).execute().use { r ->
    val text = r.body.string()
    if (!r.isSuccessful) {
      val message = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        listOf("msg", "message", "error_description", "error").firstNotNullOfOrNull { o[it]?.jsonPrimitive?.content }
      }.getOrNull() ?: text.take(200)
      // 5xx and 429 may pass on a retry: report them as network trouble, so the worker backs off and tries again.
      if (r.code >= 500 || r.code == 429) throw IOException("${request.method} ${request.url.encodedPath}: ${r.code} $message")
      throw SupabaseException(r.code, "${request.method} ${request.url.encodedPath}: ${r.code} $message")
    }
    if (text.isBlank()) JsonArray(emptyList()) else Json.parseToJsonElement(text)
  }

  companion object {
    const val BUCKET = "receipts"
    val JSON = "application/json".toMediaType()
  }
}
