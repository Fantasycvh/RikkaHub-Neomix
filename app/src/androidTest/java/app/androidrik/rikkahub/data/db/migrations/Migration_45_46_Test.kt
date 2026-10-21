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

/** Synthetic test-owned databases only. Existing conversations must not acquire a frozen config. */
@RunWith(AndroidJUnit4::class)
class Migration_45_46_Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "upstream-config-migration-test-${UUID.randomUUID()}.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() {
        check(name.startsWith("upstream-config-migration-test-"))
        context.deleteDatabase(name)
    }

    @Test
    fun migrate45To46_preservesRowsAndAddsNullableSnapshot() {
        helper.createDatabase(name, 45).use { db ->
            db.execSQL(
                "INSERT INTO ConversationEntity (id,title,nodes,create_at,update_at,memory_disabled) " +
                    "VALUES ('conversation-45','unchanged','[]',100,200,1)",
            )
            db.execSQL(
                "INSERT INTO translation_history " +
                    "(id,source_text,translated_text,target_language,model_id,create_at) " +
                    "VALUES ('translation-45','hello','bonjour','fr','model-45',300)",
            )
        }
        helper.runMigrationsAndValidate(name, 46, true).use { db ->
            assertEquals(46, db.version)
            db.query("SELECT title,create_at,update_at,memory_disabled,upstream_config FROM ConversationEntity")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("unchanged", cursor.getString(0))
                    assertEquals(100L, cursor.getLong(1))
                    assertEquals(200L, cursor.getLong(2))
                    assertEquals(1, cursor.getInt(3))
                    assertTrue(cursor.isNull(4))
                }
            db.query("SELECT translated_text FROM translation_history WHERE id='translation-45'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("bonjour", cursor.getString(0))
            }
            db.execSQL("UPDATE ConversationEntity SET upstream_config=?", arrayOf("{}"))
            db.query("SELECT upstream_config FROM ConversationEntity").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{}", cursor.getString(0))
            }
        }
    }
}
