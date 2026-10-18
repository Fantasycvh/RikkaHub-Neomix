package app.androidrik.rikkahub.data.ai

import app.androidrik.common.tokenizer.TokenizerRegistry
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.utils.estimateTokens
import app.androidrik.rikkahub.utils.heuristicTokenCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildMemoryInjectionPlan] 的预算/去重/计费测试。
 *
 * 关键口径：断言的是**实际渲染字符串的 token 计数**（discipline/anchor/data 三段），
 * 不是「入选条数」。同样地，凡是出现预算的地方都用 plan 的 [MemoryInjectionPlan.budgetTokens]
 * 与真实三段计数复核，而不是照抄旧 fixture 的数字。
 *
 * 测试**不依赖** tokenizer 是否预热：用 [MemoryInjectionPlan.estimationMode] 选择与 plan
 * 相同的计数器，避免把 heuristic 的期望值写死。
 */
class MemoryInjectionPlanTest {

    private val now = 1_700_000_000_000L

    private fun memory(
        id: String,
        content: String,
        importance: Float = 0.9f,
        description: String = "",
        sortKey: Int = 0,
        isAnchor: Boolean = false,
        revision: Long = 1L,
        updatedAt: Long = now,
    ) = AssistantMemory(
        id = id,
        content = content,
        importance = importance,
        description = description,
        isAnchor = isAnchor,
        sortKey = sortKey,
        updatedAt = updatedAt,
        revision = revision,
    )

    private fun counterOf(mode: String): (String) -> Int =
        if (mode == MEMORY_ESTIMATION_TOKENIZER) {
            { text -> estimateTokens(text) }
        } else {
            { text -> heuristicTokenCount(text) }
        }

    private fun counterFor(plan: MemoryInjectionPlan): (String) -> Int = counterOf(plan.estimationMode)

    /** 三个 token 字段必须等于**对应渲染字符串**的实际计数，且总和不超过预算。 */
    private fun assertExactBilling(plan: MemoryInjectionPlan) {
        val count = counterFor(plan)
        assertEquals("disciplineTokens 必须等于纪律文本的实际计数", plan.disciplineTokens, count(plan.disciplineText))
        assertEquals("anchorTokens 必须等于锚点文本的实际计数", plan.anchorTokens, count(plan.anchorText))
        assertEquals("dataTokens 必须等于尾部文本的实际计数", plan.dataTokens, count(plan.dataText))
        assertEquals(
            "totalEstimatedTokens 必须等于三个字段之和",
            plan.disciplineTokens + plan.anchorTokens + plan.dataTokens,
            plan.totalEstimatedTokens,
        )
        assertTrue(
            "总计费 ${plan.totalEstimatedTokens} 超出预算 ${plan.budgetTokens}",
            plan.totalEstimatedTokens <= maxOf(0, plan.budgetTokens),
        )
        // A notice may itself not fit. Keep the omitted diagnostic, never fabricate a rendered notice.
        if (plan.dataText.isEmpty()) {
            assertTrue(plan.fullText.isEmpty() && plan.indexed.isEmpty())
        } else {
            assertEquals(
                renderMemoryDataPrompt(plan.fullText, plan.indexed, plan.omittedCount),
                plan.dataText,
            )
        }
    }

    // ---- 1. 空库 ----

    @Test
    fun `empty library yields a fully empty plan`() {
        val plan = buildMemoryInjectionPlan(emptyList(), budgetTokens = 8_000, now = now)

        assertTrue(plan.disciplineText.isEmpty())
        assertTrue(plan.anchorText.isEmpty())
        assertTrue(plan.dataText.isEmpty())
        assertTrue(plan.anchors.isEmpty())
        assertTrue(plan.fullText.isEmpty())
        assertTrue(plan.indexed.isEmpty())
        assertEquals(0, plan.omittedCount)
        assertEquals(0, plan.totalEstimatedTokens)
        assertEquals(8_000, plan.budgetTokens)
        assertTrue(
            plan.estimationMode == MEMORY_ESTIMATION_TOKENIZER || plan.estimationMode == MEMORY_ESTIMATION_HEURISTIC,
        )
    }

