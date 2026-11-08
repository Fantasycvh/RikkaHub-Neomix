package app.androidrik.rikkahub.service

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.isExternalNotice
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.db.dao.ExternalNoticeDAO
import app.androidrik.rikkahub.data.db.entity.ExternalNoticeEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.toMessageNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceNoticeInboxTest {
    @Before fun main() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun reset() { Dispatchers.resetMain() }

    @Test fun `busy generation and approval defer delivery until the safe idle boundary`() = runTest {
        val f = Fixture(this)
        val job = Job()
        f.session.setJob(job)
        f.inbox.start(backgroundScope)
        f.dao.insert(f.row)
        runCurrent()
        assertEquals("pending", f.dao.get(f.row.id)!!.deliveryState)
        assertEquals(0, f.wakes)
        job.complete()
        val pending = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            "approval", "write", "{}", approvalState = ToolApprovalState.Pending,
        )))
        val original = f.session.state.value
        f.session.state.value = original.copy(messageNodes = original.messageNodes + pending.toMessageNode())
        f.inbox.request(); runCurrent()
        assertEquals("pending", f.dao.get(f.row.id)!!.deliveryState)
        f.session.state.value = original
        f.inbox.request(); runCurrent()
        assertEquals(1, f.wakes)
        repeat(5) { f.inbox.request(); runCurrent() }
        assertEquals(1, f.wakes)
    }

    @Test fun `a later independent completion in the same root can wake once more`() = runTest {
        val f = Fixture(this)
        f.dao.insert(f.row)
        f.inbox.start(backgroundScope); runCurrent()
        assertEquals(1, f.wakes)
        val second = f.row.copy(id = Uuid.random().toString(), sourceId = "second-task")
        val source = f.tool.copy(id = Uuid.random(), parts = listOf(UIMessagePart.Tool("second-call", "workspace_run_background", "{}",
            listOf(UIMessagePart.Text("""{"completionEventId":"${second.id}"}""")))))
        val current = f.session.state.value
        f.session.state.value = current.copy(messageNodes = current.messageNodes + source.toMessageNode())
        f.dao.insert(second); runCurrent()
        assertEquals(2, f.wakes)
        assertEquals("claimed", f.dao.get(second.id)!!.wakeState)
        assertEquals(2, f.session.state.value.currentMessages.count { it.isExternalNotice })
    }

    @Test fun `user consumes pending notice without a second model request`() = runTest {
        val f = Fixture(this)
        f.dao.insert(f.row)
        f.session.messageQueue.enqueue(listOf(UIMessagePart.Text("user first")))
        f.inbox.start(backgroundScope); runCurrent()
        assertEquals(0, f.wakes)
        f.inbox.beforeUser(f.session)
        assertEquals("suppressed", f.dao.get(f.row.id)!!.wakeState)
        assertEquals(1, f.persisted.currentMessages.count { it.isExternalNotice })
        f.inbox.beforeUser(f.session)
        assertEquals(1, f.session.state.value.currentMessages.count { it.isExternalNotice })
    }

    @Test fun `notice already in memory still commits history atomically with delivery acknowledgement`() = runTest {
        val f = Fixture(this)
        f.dao.insert(f.row)
        f.session.state.value = f.initial.copy(messageNodes = f.initial.messageNodes +
            app.androidrik.rikkahub.data.task.externalNoticeMessage(f.row).toMessageNode())
        assertEquals(0, f.persisted.currentMessages.count { it.isExternalNotice })
        f.inbox.beforeUser(f.session)
        assertEquals(1, f.persisted.currentMessages.count { it.isExternalNotice })
        assertEquals(1, f.session.state.value.currentMessages.count { it.isExternalNotice })
        assertEquals("delivered", f.dao.get(f.row.id)!!.deliveryState)
    }

    @Test fun `transaction failure restores memory and durable state then retries once`() = runTest {
        val f = Fixture(this)
        f.dao.insert(f.row)
        val original = f.session.state.value
        f.failCommit = true
        try { f.inbox.beforeUser(f.session); fail("expected rollback") } catch (_: IllegalStateException) { }
        assertSame(original, f.session.state.value)
        assertEquals("pending", f.dao.get(f.row.id)!!.deliveryState)
        assertEquals(0, f.persisted.currentMessages.count { it.isExternalNotice })
        f.failCommit = false
        f.inbox.beforeUser(f.session)
        assertEquals(1, f.persisted.currentMessages.count { it.isExternalNotice })
    }

    @Test fun `stop and disabled rollout prevent wake but preserve result visibility`() = runTest {
        val f = Fixture(this)
        f.dao.insert(f.row)
        f.session.messageQueue.pause()
        f.inbox.start(backgroundScope); runCurrent()
        assertEquals("delivered", f.dao.get(f.row.id)!!.deliveryState)
        assertEquals(0, f.wakes)
        f.inbox.suppressAndPersist(f.row.rootScope)
        f.session.messageQueue.resume()
        f.inbox.request(); runCurrent()
        assertEquals("suppressed", f.dao.get(f.row.id)!!.wakeState)
        assertEquals(0, f.wakes)
    }

    @Test fun `rollout disabled and interrupted claims never auto replay`() = runTest {
        val f = Fixture(this)
        f.settings.value = f.settings.value.copy(workspaceTaskNoticeEnabled = false)
        f.dao.insert(f.row)
        f.inbox.start(backgroundScope); runCurrent()
        assertEquals("suppressed", f.dao.get(f.row.id)!!.wakeState)
        assertEquals(0, f.wakes)
        val interrupted = f.row.copy(id = Uuid.random().toString(), deliveryState = "delivered", wakeState = "started")
        f.dao.insert(interrupted)
        f.dao.recoverInterrupted()
        assertEquals("failed", f.dao.get(interrupted.id)!!.wakeState)
    }

    @Test fun `changed branch or exhausted context keeps original event pending`() = runTest {
        val f = Fixture(this)
        f.dao.insert(f.row)
        f.budget = 100
        f.inbox.beforeUser(f.session)
        assertEquals("pending", f.dao.get(f.row.id)!!.deliveryState)
        f.budget = 100_000
        f.session.state.value = f.session.state.value.copy(messageNodes = listOf(f.user.toMessageNode()))
        f.inbox.beforeUser(f.session)
        assertEquals("pending", f.dao.get(f.row.id)!!.deliveryState)
        assertEquals(0, f.wakes)
    }

    @Test fun `cold dummy settings cannot consume or suppress a pending wake`() = runTest {
        val f = Fixture(this)
        val loaded = f.settings.value
        f.settings.value = Settings.dummy()
        f.dao.insert(f.row)
        f.inbox.start(backgroundScope); runCurrent()
        assertEquals("pending", f.dao.get(f.row.id)!!.deliveryState)
        assertEquals("pending", f.dao.get(f.row.id)!!.wakeState)
        f.settings.value = loaded
        runCurrent()
        assertEquals(1, f.wakes)
    }

    @Test fun `active loop consumes results only at a completed step without starting another turn`() = runTest {
        val f = Fixture(this)
        val job = Job()
        f.session.setJob(job)
        f.dao.insert(f.row)
        val updated = f.inbox.atStep(f.session, f.initial.currentMessages)
        assertTrue(updated.last().parts.single() is UIMessagePart.Notice)
        assertEquals(f.tool.parts, updated[1].parts)
        assertEquals("delivered", f.dao.get(f.row.id)!!.deliveryState)
        assertEquals("suppressed", f.dao.get(f.row.id)!!.wakeState)
        assertEquals(0, f.wakes)
        assertEquals(updated, f.inbox.atStep(f.session, updated))
        job.complete()
    }

    @Test fun `step hook never consumes across a user pause or unresolved approved tool`() = runTest {
        val f = Fixture(this)
        f.dao.insert(f.row)
        f.session.messageQueue.pause()
        assertEquals(f.initial.currentMessages, f.inbox.atStep(f.session, f.initial.currentMessages))
        assertEquals("pending", f.dao.get(f.row.id)!!.deliveryState)
        f.session.messageQueue.resume()
        val approved = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            "approved", "memory_tool", "{}", approvalState = ToolApprovalState.Approved)))
        val live = f.initial.currentMessages + approved
        assertEquals(live, f.inbox.atStep(f.session, live))
        assertEquals("pending", f.dao.get(f.row.id)!!.deliveryState)
    }

    @Test fun `retained result is deferred during the newer turn and delivered at idle without waking`() = runTest {
        val f = Fixture(this)
        f.inbox.suppressAndPersist(f.row.rootScope)
        f.dao.insert(f.row.copy(wakeState = "suppressed"))
        f.session.beginNewTurn()
        val job = Job()
        f.session.setJob(job)
        f.inbox.start(backgroundScope); runCurrent()
        f.inbox.beforeUser(f.session)
        assertEquals(f.initial.currentMessages, f.inbox.atStep(f.session, f.initial.currentMessages))
        assertEquals("pending", f.dao.get(f.row.id)!!.deliveryState)
        job.complete(); f.inbox.request(); runCurrent()
        assertEquals("delivered", f.dao.get(f.row.id)!!.deliveryState)
        assertEquals(0, f.wakes)
    }

    @Test fun `handoff fences delivery while interrupted tool receipts are being restored`() = runTest {
        val f = Fixture(this)
        f.session.taskHandoffInProgress = true
        f.dao.insert(f.row)
        f.inbox.start(backgroundScope); runCurrent()
        assertEquals("pending", f.dao.get(f.row.id)!!.deliveryState)
        f.session.taskHandoffInProgress = false
        f.inbox.request(); runCurrent()
        assertEquals("delivered", f.dao.get(f.row.id)!!.deliveryState)
    }

    @Test fun `a newer intent defers old results until idle even when automatic wake is enabled`() = runTest {
        val f = Fixture(this)
        f.session.beginNewTurn()
        f.dao.insert(f.row)
        val job = Job(); f.session.setJob(job)
        f.inbox.start(backgroundScope); runCurrent()
        f.inbox.beforeUser(f.session)
        assertEquals(f.initial.currentMessages, f.inbox.atStep(f.session, f.initial.currentMessages))
        assertEquals("pending", f.dao.get(f.row.id)!!.deliveryState)
        job.complete(); f.inbox.request(); runCurrent()
        assertEquals("delivered", f.dao.get(f.row.id)!!.deliveryState)
        assertEquals(1, f.wakes)
    }

    @Test fun `eight consumed completion events bound automatic continuation without dropping a ninth result`() = runTest {
        val f = Fixture(this)
        repeat(8) { f.dao.insert(f.row.copy(id = Uuid.random().toString(), deliveryState = "delivered", wakeState = "completed", createdAt = System.currentTimeMillis())) }
        f.dao.insert(f.row)
        f.inbox.start(backgroundScope); runCurrent()
        assertEquals("delivered", f.dao.get(f.row.id)!!.deliveryState)
        assertEquals("suppressed", f.dao.get(f.row.id)!!.wakeState)
        assertEquals(0, f.wakes)
    }

    @Test fun `different original tasks do not share their first continuation context`() = runTest {
        val f = Fixture(this)
        val second = f.row.copy(id = Uuid.random().toString(), rootScope = "turn:other", sourceId = "other-task")
        val source = f.tool.copy(id = Uuid.random(), parts = listOf(UIMessagePart.Tool("other-call", "workspace_run_background", "{}",
            listOf(UIMessagePart.Text("""{"completionEventId":"${second.id}"}""")))))
        f.session.state.value = f.initial.copy(messageNodes = f.initial.messageNodes + source.toMessageNode())
        f.dao.insert(f.row); f.dao.insert(second)
        f.inbox.start(backgroundScope); runCurrent()
        assertEquals(2, f.wakes)
        assertEquals(1, f.wakeSnapshots.first().count { it.isExternalNotice })
        assertEquals(2, f.wakeSnapshots.last().count { it.isExternalNotice })
    }

    private class Fixture(test: TestScope) {
        val dao = FakeDAO()
        val assistant = Assistant(workspaceId = Uuid.random())
        val user = UIMessage.user("build")
        val event = Uuid.random().toString()
        val tool = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            "call", "workspace_run_background", "{}", listOf(UIMessagePart.Text("""{"completionEventId":"$event"}""")),
        )))
        val initial = Conversation.ofId(Uuid.random(), assistant.id).copy(messageNodes = listOf(user.toMessageNode(), tool.toMessageNode()))
        val session = ConversationSession(initial.id, initial, test.backgroundScope, {}).apply { beginNewTurn("turn:root") }
        val settings = MutableStateFlow(Settings(assistants = listOf(assistant), workspaceTaskNoticeEnabled = true))
        val row = ExternalNoticeEntity(event, initial.id.toString(), assistant.id.toString(), assistant.workspaceId.toString(),
            user.id.toString(), "turn:root", "task", "EXITED", "result", 1L, true)
        var persisted = initial
        var failCommit = false
        var budget = 100_000
        var wakes = 0
        val wakeSnapshots = mutableListOf<List<UIMessage>>()
        val inbox = WorkspaceNoticeInbox(dao, { block ->
            val saved = dao.rows.value
            val conversation = persisted
            try { block(); check(!failCommit) }
            catch (e: Throwable) { dao.rows.value = saved; persisted = conversation; throw e }
        }, { true }, { persisted = it }, flowOf(emptyList()), {}, settings,
            { if (it == session.id) session else null }, { budget }, { _, _, _ -> wakes++; wakeSnapshots += session.state.value.currentMessages; true })
    }

    private class FakeDAO : ExternalNoticeDAO {
        val rows = MutableStateFlow<List<ExternalNoticeEntity>>(emptyList())
        override suspend fun insert(notice: ExternalNoticeEntity) { if (rows.value.none { it.id == notice.id }) rows.value += notice }
        override suspend fun pendingCount() = rows.value.count { it.deliveryState == "pending" || (it.deliveryState == "delivered" && it.wakeState == "pending") }
        override suspend fun pruneResolved(before: Long) { rows.value = rows.value.filterNot { it.createdAt < before && it.deliveryState != "pending" && it.wakeState in listOf("completed", "failed", "suppressed") } }
        override suspend fun get(id: String) = rows.value.find { it.id == id }
        override fun observePending() = rows.map { list -> list.filter {
            it.deliveryState == "pending" || (it.deliveryState == "delivered" && it.wakeState == "pending")
        } }
        override suspend fun pendingForConversation(conversationId: String) = rows.value.filter { it.conversationId == conversationId && it.deliveryState == "pending" }.take(8)
        override suspend fun delivered(ids: List<String>, state: String) { rows.value = rows.value.map { if (it.id in ids) it.copy(deliveryState = state) else it } }
        override suspend fun wake(ids: List<String>, state: String) { rows.value = rows.value.map { if (it.id in ids) it.copy(wakeState = state) else it } }
        override suspend fun wasWoken(scope: String) = rows.value.any { it.rootScope == scope && it.wakeState in listOf("claimed", "started", "waiting_approval", "completed", "failed") }
        override suspend fun wakeCount(scope: String) = rows.value.count { it.rootScope == scope && it.wakeState in listOf("claimed", "started", "waiting_approval", "completed", "failed") }
        override suspend fun suppressScope(scope: String) { rows.value = rows.value.map { if (it.rootScope == scope && it.wakeState == "pending") it.copy(wakeState = "suppressed") else it } }
        override suspend fun isScopeSuppressed(scope: String) = rows.value.any { it.rootScope == scope && it.sourceType == "cancel_scope" }
        override suspend fun recoverInterrupted() { rows.value = rows.value.map { if (it.wakeState in listOf("claimed", "started", "waiting_approval")) it.copy(wakeState = "failed") else it } }
    }
}
