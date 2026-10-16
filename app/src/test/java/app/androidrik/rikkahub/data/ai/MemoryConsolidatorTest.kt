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
    private val cursorDAO = FakeReviewCursorDAO()

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
            reviewCursorDAO = cursorDAO,
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
    fun `manual review walks the library page by page and advances the cursor`() = runBlocking {
        // 这是「全量整理」的核心：库装不进一次请求，所以必须**跨轮次推进**。
        // 以前没有游标 ⇒ 每次「整理一下」都从同一位置取，剩下的永远看不到。
        val assistant = assistant()
        val conversation = conversation(assistant, 2)
        conversations[conversation.id] = conversation
        // 造 30 条够大的记忆，逼出多页
        memoryList += (1..30).map {
            app.androidrik.rikkahub.data.model.AssistantMemory(
                id = "mem$it",
                content = "这是一条足够长的记忆内容，占掉一些预算。".repeat(400) + " #$it",
                sortKey = it,
            )
        }
        val worker = consolidator(listOf(assistant))
        val memoryId = assistant.id.toString()

        val first = worker.consolidate(memoryId, setOf(conversation.id), fullReview = true)
        assertTrue("必须走出第一页以上（否则谈不上分页）", (first?.libraryReviewed ?: 0) > 0)
        val cursorAfterFirst = cursorDAO.rows[memoryId]
        assertTrue("水位必须推进", (cursorAfterFirst ?: 0L) > 0L)
        assertFalse(first!!.libraryPageDone)

        val second = worker.consolidate(memoryId, setOf(conversation.id), fullReview = true)
        assertTrue(
            "第二页要从第一页之后开始，不能原地重复",
            (second?.libraryReviewed ?: 0) > first.libraryReviewed,
        )
        assertTrue("两页看到的记录不能重合", second!!.libraryReviewed > first.libraryReviewed)
    }

    @Test
    fun `the calibration table only lists records visible this round`() = runBlocking {
        // 真机缺陷：校准表列**全库**（含读不到正文的 id）⇒ 模型「知道 id 却看不到内容」⇒ 盲改
        // （实证：同一条 edit 被逐字节重复提出）。看不到的记录不该出现在它面前。
        val assistant = assistant()
        val conversation = conversation(assistant, 2)
        conversations[conversation.id] = conversation
        memoryList += (1..30).map {
            app.androidrik.rikkahub.data.model.AssistantMemory(
                id = "mem$it",
                content = "记忆内容够长以逼出分页。".repeat(400) + " #$it",
                sortKey = it,
            )
        }
        val worker = consolidator(listOf(assistant))

        worker.consolidate(assistant.id.toString(), setOf(conversation.id), fullReview = true)

        val request = agent.requests.single()
        val listedIds = Regex("mem[0-9]+").findAll(request.calibrationTable).map { it.value }.toSet()
        assertTrue("校准表非空", listedIds.isNotEmpty())
        assertTrue(
            "校准表不许列出读不到正文的记录（本页 30 条里只应出现一部分）：${listedIds.size}",
            listedIds.size < 30,
        )
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

/**
 * 内存版复核游标（真实现是 Room 表 `memory_review_cursor`）。
 *
 * 提成顶层 `internal` 而不是 file-private：用例要断言「跑完一轮之后水位推到哪了」。
 */
internal class FakeReviewCursorDAO : app.androidrik.rikkahub.data.db.dao.MemoryReviewCursorDAO {
    val rows = mutableMapOf<String, Long>()
    override suspend fun getCursor(memoryId: String): Long? = rows[memoryId]
    override suspend fun upsert(entry: app.androidrik.rikkahub.data.db.entity.MemoryReviewCursorEntity) {
        rows[entry.memoryId] = entry.cursor
    }
    override suspend fun clear(memoryId: String) { rows.remove(memoryId) }
}
}

/**
 * **全量复核的分页**（`docs/design/design-memory-retrieval-and-consolidation.md` §7）。
 *
 * 起因：库有 ≈58K token 而一页装不下，而原来的选取位置**每轮都一样** ⇒ 反复「整理一下」
 * 永远看同一批、剩下的永不见天日。「全量」只能靠**跨轮次推进的页**实现。
 */
class MemoryLibraryPagingTest {

    private fun memory(sortKey: Int, content: String) =
        app.androidrik.rikkahub.data.model.AssistantMemory(
            id = "id$sortKey",
            content = content,
            sortKey = sortKey,
        )

    private val library = (1..20).map { memory(it, "记忆内容 $it") }

    @Test
    fun `successive pages cover the whole library without overlap or loss`() {
        val seen = mutableSetOf<String>()
        var cursor = 0L
        var rounds = 0
        while (true) {
            val page = pageOfLibrary(library, cursor, budgetTokens = 20)
            assertTrue("有剩余记录时本页绝不能为空（否则水位卡死）", page.records.isNotEmpty())
            page.records.forEach {
                assertTrue("同一个 id 不能被复核两次：${it.id}", seen.add(it.id))
            }
            rounds++
            if (page.isLast) {
                assertEquals("走完一轮要从头开始", 0L, page.nextCursor)
                break
            }
            cursor = page.nextCursor
            assertTrue("轮数不该爆掉", rounds < 100)
        }
        assertEquals("20 条必须全部被看到", library.map { it.id }.toSet(), seen)
    }

    @Test
    fun `a single oversized record still yields a page`() {
        // 真机上 0d01f604 ≈5,151 token > 一整页预算。若它能把水位卡住，「全量」永远走不完。
        val huge = memory(1, "字".repeat(20_000))
        val page = pageOfLibrary(listOf(huge, memory(2, "小")), cursor = 0L, budgetTokens = 100)
        assertEquals("首条豁免预算，必须给出且只给这一条", listOf(huge.id), page.records.map { it.id })
        assertEquals("水位要推到它，否则下一轮还是它", 1L, page.nextCursor)
        assertFalse(page.isLast)
    }

    @Test
    fun `the page reports where it sits in the library`() {
        val first = pageOfLibrary(library, cursor = 0L, budgetTokens = 20)
        assertEquals(1, first.from)
        assertEquals(first.records.size, first.to)
        assertEquals(20, first.total)

        val second = pageOfLibrary(library, cursor = first.nextCursor, budgetTokens = 20)
        assertEquals("第二页要从第一页之后开始", first.records.size + 1, second.from)
        assertEquals(first.records.size, second.reviewedBefore)
    }

    @Test
    fun `a stale cursor restarts from the beginning`() {
        // 库会变（记录被删），旧的「已复核到 sortKey=999」不能变成永久跳过
        val page = pageOfLibrary(library, cursor = 999L, budgetTokens = 20)
        assertTrue(page.records.isNotEmpty())
        assertEquals("id1", page.records.first().id)
    }

    @Test
    fun `an empty library is one empty last page`() {
        val page = pageOfLibrary(emptyList(), cursor = 0L)
        assertTrue(page.records.isEmpty())
        assertTrue(page.isLast)
        assertEquals(0L, page.nextCursor)
        assertEquals(0, page.total)
    }

    @Test
    fun `the page prompt says which slice it is and forbids guessing`() {
        val page = pageOfLibrary(library, cursor = 0L, budgetTokens = 20)
        val prompt = buildLibraryPagePrompt(page)
        assertTrue("要说出这是第几页到第几页", prompt.contains("page ${page.from}–${page.to} of 20"))
        assertTrue("没给全时必须阻止它猜别的记录", prompt.contains("do not guess"))
        assertTrue("本页记录要给全文", prompt.contains("记忆内容 1"))
    }
}
