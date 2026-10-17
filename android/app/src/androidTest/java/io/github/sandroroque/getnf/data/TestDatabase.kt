package io.github.sandroroque.getnf.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry

/** An in-memory [ReceiptDatabase] on the device's SQLite. */
fun testDatabase(): ReceiptDatabase =
  Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ReceiptDatabase::class.java).build()
