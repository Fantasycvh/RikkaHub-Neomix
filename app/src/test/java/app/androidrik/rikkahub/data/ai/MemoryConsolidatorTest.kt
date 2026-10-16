package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.db.dao.MemoryConsolidationDAO
import app.androidrik.rikkahub.data.db.entity.MemoryConsolidationEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.repository.MemoryConsolidationRepository
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 做梦 worker（[MemoryConsolidator]）的**策略**单测：切多少轮、水位推进到哪、审批怎么降级、
 * 没模型可用怎么办、失败怎么办。
 *
 * 模型调用本身被 [MemoryConsolidationAgent] 挡住（生产实现是 Android 那一侧，见
 * [MemoryConsolidationWiringTest] 的源码扫描守卫），所以这里能拿一个假 agent 把每条判据钉死。
 */
class MemoryConsolidatorTest {

    private val zone = TimeZone.currentSystemDefault()

    /** 固定的"现在"：频次上限那类判定与绝对时间有关，测试要确定性 */
    private val now = 1_000L * 24 * 60 * 60 * 1000

    private fun stamp(hour: Int): LocalDateTime =
        LocalDateTime.parse("2026-01-01T${"%02d".format(hour)}:00")

    private fun millis(hour: Int): Long = stamp(hour).toInstant(zone).toEpochMilliseconds()

    private class FakeConsolidationDAO : MemoryConsolidationDAO {
        val progress = mutableMapOf<String, Long>()

        override suspend fun getConsolidatedAt(conversationId: String): Long? = progress[conversationId]

        override suspend fun getProgress(conversationId: String): MemoryConsolidationEntity? = null

        override suspend fun upsertProgress(progress: MemoryConsolidationEntity) {
            this.progress[progress.conversationId] = progress.consolidatedAt
        }

        override suspend fun deleteProgress(conversationId: String) {
            progress.remove(conversationId)
        }

        override suspend fun getLastConsolidatedAt(assistantId: String): Long? = progress.values.maxOrNull()

        override suspend fun getCandidateConversationIds(assistantId: String, baseline: Long, limit: Int): List<String> =
            emptyList()
    }

    private val dao = FakeConsolidationDAO()
    private val modelId = Uuid.random()
    private val conversations = mutableMapOf<Uuid, Conversation>()
    private val memoryList = mutableListOf<AssistantMemory>()

