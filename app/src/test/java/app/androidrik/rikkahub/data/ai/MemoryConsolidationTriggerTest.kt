package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.repository.MemoryProposalApplyResult
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 「事件 → 调度器」这条链路的单测（V3-b3 的触发面）。
 *
 * 为什么值得测：Runtime 那一层只是 Android 粘合（`ProcessLifecycleOwner` + 事件总线），
 * 但它转发的东西一旦接错（比如拿到 `conversationId` 却不去取会话），整条做梦路径会**静默不工作**——
 * 没有报错、开关也开着，只是永远不整理。这里用真调度器 + 假 worker 把这条链路钉住。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryConsolidationTriggerTest {

    private val assistant = Assistant(
        id = Uuid.random(),
        name = "a",
        enableMemory = true,
        memoryConsolidationSince = 1L,
    )
    private val memoryId = assistant.memoryId()

    private val conversation = Conversation(
        assistantId = assistant.id,
        title = "会话",
        messageNodes = listOf(
            MessageNode(
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("聊了点别的")),
                    )
                )
            )
        ),
    )

    private class FakeWorker : MemoryConsolidationWorker {
        var pendingTurns: Int? = null
        var updatedStores: List<String> = emptyList()
        /** 这个库的 id（真 worker 用 assistant.memoryId()，测试里由用例给） */
        var storeId: String = "store"
        val scanned = mutableListOf<String>()

        override suspend fun pendingOf(conversation: Conversation): PendingMemoryConversation? =
            pendingTurns?.let { PendingMemoryConversation(conversation.id, storeId, it) }

        override suspend fun memoryIdsWithUpdates(): List<String> = updatedStores

        override suspend fun findPending(memoryId: String, manual: Boolean): List<PendingMemoryConversation> {
            scanned += memoryId
            return listOf(PendingMemoryConversation(Uuid.random(), memoryId, pendingTurns ?: 1))
        }

        override suspend fun markConsolidated(conversation: Conversation) = Unit

        override suspend fun consolidate(
            memoryId: String,
            conversationIds: Set<Uuid>,
            fullReview: Boolean,
        ): MemoryConsolidationRun? = MemoryConsolidationRun(
            memoryId = memoryId,
            finishedAt = 0L,
            conversationIds = conversationIds,
            fullReview = fullReview,
        )

        // 建议的落库与应用：本用例只关心"什么时候整理"，这几条留空实现
        override suspend fun pendingProposals(memoryId: String): List<MemoryConsolidationProposal> = emptyList()
        override suspend fun memoryIdsWithProposals(): List<String> = emptyList()
        override suspend fun applyProposals(memoryId: String, ids: Set<Long>): MemoryProposalApplyResult =
            MemoryProposalApplyResult()
        override suspend fun rejectProposals(memoryId: String, ids: Set<Long>): Int = 0
    }

    private fun scheduler(scope: CoroutineScope, worker: FakeWorker): MemoryConsolidationScheduler =
        MemoryConsolidationScheduler(scope = scope, worker = worker, isAppVisible = { true })

    @Test
    fun `a finished generation shows up as pending turns`() = runTest {
        val worker = FakeWorker().apply { pendingTurns = 2; storeId = memoryId }
        val scheduler = scheduler(backgroundScope, worker)
        val trigger = MemoryConsolidationTrigger(scheduler) { id -> conversation.takeIf { it.id == id } }

        trigger.onGenerationEnded(conversation.id)
        runCurrent()

        assertEquals(
            "轮末事件必须真的走到调度器（否则开关开着也永远不整理）",
            2,
            scheduler.statusOf(memoryId).first().pendingTurns,
        )
    }

    @Test
    fun `an unknown conversation is ignored instead of crashing`() = runTest {
        val worker = FakeWorker().apply { pendingTurns = 1 }
        val scheduler = scheduler(backgroundScope, worker)
        val trigger = MemoryConsolidationTrigger(scheduler) { null }

        trigger.onGenerationEnded(Uuid.random())
        runCurrent()

        assertEquals(0, scheduler.statusOf(memoryId).first().pendingTurns)
    }

    @Test
    fun `startup resume scans every store that has updates`() = runTest {
        val worker = FakeWorker().apply {
            pendingTurns = 3
            updatedStores = listOf(memoryId, "__global__")
        }
        val scheduler = scheduler(backgroundScope, worker)
        val trigger = MemoryConsolidationTrigger(scheduler) { null }

        trigger.resumePending()
        runCurrent()

        assertTrue(
            "启动补漏要按库扫一遍（进程被杀前没跑完的部分），实际扫到 $worker.scanned",
            worker.scanned.containsAll(listOf(memoryId, "__global__")),
        )
    }
}
