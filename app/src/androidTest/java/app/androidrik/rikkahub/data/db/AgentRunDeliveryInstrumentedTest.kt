package app.androidrik.rikkahub.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteDatabase
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.DELIVERY_STATE_PENDING
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity.Companion.DELIVERY_STATE_DELIVERED
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
import android.content.ContextWrapper
import androidx.room.withTransaction
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.fts.MessageFtsManager
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.repository.FilesRepository
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.toMessageNode
import app.androidrik.rikkahub.data.task.appendNoticeToSession
import app.androidrik.rikkahub.data.task.AppendNoticeResult
import app.androidrik.rikkahub.service.ConversationSession
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.isSubagentNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlin.uuid.Uuid
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

    @Test fun migration62To63PreservesExistingNoticesAndLeavesLegacyAgentsWithoutWakeAuthority() {
        val name = "agent-delivery-migration-test"
        migrations.createDatabase(name, 62).use { old ->
            old.execSQL("""INSERT INTO external_notice (id,conversationId,assistantId,workspaceId,anchorMessageId,rootScope,sourceId,status,body,createdAt,allowWake,deliveryState,wakeState)
                VALUES ('legacy','parent','assistant','workspace','anchor','turn:old','task','EXITED','retained',1,1,'pending','pending')""")
            old.execSQL("""INSERT INTO agent_run (id,entry_point,status,profile_assistant_id,profile_name,model_id,model_name,goal,tool_envelope,transcript,result_summary,error,parent_conversation_id,created_at,started_at,finished_at)
                VALUES ('legacy-agent','chat','completed','p','worker',NULL,NULL,'old','[]','[]','old result',NULL,'parent',1,1,2)""")
        }
        migrations.runMigrationsAndValidate(name, 63, true).use { migrated ->
            migrated.query("SELECT sourceType,body FROM external_notice WHERE id='legacy'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("workspace", c.getString(0)); assertEquals("retained", c.getString(1))
            }
            migrated.query("SELECT notice_target,delivery_mode FROM agent_run WHERE id='legacy-agent'").use { c ->
                assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)); assertEquals("await", c.getString(1))
            }
        }
    }

    @Test fun handoffProgressAndCompletionKeepDurableDeliveryInEitherOrder() = runBlocking {
        val dao = openMemory().agentRunDao()
        dao.insertRun(run("before", DELIVERY_MODE_AWAIT))
        dao.retainAwaitedRun("before")
        dao.updateProgress("before", STATUS_RUNNING, 101, "[\"progress\"]", "[]")
        dao.finishRun("before", STATUS_COMPLETED, "answer", null, 1000, null)
        assertEquals(AgentRunEntity.DELIVERY_MODE_HANDOFF, dao.getRun("before")!!.deliveryMode)
        assertEquals(DELIVERY_STATE_PENDING, dao.getRun("before")!!.deliveryState)
        dao.markDelivered(listOf("before"), 2000)
        dao.updateProgress("before", STATUS_RUNNING, 101, "late", "[]")
        assertEquals(STATUS_COMPLETED, dao.getRun("before")!!.status)
        assertEquals(DELIVERY_STATE_DELIVERED, dao.getRun("before")!!.deliveryState)

        dao.insertRun(run("after", DELIVERY_MODE_AWAIT))
        dao.finishRun("after", STATUS_COMPLETED, "racing result", null, 1001, null)
        dao.retainAwaitedRun("after")
        assertEquals(listOf("after"), dao.observePendingDeliveries().first().map { it.id })
        dao.retainAwaitedRun("after")
        dao.markDelivered(listOf("after"), 2001)
        dao.retainAwaitedRun("after")
        assertTrue(dao.observePendingDeliveries().first().isEmpty())
    }

    @Test fun scopeHandoffPreservesOnlyItsActiveOrBackgroundRunsAndRecoversAfterRestart() = runBlocking {
        val dao = openMemory().agentRunDao()
        dao.insertRun(run("active", DELIVERY_MODE_AWAIT))
        dao.insertRun(run("finished", DELIVERY_MODE_AWAIT))
        dao.finishRun("finished", STATUS_COMPLETED, "already consumed", null, 1000, null)
        dao.insertRun(run("background"))
        dao.insertRun(run("other", DELIVERY_MODE_AWAIT).copy(scopeToken = "turn:two"))
        assertEquals(2, dao.retainScopeRuns("turn:one"))
        assertEquals(DELIVERY_MODE_AWAIT, dao.getRun("finished")!!.deliveryMode)
        assertEquals(DELIVERY_MODE_AWAIT, dao.getRun("other")!!.deliveryMode)
        dao.markInterruptedBefore(500, 1500, STATUS_INTERRUPTED, "restart",
            listOf(STATUS_RUNNING), "process_restart")
        assertEquals(setOf("active", "background"), dao.observePendingDeliveries().first().map { it.id }.toSet())
    }

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
        // AppDatabase migrates through all registered versions, not just the original 57→58 change.
        assertEquals(AppDatabase.SCHEMA_VERSION, db.openHelper.readableDatabase.version)
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

    @Test fun conversationTaskQueryNeverIncludesAnotherParent() = runBlocking {
        val dao = openMemory().agentRunDao()
        dao.insertRun(run("a").copy(parentConversationId = "one"))
        dao.insertRun(run("b").copy(parentConversationId = "two"))
        dao.insertRun(run("c").copy(parentConversationId = null))
        assertEquals(listOf("a"), dao.observeRunsByConversation("one").first().map { it.id })
        assertEquals(listOf("b"), dao.observeRunsByConversation("two").first().map { it.id })
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
                // 模拟「比 57 新一版」的备份：v58 提升时本测试写成 SCHEMA_VERSION，
                // 而它要验证的是「新版本备份被旧版本隔离」这一语义，故固定为 58。
                it.version = 58
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

    @Test
    fun noticeSurvivesTheNextRealRepositorySaveAndFailedTransactionCanRetry() = runBlocking {
        val db = openMemory()
        // Framework SQLite fixture: index persistence is real; tokenizer behavior is outside this test.
        db.openHelper.writableDatabase.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT)")
        db.openHelper.writableDatabase.execSQL("CREATE TABLE message_fts_rowid (fts_rowid INTEGER PRIMARY KEY, conversation_id TEXT)")
        val scope = AppScope()
        val isolatedFiles = File(context.cacheDir, "notice-repository-${Uuid.random()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = isolatedFiles }
        try {
            val files = FilesManager(isolated, FilesRepository(db.managedFileDao()), scope,
                SettingsStore(isolated, AppScope().apply { cancel() }, File(isolatedFiles, "settings.preferences_pb")))
            val repo = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
                files, MessageFtsManager(db), db.compressionCheckpointDao())
            val id = Uuid.random()
            val initial = Conversation.ofId(id, Uuid.random()).copy(messageNodes = listOf(UIMessage.user("original").toMessageNode()))
            repo.insertConversation(initial)
            val entry = run("deliver").copy(parentConversationId = id.toString())
            db.agentRunDao().insertRun(entry)
            db.agentRunDao().finishRun(entry.id, STATUS_COMPLETED, "result", null, 1000, null)
            val session = ConversationSession(id, initial, scope, {})
            val owner = session.backgroundTaskOwner
            val revision = session.turnRevision.value
            val failed = runCatching {
                withContext(Dispatchers.Main) {
                    appendNoticeToSession(session, listOf(entry), 100_000, 0) { updated, _ ->
                        db.withTransaction {
                            repo.updateConversation(updated)
                            error("simulated commit failure")
                        }
                    }
                }
            }
            assertTrue(failed.isFailure)
            assertEquals(initial.messageNodes, repo.getConversationById(id)!!.messageNodes)
            assertEquals(initial, session.state.value)
            assertEquals(1, db.agentRunDao().observePendingDeliveries().first().size)
            repeat(2) {
                val result = withContext(Dispatchers.Main) {
                    appendNoticeToSession(session, listOf(entry), 100_000, 0) { updated, ids ->
                        db.withTransaction {
                            repo.updateConversation(updated)
                            db.agentRunDao().markDelivered(ids, 2000)
                        }
                    }
                }
                assertEquals(AppendNoticeResult.DELIVERED, result)
            }
            // This is the saveMessageNodes path that formerly deleted DB-only injected messages.
            session.state.value = session.state.value.copy(
                messageNodes = session.state.value.messageNodes + UIMessage.user("next question").toMessageNode(),
            )
            repo.updateConversation(session.state.value)
            val loaded = repo.getConversationById(id)!!
            assertEquals(1, loaded.currentMessages.count { it.isSubagentNotice })
            assertEquals(3, loaded.messageNodes.size)
            assertTrue(db.agentRunDao().observePendingDeliveries().first().isEmpty())
            assertNull(session.getJob())
            assertEquals(owner, session.backgroundTaskOwner)
            assertEquals(revision, session.turnRevision.value)
        } finally {
            scope.cancel()
            isolatedFiles.deleteRecursively()
        }
    }
}
