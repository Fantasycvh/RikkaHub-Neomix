package app.androidrik.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书 token 预算裁剪（对齐 ST 的 `world_info_budget`）。
 *
 * 动机（`docs/peer-apps-benchmark.md` §6.2 ③）：fork 此前**没有预算**，命中多少注入多少
 * ⇒ 导入大型 ST 世界书、或关键词设得过宽时，会**静默**把上下文塞满（用户看不出是哪些
 * 条目吃掉的）。ST 默认取上下文 25% 为上限。
 *
 * 本仓默认 0 = 不限制（保持旧行为），由用户显式开启——照搬 25% 会静默改变既有注入量。
 */
class LorebookBudgetTest {

    /** 每条 10 token（便于精确算账）。 */
    private val perEntryCost = { _: String -> 10 }

    private fun entry(
        name: String,
        priority: Int = 0,
        constantActive: Boolean = false,
        content: String = "x",
    ) = PromptInjection.RegexInjection(
        name = name,
        priority = priority,
        constantActive = constantActive,
        content = content,
    )

    // region 零回归：预算 <= 0 不限制

    @Test
    fun `zero budget means unlimited - legacy behavior`() {
        val entries = (1..20).map { entry("e$it") }
        val result = entries.limitByTokenBudget(budgetTokens = 0, estimate = perEntryCost)
        assertEquals(entries.size, result.size)
    }

    @Test
    fun `negative budget also means unlimited`() {
        val entries = (1..5).map { entry("e$it") }
        assertEquals(5, entries.limitByTokenBudget(-1, perEntryCost).size)
    }

    @Test
    fun `empty list stays empty`() {
        assertTrue(emptyList<PromptInjection.RegexInjection>().limitByTokenBudget(100, perEntryCost).isEmpty())
    }

    // endregion

    // region 裁剪语义

    @Test
    fun `budget caps how many entries are injected`() {
        // 预算 35、每条 10 → 至多 3 条（3*10=30 ≤ 35；第 4 条会到 40 > 35）
        val entries = (1..10).map { entry("e$it") }
        val result = entries.limitByTokenBudget(budgetTokens = 35, estimate = perEntryCost)
        assertTrue("should cut down to fit budget, got ${result.size}", result.size <= 3)
        assertTrue("should keep at least one", result.isNotEmpty())
    }

    @Test
    fun `higher priority entries win`() {
        val entries = listOf(
            entry("low", priority = 1),
            entry("high", priority = 100),
            entry("mid", priority = 50),
        )
        val result = entries.limitByTokenBudget(budgetTokens = 10, estimate = perEntryCost)
        assertEquals(1, result.size)
        assertEquals("high", result.first().name)
    }

    @Test
    fun `constant entries are never evicted by the budget`() {
        // 常驻条目是用户显式声明的「永远要在」；不计预算、不淘汰。
        // 否则「设了预算就再也看不到常驻条目」会非常反直觉。
        val entries = listOf(
            entry("always", constantActive = true),
            entry("big1", priority = 100, content = "x".repeat(1000)),
            entry("big2", priority = 99, content = "x".repeat(1000)),
        )
        val expensive = { s: String -> s.length }
        val result = entries.limitByTokenBudget(budgetTokens = 5, estimate = expensive)
        assertTrue("constant entry must survive", result.any { it.name == "always" })
    }

    @Test
    fun `an oversized entry does not block smaller ones behind it`() {
        // 超预算时「跳过本条并继续」（不是 break）：一条超长条目不该把后续预算全堵死
        val entries = listOf(
            entry("huge", priority = 100, content = "x".repeat(10_000)),
            entry("small1", priority = 50),
            entry("small2", priority = 40),
        )
        val result = entries.limitByTokenBudget(budgetTokens = 25, estimate = { it.length.coerceAtMost(10) })
        assertTrue("small entries should still get in", result.any { it.name == "small1" })
    }

    @Test
    fun `first entry is kept even if it exceeds the budget`() {
        // 避免「预算极小 → 一条都不注入」这种最坏结果（用户会以为功能坏了）
        val entries = listOf(entry("only", content = "x".repeat(100)))
        val result = entries.limitByTokenBudget(budgetTokens = 1, estimate = { it.length })
        assertEquals(1, result.size)
        assertEquals("only", result.first().name)
    }

    @Test
    fun `result order is by priority descending`() {
        val entries = listOf(
            entry("a", priority = 1),
            entry("b", priority = 3),
            entry("c", priority = 2),
        )
        val result = entries.limitByTokenBudget(budgetTokens = 1000, estimate = perEntryCost)
        assertEquals(listOf("b", "c", "a"), result.map { it.name })
    }

    // endregion
}
