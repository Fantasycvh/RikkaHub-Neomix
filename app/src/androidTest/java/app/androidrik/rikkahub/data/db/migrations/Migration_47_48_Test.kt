package app.androidrik.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_47_48_Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "ssh-workspace-migration-test-${UUID.randomUUID()}.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() {
        check(name.startsWith("ssh-workspace-migration-test-"))
        context.deleteDatabase(name)
    }

    @Test
    fun migrate47To48_backfillsLocalAndAcceptsSshConfig() {
        helper.createDatabase(name, 47).use { db ->
            db.execSQL(
                "INSERT INTO `workspaces` " +
                    "(id,name,root,shell_status,created_at,updated_at,tool_approvals," +
                    "shell_compatibility_mode,max_read_file_bytes) " +
                    "VALUES ('local-1','Local','local-1','READY',1,2,'{}',0,8388608)"
            )
        }

        helper.runMigrationsAndValidate(name, 48, true, Migration_47_48).use { db ->
            db.query("SELECT type, ssh_config FROM `workspaces` WHERE id = 'local-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("LOCAL", cursor.getString(0))
                assertNull(cursor.getString(1))
            }

            db.execSQL(
                "INSERT INTO `workspaces` " +
                    "(id,name,root,shell_status,created_at,updated_at,type,ssh_config) " +
                    "VALUES ('ssh-1','Remote','ssh-1','READY',3,4,'SSH','{\"host\":\"example.com\"}')"
            )
            db.query("SELECT type, ssh_config FROM `workspaces` WHERE id = 'ssh-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("SSH", cursor.getString(0))
                assertEquals("{\"host\":\"example.com\"}", cursor.getString(1))
            }
        }
    }
}
