package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆**渐进披露**（V2）的注入层测试：全文层 + 清单层。
 *
 * 要根治的事：全文注入有 token 预算，**预算外的记忆此前静默不可见**（模型既不会用、
 * 也不会纠正）。现在它们以清单行露出，模型可按需 `memory_tool action=read` 取全文。
 *
 * 验收点：
 * 1. 预算外条目**在清单里出现**（不再消失）；
 * 2. 两层**不重叠**（同一 id 只出现一次）；
 * 3. 预算口径 = 总预算拆成 3/4 全文 + 1/4 清单；
 * 4. 清单**不套用全文的相关性下限**（套了就等于又静默消失）；
 * 5. 清单顺序按 `sortKey`（写入先后）稳定 ⇒ 不随分数衰减抖动（前缀稳定纪律）。
 */
class MemoryProgressiveDisclosureTest {

    private val now = 1_000_000_000_000L

    private fun memory(
        id: String,
        content: String,
        importance: Float = 0.6f,
        description: String = "",
        sortKey: Int = 0,
        ageDays: Float = 0f,
    ) = AssistantMemory(
        id = id,
        content = content,
        importance = importance,
        description = description,
        sortKey = sortKey,
        updatedAt = now - (ageDays * 24 * 60 * 60 * 1000).toLong(),
    )

    /** 每条约 40 token 的正文，便于按 token 计算预算边界。 */
    private fun bulky(prefix: String, index: Int) = "$prefix-$index " + "内容填充".repeat(20)

    /**
     * 造出「清单非空」的提示词：预算 400 ⇒ 全文层 300（装得下 4 条大记忆）、清单层 100。
     * 全文层按分数取（同分时 sortKey 大的先），清单层按 sortKey 升序 ⇒ 两端互补、互不重叠。
     */
    private fun buildPromptWithIndex(count: Int = 10): String = buildMemoryDataPrompt(
        memories = (1..count).map { i ->
            memory("id$i", bulky("note", i), description = "topic $i", sortKey = i)
        },
        budgetTokens = 400,
        now = now,
    )

    // ---- 核心：预算外条目不再静默消失 ----

    @Test
    fun `memories outside the full text budget still appear in the index`() {
        val prompt = buildPromptWithIndex(count = 10)
        assertTrue("清单段应存在", prompt.contains("**Memory index**"))
        // 全文层按分数取（同分时 sortKey 大的先），清单层按 sortKey 升序 ⇒ 装不进全文层的
        // 正是写入最早的那些，它们必须出现在清单里
        assertTrue("预算外的条目必须在清单里可见", prompt.contains("- id1 |"))
        assertTrue("清单行要带主题词", prompt.contains("topic 1"))
    }

    @Test
    fun `no memory disappears from the whole prompt`() {
        val memories = (1..10).map { i ->
            memory("id$i", bulky("note", i), description = "topic $i", sortKey = i)
        }
        val plan = buildMemoryInjectionPlan(memories, budgetTokens = 400, now = now)
        val visible = (plan.fullText + plan.indexed).map { it.id }.toSet()
        assertEquals(memories.size - visible.size, plan.omittedCount)
        assertTrue(plan.totalEstimatedTokens <= 400)
        assertTrue(plan.omittedCount > 0)
        assertTrue(plan.dataText.contains("**Omitted**: ${plan.omittedCount}"))
        assertTrue("Hidden IDs require search, not guessing an ID", plan.dataText.contains("action:\"search\""))
    }

