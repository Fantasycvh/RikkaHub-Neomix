package app.androidrik.rikkahub.data.repository

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.MEMORY_CONSOLIDATION_ALL_HISTORY
import app.androidrik.rikkahub.data.ai.memoryConsolidationEnabled
import app.androidrik.rikkahub.data.ai.memoryId
import app.androidrik.rikkahub.data.db.dao.MemoryConsolidationDAO
import app.androidrik.rikkahub.data.db.entity.MemoryConsolidationEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 做梦（V3）**数据面**的单测：门控四道、基线取法、粗筛 + 精算、记忆库分组。
 *
 * 用内存 fake DAO（对齐 SQL 的过滤语义）+ 假的"载入会话"，不依赖 Android/Room——
 * 与 [MemoryRepositoryTest] 同款。纯策略本身见 [app.androidrik.rikkahub.data.ai.MemoryConsolidationPolicyTest]。
 */
class MemoryConsolidationRepositoryTest {

    private val zone = TimeZone.currentSystemDefault()

    /** 固定基准时刻（相对时间就够：所有判定都是"谁更晚"） */
    private fun at(hours: Int): LocalDateTime =
        LocalDateTime.parse("2026-01-01T${hours.toString().padStart(2, '0')}:00")

    private fun millis(hours: Int): Long = at(hours).toInstant(zone).toEpochMilliseconds()

    /**
     * 内存进度表 + 候选查询，**过滤语义对齐 `MemoryConsolidationDAO` 的 SQL**：
     * `末尾消息时间 > MAX(进度, 基线)`，倒序，LIMIT。
     */
    private class FakeConsolidationDAO : MemoryConsolidationDAO {
        /** assistantId -> (conversationId -> 末尾消息时间) */
        val lastActivity = mutableMapOf<String, MutableMap<String, Long>>()
        val progress = mutableMapOf<String, Long>()

        /** 每次候选查询用的 (assistantId, 基线)，用来断言基线取的是「最晚开启」 */
        val queriedBaselines = mutableListOf<Pair<String, Long>>()

        fun remember(assistantId: String, conversationId: String, lastActivityAt: Long) {
            lastActivity.getOrPut(assistantId) { mutableMapOf() }[conversationId] = lastActivityAt
        }

        override suspend fun getConsolidatedAt(conversationId: String): Long? = progress[conversationId]

        override suspend fun getProgress(conversationId: String): MemoryConsolidationEntity? =
            progress[conversationId]?.let { MemoryConsolidationEntity(conversationId, it) }

        override suspend fun upsertProgress(progress: MemoryConsolidationEntity) {
            this.progress[progress.conversationId] = progress.consolidatedAt
        }

        override suspend fun deleteProgress(conversationId: String) {
            progress.remove(conversationId)
        }

        /** 等价于 SQL 的 `MAX(consolidated_at)` over 该助手的会话：频次上限的近似口径 */
        override suspend fun getLastConsolidatedAt(assistantId: String): Long? =
            lastActivity[assistantId].orEmpty().keys
                .mapNotNull { progress[it] }
                .maxOrNull()

        override suspend fun getCandidateConversationIds(
            assistantId: String,
            baseline: Long,
            limit: Int,
        ): List<String> {
            queriedBaselines += assistantId to baseline
            return lastActivity[assistantId].orEmpty()
                .filter { (id, last) -> last > maxOf(baseline, progress[id] ?: 0L) }
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
                .take(limit)
        }
    }

    private val dao = FakeConsolidationDAO()
    private val conversations = mutableMapOf<Uuid, Conversation>()

    private fun repository(
        assistants: List<Assistant>,
        limitPerRun: Int = MemoryConsolidationRepository.DEFAULT_LIMIT_PER_RUN,
        now: Long = millis(23),
    ) = MemoryConsolidationRepository(
        consolidationDAO = dao,
        assistants = { assistants },
        conversationLoader = { id -> conversations[id] },
        limitPerRun = limitPerRun,
        now = { now },
    )

    private fun assistant(
        since: Long = millis(0),
        useGlobalMemory: Boolean = false,
        enableMemory: Boolean = true,
        enabled: Boolean = true,
    ) = Assistant(
        id = Uuid.random(),
        name = "a",
        enabled = enabled,
        enableMemory = enableMemory,
        useGlobalMemory = useGlobalMemory,
        memoryConsolidationSince = since,
    )