    // ---- 2. 非正预算 ----

    @Test
    fun `non positive budgets yield no memory blocks`() {
        val memories = listOf(memory("id1", "content one", sortKey = 1))
        listOf(0, -1, -100).forEach { budget ->
            val plan = buildMemoryInjectionPlan(memories, budgetTokens = budget, now = now)
            assertTrue("budget=$budget", plan.disciplineText.isEmpty())
            assertTrue("budget=$budget", plan.anchorText.isEmpty())
            assertTrue("budget=$budget", plan.dataText.isEmpty())
            assertTrue(
                "budget=$budget",
                plan.anchors.isEmpty() && plan.fullText.isEmpty() && plan.indexed.isEmpty(),
            )
            assertEquals("budget=$budget", 0, plan.totalEstimatedTokens)
            assertEquals(budget, plan.budgetTokens)
            assertExactBilling(plan)
        }
    }

    // ---- 3. 预算连 L0 都装不下 / 刚好等于 L0 ----

    @Test
    fun `budget below discipline is empty and exactly discipline fits only L0`() {
        val mode = if (TokenizerRegistry.isReady) MEMORY_ESTIMATION_TOKENIZER else MEMORY_ESTIMATION_HEURISTIC
        val count = counterOf(mode)
        val discipline = buildMemoryDisciplinePrompt()
        val disciplineTokens = count(discipline)
        assertTrue("前提：纪律层成本应为正", disciplineTokens > 0)

        val below = buildMemoryInjectionPlan(
            listOf(memory("id1", "content", sortKey = 1)),
            budgetTokens = disciplineTokens - 1,
            now = now,
        )
        assertTrue("连 L0 都装不下 ⇒ 全空", below.disciplineText.isEmpty() && below.dataText.isEmpty())
        assertEquals(0, below.totalEstimatedTokens)

        val exact = buildMemoryInjectionPlan(
            listOf(memory("id1", "content", sortKey = 1)),
            budgetTokens = disciplineTokens,
            now = now,
        )
        assertEquals(discipline, exact.disciplineText)
        assertEquals(disciplineTokens, exact.disciplineTokens)
        assertTrue("预算被 L0 吃光 ⇒ 尾部无空间", exact.dataText.isEmpty())
        assertTrue(exact.anchorText.isEmpty())
        assertExactBilling(exact)
    }

    // ---- 4. 计费包含 JSON 转义 / 长 id / revision ----

    @Test
    fun `billing covers json escaping long ids revisions and rendered text`() {
        val longId = "mem-" + "x".repeat(120)
        val content = "第一行\n第二行 \"quoted\" 中文内容"
        val plan = buildMemoryInjectionPlan(
            listOf(memory(longId, content, sortKey = 1, revision = 42)),
            budgetTokens = 2_000,
            now = now,
        )

        assertEquals(1, plan.fullText.size)
        assertEquals("正文必须逐字保留（不截断）", content, plan.fullText.single().content)
        assertTrue("长 id 必须实际渲染", plan.dataText.contains(longId))
        assertTrue("revision 必须实际渲染", plan.dataText.contains("\"revision\": 42"))
        assertTrue("引号必须 JSON 转义后渲染", plan.dataText.contains("\\\"quoted\\\""))
        assertTrue("换行必须 JSON 转义后渲染", plan.dataText.contains("\\n"))
        assertFalse("无索引时不应渲染清单标题", plan.dataText.contains("**Memory index**"))
        assertExactBilling(plan)
    }

    // ---- 5. 省略告知按实际渲染计费 ----

