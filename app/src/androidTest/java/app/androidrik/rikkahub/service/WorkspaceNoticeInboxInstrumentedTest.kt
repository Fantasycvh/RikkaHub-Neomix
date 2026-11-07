package app.androidrik.rikkahub.service

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isExternalNotice
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.ExternalNoticeEntity
import app.androidrik.rikkahub.data.db.fts.MessageFtsManager
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.toMessageNode
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.FilesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** Isolated Room + real repository/session; never opens the user's database or invokes a model. */
@RunWith(AndroidJUnit4::class)
class WorkspaceNoticeInboxInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migration60To61PreservesExistingDatabase() {
        val name = "workspace-inbox-migration-test"
        try {
            migrations.createDatabase(name, 60).close()
            migrations.runMigrationsAndValidate(name, 61, true).use {
                it.query("SELECT COUNT(*) FROM external_notice").use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun pendingUserConsumesResultOnceAndNextRepositorySaveKeepsIt() = fixture { f ->
        val row = f.row()
        f.dao.insert(row)
        f.dao.insert(row.copy(body = "duplicate must not replace original"))
        f.inbox.beforeUser(f.session)
        f.inbox.beforeUser(f.session)
        assertEquals(1, f.session.state.value.currentMessages.count { it.isExternalNotice })
        assertEquals("suppressed", f.dao.get(row.id)!!.wakeState)
        assertEquals("delivered", f.dao.get(row.id)!!.deliveryState)
        assertEquals(0, f.wakes.get())
        val next = f.session.state.value.copy(messageNodes = f.session.state.value.messageNodes + UIMessage.user("next").toMessageNode())
        f.repo.updateConversation(next)
        assertEquals(1, f.repo.getConversationById(f.session.id)!!.currentMessages.count { it.isExternalNotice })
    }

    @Test fun idleResultWakesOnceAndDuplicateRequestCannotWakeAgain() = fixture { f ->
        val row = f.row()
        f.inbox.start(f.scope)
        f.dao.insert(row)
        f.await { f.wakes.get() == 1 }
        repeat(10) { f.inbox.request() }
        delay(100)
        assertEquals(1, f.wakes.get())
        assertEquals("delivered", f.dao.get(row.id)!!.deliveryState)
    }

    @Test fun pauseStillShowsResultButBlocksWakeAndStopPersistsSuppression() = fixture { f ->
        f.session.messageQueue.pause()
        val row = f.row()
        f.inbox.start(f.scope)
        f.dao.insert(row)
        f.await { f.dao.get(row.id)?.deliveryState == "delivered" }
        assertEquals(0, f.wakes.get())
        f.inbox.suppressAndPersist(row.rootScope)
        assertEquals("suppressed", f.dao.get(row.id)!!.wakeState)
        f.session.messageQueue.resume()
        f.inbox.request()
        delay(100)
        assertEquals(0, f.wakes.get())
    }

    @Test fun interruptedWakeIsNotReplayedOnStartup() = fixture { f ->
        val row = f.row().copy(deliveryState = "delivered", wakeState = "started")
        f.dao.insert(row)
        f.inbox.start(f.scope)
        f.await { f.dao.get(row.id)?.wakeState == "failed" }
        assertEquals(0, f.wakes.get())
    }

    @Test fun differentSelectedToolBranchCannotReceiveOldResult() = fixture { f ->
        val row = f.row()
        val user = f.session.state.value.messageNodes.first()
        f.session.state.value = f.session.state.value.copy(messageNodes = listOf(user, UIMessage.assistant("regenerated").toMessageNode()))
        f.dao.insert(row)
        f.inbox.beforeUser(f.session)
        assertEquals("pending", f.dao.get(row.id)!!.deliveryState)
        assertEquals(0, f.session.state.value.currentMessages.count { it.isExternalNotice })
        assertEquals(0, f.wakes.get())
    }

    @Test fun failedDeliveryTransactionRollsBackHistoryAndCanRetryOnce() = fixture { f ->
        val row = f.row()
        f.dao.insert(row)
        val original = f.session.state.value
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_notice BEFORE UPDATE OF deliveryState ON external_notice BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try {
            f.inbox.beforeUser(f.session)
            fail("transaction must fail")
        } catch (expected: android.database.SQLException) { }
        assertSame(original, f.session.state.value)
        assertEquals("pending", f.dao.get(row.id)!!.deliveryState)
        assertEquals(0, f.repo.getConversationById(f.session.id)!!.currentMessages.count { it.isExternalNotice })
        f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_notice")
        f.inbox.beforeUser(f.session)
        assertEquals(1, f.repo.getConversationById(f.session.id)!!.currentMessages.count { it.isExternalNotice })
    }

    @Test fun contextBudgetDefersDeliveryWithoutLosingEvent() = fixture { f ->
        val row = f.row()
        f.dao.insert(row)
        f.budget = 100
        f.inbox.beforeUser(f.session)
        assertEquals("pending", f.dao.get(row.id)!!.deliveryState)
        f.budget = 100_000
        f.inbox.beforeUser(f.session)
        assertEquals("delivered", f.dao.get(row.id)!!.deliveryState)
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        try { f.repo.insertConversation(f.session.state.value); block(f) }
        finally { f.scope.cancel(); f.scope.coroutineContext[Job]?.join(); f.db.close(); f.files.deleteRecursively() }
    }

    private inner class Fixture {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().also {
            it.openHelper.writableDatabase.execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT)")
            it.openHelper.writableDatabase.execSQL("CREATE TABLE message_fts_rowid (fts_rowid INTEGER PRIMARY KEY, conversation_id TEXT)")
        }
        val dao = db.externalNoticeDao()
        val scope = AppScope()
        val files = File(context.cacheDir, "workspace-inbox-${Uuid.random()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = files }
        val fileManager = FilesManager(isolated, FilesRepository(db.managedFileDao()), scope,
            SettingsStore(isolated, AppScope().apply { cancel() }, File(files, "settings.preferences_pb")))
        val repo = ConversationRepository(db.conversationDao(), db.messageNodeDao(), db.favoriteDao(), db,
            fileManager, MessageFtsManager(db), db.compressionCheckpointDao())
        val assistant = Assistant(workspaceId = Uuid.random())
        val eventId = Uuid.random().toString()
        val user = UIMessage.user("build")
        val tool = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            "call", "workspace_run_background", "{}", listOf(UIMessagePart.Text("""{"completionEventId":"$eventId"}""")),
        )))
        val initial = Conversation.ofId(Uuid.random(), assistant.id).copy(messageNodes = listOf(user.toMessageNode(), tool.toMessageNode()))
        val session = ConversationSession(initial.id, initial, scope, {})
        val settings = MutableStateFlow(Settings(assistants = listOf(assistant), workspaceTaskNoticeEnabled = true))
        val wakes = AtomicInteger()
        var budget = 100_000
        val inbox = WorkspaceNoticeInbox(db, flowOf(emptyList()), {}, repo, settings,
            { if (it == session.id) session else null }, { budget }, { _, _, _ -> wakes.incrementAndGet(); true })
        fun row() = ExternalNoticeEntity(eventId, initial.id.toString(), assistant.id.toString(), assistant.workspaceId.toString(),
            user.id.toString(), "turn:root", "task", "EXITED", "output", 1L, true)
        suspend fun await(condition: suspend () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }
    }
}
