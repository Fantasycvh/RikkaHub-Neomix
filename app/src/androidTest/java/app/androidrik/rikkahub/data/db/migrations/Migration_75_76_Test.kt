package app.androidrik.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Migration_75_76_Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test
    fun retainedMediaHistoryAndScheduledRunHaveNoImplicitGrant() {
        val name = "media-migration-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 75).use { db ->
                db.execSQL("INSERT INTO media_creation_session VALUES ('session','Old album','{}',1,2)")
                db.execSQL("INSERT INTO media_creation_node VALUES ('node','session','record',1)")
                db.execSQL("""
                    INSERT INTO media_creation_record
                    (id,session_id,node_id,provider_id,provider_name,model_id,kind,prompt,params,inputs,status,outputs,create_at,update_at)
                    VALUES ('record','session','node','provider','Legacy','image-fixture','IMAGE','old prompt','{}','[]',
                        'SUCCEEDED','[{"path":"media_creation/session/record/out_0.png","mimeType":"image/png"}]',1,2)
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO scheduled_tasks
                    (id,conversation_id,title,prompt,schedule_type,schedule_payload,next_run_at,miss_policy,idle_only,enabled,created_at)
                    VALUES ('task','chat','Old task','old task prompt','DAILY','{}',1234,'RUN_LATE',0,1,1)
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO scheduled_task_runs (id,task_id,source,conversation_id,started_at,finished_at,status)
                    VALUES ('run','task','alarm','chat',1,2,'OK')
                """.trimIndent())
            }
            helper.runMigrationsAndValidate(name, 76, true).use { db ->
                db.query("""
                    SELECT r.model_id,r.outputs,r.catalog_model_id,n.selected_record_id,s.title
                    FROM media_creation_record r JOIN media_creation_node n ON n.id=r.node_id
                    JOIN media_creation_session s ON s.id=r.session_id
                """.trimIndent()).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("image-fixture", cursor.getString(0))
                    assertTrue(cursor.getString(1).contains("media_creation/session/record/out_0.png"))
                    assertTrue(cursor.isNull(2))
                    assertEquals("record", cursor.getString(3))
                    assertEquals("Old album", cursor.getString(4))
                }
                db.query("""
                    SELECT t.conversation_id,t.enabled,t.next_run_at,t.media_action,r.status,r.media_session_id
                    FROM scheduled_tasks t JOIN scheduled_task_runs r ON r.task_id=t.id
                """.trimIndent()).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("chat", cursor.getString(0))
                    assertEquals(1, cursor.getInt(1))
                    assertEquals(1234L, cursor.getLong(2))
                    assertTrue(cursor.isNull(3))
                    assertEquals("OK", cursor.getString(4))
                    assertTrue(cursor.isNull(5))
                }
            }
        } finally {
            instrumentation.targetContext.deleteDatabase(name)
        }
    }
}