    /** [hours] 是每轮消息的时间（相对 [base] 的小时数） */
    private fun conversation(assistant: Assistant, vararg hours: Int, memoryDisabled: Boolean = false): Conversation =
        Conversation(
            assistantId = assistant.id,
            messageNodes = hours.map { hour ->
                MessageNode(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Text("turn")),
                            createdAt = at(hour),
                        )
                    )
                )
            },
            memoryDisabled = memoryDisabled,
        )

    private fun remember(assistant: Assistant, conversation: Conversation, lastActivityAt: Long) {
        conversations[conversation.id] = conversation
        dao.remember(assistant.id.toString(), conversation.id.toString(), lastActivityAt)
    }

    @Test
    fun `pendingOf stays null unless every gate is open`() = runBlocking {
        val off = assistant(since = 0L)
        val disabled = assistant(enabled = false)
        val noMemory = assistant(enableMemory = false)
        val on = assistant()

        listOf(off, disabled, noMemory).forEach { assistant ->
            val conversation = conversation(assistant, 1)
            assertNull(
                "门控未开必须返回 null（否则调度器会真的去整理）",
                repository(listOf(assistant)).pendingOf(conversation),
            )
        }

        val conversation = conversation(on, 1)
        assertEquals(1, repository(listOf(on)).pendingOf(conversation)?.turns)
    }

    @Test
    fun `pendingOf skips incognito conversations`() = runBlocking {
        val assistant = assistant()
        val conversation = conversation(assistant, 1, memoryDisabled = true)
        assertNull("隐身对话（不注入 + 不装配工具）也不该被整理", repository(listOf(assistant)).pendingOf(conversation))
    }

    @Test
    fun `pendingOf returns null when the assistant is not in settings`() = runBlocking {
        val assistant = assistant()
        val conversation = conversation(assistant, 1)
        assertNull(repository(emptyList()).pendingOf(conversation))
    }

    @Test
    fun `pendingOf counts only turns newer than the switch-on moment`() = runBlocking {
        val assistant = assistant(since = millis(3))
        // 3 轮在开启之前，2 轮在开启之后
        val conversation = conversation(assistant, 1, 2, 3, 4, 5)

        val pending = repository(listOf(assistant)).pendingOf(conversation)
        assertEquals("只对开启之后的新内容负责", 2, pending?.turns)
        assertEquals(conversation.id, pending?.conversationId)
        assertEquals(assistant.id.toString(), pending?.memoryId)
    }

    @Test
    fun `progress pushes the baseline past the switch-on moment`() = runBlocking {
        val assistant = assistant(since = millis(0))
        val conversation = conversation(assistant, 1, 2, 3)
        remember(assistant, conversation, lastActivityAt = millis(3))

        val repository = repository(listOf(assistant))
        assertEquals(3, repository.pendingOf(conversation)?.turns)

        // 整理完（进度推进到第 3 小时）之后，前 3 轮不再算待整理
        repository.markConsolidated(conversation, now = millis(3))
        assertNull(repository.pendingOf(conversation))

        // 又聊了一轮 ⇒ 只有这一轮待整理
        val grown = conversation(assistant, 1, 2, 3, 4).copy(id = conversation.id)
        assertEquals(1, repository(listOf(assistant)).pendingOf(grown)?.turns)
    }

    @Test
    fun `pendingOf reports the shared store for global memory`() = runBlocking {
        val assistant = assistant(useGlobalMemory = true)
        val pending = repository(listOf(assistant)).pendingOf(conversation(assistant, 1))
        assertEquals(MemoryRepository.GLOBAL_MEMORY_ID, pending?.memoryId)
    }

    @Test
    fun `markConsolidated writes the progress row`() = runBlocking {
        val assistant = assistant()
        val conversation = conversation(assistant, 1)
        val repository = repository(listOf(assistant))

        repository.markConsolidated(conversation, now = millis(7))
        assertEquals(millis(7), dao.progress[conversation.id.toString()])
        assertEquals(millis(7), dao.getProgress(conversation.id.toString())?.consolidatedAt)
    }

    @Test
    fun `memoryIdsWithUpdates lists only stores with candidates`() = runBlocking {
        val withUpdates = assistant(since = millis(0))
        // 这个助手的会话最后活跃在开启之前 ⇒ 不是候选
        val quiet = assistant(since = millis(5))
        val quietConversation = conversation(quiet, 1)
        remember(quiet, quietConversation, lastActivityAt = millis(1))

        val repository = repository(listOf(withUpdates, quiet))
        assertTrue("没有任何候选 ⇒ 不列出来（启动补漏只查有动静的库）", repository.memoryIdsWithUpdates().isEmpty())

        val busyConversation = conversation(withUpdates, 2)
        remember(withUpdates, busyConversation, lastActivityAt = millis(2))
        assertEquals(listOf(withUpdates.id.toString()), repository.memoryIdsWithUpdates())
    }

    @Test
    fun `findPending drops coarse hits that have no new turns`() = runBlocking {
        val assistant = assistant(since = millis(5))
        // 末尾消息时间很晚（粗筛命中），但消息都在开启之前（精算为 0：
        // 例如恢复了旧库、或只是把标题改了）
        val stale = conversation(assistant, 1, 2)
        remember(assistant, stale, lastActivityAt = millis(9))

        assertTrue(repository(listOf(assistant)).findPending(assistant.id.toString()).isEmpty())
        assertTrue("精算为 0 时不许写进度行（写了就把没整理的内容标记成整理过了）", dao.progress.isEmpty())
    }

    @Test
    fun `findPending is capped and prefers the most recent conversations`() = runBlocking {
        val assistant = assistant()
        val old = conversation(assistant, 1)
        val middle = conversation(assistant, 2)
        val recent = conversation(assistant, 3)
        remember(assistant, old, lastActivityAt = millis(1))
        remember(assistant, middle, lastActivityAt = millis(2))
        remember(assistant, recent, lastActivityAt = millis(3))

        val pending = repository(listOf(assistant), limitPerRun = 2).findPending(assistant.id.toString())
        assertEquals(listOf(recent.id, middle.id), pending.map { it.conversationId })
    }

    @Test
    fun `findPending merges every assistant of a shared store using the latest switch-on`() = runBlocking {
        val early = assistant(since = millis(0), useGlobalMemory = true)
        val late = assistant(since = millis(3), useGlobalMemory = true)
        val earlyConversation = conversation(early, 1, 2)
        val lateConversation = conversation(late, 4)
        remember(early, earlyConversation, lastActivityAt = millis(2))
        remember(late, lateConversation, lastActivityAt = millis(4))

        val pending = repository(listOf(early, late)).findPending(MemoryRepository.GLOBAL_MEMORY_ID)

        assertEquals(
            "共享库一次覆盖所有参与的助手，且基线取最晚开启（早期那个助手的历史不该被倒灌）",
            listOf(lateConversation.id),
            pending.map { it.conversationId },
        )
        assertEquals(
            "两次候选查询都必须带最晚开启时刻",
            listOf(early.id.toString() to millis(3), late.id.toString() to millis(3)),
            dao.queriedBaselines,
        )
    }

    @Test
    fun `findPending ignores stores nobody participates in`() = runBlocking {
        val assistant = assistant()
        assertTrue(repository(listOf(assistant)).findPending("nobody").isEmpty())
        assertTrue("不认识这个库时一次库都不查", dao.queriedBaselines.isEmpty())
    }

    // ---- 自动频次上限（每库 6 小时；手动整理不受约束，见设计稿 §7.3）----

    @Test
    fun `auto paths stay quiet inside the minimum interval while manual ignores it`() = runBlocking {
        val assistant = assistant(since = millis(0))
        // 第 9 轮还没整理（进度停在第 8 小时），而「现在」是第 9 小时 ⇒ 间隔只过了 1 小时
        val conversation = conversation(assistant, 9)
        remember(assistant, conversation, lastActivityAt = millis(9))
        dao.progress[conversation.id.toString()] = millis(8)

        val repository = repository(listOf(assistant), now = millis(9))
        assertNull("间隔内不再自动排新活（否则活跃会话每 2 分钟烧一次模型）", repository.pendingOf(conversation))
        assertTrue("启动补漏也不排", repository.memoryIdsWithUpdates().isEmpty())
        assertTrue("自动扫库为空（这条路径不判的话限流形同虚设）", repository.findPending(assistant.id.toString()).isEmpty())
        assertEquals(
            "手动整理不受频次上限约束（用户显式点击即授权）",
            1,
            repository.findPending(assistant.id.toString(), manual = true).size,
        )
    }

    @Test
    fun `auto paths resume after the interval has passed`() = runBlocking {
        val assistant = assistant(since = millis(0))
        val conversation = conversation(assistant, 9)
        remember(assistant, conversation, lastActivityAt = millis(9))
        // 上次整理在第 1 小时，距「现在」8 小时 > 6 小时
        dao.progress[conversation.id.toString()] = millis(1)

        val repository = repository(listOf(assistant), now = millis(9))
        assertEquals(1, repository.pendingOf(conversation)?.turns)
        assertEquals(listOf(assistant.id.toString()), repository.memoryIdsWithUpdates())
        assertEquals(1, repository.findPending(assistant.id.toString()).size)
    }

    // ---- 存量历史整理：手动整理丢掉「开启时刻」那半基线 ----
    // 见 docs/design/design-memory-retrieval-and-consolidation.md §4。
    // 契约（本轮定案）：**开关仍是总闸**（自动与手动都要它开着），但手动整理不再受
    // 「开启时刻」约束 —— 它被定义为「所有还没整理过的轮次，不论多老」。

    @Test
    fun `manual run reaches history older than the switch-on moment`() = runBlocking {
        val assistant = assistant(since = millis(10))
        val old = conversation(assistant, 1, 2, 3) // 三轮全部早于开启时刻
        remember(assistant, old, lastActivityAt = millis(3))
        val repository = repository(listOf(assistant))

        assertTrue(
            "自动路径必须看不见开启之前的历史 —— 否则拨开开关就会把几百个历史会话灌进队列",
            repository.findPending(assistant.memoryId(), manual = false).isEmpty(),
        )
        val manual = repository.findPending(assistant.memoryId(), manual = true)
        assertEquals("手动整理要能到达全部历史", 1, manual.size)
        assertEquals(3, manual.first().turns)
    }

    @Test
    fun `manual run still honours the per-conversation progress row`() = runBlocking {
        val assistant = assistant(since = millis(10))
        val old = conversation(assistant, 1, 2, 3)
        remember(assistant, old, lastActivityAt = millis(3))
        // 已整理到第 2 小时 ⇒ 只剩第 3 轮
        dao.progress[old.id.toString()] = millis(2)

        val manual = repository(listOf(assistant)).findPending(assistant.memoryId(), manual = true)
        assertEquals(1, manual.size)
        assertEquals("进度行仍生效：不把已整理过的轮次再灌一遍", 1, manual.first().turns)
    }

    @Test
    fun `all-history sentinel keeps the switch on and covers pre-switch turns`() = runBlocking {
        val assistant = assistant(since = MEMORY_CONSOLIDATION_ALL_HISTORY)
        assertTrue(
            "哨兵必须 > 0 —— 用 0 表示「全部历史」会与「关闭」撞车（见常量 kdoc）",
            assistant.memoryConsolidationEnabled(),
        )
        val ancient = conversation(assistant, 1, 2)
        remember(assistant, ancient, lastActivityAt = millis(2))

        val auto = repository(listOf(assistant)).findPending(assistant.memoryId(), manual = false)
        assertEquals("选「全部历史」后连自动路径也能覆盖开启之前的轮次", 1, auto.size)
        assertEquals(2, auto.first().turns)
    }

    @Test
    fun `manual tidy still requires the feature switch to be on`() = runBlocking {
        // 刻意保留的决定：手动整理**不走**开关后门。开关关着 = 这个助手根本不用整理后的记忆，
        // 此时替它整理只会写出一批没人要的记忆。要整理就先拨开开关（可选「全部历史」）。
        val off = assistant(since = 0L)
        val conversation = conversation(off, 1, 2)
        remember(off, conversation, lastActivityAt = millis(2))
        assertTrue(
            "开关关闭时手动整理也必须为空（门控是总闸）",
            repository(listOf(off)).findPending(off.memoryId(), manual = true).isEmpty(),
        )
    }
}
