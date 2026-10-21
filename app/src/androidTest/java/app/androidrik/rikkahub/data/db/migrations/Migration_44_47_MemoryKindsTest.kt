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

/** Exercises generated 44→45→46 plus manual 46→47 in one database open. */
@RunWith(AndroidJUnit4::class)
class Migration_44_47_MemoryKindsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "memory-kinds-chain-test-${UUID.randomUUID()}.db"
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After fun tearDown() {
        check(name.startsWith("memory-kinds-chain-test-"))
        context.deleteDatabase(name)
    }

    @Test fun migration44Through47RetainsMemoryAndConversationWhileAddingAllDefaults() {
        helper.createDatabase(name, 44).use { db ->
            db.execSQL("INSERT INTO memoryentity (assistant_id,content,is_anchor) VALUES ('a','topic',0),('a','anchor',1)")
            db.execSQL("INSERT INTO ConversationEntity (id,title,nodes,create_at,update_at,memory_disabled) VALUES ('conversation-44','unchanged','[]',100,200,1)")
        }
        helper.runMigrationsAndValidate(name, 47, true, Migration_46_47).use { db ->
            assertEquals(47, db.version)
            db.query("SELECT content,kind,task_status,due_at,is_anchor FROM memoryentity ORDER BY id").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("topic", cursor.getString(0))
                assertEquals("topic", cursor.getString(1))
                assertEquals("open", cursor.getString(2))
                assertEquals(0L, cursor.getLong(3))
                assertTrue(cursor.moveToNext())
                assertEquals("anchor", cursor.getString(0))
                assertEquals("profile", cursor.getString(1))
                assertEquals(1, cursor.getInt(4))
            }
            db.query("SELECT title,create_at,update_at,memory_disabled,upstream_config FROM ConversationEntity WHERE id='conversation-44'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("unchanged", cursor.getString(0))
                assertEquals(100L, cursor.getLong(1))
                assertEquals(200L, cursor.getLong(2))
                assertEquals(1, cursor.getInt(3))
                assertTrue(cursor.isNull(4))
            }
            db.execSQL("INSERT INTO translation_history (id,source_text,translated_text,target_language,model_id,create_at) VALUES ('translation-chain','hello','bonjour','fr','model',300)")
            db.query("SELECT translated_text FROM translation_history WHERE id='translation-chain'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("bonjour", cursor.getString(0))
            }
        }
    }
}
