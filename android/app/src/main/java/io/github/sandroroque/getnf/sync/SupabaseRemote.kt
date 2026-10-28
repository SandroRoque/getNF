package io.github.sandroroque.getnf.sync

import java.io.File
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** [SyncRemote] over a [SupabaseClient], as the user of [session], for the caderneta [ledgerId] (D104). */
class SupabaseRemote(
  private val client: SupabaseClient, private val session: SupabaseSession, override val ledgerId: String,
) : SyncRemote {
  override val userId: String get() = session.userId
  override fun rpc(function: String, args: JsonObject) = client.rpc(session, function, args)
  override fun upsert(table: String, rows: List<JsonObject>, onConflict: String, ignoreDuplicates: Boolean) =
    client.upsert(session, table, rows, onConflict, ignoreDuplicates)
  override fun delete(table: String, filters: List<Pair<String, String>>) = client.delete(session, table, filters)
  override fun select(table: String, filters: List<Pair<String, String>>) = client.select(session, table, filters)
  override fun upload(path: String, bytes: ByteArray, type: String) = client.upload(session, path, bytes.toRequestBody(type.toMediaType()))
  override fun download(path: String, target: File) = client.download(session, path, target)
}
