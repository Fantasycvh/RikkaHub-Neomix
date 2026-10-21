package app.androidrik.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Migration_46_47_Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "memory-kinds-migration-test-${UUID.randomUUID()}.db"
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )
    @After fun tearDown() {
        check(name.startsWith("memory-kinds-migration-test-"))
        context.deleteDatabase(name)
    }

    @Test fun migrate46To47_defaultsAndAnchorBackfillMatchSchema() {
        helper.createDatabase(name, 46).use { db ->
            db.execSQL("INSERT INTO memoryentity (assistant_id,content,is_anchor) VALUES ('a','topic',0),('a','anchor',1)")
        }
        helper.runMigrationsAndValidate(name, 47, true, Migration_46_47).use { db ->
            db.query("SELECT content,kind,task_status,due_at,is_anchor FROM memoryentity ORDER BY id").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("topic", cursor.getString(1))
                assertEquals("open", cursor.getString(2))
                assertEquals(0L, cursor.getLong(3))
                assertTrue(cursor.moveToNext())
                assertEquals("profile", cursor.getString(1))
                assertEquals("open", cursor.getString(2))
                assertEquals(0L, cursor.getLong(3))
                assertEquals(1, cursor.getInt(4))
            }
            db.query("PRAGMA table_info(memory_proposal)").use { cursor ->
                val defaults = mutableMapOf<String, String>()
                while (cursor.moveToNext()) defaults[cursor.getString(1)] = cursor.getString(4) ?: ""
                assertEquals("''", defaults["kind"])
                assertEquals("0", defaults["due_at"])
            }
        }
    }
}
