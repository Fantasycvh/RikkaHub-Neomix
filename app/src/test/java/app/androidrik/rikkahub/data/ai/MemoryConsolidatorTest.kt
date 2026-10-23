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
        var candidates = emptyList<String>()

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
            candidates.take(limit)
    }

    private val dao = FakeConsolidationDAO()
    private val modelId = Uuid.random()
    private val conversations = mutableMapOf<Uuid, Conversation>()
    private val memoryList = mutableListOf<AssistantMemory>()

    private fun provider(contextLength: Int = 32_000) = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = "test",
        // 刻意给小窗口：手动页预算按窗口放开（2026-10-16），默认兜底 256K 会一轮装完全库，
        // 于是「部分覆盖 / 校准表只列本页」这类断言就没有区分度了
        models = listOf(Model(id = modelId, displayName = "fast", customContextLength = contextLength)),
        apiKey = "sk-test",
        baseUrl = "https://example.invalid/v1",
    )

    private fun assistant(
        since: Long = 1L,
        modifyApproval: Boolean = false,
        createApproval: Boolean = false,
        name: String = "a",
        reclassify: Boolean = false,
        globalMemory: Boolean = false,
    ) = Assistant(
        id = Uuid.random(),
        name = name,
        enableMemory = true,
        memoryConsolidationSince = since,
        memoryModifyApproval = modifyApproval,
        memoryCreateApproval = createApproval,
        memoryConsolidationReclassify = reclassify,
        useGlobalMemory = globalMemory,
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
    private val proposalStore = RecordingProposalStore()
    private var transactionCalls = 0
    private val transactions = object : app.androidrik.rikkahub.data.repository.MemoryTransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T {
            transactionCalls++
            val progress = dao.progress.toMap()
            val cursors = cursorDAO.rows.toMap()
            val proposals = proposalStore.rows.toList()
            try {
                return block()
            } catch (failure: Throwable) {
                dao.progress.clear()
                dao.progress.putAll(progress)
                cursorDAO.rows.clear()
                cursorDAO.rows.putAll(cursors)
                proposalStore.rows = proposals.toMutableList()
                throw failure
            }
        }
    }

    private fun consolidator(
        assistants: List<Assistant>,
        fastModel: Boolean = true,
        contextLength: Int = 32_000,
        configure: (Settings) -> Settings = { it },
    ): MemoryConsolidator {
        val settings = configure(Settings(
            assistants = assistants,
            providers = if (fastModel) listOf(provider(contextLength)) else emptyList(),
            fastModelId = if (fastModel) modelId else null,
        ))
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
            proposals = proposalStore,
            transactions = transactions,
            now = { now },
        )
    }

    @Test
    fun `dedicated model takes priority for automatic and manual consolidation`() = runBlocking {
        val assistant = assistant().copy(chatModelId = modelId)
        val dedicated = Model(id = Uuid.random(), displayName = "dream")
        val worker = consolidator(listOf(assistant), configure = { settings ->
            settings.copy(
                providers = listOf(provider().let { it.copy(models = it.models + dedicated) }),
                memoryConsolidationModelId = dedicated.id,
                memoryConsolidationPrompt = "Custom librarian instructions",
            )
        })
        val conversation = conversation(assistant, 2)
        conversations[conversation.id] = conversation
        worker.consolidate(assistant.id.toString(), setOf(conversation.id), fullReview = false)
        worker.consolidate(assistant.id.toString(), emptySet(), fullReview = true)
        assertEquals(2, agent.requests.size)
        agent.requests.forEach { request ->
            assertEquals(dedicated.id, request.model.id)
            assertEquals("Custom librarian instructions", request.settings.memoryConsolidationPrompt)
        }
    }

    @Test
    fun `missing dedicated model falls back to fast then assistant chat model`() = runBlocking {
        val chat = Model(id = Uuid.random(), displayName = "chat")
        val assistant = assistant().copy(chatModelId = chat.id)
        for (fastModel in listOf(true, false)) {
            val worker = consolidator(listOf(assistant), configure = { settings ->
                settings.copy(
                    providers = listOf(provider().let { it.copy(models = it.models + chat) }),
                    memoryConsolidationModelId = Uuid.random(),
                    fastModelId = if (fastModel) modelId else null,
                )
            })
            worker.consolidate(assistant.id.toString(), emptySet(), fullReview = true)
            assertEquals(if (fastModel) modelId else chat.id, agent.requests.last().model.id)
        }
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
        assertFalse("改类默认关闭 ⇒ 请求里必须是 false", agent.requests.single().allowKindChange)
        assertTrue("正文里要带上新轮次", agent.requests.single().transcript.contains("第 3 小时说的话"))
        assertEquals(now, run?.finishedAt)
    }

    @Test
    fun `shared library requires every participant to allow kind changes`() = runBlocking {
        // 共享库时不能由单个助手替别人放宽纪律：全开才允许，混开就退回关闭。
        val on = assistant(name = "on", reclassify = true, globalMemory = true)
        val alsoOn = assistant(name = "also-on", reclassify = true, globalMemory = true)
        val off = assistant(name = "off", reclassify = false, globalMemory = true)
        val globalId = app.androidrik.rikkahub.data.repository.MemoryRepository.GLOBAL_MEMORY_ID

        val first = conversation(on, 2)
        conversations[first.id] = first
        consolidator(listOf(on)).consolidate(globalId, setOf(first.id), fullReview = true)
        assertTrue("唯一助手开着 ⇒ 允许改类", agent.requests.single().allowKindChange)

        val second = conversation(off, 3)
        conversations[second.id] = second
        consolidator(listOf(on, off)).consolidate(globalId, setOf(second.id), fullReview = true)
        assertFalse("任一相关助手关闭 ⇒ 整库退回关闭", agent.requests.last().allowKindChange)

        val third = conversation(alsoOn, 4)
        conversations[third.id] = third
        consolidator(listOf(on, alsoOn)).consolidate(globalId, setOf(third.id), fullReview = true)
        assertTrue("全部相关助手开启 ⇒ 允许改类", agent.requests.last().allowKindChange)

        // 未开整理 / 已停用的共享助手不参与本次整理，其不可见的开关不能否决：
        // 否则用户在当前助手打开开关也会被永远隐藏的 false 静默挡掉。
        val consolidatingOff = assistant(since = 0L, name = "consolidation-off", globalMemory = true)
        val disabled = assistant(name = "disabled", globalMemory = true).copy(enabled = false)
        val fourth = conversation(on, 5)
        conversations[fourth.id] = fourth
        consolidator(listOf(on, consolidatingOff, disabled))
            .consolidate(globalId, setOf(fourth.id), fullReview = true)
        assertTrue("非参与者不投票 ⇒ 仍按真正参与者允许", agent.requests.last().allowKindChange)
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
    fun `manual page budget scales with the model window`() {
        // 用户 2026-10-16 定的一档：上限 512K、输入占 60%，目的是**一轮装完全库**
        // （真机库 ≈30–58K token，固定 12K 要分 2–3 轮）。
        assertEquals("200K 窗口 ⇒ 60%（再扣掉新轮次与校准表）", 110_000, manualLibraryBudget(200_000))
        assertEquals("1M 窗口被 512K 封顶 ⇒ 512K×60% 再扣", 297_200, manualLibraryBudget(1_000_000))
        assertEquals("窗口大小不同，页预算必须不同", 
            manualLibraryBudget(200_000) > manualLibraryBudget(32_000), true)
        // 小窗口：60% 扣完可能为负 ⇒ 兜底，不能让库预算变成 0（那样全量复核会永远空转）
        assertTrue("小窗口要有兜底", manualLibraryBudget(8_000) >= 4_000)
        assertTrue("兜底也不许超过窗口本身", manualLibraryBudget(8_000) <= 8_000)
    }

    @Test
    fun `a large window reviews the whole library in one round`() = runBlocking {
        // 「减少轮次」的可证伪形式：窗口放开后，一次手动复核应当走完整个库（isLast）。
        val assistant = assistant()
        val conversation = conversation(assistant, 2)
        conversations[conversation.id] = conversation
        memoryList += (1..30).map {
            app.androidrik.rikkahub.data.model.AssistantMemory(
                id = "mem$it",
                content = "记忆内容。".repeat(600) + " #$it", // 每条约 3.6K 字符
                sortKey = it,
            )
        }
        // 用 1M 窗口的模型：手动页预算被 512K 封顶 ⇒ ≈297K ⇒ 36K 字符的库一轮装完
        val worker = consolidator(listOf(assistant), contextLength = 1_000_000)

        val run = worker.consolidate(assistant.id.toString(), setOf(conversation.id), fullReview = true)

        assertTrue("一次复核要覆盖全库（这就是「减少轮次」）", run?.libraryPageDone == true)
        assertEquals("全库都被看到", 30, run?.libraryReviewed)
        assertEquals("走完一轮要从头开始", 0L, cursorDAO.rows[assistant.id.toString()] ?: 0L)
    }

    @Test
    fun `automatic runs do not consume the library review progress`() = runBlocking {
        // 真机日志（21:45:45）抓到的设计错误：
        //   page=30-30/52 last=false remaining=274 fullReview=false
        // 自动路径页预算只有 4K，一条大记忆就吃满 ⇒ 每次自动整理只推进一条记录，
        // 52 条要 52 次付费的自动调用才走完一遍。复核进度必须由手动的 12K 页独占。
        val assistant = assistant()
        val conversation = conversation(assistant, 2)
        conversations[conversation.id] = conversation
        memoryList += (1..30).map {
            app.androidrik.rikkahub.data.model.AssistantMemory(
                id = "mem$it",
                content = "记忆内容够长以逼出分页。".repeat(400) + " #$it",
                sortKey = it,
                revision = it.toLong() + 3,
            )
        }
        // **必须另开一个会话**：手动那次已经把 conversation 的水位推过去了，
        // 复用同一个会话时自动那次会因为「没有新正文」提前 return —— 那样测试是空的
        // （第一版就是这么写的，证伪时它没变红，才发现）。
        val other = conversation(assistant, 2)
        conversations[other.id] = other
        val worker = consolidator(listOf(assistant))
        val memoryId = assistant.id.toString()

        // 先手动复核一次，把游标推到中途
        worker.consolidate(memoryId, setOf(conversation.id), fullReview = true)
        val afterManual = cursorDAO.rows[memoryId]
        assertTrue("手动复核要推进游标", (afterManual ?: 0L) > 0L)

        // 再跑自动整理：库快照照给，但游标不许动
        worker.consolidate(memoryId, setOf(other.id), fullReview = false)
        assertEquals(
            "自动整理不许消耗复核进度（否则就是拿付费的自动调用按条爬库）",
            afterManual,
            cursorDAO.rows[memoryId],
        )
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
                revision = it.toLong() + 3,
            )
        }
        val worker = consolidator(listOf(assistant))

        worker.consolidate(assistant.id.toString(), setOf(conversation.id), fullReview = true)

        val request = agent.requests.single()
        val listedIds = Regex("mem[0-9]+").findAll(request.calibrationTable).map { it.value }.toSet()
        assertTrue("校准表非空", listedIds.isNotEmpty())
        assertEquals("Observed targets must be exactly this rendered page", listedIds, request.initialRevisions.keys)
        assertEquals(
            memoryList.filter { it.id in listedIds }.associate { it.id to it.revision },
            request.initialRevisions,
        )
        request.initialRevisions.forEach { (id, revision) ->
            assertTrue("Every observed record needs visible full text", request.libraryPrompt.contains(id))
            assertTrue(request.libraryPrompt.contains("\"revision\": $revision"))
        }
        assertTrue(
            "校准表不许列出读不到正文的记录（本页 30 条里只应出现一部分）：${listedIds.size}",
            listedIds.size < 30,
        )
    }

    @Test
    fun `review request exposes task lifecycle consistently in snapshot and calibration`() = runBlocking {
        val assistant = assistant(modifyApproval = true)
        val chat = conversation(assistant, 2)
        conversations[chat.id] = chat
        memoryList += AssistantMemory(
            id = "completed-task", content = "deliver assignment", revision = 9L,
            kind = app.androidrik.rikkahub.data.model.MemoryKind.TASK,
            taskStatus = app.androidrik.rikkahub.data.model.MemoryTaskStatus.DONE,
            dueAt = millis(1),
        )
        consolidator(listOf(assistant)).consolidate(assistant.id.toString(), setOf(chat.id), fullReview = true)
        val request = agent.requests.single()
        assertTrue(request.proposeOnly)
        assertEquals(mapOf("completed-task" to 9L), request.initialRevisions)
        assertTrue(request.libraryPrompt.contains("\"kind\": \"task\""))
        assertTrue(request.libraryPrompt.contains("\"task_status\": \"done\""))
        assertTrue(request.libraryPrompt.contains("\"due\": \"2026-01-01\""))
        assertTrue(request.calibrationTable.contains("kind task; status done; due 2026-01-01"))
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
    fun `manual historical candidates reach the model with the same baseline`() = runBlocking {
        val assistant = assistant(since = millis(10), modifyApproval = true)
        val old = conversation(assistant, 1, 2)
        conversations[old.id] = old
        dao.candidates = listOf(old.id.toString())
        val worker = consolidator(listOf(assistant))

        assertTrue(worker.findPending(assistant.id.toString(), manual = false).isEmpty())
        val selected = worker.findPending(assistant.id.toString(), manual = true)
        assertEquals(2, selected.single().turns)
        worker.consolidate(assistant.id.toString(), selected.map { it.conversationId }.toSet(), fullReview = true)

        assertTrue(agent.requests.single().transcript.contains("第 1 小时说的话"))
        assertTrue(agent.requests.single().transcript.contains("第 2 小时说的话"))
        assertEquals(millis(2), dao.progress[old.id.toString()])
    }

    @Test
    fun `manual backfill still respects already consumed messages`() = runBlocking {
        val assistant = assistant(since = millis(10))
        val old = conversation(assistant, 1, 2)
        conversations[old.id] = old
        dao.progress[old.id.toString()] = millis(1)
        consolidator(listOf(assistant)).consolidate(assistant.id.toString(), setOf(old.id), fullReview = true)
        assertFalse(agent.requests.single().transcript.contains("第 1 小时说的话"))
        assertTrue(agent.requests.single().transcript.contains("第 2 小时说的话"))
    }

    @Test
    fun `proposal storage failure keeps evidence retryable and preserves old pending`() = runBlocking {
        val assistant = assistant(modifyApproval = true)
        val chat = conversation(assistant, 1, 2)
        conversations[chat.id] = chat
        proposalStore.rows += MemoryConsolidationProposal("create", content = "previous", id = 77)
        agent.outcome = MemoryConsolidationOutcome(0, listOf(MemoryConsolidationProposal("create", content = "new")))
        proposalStore.failure = IllegalStateException("storage failed")
        val worker = consolidator(listOf(assistant))
        try {
            worker.consolidate(assistant.id.toString(), setOf(chat.id), fullReview = true)
            org.junit.Assert.fail("storage failure must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("storage failed", expected.message)
        }
        assertTrue(dao.progress.isEmpty())
        assertTrue(cursorDAO.rows.isEmpty())
        assertEquals("previous", proposalStore.rows.single().content)

        proposalStore.failure = null
        val run = worker.consolidate(assistant.id.toString(), setOf(chat.id), fullReview = true)!!
        assertEquals(millis(2), dao.progress[chat.id.toString()])
        assertEquals("new", run.proposals.single().content)
        assertTrue(run.proposals.single().id > 0)
        assertEquals(proposalStore.rows, run.proposals)
    }

    @Test
    fun `cursor failure rolls back proposals and conversation progress together`() = runBlocking {
        val assistant = assistant(modifyApproval = true)
        val chat = conversation(assistant, 1, 2)
        conversations[chat.id] = chat
        dao.progress[chat.id.toString()] = millis(1)
        cursorDAO.rows[assistant.id.toString()] = 99L
        proposalStore.rows += MemoryConsolidationProposal("create", content = "previous", id = 77)
        agent.outcome = MemoryConsolidationOutcome(0, listOf(MemoryConsolidationProposal("create", content = "new")))
        cursorDAO.failure = IllegalStateException("cursor failed")
        try {
            consolidator(listOf(assistant)).consolidate(assistant.id.toString(), setOf(chat.id), fullReview = true)
            org.junit.Assert.fail("cursor failure must propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("cursor failed", expected.message)
        }
        assertEquals(1, transactionCalls)
        assertEquals(millis(1), dao.progress[chat.id.toString()])
        assertEquals(99L, cursorDAO.rows[assistant.id.toString()])
        assertEquals("previous", proposalStore.rows.single().content)
    }

    @Test
    fun `cancellation during proposal persistence does not consume evidence`() = runBlocking {
        val assistant = assistant(modifyApproval = true)
        val chat = conversation(assistant, 1)
        conversations[chat.id] = chat
        agent.outcome = MemoryConsolidationOutcome(0, listOf(MemoryConsolidationProposal("create", content = "new")))
        proposalStore.failure = kotlinx.coroutines.CancellationException("cancelled commit")
        try {
            consolidator(listOf(assistant)).consolidate(assistant.id.toString(), setOf(chat.id), fullReview = true)
            org.junit.Assert.fail("cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertTrue(dao.progress.isEmpty())
            assertTrue(cursorDAO.rows.isEmpty())
            assertTrue(proposalStore.rows.isEmpty())
        }
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
    var rows = mutableListOf<MemoryConsolidationProposal>()
    var failure: Exception? = null
    private var nextId = 1L

    override suspend fun savePending(memoryId: String, proposals: List<MemoryConsolidationProposal>) {
        if (proposals.isEmpty()) return
        saved += memoryId to proposals
        rows = proposals.map { it.copy(id = nextId++) }.toMutableList()
        failure?.let { throw it }
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
    var failure: Exception? = null
    override suspend fun getCursor(memoryId: String): Long? = rows[memoryId]
    override suspend fun upsert(entry: app.androidrik.rikkahub.data.db.entity.MemoryReviewCursorEntity) {
        rows[entry.memoryId] = entry.cursor
        failure?.let { throw it }
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