    private fun provider() = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = "test",
        models = listOf(Model(id = modelId, displayName = "fast")),
        apiKey = "sk-test",
        baseUrl = "https://example.invalid/v1",
    )

    private fun assistant(
        since: Long = 1L,
        modifyApproval: Boolean = false,
        createApproval: Boolean = false,
        name: String = "a",
    ) = Assistant(
        id = Uuid.random(),
        name = name,
        enableMemory = true,
        memoryConsolidationSince = since,
        memoryModifyApproval = modifyApproval,
        memoryCreateApproval = createApproval,
    )

    private fun conversation(assistant: Assistant, vararg hours: Int): Conversation = Conversation(
        assistantId = assistant.id,
        title = "会话",
        messageNodes = hours.map { hour ->
            MessageNode(
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("第 $hour 小时说的话")),
                        createdAt = stamp(hour),
                    )
                )
            )
        },
    )

    /** 记录请求的假 agent；[failure] 非 null 时抛出（模拟模型/网络失败） */
    private class FakeAgent : MemoryConsolidationAgent {
        val requests = mutableListOf<MemoryConsolidationRequest>()
        var outcome = MemoryConsolidationOutcome(appliedChanges = 0, proposals = emptyList())
        var failure: Exception? = null

        override suspend fun run(request: MemoryConsolidationRequest): MemoryConsolidationOutcome {
            requests += request
            failure?.let { throw it }
            return outcome
        }
    }

    private val agent = FakeAgent()

    private fun consolidator(assistants: List<Assistant>, fastModel: Boolean = true): MemoryConsolidator {
        val settings = Settings(
            assistants = assistants,
            providers = if (fastModel) listOf(provider()) else emptyList(),
            fastModelId = if (fastModel) modelId else null,
        )
        val repository = MemoryConsolidationRepository(
            consolidationDAO = dao,
            assistants = { settings.assistants },
            conversationLoader = { id -> conversations[id] },
            limitPerRun = 4,
        )
        return MemoryConsolidator(
            repository = repository,
            memories = { memoryList.toList() },
            agent = agent,
            settings = { settings },
            // 建议存储：本类用例不碰「应用建议」，留一个记账用的空实现即可
            proposals = RecordingProposalStore(),
            now = { now },
        )
    }

    @Test
    fun `no model available means no request at all`() = runBlocking {
        val assistant = assistant()
        val conversation = conversation(assistant, 2)
        conversations[conversation.id] = conversation
        val worker = consolidator(listOf(assistant), fastModel = false)

        val run = worker.consolidate(assistant.id.toString(), setOf(conversation.id), fullReview = false)

        assertTrue("没有可用模型时必须记成一次失败（换来退避），而不是「没可做的事」", run?.error != null)
        assertTrue("一次请求都不许发", agent.requests.isEmpty())
        assertTrue("水位不许推进（内容不丢）", dao.progress.isEmpty())
    }

    @Test
    fun `auto runs use the auto step cap`() = runBlocking {
        val assistant = assistant()
        val conversation = conversation(assistant, 2, 3)
        conversations[conversation.id] = conversation
        val worker = consolidator(listOf(assistant))

        val run = worker.consolidate(assistant.id.toString(), setOf(conversation.id), fullReview = false)

        assertEquals(1, agent.requests.size)
        assertEquals(MemoryConsolidator.MAX_AUTO_STEPS, agent.requests.single().maxSteps)
        assertFalse("两个开关都关 ⇒ 正常改库", agent.requests.single().proposeOnly)
        assertTrue("正文里要带上新轮次", agent.requests.single().transcript.contains("第 3 小时说的话"))
        assertEquals(now, run?.finishedAt)
    }

    @Test
    fun `manual review raises the step cap`() = runBlocking {
        val assistant = assistant()
        val conversation = conversation(assistant, 2)
        conversations[conversation.id] = conversation
        val worker = consolidator(listOf(assistant))

        worker.consolidate(assistant.id.toString(), setOf(conversation.id), fullReview = true)

        assertEquals("手动整理允许更彻底", MemoryConsolidator.MAX_MANUAL_STEPS, agent.requests.single().maxSteps)
    }

    @Test
    fun `approval switches downgrade the run to proposals`() = runBlocking {
        val assistant = assistant(modifyApproval = true)
        val conversation = conversation(assistant, 2)
        conversations[conversation.id] = conversation
        agent.outcome = MemoryConsolidationOutcome(
            appliedChanges = 0,
            proposals = listOf(MemoryConsolidationProposal("edit", "aaa", "合并后的内容")),
        )
        val worker = consolidator(listOf(assistant))

        val run = worker.consolidate(assistant.id.toString(), setOf(conversation.id), fullReview = false)

        assertTrue("审批开着 ⇒ 整轮只提案（后台没有用户可等）", agent.requests.single().proposeOnly)
        assertEquals(0, run?.changedMemories)
        assertEquals(1, run?.proposals?.size)
        assertTrue("带上了库现状与校准表", agent.requests.single().libraryPrompt.isNotBlank())
    }

    @Test
    fun `watermarks advance only for the conversations the model actually saw`() = runBlocking {
        val assistant = assistant()
        val first = conversation(assistant, 2)
        // 第二个会话的正文很长 ⇒ 装不下（预算 6K token，这里用 40K 字符 ≈ 10K token）
        val second = Conversation(
            assistantId = assistant.id,
            title = "长",
            messageNodes = listOf(
                MessageNode(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Text("x".repeat(40_000))),
                            createdAt = stamp(4),
                        )
                    )
                )
            ),
        )
        conversations[first.id] = first
        conversations[second.id] = second
        val worker = consolidator(listOf(assistant))

        worker.consolidate(assistant.id.toString(), setOf(first.id, second.id), fullReview = false)

        assertEquals("带进去的会话水位推进到最后一轮", millis(2), dao.progress[first.id.toString()])
        assertNull("装不下的会话不许推进（内容不丢）", dao.progress[second.id.toString()])
        assertTrue(
            "没装下的会话仍是待整理（下次再吃）",
            worker.pendingOf(second) != null,
        )
    }

    @Test
    fun `an agent failure leaves every watermark untouched`() = runBlocking {
        val assistant = assistant()
        val conversation = conversation(assistant, 2)
        conversations[conversation.id] = conversation
        agent.failure = IllegalStateException("model exploded")
        val worker = consolidator(listOf(assistant))

        val thrown = runCatching {
            worker.consolidate(assistant.id.toString(), setOf(conversation.id), fullReview = false)
        }.exceptionOrNull()

        assertEquals("model exploded", thrown?.message)
        assertTrue("失败不许推进水位", dao.progress.isEmpty())
    }

    @Test
    fun `a repeated call with nothing new does not call the model`() = runBlocking {
        val assistant = assistant()
        val conversation = conversation(assistant, 2)
        conversations[conversation.id] = conversation
        val worker = consolidator(listOf(assistant))

        worker.consolidate(assistant.id.toString(), setOf(conversation.id), fullReview = false)
        val second = worker.consolidate(assistant.id.toString(), setOf(conversation.id), fullReview = false)

        assertNull("幂等：没有新轮次就是「没可做的事」", second)
        assertEquals("不该再花一次调用", 1, agent.requests.size)
    }

    @Test
    fun `turns without text advance the watermark without a model call`() = runBlocking {
        val assistant = assistant()
        val thinkingOnly = Conversation(
            assistantId = assistant.id,
            title = "只有思考",
            messageNodes = listOf(
                MessageNode(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Reasoning("thinking")),
                            createdAt = stamp(2),
                        )
                    )
                )
            ),
        )
        conversations[thinkingOnly.id] = thinkingOnly
        val worker = consolidator(listOf(assistant))

        val run = worker.consolidate(assistant.id.toString(), setOf(thinkingOnly.id), fullReview = false)

        assertNull("没有可整理的正文 ⇒ 没可做的事", run)
        assertTrue("一次调用都不该发", agent.requests.isEmpty())
        assertEquals("但水位要跨过去（否则每 2 分钟被扫一次）", millis(2), dao.progress[thinkingOnly.id.toString()])
    }

    @Test
    fun `a store nobody participates in is ignored`() = runBlocking {
        val assistant = assistant()
        val worker = consolidator(listOf(assistant))

        assertNull(worker.consolidate("nobody", emptySet(), fullReview = true))
        assertTrue(agent.requests.isEmpty())
    }

