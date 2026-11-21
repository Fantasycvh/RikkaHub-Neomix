package app.androidrik.rikkahub.data.ai.compression

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.transformers.balancedForRequest
import app.androidrik.rikkahub.data.model.MessageNode
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")

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
    fun `savings never report negative`() {
        // 完成报告（#4 轻量版）：压缩后占用反而更高（ rare：系统提示词/保护区挤占）时如实 coerce 到 0
        assertEquals(0, CompressionSavings(beforeTokens = 100, afterTokens = 150).savedTokens)
        assertEquals(40, CompressionSavings(beforeTokens = 100, afterTokens = 60).savedTokens)
    }

    @Test
    fun `default threshold matches harness ratio`() {
        assertEquals(80, DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT)
        assertEquals(40, DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT)
    }

    // ===== 安全切点（守卫六同源判据 + D143 轮边界对齐，design-context-boundaries §2.1）=====

    private fun assistantNodeTokens(tokens: Int) = MessageNode(
        messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("x".repeat(tokens * 4))))
        )
    )

    /** 旧版 ToolResult 节点：part 本身不计价（estimateTokenCount 只数 Text），配一段文本撑起 token 量 */
    private fun legacyResultNodeTokens(id: String, tokens: Int) = MessageNode(
        messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = id, toolName = "t",
                        content = JsonPrimitive("ok"), arguments = JsonPrimitive("{}"),
                    ),
                    UIMessagePart.Text("x".repeat(tokens * 4)),
                ),
            )
        )
    )

    @Test
    fun `cut aligns to the user turn start at or before the candidate`() {
        // U A A U A：token 预算的候选切点 = 3（恰好落在 U 上）；候选落在助手区时回退到轮起点
        val nodes = listOf(
            nodeTokens(100), assistantNodeTokens(100), assistantNodeTokens(100),
            nodeTokens(100), assistantNodeTokens(100),
        )
        // 预算 150：尾部保留 A(4) + U(3)（minKeep 强制）→ 候选 3；轮起点 3 直接合法
        val plan = estimateCompressionPlan(nodes, budgetTokens = 150)
        assertTrue(plan.shouldCompress)
        assertEquals(3, plan.toCompress.size)
        assertEquals(nodes[3], plan.toKeep.first())
    }

    @Test
    fun `cut inside an assistant turn aligns back and may yield no compression`() {
        // U A A：候选 1（助手区）⇒ 轮对齐回退到起点 0 ⇒ 无可压缩（等下一轮候选越过下一个 USER）
        val nodes = listOf(nodeTokens(100), assistantNodeTokens(100), assistantNodeTokens(100))
        val plan = estimateCompressionPlan(nodes, budgetTokens = 50)
        assertFalse(plan.shouldCompress)
        assertEquals(nodes, plan.toKeep)
    }

    @Test
    fun `legacy tool pair straddling the cut forces the plan to give up`() {
        // call(0) 与 result(2) 之间的候选 2 骑线 ⇒ 回退到 0 ⇒ 无可压缩（绝不送出孤儿结果）
        val nodes = listOf(
            MessageNode(
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.ToolCall(toolCallId = "r1", toolName = "t", arguments = "{}")),
                    )
                )
            ),
            nodeTokens(100),
            legacyResultNodeTokens("r1", 100),
            nodeTokens(100), assistantNodeTokens(100),
        )
        // 预算 350：尾部保留 result(2)+U(3)+A(4) → 候选 2，骑线回退到 0
        val plan = estimateCompressionPlan(nodes, budgetTokens = 350)
        assertFalse(plan.shouldCompress)
        assertEquals(nodes, plan.toKeep)
    }

    @Test
    fun `legacy tool pair fully inside the compressed prefix does not block compression`() {
        // call(0) 与 result(2) 同在待压缩侧：候选 3 合法（轮起点 3），配对整体进摘要
        val nodes = listOf(
            MessageNode(
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.ToolCall(toolCallId = "r1", toolName = "t", arguments = "{}")),
                    )
                )
            ),
            nodeTokens(100),
            legacyResultNodeTokens("r1", 100),
            nodeTokens(100), assistantNodeTokens(100),
        )
        val plan = estimateCompressionPlan(nodes, budgetTokens = 250)
        assertTrue(plan.shouldCompress)
        assertEquals(3, plan.toCompress.size)
        // 切后保留区无孤儿结果（出口配平零修复，设计稿 §2.2-3 的计数检验）
        val kept = plan.toKeep.map { it.currentMessage }
        assertEquals(kept, kept.balancedForRequest())
    }

    @Test
    fun `plan below minimum progress yields no compression`() {
        // 11 条 × 100 token，预算 1050 → 候选留 10 条 ⇒ 可压缩 1/11 ≈ 9% < 10% 闸 ⇒ 无变化
        val nodes = (1..11).map { nodeTokens(100) }
        val plan = estimateCompressionPlan(nodes, budgetTokens = 1050)
        assertFalse(plan.shouldCompress)
        // 关闸后同一输入正常压缩（闸参数语义）
        val ungated = estimateCompressionPlan(nodes, budgetTokens = 1050, minProgressRatio = 0.0)
        assertTrue(ungated.shouldCompress)
        assertEquals(1, ungated.toCompress.size)
    }

    @Test
    fun `plan above minimum progress compresses normally`() {
        // 10 条 × 100 token，预算 850 → 留 8 条 ⇒ 可压缩 2/10 = 20% ≥ 10% ⇒ 正常压缩
        val nodes = (1..10).map { nodeTokens(100) }
        val plan = estimateCompressionPlan(nodes, budgetTokens = 850)
        assertTrue(plan.shouldCompress)
        assertEquals(2, plan.toCompress.size)
        assertEquals(8, plan.toKeep.size)
    }

    // ===== 中途压缩切分点：进行中的工具批整批留在原文侧 =====

    @Test
    fun `mid-turn plan keeps the pending tool batch as original text`() {
        val pendingTool = UIMessagePart.Tool(
            toolCallId = "call1", toolName = "t", input = "{}", output = emptyList(),
        )
        val nodes = listOf(
            nodeTokens(100),
            assistantNodeTokens(100),
            MessageNode(messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pendingTool)))),
            assistantNodeTokens(100),
        )
        val plan = midTurnCompressionPlan(nodes)
        assertTrue(plan.shouldCompress)
        assertEquals(listOf(nodes[0], nodes[1]), plan.toCompress)
        assertEquals(listOf(nodes[2], nodes[3]), plan.toKeep)
    }

    @Test
    fun `mid-turn plan compresses the whole window when no tool is unfinished`() {
        val executedTool = UIMessagePart.Tool(
            toolCallId = "call1", toolName = "t", input = "{}",
            output = listOf(UIMessagePart.Text("result")),
        )
        val nodes = listOf(
            nodeTokens(100),
            MessageNode(messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(executedTool)))),
        )
        val plan = midTurnCompressionPlan(nodes)
        assertTrue(plan.shouldCompress)
        assertEquals(nodes, plan.toCompress)
        assertTrue(plan.toKeep.isEmpty())
    }

    @Test
    fun `mid-turn plan gives up when the window starts with a pending batch`() {
        val pendingTool = UIMessagePart.Tool(
            toolCallId = "call1", toolName = "t", input = "{}", output = emptyList(),
        )
        val nodes = listOf(
            MessageNode(messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pendingTool)))),
            assistantNodeTokens(100),
        )
        val plan = midTurnCompressionPlan(nodes)
        assertFalse(plan.shouldCompress)
    }
}
