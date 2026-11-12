package io.github.sandroroque.getnf.sync

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.IOException
import java.nio.file.Files
import org.junit.Test
import org.junit.Assert.*

/** Real HTTP responses from a loopback server; synthetic session, no external service. */
class Round2HttpTest {
  private val session = SupabaseSession("synthetic", "synthetic@example.invalid", "synthetic", "synthetic")
  private fun withStatus(status: Int, block: (SupabaseClient) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      val bytes = "{\"message\":\"synthetic failure\"}".toByteArray()
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try { block(SupabaseClient("http://127.0.0.1:${server.address.port}", "synthetic")) }
    finally { server.stop(0) }
  }
  @Test fun restClassifiesTransientAndAuthorizationErrors() {
    for (status in listOf(401, 403, 429, 500, 503)) withStatus(status) { client ->
      val error = runCatching { client.select(session, "receipt", emptyList()) }.exceptionOrNull()
      if (status >= 500 || status == 429) assertTrue("$status must retry", error is IOException)
      else assertEquals(status, (error as SupabaseException).status)
    }
  }
  @Test fun downloadTransientFailureMustRetryAndKeepExistingFile() {
    val dir = Files.createTempDirectory("review-http").toFile()
    try {
      for (status in listOf(503, 429)) withStatus(status) { client ->
        val file = java.io.File(dir, "page.html").apply { writeText("synthetic original") }
        val error = runCatching { client.download(session, "synthetic/page.html", file) }.exceptionOrNull()
        assertEquals("synthetic original", file.readText())
        assertTrue("download $status must reach SyncWorker as IOException for retry; got ${error?.javaClass?.simpleName}", error is IOException)
      }
    } finally { dir.deleteRecursively() }
  }
}
