package app.androidrik.rikkahub.data.task

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.isSubagentNotice
import app.androidrik.ai.ui.messagesForAuxGeneration
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.model.Conversation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import app.androidrik.rikkahub.service.ConversationSession
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Job
import kotlin.uuid.Uuid

class NoticeDeliveryTest {
    private fun run(id: String, parent: String? = "parent") = AgentRunEntity(
        id = id, entryPoint = "chat", status = "completed", profileAssistantId = "p", profileName = "research",
        modelId = null, modelName = null, goal = "goal", toolEnvelope = "[]", transcript = "[]",
        resultSummary = "answer", error = null, parentConversationId = parent, createdAt = 1,
        startedAt = 1, finishedAt = 2, deliveryMode = "notice", deliveryState = "pending",
    )

    @Test fun `disabled pump neither reads nor writes`() = runBlocking {
        val pump = NoticeDelivery({ false }, { error("must not read") }, { error("must not orphan") },
            { _, _ -> error("must not append") })
        pump.flush()
    }

    @Test fun `busy and full parents remain pending until an idle event`() = runBlocking {
        val pending = mutableListOf(run("a"), run("b", "other"))
        var result = AppendNoticeResult.BUSY
        val delivered = mutableListOf<String>()
        val pump = NoticeDelivery({ true }, { pending.toList() }, { error("not orphaned") }, { _, batch ->
            if (result == AppendNoticeResult.DELIVERED) {
                delivered += batch.map { it.id }; pending.removeAll(batch)
            }
            result
        })
        pump.flush()
        result = AppendNoticeResult.CONTEXT_FULL
        pump.flush()
        assertEquals(2, pending.size)
        result = AppendNoticeResult.DELIVERED
        pump.flush()
        pump.flush()
        assertEquals(listOf("a", "b"), delivered)
        assertTrue(pending.isEmpty())
    }

    @Test fun `deleted and absent parents are orphaned without stopping other deliveries`() = runBlocking {
        val orphaned = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        val pump = NoticeDelivery({ true }, { listOf(run("a", null), run("b", "deleted"), run("c")) },
            { orphaned += it }, { parent, batch ->
                if (parent == "deleted") AppendNoticeResult.MISSING else {
                    delivered += batch.map { it.id }; AppendNoticeResult.DELIVERED
                }
            })
        pump.flush()
        assertEquals(listOf("a", "b"), orphaned)
        assertEquals(listOf("c"), delivered)
    }

    @Test fun `concurrent flushes do not deliver twice and batches are bounded`() = runBlocking {
        val pending = (1..19).map { run("$it") }.toMutableList()
        val sizes = mutableListOf<Int>()
        val ids = mutableListOf<String>()
        val pump = NoticeDelivery({ true }, { pending.toList() }, {}, { _, batch ->
            yield()
            sizes += batch.size
            ids += batch.map { it.id }
            pending.removeAll(batch)
            AppendNoticeResult.DELIVERED
        })
        List(4) { async { pump.flush() } }.awaitAll()
        assertEquals(listOf(8, 8, 3), sizes)
        assertEquals(19, ids.toSet().size)
        assertEquals(19, ids.size)
    }

    @Test fun `one broken parent leaves results pending and allows other parents`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        var delivered = false
        val pump = NoticeDelivery({ true }, { listOf(run("a", "broken"), run("b")) }, {}, { parent, _ ->
            if (parent == "broken") error("storage unavailable")
            delivered = true
            AppendNoticeResult.DELIVERED
        }, { failures += it })
        pump.flush()
        assertTrue(delivered)
        assertEquals(1, failures.size)
    }

    @Test fun `notices are system outputs excluded from auxiliary generation and retained past compression`() {
        val notice = subagentNotice(listOf(run("a")))
        assertEquals(MessageRole.SYSTEM, notice.role)
        assertTrue(notice.isSubagentNotice)
        val user = UIMessage.user("question")
        val checkpoint = UIMessage.user("summary")
        val messages = listOf(user, notice)
        assertEquals(listOf(user), messages.messagesForAuxGeneration())
        val window = Conversation.CompressionWindow(checkpoint, 1)
        assertEquals(listOf(checkpoint, notice), window.applyTo(messages))
        assertEquals(listOf(checkpoint, notice, user), window.applyTo(messages + user))
        assertEquals(listOf("a"), notice.annotations.filterIsInstance<UIMessageAnnotation.SubagentNotice>().single().runIds)
    }

    @Test fun `append preserves turn identity and reply target without starting generation`() = runTest {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id, Uuid.random()), backgroundScope, {})
        val owner = session.backgroundTaskOwner
        val revision = session.turnRevision.value
        val reply = Uuid.random()
        session.replyMessageId.value = reply
        var persisted = 0
        repeat(2) {
            assertEquals(AppendNoticeResult.DELIVERED,
                appendNoticeToSession(session, listOf(run("a")), 100_000, 0) { conversation, ids ->
                    assertEquals(listOf("a"), ids)
                    assertSame(conversation, session.state.value)
                    persisted++
                })
        }
        assertEquals(1, session.state.value.messageNodes.size)
        assertEquals(2, persisted)
        assertEquals(owner, session.backgroundTaskOwner)
        assertEquals(revision, session.turnRevision.value)
        assertEquals(reply, session.replyMessageId.value)
        assertNull(session.getJob())
    }

    @Test fun `append defers busy sessions and low context without changing memory`() = runTest {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id, Uuid.random()), backgroundScope, {})
        val original = session.state.value
        val job = Job()
        session.setJob(job)
        assertEquals(AppendNoticeResult.BUSY,
            appendNoticeToSession(session, listOf(run("a")), 100_000, 0) { _, _ -> error("must not save") })
        job.complete()
        assertEquals(AppendNoticeResult.CONTEXT_FULL,
            appendNoticeToSession(session, listOf(run("a")), 4096, 0) { _, _ -> error("must not save") })
        assertSame(original, session.state.value)
    }

    @Test fun `failed persistence restores original memory and retry can append`() = runTest {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id, Uuid.random()), backgroundScope, {})
        val original = session.state.value
        val error = runCatching {
            appendNoticeToSession(session, listOf(run("a")), 100_000, 0) { _, _ -> error("disk full") }
        }.exceptionOrNull()
        assertNotNull(error)
        assertSame(original, session.state.value)
        assertEquals(AppendNoticeResult.DELIVERED,
            appendNoticeToSession(session, listOf(run("a")), 100_000, 0) { _, _ -> })
        assertEquals(1, session.state.value.messageNodes.size)
    }

    @Test fun `pending approval defers notices even when there is no generation job`() = runTest {
        val id = Uuid.random()
        val pendingTool = app.androidrik.ai.ui.UIMessagePart.Tool("approval", "workspace_write_file", "{}",
            approvalState = app.androidrik.ai.ui.ToolApprovalState.Pending)
        val original = Conversation.ofId(id, Uuid.random()).copy(messageNodes = listOf(
            app.androidrik.rikkahub.data.model.MessageNode(messages = listOf(
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pendingTool)),
            )),
        ))
        val session = ConversationSession(id, original, backgroundScope, {})
        assertNull(session.getJob())
        assertEquals(AppendNoticeResult.BUSY,
            appendNoticeToSession(session, listOf(run("a")), 100_000, 0) { _, _ -> error("must not save") })
        assertSame(original, session.state.value)
    }
}