    @Test
    fun `omission notice is rendered and billed`() {
        val memories = (1..60).map { i ->
            memory("id$i", "note $i", description = "topic $i", sortKey = i)
        }
        val plan = buildMemoryInjectionPlan(memories, budgetTokens = 600, now = now)

        assertTrue("前提：应有条目进索引", plan.indexed.isNotEmpty())
        assertTrue("前提：应有条目被省略", plan.omittedCount > 0)
        assertTrue(plan.dataText.contains("**Omitted**: ${plan.omittedCount}"))
        assertTrue(plan.dataText.contains("**Memory index**"))
        assertEquals(
            "省略数 = 总数 − 锚点 − 全文 − 索引",
            memories.size - plan.anchors.size - plan.fullText.size - plan.indexed.size,
            plan.omittedCount,
        )
        assertExactBilling(plan)
    }

    // ---- 6. L1 与 L2/L3 去重 ----

    @Test
    fun `anchor tier never repeats in full text or index`() {
        val memories = listOf(
            memory("a1", "anchor one content", importance = 0.95f, sortKey = 1, isAnchor = true),
            memory("a2", "anchor two content", importance = 0.9f, sortKey = 2, isAnchor = true),
            memory("n1", "normal one", importance = 0.8f, sortKey = 3),
            memory("n2", "normal two", importance = 0.7f, sortKey = 4),
        )
        val plan = buildMemoryInjectionPlan(memories, budgetTokens = 1_500, now = now)

        assertTrue("前提：锚点应入选 L1", plan.anchors.isNotEmpty())
        val anchorIds = plan.anchors.map { it.id }.toSet()
        val tailIds = (plan.fullText + plan.indexed).map { it.id }
        assertTrue("L1 不得重复出现在 L2/L3", tailIds.none { it in anchorIds })
        assertTrue("锚点正文应出现在 anchorText", plan.anchorText.contains("anchor one content"))
        assertTrue(
            "L2 与 L3 不得重叠",
            plan.fullText.map { it.id }.toSet().intersect(plan.indexed.map { it.id }.toSet()).isEmpty(),
        )
        assertExactBilling(plan)
    }

    // ---- 7. 冻结锚点集合语义 ----

    @Test
    fun `frozen anchor ids keep the old set and frozen out anchors stay ordinary candidates`() {
        val memories = listOf(
            memory("a1", "anchor one", importance = 0.99f, sortKey = 1, isAnchor = true),
            memory("a2", "anchor two", importance = 0.98f, sortKey = 2, isAnchor = true),
            memory("n1", "normal one", importance = 0.6f, sortKey = 3),
        )

        val frozen = buildMemoryInjectionPlan(
            memories,
            budgetTokens = 2_000,
            now = now,
            frozenAnchorIds = setOf("a1"),
        )
        assertEquals("冻结集合外的锚点不得进系统层", listOf("a1"), frozen.anchors.map { it.id })
        assertTrue(
            "被冻结集合排除的锚点仍是普通 L2/L3 候选",
            (frozen.fullText + frozen.indexed).any { it.id == "a2" },
        )
        assertExactBilling(frozen)

        val unfrozen = buildMemoryInjectionPlan(memories, budgetTokens = 2_000, now = now)
        assertEquals(setOf("a1", "a2"), unfrozen.anchors.map { it.id }.toSet())
        assertExactBilling(unfrozen)
    }

    // ---- 8. 超大锚点只留索引 ----

    @Test
    fun `oversized anchor becomes index only`() {
        val huge = "超大锚点".repeat(400)
        val memories = listOf(
            memory("big-anchor", huge, importance = 0.95f, sortKey = 1, isAnchor = true),
            memory("small", "tiny note", importance = 0.9f, sortKey = 2),
        )
        val plan = buildMemoryInjectionPlan(memories, budgetTokens = 500, now = now)

        assertTrue("放不进 system 的锚点不得进 L1", plan.anchors.isEmpty())
        assertTrue(plan.anchorText.isEmpty())
        assertTrue("overflow 锚点不得进 L2 全文", plan.fullText.none { it.id == "big-anchor" })
        assertTrue("overflow 锚点必须只以索引形式露出", plan.indexed.any { it.id == "big-anchor" })
        assertFalse("不得渲染半条正文", plan.dataText.contains(huge))
        assertExactBilling(plan)

        // 连索引都放不下时：正文/索引全空，只留一条「全部省略」的告知（告知本身也计费）。
        val tiny = buildMemoryInjectionPlan(
            listOf(memory("big-anchor", huge, importance = 0.95f, sortKey = 1, isAnchor = true)),
            budgetTokens = 200,
            now = now,
        )
        assertTrue(tiny.fullText.isEmpty() && tiny.indexed.isEmpty())
        assertEquals(1, tiny.omittedCount)
        assertTrue(tiny.dataText.contains("**Omitted**: 1"))
        assertFalse(tiny.dataText.contains(huge))
        assertExactBilling(tiny)
    }

