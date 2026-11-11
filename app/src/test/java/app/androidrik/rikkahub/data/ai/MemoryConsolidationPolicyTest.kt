package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.repository.MemoryRepository
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * 做梦（V3）**纯策略**的单测：门控、基线、轮数、记忆库 id。
 * 带数据库的那一半见 [app.androidrik.rikkahub.data.repository.MemoryConsolidationRepositoryTest]。
 */
class MemoryConsolidationPolicyTest {

    private val zone = TimeZone.currentSystemDefault()

    /** 固定基准时刻（不用 `now()`：基线判定全是"谁更晚"，绝对时间无关） */
    private fun at(hours: Int): LocalDateTime =
        LocalDateTime.parse("2026-01-01T${hours.toString().padStart(2, '0')}:00")

    private fun millis(hours: Int): Long = at(hours).toInstant(zone).toEpochMilliseconds()

    private fun assistant(
        enabled: Boolean = true,
        enableMemory: Boolean = true,
        since: Long = 1_000L,
        useGlobalMemory: Boolean = false,
    ) = Assistant(
        id = Uuid.random(),
        name = "a",
        enabled = enabled,
        enableMemory = enableMemory,
        useGlobalMemory = useGlobalMemory,
        memoryConsolidationSince = since,
    )

    private fun node(vararg parts: UIMessagePart.Text, at: LocalDateTime): MessageNode = MessageNode(
        messages = listOf(UIMessage(role = MessageRole.USER, parts = parts.toList(), createdAt = at)),
    )

    private fun conversation(vararg hours: Int, memoryDisabled: Boolean = false): Conversation = Conversation(
        assistantId = Uuid.random(),
        messageNodes = hours.map { node(UIMessagePart.Text("turn"), at = at(it)) },
        memoryDisabled = memoryDisabled,
    )

    @Test
    fun `consolidation needs all three switches`() {
        assertTrue("三道闸全开才算开启", assistant().memoryConsolidationEnabled())
        assertFalse("停用的助手不该在后台被整理", assistant(enabled = false).memoryConsolidationEnabled())
        assertFalse("没开记忆就没有可整理的库", assistant(enableMemory = false).memoryConsolidationEnabled())
        assertFalse("开关没拨开（since = 0）", assistant(since = 0L).memoryConsolidationEnabled())
    }

    @Test
    fun `baseline is the later of switch-on time and progress`() {
        assertEquals("进度更晚 ⇒ 用进度", 500L, memoryConsolidationBaseline(consolidationSince = 100L, consolidatedAt = 500L))
        assertEquals("开关更晚 ⇒ 用开关（刚开启不倒灌历史）", 500L, memoryConsolidationBaseline(consolidationSince = 500L, consolidatedAt = 100L))
        assertEquals("没有进度行 ⇒ 只剩开关时刻", 100L, memoryConsolidationBaseline(consolidationSince = 100L, consolidatedAt = 0L))
    }

    @Test
    fun `turns count only messages newer than the baseline`() {
        val conversation = conversation(1, 2, 3)

        assertEquals("基线之前的都不算", 0, unconsolidatedTurns(conversation, baseline = millis(10)))
        assertEquals("恰好等于基线的不算（严格晚于）", 2, unconsolidatedTurns(conversation, baseline = millis(1)))
        assertEquals("基线为 0 ⇒ 全部轮次", 3, unconsolidatedTurns(conversation, baseline = 0L))
    }

    @Test
    fun `a node counts once even with several new messages in it`() {
        // 一轮里的多条消息（用户 + 助手）只算一轮：节点才是"轮"的单位
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                MessageNode(
                    messages = listOf(
                        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("q")), createdAt = at(5)),
                        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("a")), createdAt = at(6)),
                    )
                )
            ),
        )
        assertEquals(1, unconsolidatedTurns(conversation, baseline = millis(1)))
    }

    @Test
    fun `an empty node does not blow up the counting`() {
        // 候选损坏时节点可能是空的（见 NodeCandidateRepairTest 那套修复）：后台路径不许因此抛异常
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = emptyList()), node(UIMessagePart.Text("turn"), at = at(5))),
        )
        assertEquals(1, unconsolidatedTurns(conversation, baseline = millis(1)))
    }

    @Test
    fun `global memory resolves to the shared store id`() {
        val own = assistant()
        assertEquals(own.id.toString(), own.memoryId())

        val shared = assistant(useGlobalMemory = true)
        assertEquals(MemoryRepository.GLOBAL_MEMORY_ID, shared.memoryId())
    }

    /**
     * 助手是 DataStore 里的 JSON：老数据里**没有** `memoryConsolidationSince` 这个键，
     * 反序列化必须落到默认值 0（关）——否则升级后所有助手会静默开始后台整理。
     */
    @Test
    fun `an assistant json without the new key stays off`() {
        val json = Json { ignoreUnknownKeys = true }
        val assistant = json.decodeFromString<Assistant>("""{"id":"${Uuid.random()}","name":"legacy"}""")
        assertEquals("缺字段必须落到 0（关）", 0L, assistant.memoryConsolidationSince)
        assertFalse(assistant.memoryConsolidationEnabled())
    }

    // ---- 触发方式：范围解析（docs/design/design-memory-consolidation-trigger.md §2.3）----

    @Test
    fun `resolveMemoryIds dedupes a shared global store`() {
        val a = assistant(useGlobalMemory = true)
        val b = assistant(useGlobalMemory = true)
        val c = assistant() // 自己的库

        assertEquals(
            "共享 __global__ 的两个助手只算一个库（决议 #5）",
            setOf(MemoryRepository.GLOBAL_MEMORY_ID, c.id.toString()),
            resolveMemoryIds(MemoryTriggerConfig(), listOf(a, b, c)),
        )
    }

    @Test
    fun `resolveMemoryIds honours the scope and drops disabled participants`() {
        val wanted = assistant()
        val unwanted = assistant()
        val off = assistant(since = 0L) // 做梦关着

        assertEquals(
            setOf(wanted.id.toString()),
            resolveMemoryIds(
                MemoryTriggerConfig(scopeAssistantIds = setOf(wanted.id)),
                listOf(wanted, unwanted, off),
            ),
        )
        assertEquals(
            "空集 = 全部（但做梦没开的助手仍不在内）",
            setOf(wanted.id.toString(), unwanted.id.toString()),
            resolveMemoryIds(MemoryTriggerConfig(), listOf(wanted, unwanted, off)),
        )
    }

    @Test
    fun `trigger config defaults reproduce the historical behaviour`() {
        val config = MemoryTriggerConfig()
        assertEquals(MemoryTriggerMode.IDLE, config.mode)
        assertEquals("历史硬编码值：空闲 2 分钟", 2.minutes, config.idleDelay)
        assertEquals("历史硬编码值：攒 6 轮走捷径", 6, config.effectiveEveryNTurns)
        assertTrue("范围默认 = 全部", config.scopeAssistantIds.isEmpty())
    }
}
