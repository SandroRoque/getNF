package io.github.sandroroque.getnf.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Each schema version must open the previous one's data intact (D45, D46, D49). */
@RunWith(AndroidJUnit4::class)
class ReceiptDatabaseMigrationTest {
  @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ReceiptDatabase::class.java)

  @Test fun version1ReceiptsAndPhotosSurviveVersion2() {
    val name = "migration-test.db"
    helper.createDatabase(name, 1).use { db ->
      db.execSQL("INSERT INTO receipt (chave, qrUrl, capturedAt, pageUrl, pageFile, pageFetchedAt, version, uploadedVersion, remoteId, uploadError) VALUES ('k', 'qr', 1, NULL, 'page.html', 2, 3, 3, 'r1', NULL)")
      db.execSQL("INSERT INTO receipt_photo (receipt, localName, position, uploaded, segment) VALUES ('k', 'a.jpg', 0, 1, '{}')")
      db.execSQL("INSERT INTO capture_session (receipt, sessionId, position) VALUES ('k', 's1', 0)")
    }
    helper.runMigrationsAndValidate(name, 2, true).use { db ->
      db.query("SELECT version, uploadedVersion, remoteId FROM receipt WHERE chave = 'k'").use { c ->
        c.moveToFirst(); assertEquals(Triple(3L, 3L, "r1"), Triple(c.getLong(0), c.getLong(1), c.getString(2)))
      }
      db.query("SELECT COUNT(*) FROM receipt_photo WHERE uploaded = 1").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
      db.query("SELECT COUNT(*) FROM capture_session").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
      db.query("SELECT COUNT(*) FROM receipt_line").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }
  }

  @Test fun version2ParsedRowsSurviveVersion3AsServerParses() {
    val name = "migration-test-3.db"
    helper.createDatabase(name, 2).use { db ->
      db.execSQL("INSERT INTO receipt (chave, qrUrl, capturedAt, pageUrl, pageFile, pageFetchedAt, version, uploadedVersion, remoteId, uploadError) VALUES ('k', 'qr', 1, NULL, 'page.html', 2, 3, 3, 'r1', NULL)")
      db.execSQL("INSERT INTO receipt_parsed (chave, issuerCnpj, issuedAt, grossCents, discountCents, paidCents, parserVersion, parseError) VALUES ('k', '1', 5, 100, 10, 90, 1, NULL)")
    }
    helper.runMigrationsAndValidate(name, 3, true).use { db ->
      db.query("SELECT paidCents, source, pageFetchedAt FROM receipt_parsed WHERE chave = 'k'").use { c ->
        c.moveToFirst()
        assertEquals(90L, c.getLong(0))
        // Rows from before D49 came from the server, so the phone parses those receipts again.
        assertEquals("server", c.getString(1))
        assertEquals(true, c.isNull(2))
      }
    }
  }
}