    // ---- 9. 稳定顺序：L2 sortKey 降序，L3 sortKey 升序 ----

    @Test
    fun `full text is sortKey descending and index ascending`() {
        val memories = (1..12).map { i ->
            memory(
                id = "id$i",
                content = "content number $i " + "filler ".repeat(20),
                description = "topic $i",
                sortKey = i,
                importance = 0.5f + i * 0.01f,
            )
        }
        val plan = buildMemoryInjectionPlan(memories, budgetTokens = 600, now = now)

        assertTrue("前提：L2 非空", plan.fullText.isNotEmpty())
        assertTrue("前提：L3 非空", plan.indexed.isNotEmpty())
        assertEquals(plan.fullText.map { it.sortKey }.sortedDescending(), plan.fullText.map { it.sortKey })
        assertEquals(plan.indexed.map { it.sortKey }.sorted(), plan.indexed.map { it.sortKey })
        assertExactBilling(plan)
    }

    // ---- 10. 排序口径：0.2 下限与查询相关性 ----

    @Test
    fun `ranking applies raw score floor and query relevance boost`() {
        val stale = memory(
            "stale",
            "old note",
            importance = 0.3f,
            description = "old topic",
            sortKey = 1,
            updatedAt = now - 400L * 24 * 60 * 60 * 1000,
        )
        val fresh = memory("fresh", "new note", importance = 0.9f, sortKey = 2)

        val ranked = rankedMemoryCandidates(listOf(stale, fresh), now)
        assertEquals("低于 0.2 的原始分数必须被淘汰", listOf("fresh"), ranked.map { it.id })

        val queried = rankedMemoryCandidates(listOf(stale, fresh), now, query = "old")
        assertTrue("相关性增益应把 stale 抬回线上", queried.any { it.id == "stale" })
        assertEquals("fresh 仍应排在前面", "fresh", queried.first().id)

        val tooLow = memory(
            "too-low",
            "irrelevant filler",
            importance = 0.12f,
            sortKey = 3,
            updatedAt = now - 400L * 24 * 60 * 60 * 1000,
        )
        assertTrue(
            "相关性别名也不能救回原始分过低的条目",
            rankedMemoryCandidates(listOf(tooLow), now, query = "filler").none { it.id == "too-low" },
        )
    }

    // ---- 11. 正文绝不截断 ----

    @Test
    fun `full record content is never truncated`() {
        val hugeContent = "记忆正文" + "很长的内容".repeat(400)
        val big = memory("big", hugeContent, sortKey = 1)

        val roomy = buildMemoryInjectionPlan(listOf(big), budgetTokens = 100_000, now = now)
        assertEquals(1, roomy.fullText.size)
        assertEquals("正文必须完整保留", hugeContent, roomy.fullText.single().content)
        assertTrue(roomy.dataText.contains(hugeContent))
        assertExactBilling(roomy)

        val tight = buildMemoryInjectionPlan(
            listOf(big, memory("s1", "short 1", sortKey = 2), memory("s2", "short 2", sortKey = 3)),
            budgetTokens = 600,
            now = now,
        )
        assertTrue("大正文放不进 L2", tight.fullText.none { it.id == "big" })
        assertTrue("大正文应进 L3 索引", tight.indexed.any { it.id == "big" })
        assertFalse("不得把正文截断前半段塞进上下文", tight.dataText.contains(hugeContent))
        assertExactBilling(tight)
    }

