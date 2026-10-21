package app.androidrik.rikkahub.data.ai.compression

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩保留策略与分块（design-auto-compaction §4.1 三边界对齐，token 驱动保留）。
 */
class CompressionPlannerTest {

    private fun node(text: String) = MessageNode(
        messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
        )
    )

    /** 每条 "x"*4 ≈ 1 token（非 CJK 4 字符/token） */
    private fun nodeTokens(tokens: Int) = node("x".repeat(tokens * 4))

    @Test
    fun `empty nodes produce empty plan`() {
        val plan = estimateCompressionPlan(emptyList(), 100)
        assertFalse(plan.shouldCompress)
        assertTrue(plan.toCompress.isEmpty())
    }

    @Test
    fun `keeps at least minKeep messages even with tiny budget`() {
        val nodes = (1..10).map { nodeTokens(100) }
        val plan = estimateCompressionPlan(nodes, budgetTokens = 1)
        assertEquals(COMPRESS_MIN_KEEP_MESSAGES, plan.toKeep.size)
        assertEquals(8, plan.toCompress.size)
        assertTrue(plan.shouldCompress)
    }

    // ===== 用户可选「保留最近 N 条」（上游 #2171/#2180 的条数下限，本仓按 token 比例架构落地）=====

    @Test
    fun `effective min keep falls back to default for unset or invalid values`() {
        assertEquals(COMPRESS_MIN_KEEP_MESSAGES, effectiveMinKeep(0))
        assertEquals(COMPRESS_MIN_KEEP_MESSAGES, effectiveMinKeep(-3))
        assertEquals(COMPRESS_MIN_KEEP_MESSAGES, effectiveMinKeep(1))
    }

    @Test
    fun `effective min keep honors larger user value`() {
        assertEquals(8, effectiveMinKeep(8))
    }

    @Test
    fun `user min keep is a floor on the kept tail even with tiny budget`() {
        // 每条 100 token、预算 1：默认只会留 2 条；用户要求 6 条时必须留满 6 条
        val nodes = (1..10).map { nodeTokens(100) }
        val plan = estimateCompressionPlan(nodes, budgetTokens = 1, minKeep = effectiveMinKeep(6))
        assertEquals(6, plan.toKeep.size)
        assertEquals(4, plan.toCompress.size)
        assertTrue(plan.shouldCompress)
    }

    @Test
    fun `user min keep larger than window means no compression`() {
        val nodes = (1..3).map { nodeTokens(100) }
        val plan = estimateCompressionPlan(nodes, budgetTokens = 1, minKeep = effectiveMinKeep(10))
        assertFalse(plan.shouldCompress)
        assertEquals(3, plan.toKeep.size)
    }

    @Test
    fun `keep recent messages range allows zero`() {
        assertEquals(0, COMPRESS_KEEP_RECENT_MESSAGES_RANGE.first)
        assertTrue(COMPRESS_KEEP_RECENT_MESSAGES_RANGE.last >= 1)
    }

    @Test
    fun `budget limits how many messages are kept as original`() {
        // 每条 10 token，预算 35 -> 尾部最多保留 3 条（第 4 条会超预算）
        val nodes = (1..20).map { nodeTokens(10) }
        val plan = estimateCompressionPlan(nodes, budgetTokens = 35)
        assertEquals(3, plan.toKeep.size)
        assertEquals(17, plan.toCompress.size)
    }

    @Test
    fun `order is preserved in both groups`() {
        // 每条 10 token（40 字符），预算 15 -> 保留尾部 2 条、压缩前 4 条
        fun numberedNode(index: Int) = MessageNode(
            messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text("$index:" + "x".repeat(38))),
                )
            )
        )
        val nodes = (1..6).map { numberedNode(it) }
        val plan = estimateCompressionPlan(nodes, budgetTokens = 15)

        fun indexOf(node: MessageNode) = node.currentMessage.toText().substringBefore(':').toInt()
        assertEquals(listOf(1, 2, 3, 4), plan.toCompress.map { indexOf(it) })
        assertEquals(listOf(5, 6), plan.toKeep.map { indexOf(it) })
    }

    @Test
    fun `no compression when whole window fits in budget`() {
        val nodes = (1..3).map { nodeTokens(1) }
        val plan = estimateCompressionPlan(nodes, budgetTokens = 10_000)
        assertFalse(plan.shouldCompress)
        assertEquals(3, plan.toKeep.size)
        assertTrue(plan.toCompress.isEmpty())
    }

    @Test
    fun `does not mutate input list`() {
        val nodes = (1..5).map { nodeTokens(10) }
        val before = nodes.toList()
        estimateCompressionPlan(nodes, budgetTokens = 15)
        assertEquals(before, nodes)
    }

    @Test
    fun `split into chunks respects max size`() {
        val messages = (1..10).map { UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("$it"))) }
        val chunks = splitIntoChunks(messages, maxPerChunk = 3)
        assertTrue(chunks.all { it.size <= 3 })
        assertEquals(10, chunks.sumOf { it.size })
        // 顺序保持
        assertEquals(messages.map { it.toText() }, chunks.flatten().map { it.toText() })
    }

    @Test
    fun `split keeps single chunk when within limit`() {
        val messages = (1..5).map { UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("$it"))) }
        assertEquals(1, splitIntoChunks(messages, maxPerChunk = 5).size)
    }

    @Test
    fun `retain ratio is the conservative chat value`() {
        // 防止后续误改回 harness 的编码 agent 值（0.16 会把聊天内容过度摘要）
        assertEquals(0.4, COMPRESS_RETAIN_RATIO, 0.0001)
    }

    // ===== shouldAutoCompress（Phase 2 自动触发判断）=====

    @Test
    fun `triggers when usage reaches threshold`() {
        // 80% 阈值：80/100 恰好达到即触发
        assertTrue(shouldAutoCompress(usedTokens = 80, windowTokens = 100, thresholdPercent = 80))
        assertTrue(shouldAutoCompress(usedTokens = 81, windowTokens = 100, thresholdPercent = 80))
        assertFalse(shouldAutoCompress(usedTokens = 79, windowTokens = 100, thresholdPercent = 80))
    }

    @Test
    fun `does not trigger without window or usage info`() {
        assertFalse(shouldAutoCompress(usedTokens = 0, windowTokens = 100))
        assertFalse(shouldAutoCompress(usedTokens = 100, windowTokens = 0))
        assertFalse(shouldAutoCompress(usedTokens = -1, windowTokens = 100))
    }

    @Test
    fun `threshold percent is clamped to avoid thrashing`() {
        // 低于下限（50）会被钳到 50：占用 60% 因此触发
        assertTrue(shouldAutoCompress(usedTokens = 60, windowTokens = 100, thresholdPercent = 10))
        // 高于上限（95）会被钳到 95：占用 90% 因此不触发
        assertFalse(shouldAutoCompress(usedTokens = 90, windowTokens = 100, thresholdPercent = 200))
    }

    @Test
    fun `large token counts do not overflow`() {
        // 用 Long 中间量比较，避免 Int 乘法溢出
        val window = 1_000_000
        assertFalse(shouldAutoCompress(usedTokens = 700_000, windowTokens = window, thresholdPercent = 80))
        assertTrue(shouldAutoCompress(usedTokens = 900_000, windowTokens = window, thresholdPercent = 80))
    }

    @Test
    fun `default threshold matches harness ratio`() {
        assertEquals(80, DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT)
        assertEquals(40, DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT)
    }
}