    @Test
    fun `an id never appears in both tiers`() {
        val memories = (1..8).map { i ->
            memory("id$i", bulky("note", i), description = "topic $i", sortKey = i)
        }
        val prompt = buildMemoryDataPrompt(memories, budgetTokens = 600, now = now)

        val fullTierIds = Regex(""""id(\d+)"""").findAll(prompt).map { it.groupValues[1] }.toList()
        val indexIds = Regex("""- id(\d+) \|""").findAll(prompt).map { it.groupValues[1] }.toList()
        assertTrue("全文层应非空", fullTierIds.isNotEmpty())
        assertTrue("清单层应非空", indexIds.isNotEmpty())
        assertTrue(
            "两层不得重叠（重叠=白花 token 且让模型以为是两条）",
            fullTierIds.intersect(indexIds.toSet()).isEmpty(),
        )
    }

    // ---- 预算口径 ----

    @Test
    fun `index selection is bounded by its own budget`() {
        val memories = (1..40).map { i -> memory("id$i", "short $i", description = "topic $i", sortKey = i) }
        val narrow = selectMemoriesForIndex(memories, excludedIds = emptySet(), budgetTokens = 100, now = now)
        val wide = selectMemoriesForIndex(memories, excludedIds = emptySet(), budgetTokens = 10_000, now = now)

        assertTrue("额度小 ⇒ 清单行少", narrow.size < wide.size)
        assertTrue("额度大应能装下全部", wide.size == memories.size)
        assertTrue("额度为 0 ⇒ 不发清单", selectMemoriesForIndex(memories, emptySet(), 0, now).isEmpty())
    }

    @Test
    fun `index floor is not applied so nothing disappears silently`() {
        // 低重要度且严重老化：全文层的相关性下限会把它排除（这正是「静默消失」的成因）
        val stale = memory("stale", "old note", importance = 0.15f, description = "old topic", ageDays = 400f)
        val fresh = memory("fresh", "new note", importance = 0.9f, sortKey = 1)

        assertEquals(
            "前提：全文层确实会淘汰它",
            emptyList<String>(),
            selectMemoriesForPrompt(listOf(stale, fresh), budgetTokens = 10_000, now = now)
                .filter { it.id == "stale" }.map { it.id },
        )
        assertTrue(
            "清单层不得套用下限——否则被淘汰的记忆再次静默消失",
            selectMemoriesForIndex(listOf(stale, fresh), emptySet(), 10_000, now = now).any { it.id == "stale" },
        )
    }

    // ---- 顺序与稳定性 ----

    @Test
    fun `index is ordered by sortKey not by score`() {
        // 高分但写入晚的放在后面：顺序只跟写入先后有关 ⇒ 分数衰减不改变清单字节
        val memories = listOf(
            memory("late", "content", importance = 0.95f, sortKey = 9),
            memory("early", "content", importance = 0.3f, sortKey = 1),
        )
        val indexed = selectMemoriesForIndex(memories, emptySet(), 10_000, now = now)
        assertEquals(listOf("early", "late"), indexed.map { it.id })
    }

    // ---- 清单行内容 ----

    @Test
    fun `index line falls back to a content excerpt when description is blank`() {
        val memories = listOf(memory("idA", "这是一条没有主题词的记忆内容", sortKey = 1))
        val prompt = indexLine(memories.single())
        assertTrue("缺主题词时用内容截断兜底（不把派生文本写回库里）", prompt.contains("这是一条没有主题词的记忆内容"))
    }

    @Test
    fun `index line carries the update date and marks unknown dates`() {
        // 两条最早写入的记忆（⇒ 进清单层），一条有时间、一条没有，其余填满全文层
        val memories = listOf(
            memory("idA", bulky("a", 1), description = "dated topic", sortKey = 1),
            AssistantMemory(
                id = "idB",
                content = bulky("b", 2),
                description = "no date",
                sortKey = 2,
                updatedAt = 0L,
            ),
        ) + (3..10).map { i -> memory("id$i", bulky("note", i), description = "topic $i", sortKey = i) }
        val prompt = buildMemoryDataPrompt(memories, budgetTokens = 400, now = now)

        assertTrue("清单行要带更新日（陈旧性判断的依据）", prompt.contains("- idA | dated topic | "))
        assertTrue(
            "更新日应是 ISO 日期",
            Regex("- idA \\| dated topic \\| \\d{4}-\\d{2}-\\d{2}").containsMatchIn(prompt),
        )
        assertTrue("时间未知时如实说明，不编日期", prompt.contains("- idB | no date | date unknown"))
    }

    @Test
    fun `index header tells the model to read instead of guessing`() {
        val prompt = buildPromptWithIndex()
        assertTrue(prompt.contains("**Memory index**"))
        assertTrue("必须说明「主题只是提示、要看全文得 read」", prompt.contains("description is only a hint"))
        assertTrue(prompt.contains("action:\"read\""))
    }

    @Test
    fun `empty memory list emits no index section`() {
        val prompt = buildMemoryDataPrompt(emptyList(), budgetTokens = 8_000, now = now)
        assertFalse("没有记忆就不该出现清单段", prompt.contains("**Memory index**"))
    }
}