    // ---- 12. 多预算 sweep：硬上限、精确计费、确定性 ----

    @Test
    fun `many budgets stay within the hard cap and are deterministic`() {
        val memories = listOf(
            memory("a1", "anchor fact", importance = 0.95f, sortKey = 1, isAnchor = true),
            memory("big", "大段内容".repeat(200), importance = 0.9f, sortKey = 2),
            memory("m1", "medium one " + "x".repeat(80), importance = 0.8f, sortKey = 3),
            memory("m2", "medium two " + "y".repeat(80), importance = 0.7f, sortKey = 4),
            memory(
                "stale",
                "stale low score",
                importance = 0.1f,
                sortKey = 5,
                updatedAt = now - 400L * 24 * 60 * 60 * 1000,
            ),
        )

        listOf(-5, 0, 1, 2, 5, 10, 30, 80, 150, 300, 600, 1_200, 8_000).forEach { budget ->
            val plan = buildMemoryInjectionPlan(memories, budgetTokens = budget, now = now)
            assertExactBilling(plan)
            assertEquals(
                "同一输入必须得到同一 plan（budget=$budget）",
                plan,
                buildMemoryInjectionPlan(memories, budget, now),
            )
        }
    }

    @Test
    fun `tail scaffold is charged once not for every full entry`() {
        val records = (1..15).map { memory("m$it", "note $it", sortKey = it) }
        val probe = buildMemoryInjectionPlan(records, 100_000, now)
        val count = counterFor(probe)
        val empty = count(renderMemoryDataPrompt(emptyList(), emptyList(), 1))
        val bodyCosts = records.sumOf {
            count(renderMemoryDataPrompt(listOf(it), emptyList(), 1)) - empty + 2
        }
        val fixed = count(buildMemoryDisciplinePrompt()) +
            count(renderMemoryDataPrompt(emptyList(), emptyList(), records.size))
        val budget = fixed + kotlin.math.ceil((bodyCosts + 1) / 0.75).toInt() + 2
        val plan = buildMemoryInjectionPlan(records, budget, now)
        assertEquals(records.size, plan.fullText.size)
        assertExactBilling(plan)
    }

    // ---- 13. 兼容 wrapper：只算尾部、不产生 L1 ----

    @Test
    fun `legacy data prompt delegates to tail only plan`() {
        val memories = listOf(
            memory("a1", "anchor fact", importance = 0.95f, sortKey = 1, isAnchor = true),
            memory("n1", "normal note", importance = 0.8f, sortKey = 2),
            memory("n2", "another note", importance = 0.7f, sortKey = 3),
        )
        val wrapper = buildMemoryDataPrompt(memories, budgetTokens = 900, now = now)
        val plan = buildMemoryInjectionPlan(
            memories,
            budgetTokens = 900,
            now = now,
            frozenAnchorIds = emptySet(),
        )

        assertEquals(plan.dataText, wrapper)
        assertTrue(plan.anchors.isEmpty())
        assertTrue(plan.anchorText.isEmpty())
        assertFalse("wrapper 不产生 system 锚点块", wrapper.contains("**Anchors**"))
        assertTrue("空库 ⇒ 尾部整块为空", buildMemoryDataPrompt(emptyList(), budgetTokens = 900, now = now).isEmpty())
    }
}

/** Fixture budget derived from rendered overhead plus enough variable space for either whole record. */
internal fun memoryBudgetForOneFullEntry(memories: List<AssistantMemory>): Int {
    val empty = estimateTokens(renderMemoryDataPrompt(emptyList(), emptyList(), 1))
    val largest = memories.maxOf {
        estimateTokens(renderMemoryDataPrompt(listOf(it), emptyList(), 1)) - empty + 2
    }
    val fixed = estimateTokens(buildMemoryDisciplinePrompt()) +
        estimateTokens(renderMemoryDataPrompt(emptyList(), emptyList(), memories.size))
    return fixed + kotlin.math.ceil((largest + 1) / 0.75).toInt() + 2
}
