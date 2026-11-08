package app.androidrik.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class Migration_63_64_MemoryOrderTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun displayOrderPersistsWithoutChangingBusinessFields() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "memory-order-persistence-${UUID.randomUUID()}.db"
        fun open() = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            var db = open()
            try {
                val repository = app.androidrik.rikkahub.data.repository.MemoryRepository(
                    db.memoryDao(), app.androidrik.rikkahub.data.repository.RoomMemoryTransactionRunner(db), db.memoryHistoryDao(), db.memoryTrashDao(),
                )
                val a = repository.addMemory("a", "first", now = 1)
                val b = repository.addMemory("a", "second", now = 2)
                val before = db.memoryDao().getMemoriesOfAssistant("a")
                repository.moveMemory("a", b.id, a.id)
                val after = db.memoryDao().getMemoriesOfAssistant("a")
                assertEquals(before, after.map { row -> row.copy(displayOrder = before.first { it.id == row.id }.displayOrder) })
                val ids = repository.getMemoriesOfAssistant("a").sortedBy { it.displayOrder }.map { it.id }
                assertEquals(listOf(b.id, a.id), ids)
                db.close()
                db = open()
                val persisted = db.memoryDao().getMemoriesOfAssistant("a").sortedBy { it.displayOrder ?: it.id.toLong() * 1_000_000 }
                assertEquals(listOf("second", "first"), persisted.map { it.content })
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun oldRowsRetainStableIdsAndBusinessFields() {
        val name = "memory-order-test-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 63).use { db ->
                db.execSQL("INSERT INTO memoryentity(id,assistant_id,content,public_id) VALUES(3,'store','old','00000003'),(9,'store','new','00000009')")
            }
            helper.runMigrationsAndValidate(name, 64, true).use { db ->
                db.query("SELECT id,content,revision,display_order FROM memoryentity ORDER BY id").use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(3, c.getInt(0)); assertEquals("old", c.getString(1)); assertEquals(1L, c.getLong(2)); assertTrue(c.isNull(3))
                    assertTrue(c.moveToNext()); assertEquals(9, c.getInt(0)); assertTrue(c.isNull(3)); assertFalse(c.moveToNext())
                }
            }
        } finally { InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name) }
    }
}
