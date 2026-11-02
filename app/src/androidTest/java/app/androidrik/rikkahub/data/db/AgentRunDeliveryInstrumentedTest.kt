package app.androidrik.rikkahub.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.DELIVERY_MODE_AWAIT
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.DELIVERY_MODE_NOTICE
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.DELIVERY_MODE_NOTIFY
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.DELIVERY_STATE_NONE
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.DELIVERY_STATE_ORPHANED
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.ENTRY_POINT_CHAT
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.ERROR_TIMEOUT
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.STATUS_CANCELLED
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.STATUS_COMPLETED
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.STATUS_FAILED
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.STATUS_INTERRUPTED
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.STATUS_RUNNING
import app.androidrik.rikkahub.data.task.AgentRunReconciler
import app.androidrik.rikkahub.data.sync.DowngradeQuarantineStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 独立测试库；验证真实 Room SQL、自动迁移以及持久化投递，不访问用户数据库。 */
@RunWith(AndroidJUnit4::class)
class AgentRunDeliveryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var database: AppDatabase? = null

    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
    )

    private fun openMemory(): AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .build().also { database = it }

    @After
    fun close() {
        database?.close()
        context.deleteDatabase("agent-delivery-migration-test")
    }

    private fun run(id: String, mode: String = DELIVERY_MODE_NOTICE, created: Long = 100L) = AgentRunEntity(
        id = id, entryPoint = ENTRY_POINT_CHAT, status = STATUS_RUNNING,
        profileAssistantId = "profile", profileName = "research", modelId = null, modelName = null,
        goal = "find evidence", toolEnvelope = "[]", transcript = "[]", resultSummary = null,
        error = null, parentConversationId = "parent", createdAt = created, startedAt = created,
        finishedAt = null, scopeToken = "turn:one", deliveryMode = mode,
    )

    @Test
    fun v57BackupMigratesWithSafeDefaultsAndPreservesTranscript() = runBlocking {
        migrations.createDatabase("agent-delivery-migration-test", 57).use { old ->
            old.execSQL("""
                INSERT INTO agent_run (id, entry_point, status, profile_assistant_id, profile_name,
                    model_id, model_name, goal, tool_envelope, transcript, result_summary, error,
                    parent_conversation_id, created_at, started_at, finished_at)
                VALUES ('legacy', 'chat', 'completed', 'p', 'research', NULL, NULL, 'goal', '[]',
                    '[{"evidence":"retained"}]', 'answer', NULL, 'parent', 100, 101, 102)
            """.trimIndent())
        }
        // Room opens and validates the migrated schema, including defaultValue metadata.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "agent-delivery-migration-test")
            .build().also { database = it }
        val restored = db.agentRunDao().getRun("legacy")!!
        assertEquals(58, db.openHelper.readableDatabase.version)
        assertEquals("[{\"evidence\":\"retained\"}]", restored.transcript)
        assertEquals("answer", restored.resultSummary)
        assertEquals(DELIVERY_MODE_AWAIT, restored.deliveryMode)
        assertEquals(DELIVERY_STATE_NONE, restored.deliveryState)
        assertEquals(0, restored.depth)
        assertNull(restored.parentRunId)
        assertNull(restored.scopeToken)
        assertNull(restored.deliveredAt)
        assertNull(restored.cancelReason)
        assertTrue(db.agentRunDao().observePendingDeliveries().first().isEmpty())
    }

    @Test
    fun everyTerminalOutcomeIsPendingAtomicallyAndAcknowledgementIsIdempotent() = runBlocking {
        val dao = openMemory().agentRunDao()
        listOf(STATUS_COMPLETED, STATUS_FAILED, STATUS_CANCELLED, STATUS_INTERRUPTED).forEachIndexed { i, status ->
            dao.insertRun(run(status))
            assertTrue(dao.observePendingDeliveries().first().none { it.id == status })
            val error = if (status == STATUS_FAILED) ERROR_TIMEOUT else null
            assertEquals(1, dao.finishRun(status, status, "result", error, 1000L + i, "reason"))
            assertEquals(0, dao.finishRun(status, STATUS_FAILED, null, "late write", 9999, null))
            val stored = dao.observePendingDeliveries().first().first { it.id == status }
            assertEquals(status, stored.status)
            assertEquals(error, stored.error)
            assertEquals("reason", stored.cancelReason)
        }
        assertEquals(4, dao.observePendingDeliveries().first().size)
        assertEquals(1, dao.markDelivered(listOf(STATUS_COMPLETED), 2000))
        assertEquals(0, dao.markDelivered(listOf(STATUS_COMPLETED), 3000))
        assertEquals(0, dao.markOrphaned(listOf(STATUS_COMPLETED)))
        assertEquals(2000L, dao.getRun(STATUS_COMPLETED)!!.deliveredAt)
        assertEquals(1, dao.markOrphaned(listOf(STATUS_FAILED)))
        assertEquals(0, dao.markDelivered(listOf(STATUS_FAILED), 3000))
        assertEquals(DELIVERY_STATE_ORPHANED, dao.getRun(STATUS_FAILED)!!.deliveryState)
        assertEquals(2, dao.observePendingDeliveries().first().size)
    }

    @Test
    fun blockingAndNotifyRunsDoNotEnterNoticeQueueAndScopesStayIsolated() = runBlocking {
        val dao = openMemory().agentRunDao()
        listOf(DELIVERY_MODE_AWAIT, DELIVERY_MODE_NOTIFY).forEach { mode ->
            dao.insertRun(run(mode, mode))
            dao.finishRun(mode, STATUS_COMPLETED, "answer", null, 1000, null)
        }
        dao.insertRun(run("other").copy(scopeToken = "turn:two"))
        assertTrue(dao.observePendingDeliveries().first().isEmpty())
        assertEquals(setOf(DELIVERY_MODE_AWAIT, DELIVERY_MODE_NOTIFY),
            dao.observeRunsByScope("turn:one").first().map { it.id }.toSet())
        assertEquals(listOf("other"), dao.observeRunsByScope("turn:two").first().map { it.id })
        assertEquals(0, dao.markDelivered(listOf("other"), 2000))
    }

    @Test
    fun restartMakesOldBackgroundRunsDeliverableWithoutTouchingCurrentRuns() = runBlocking {
        val dao = openMemory().agentRunDao()
        dao.insertRun(run("old"))
        dao.insertRun(run("blocking", DELIVERY_MODE_AWAIT))
        dao.insertRun(run("current", created = 1000))
        val reconciler = AgentRunReconciler(dao, now = { 2000 }, processStartedAt = { 1000 })
        assertEquals(2, reconciler.reconcileProcessRestart())
        assertEquals(0, reconciler.reconcileProcessRestart())
        assertEquals(listOf("old"), dao.observePendingDeliveries().first().map { it.id })
        assertEquals(STATUS_INTERRUPTED, dao.getRun("old")!!.status)
        assertNotNull(dao.getRun("old")!!.cancelReason)
        assertEquals(DELIVERY_STATE_NONE, dao.getRun("blocking")!!.deliveryState)
        assertEquals(STATUS_RUNNING, dao.getRun("current")!!.status)
    }

    @Test
    fun deliveryQueueAndAcknowledgementsSurviveDatabaseReopen() = runBlocking {
        fun openDisk() = Room.databaseBuilder(context, AppDatabase::class.java, "agent-delivery-migration-test")
            .build().also { database = it }
        var db = openDisk()
        db.agentRunDao().insertRun(run("durable"))
        db.agentRunDao().finishRun("durable", STATUS_COMPLETED, "answer", null, 1000, null)
        db.close()
        db = openDisk()
        assertEquals(listOf("durable"), db.agentRunDao().observePendingDeliveries().first().map { it.id })
        db.agentRunDao().markDelivered(listOf("durable"), 2000)
        db.close()
        db = openDisk()
        assertTrue(db.agentRunDao().observePendingDeliveries().first().isEmpty())
        assertEquals(2000L, db.agentRunDao().getRun("durable")!!.deliveredAt)
    }

    @Test
    fun v58BackupIsQuarantinedByV57InsteadOfOpenedOrDiscarded() {
        val root = File(context.cacheDir, "agent-delivery-downgrade-test").apply { mkdirs() }
        try {
            val backup = File(root, "databases/rikka_hub").apply { parentFile!!.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(backup, null).use {
                it.version = AppDatabase.SCHEMA_VERSION
                it.execSQL("CREATE TABLE evidence (value TEXT)")
                it.execSQL("INSERT INTO evidence VALUES ('preserved')")
            }
            val marker = DowngradeQuarantineStore.quarantineIfDowngraded(
                root, backup, 57,
                readVersion = { file ->
                    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
                },
            )
            assertEquals(58, marker!!.schemaVersion)
            assertFalse(backup.exists())
            val entries = DowngradeQuarantineStore.listEntries(root)
            assertEquals(1, entries.size)
            assertTrue(DowngradeQuarantineStore.recoverableEntries(root, 57).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
