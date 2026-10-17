package io.github.sandroroque.getnf.data

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * An in-memory [ReceiptDatabase] for local JVM tests, on a bundled SQLite (there's no device framework here). The
 * explicit journal mode keeps Room from asking the (stub) Context for ActivityManager.
 */
fun testDatabase(): ReceiptDatabase =
  Room.inMemoryDatabaseBuilder(ContextWrapper(null), ReceiptDatabase::class.java).setDriver(BundledSQLiteDriver())
    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
