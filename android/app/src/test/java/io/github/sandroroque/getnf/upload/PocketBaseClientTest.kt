package io.github.sandroroque.getnf.upload

import io.github.sandroroque.getnf.data.CaptureSegment
import io.github.sandroroque.getnf.data.Receipt
import io.github.sandroroque.getnf.data.ServerConfig
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Request shapes against a fake server; no real PocketBase or receipt data (D42). */
class PocketBaseClientTest {
  @get:Rule val tmp = TemporaryFolder()
  private val server = MockWebServer()
  private val chave = "42260611222333000181651090000012341123456789"
  private lateinit var config: ServerConfig

  @Before fun start() { server.start(); config = ServerConfig(server.url("/").toString(), "a@example.invalid", "x") }
  @After fun stop() = server.close()

  private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
  private fun bodyOf(request: mockwebserver3.RecordedRequest) = request.body?.utf8().orEmpty()

  @Test fun receiptUpdateNeverSendsPhotos() {
    val dir = tmp.newFolder()
    java.io.File(dir, "capture-a-0.jpg").writeBytes(byteArrayOf(1, 2, 3))
    server.enqueue(json("""{"id":"rec1"}"""))
    val receipt = Receipt(chave, "https://example.invalid/qr", 1000, photos = listOf("capture-a-0.jpg"), remoteId = "rec1")
    assertEquals("rec1", PocketBaseClient().upsert(config, "t", receipt, dir))
    val request = server.takeRequest()
    assertEquals("PATCH", request.method)
    // A multipart `photos` field would replace the server's list and delete the rest (D36).
    assertFalse(bodyOf(request).contains("name=\"photos\""))
    assertTrue(bodyOf(request).contains("name=\"chave\""))
  }

  @Test fun photoGoesToReceiptPhotosWithMaskAndSegment() {
    val dir = tmp.newFolder()
    java.io.File(dir, "capture-s-0.jpg").writeBytes(byteArrayOf(1, 2, 3))
    java.io.File(dir, "capture-s-0.mask.png").writeBytes(byteArrayOf(4, 5))
    val segment = CaptureSegment("capture-s-0.jpg", "capture-s-0.mask.png", listOf(listOf(.1, .1)), listOf(listOf(0.0, 100.0)),
      0.0, 100.0, 1.4, 2000, "s")
    server.enqueue(json("""{"items":[]}"""))
    server.enqueue(json("""{"id":"ph1"}"""))
    assertEquals("ph1", PocketBaseClient().uploadPhoto(config, "t", "rec1", dir, "capture-s-0.jpg", segment))
    server.takeRequest() // existence check
    val post = server.takeRequest()
    assertEquals("POST", post.method)
    assertEquals("/api/collections/receipt_photos/records", post.url.encodedPath)
    val body = bodyOf(post)
    for (field in listOf("receipt", "local_name", "image", "mask", "segment", "session_id", "captured_at"))
      assertTrue("missing $field", body.contains("name=\"$field\""))
  }

  @Test fun photoAlreadyOnTheServerIsNotSentAgain() {
    val dir = tmp.newFolder()
    java.io.File(dir, "photo-1.jpg").writeBytes(byteArrayOf(1))
    server.enqueue(json("""{"items":[{"id":"ph9"}]}"""))
    assertEquals("ph9", PocketBaseClient().uploadPhoto(config, "t", "rec1", dir, "photo-1.jpg", null))
    assertEquals(1, server.requestCount)
  }

  @Test fun lostResponseRetryResolvesToTheExistingRecord() {
    val dir = tmp.newFolder()
    java.io.File(dir, "photo-2.jpg").writeBytes(byteArrayOf(1))
    server.enqueue(json("""{"items":[]}"""))
    server.enqueue(MockResponse.Builder().code(400).body("""{"message":"Failed to create record."}""").build())
    server.enqueue(json("""{"items":[{"id":"ph7"}]}"""))
    assertEquals("ph7", PocketBaseClient().uploadPhoto(config, "t", "rec1", dir, "photo-2.jpg", null))
  }
}
