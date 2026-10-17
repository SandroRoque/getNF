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

  @Test fun snapshotReadsEveryPageAndConvertsFields() {
    val client = PocketBaseClient(pageSize = 1)
    server.enqueue(json("""{"items":[{"receipt":"r1"}]}"""))
    server.enqueue(json("""{"items":[{"receipt":"r1"}]}"""))
    server.enqueue(json("""{"items":[]}"""))
    // Each collection: a full page, then a short one.
    server.enqueue(json("""{"items":[{"id":"c1","path":"food","label":"Comida","order":1,"parent":""}]}"""))
    server.enqueue(json("""{"items":[]}"""))
    server.enqueue(json("""{"items":[]}"""))
    server.enqueue(json("""{"items":[{"id":"p1","key":"gtin:7890000000017","description":"CAFE","category":"c1","tags":[],"size_qty":0,"size_unit":"","size_source":""}]}"""))
    server.enqueue(json("""{"items":[]}"""))
    server.enqueue(json("""{"items":[{"id":"r1","chave":"$chave","qr_url":"","captured_at":"2026-01-15 13:00:00.000Z","page_url":"","page_fetched_at":"","page":"page_x.html","photos":["a.jpg"],"store_name":"M","store_cnpj":"1","issued_at":"2026-01-15 13:00:00.000Z","total_gross":1.5,"discount":0,"total_paid":1.5,"parser_version":1,"parse_error":""}]}"""))
    server.enqueue(json("""{"items":[]}"""))
    server.enqueue(json("""{"items":[{"receipt":"r1","line":1,"product":"p1","description":"CAFE","code":"7890000000017","qty":1,"unit":"UN","unit_price":1.5,"gross":1.5,"discount":0,"discount_source":"prorated"}]}"""))
    server.enqueue(json("""{"items":[]}"""))
    val s = client.fetchSnapshot(config, "t")
    assertEquals(null, s.categories.single().parentId)
    assertEquals(null, s.products.single().sizeQty)
    val r = s.receipts.single()
    assertEquals(java.time.Instant.parse("2026-01-15T13:00:00Z").toEpochMilli(), r.issuedAt)
    assertEquals(null, r.qrUrl)
    assertEquals(null, r.pageFetchedAt)
    assertEquals(listOf("a.jpg"), r.photos)
    assertEquals(2, r.evidencePhotos)
    assertEquals(1, s.items.single().line)
    repeat(3) { server.takeRequest() } // receipt_photos
    val first = server.takeRequest()
    assertEquals("/api/collections/categories/records", first.url.encodedPath)
    assertEquals("1", first.url.queryParameter("page"))
    assertEquals("2", server.takeRequest().url.queryParameter("page"))
    assertEquals(12, server.requestCount)
  }

  @Test fun downloadUsesAFileTokenAndLeavesNothingOnFailure() {
    val dir = tmp.newFolder()
    server.enqueue(json("""{"token":"ft"}"""))
    server.enqueue(MockResponse.Builder().code(200).body("jpeg").build())
    PocketBaseClient().download(config, "t", "receipts", "r1", "a.jpg", java.io.File(dir, "a.jpg"))
    assertEquals("jpeg", java.io.File(dir, "a.jpg").readText())
    server.takeRequest()
    val get = server.takeRequest()
    assertEquals("/api/files/receipts/r1/a.jpg", get.url.encodedPath)
    assertEquals("ft", get.url.queryParameter("token"))

    server.enqueue(json("""{"token":"ft"}"""))
    server.enqueue(MockResponse.Builder().code(404).body("{}").build())
    try { PocketBaseClient().download(config, "t", "receipts", "r1", "b.jpg", java.io.File(dir, "b.jpg")) } catch (_: PocketBaseException) {}
    assertEquals(listOf("a.jpg"), dir.list()!!.toList())
  }
}