/**
 * 只记账的建议存储（[MemoryConsolidator] 只做委托，落库/校验/审计的真逻辑在
 * `MemoryProposalRepository` 与 `MemoryProposalRepositoryTest` 里）。
 */
private class RecordingProposalStore : app.androidrik.rikkahub.data.repository.MemoryProposalStore {
    val saved = mutableListOf<Pair<String, List<MemoryConsolidationProposal>>>()
    val applied = mutableListOf<Pair<String, Set<Long>>>()
    val rejected = mutableListOf<Pair<String, Set<Long>>>()
    private var rows = mutableListOf<MemoryConsolidationProposal>()
    private var nextId = 1L

    override suspend fun savePending(memoryId: String, proposals: List<MemoryConsolidationProposal>) {
        saved += memoryId to proposals
        rows = proposals.map { it.copy(id = nextId++) }.toMutableList()
    }

    override suspend fun pending(memoryId: String) = rows.toList()

    override suspend fun memoryIdsWithPending() = if (rows.isEmpty()) emptyList() else listOf("m")

    /** 被拒绝的建议（本用例给「不原地重提」那条测试用） */
    val refused = mutableListOf<MemoryConsolidationProposal>()

    override suspend fun recentlyRejected(memoryId: String, limit: Int) =
        refused.take(limit).toList()

    override suspend fun apply(memoryId: String, ids: Set<Long>): app.androidrik.rikkahub.data.repository.MemoryProposalApplyResult {
        applied += memoryId to ids
        rows.removeAll { it.id in ids }
        return app.androidrik.rikkahub.data.repository.MemoryProposalApplyResult(applied = ids.size)
    }

    override suspend fun reject(memoryId: String, ids: Set<Long>): Int {
        rejected += memoryId to ids
        rows.removeAll { it.id in ids }
        return ids.size
    }
}
}
