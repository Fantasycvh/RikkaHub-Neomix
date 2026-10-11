package app.androidrik.rikkahub.utils

import app.androidrik.rikkahub.data.ai.PromptSegment
import app.androidrik.rikkahub.data.ai.PromptSegmentKind
import app.androidrik.rikkahub.data.ai.PromptSegmentLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文容量面板的**口径**测试（见 私有仓记录·规划 2026-10-02 条目）。
 *
 * 这里钉住的是三件容易做错、且用户一眼能看出来的事：
 * 1. 差值法：各项相加必须等于总占用（总占用常来自服务端 usage，分段只有本地估算）；
 * 2. 轮数：分母是问答对（工具循环不另算），分子不含工具输出/schema/检查点；
 * 3. 样本不足不预测。
 */
class ContextCapacityTest {

    private fun seg(
        kind: PromptSegmentKind,
        tokens: Int,
        role: String = "assistant",
    ) = PromptSegment(
        kind = kind,
        label = kind.name,
        role = role,
        content = "",
        isSynthetic = kind == PromptSegmentKind.COMPRESSION_CHECKPOINT,
        tokens = tokens,
        source = null,
        location = if (kind == PromptSegmentKind.TOOL_SCHEMA) {
            PromptSegmentLocation.TOP_LEVEL_FIELD
        } else {
            PromptSegmentLocation.MESSAGE
        },
    )

    private fun user(tokens: Int) = seg(PromptSegmentKind.CHAT_HISTORY, tokens, role = "user")
    private fun assistant(tokens: Int) = seg(PromptSegmentKind.CHAT_HISTORY, tokens, role = "assistant")

    @Test
    fun `各项相加等于总占用（差值法）`() {
        val segments = listOf(
            user(100), assistant(200),
            seg(PromptSegmentKind.TOOL_SCHEMA, 33_000),
            seg(PromptSegmentKind.TOOL_RESULT, 5_000, role = "tool"),
        )
        val capacity = computeContextCapacity(
            usedTokens = 40_000,
            windowTokens = 1_000_000,
            segments = segments,
            sentHistoryCount = 2,
            totalMessageCount = 2,
        )

        assertEquals(300, capacity.historyTokens)
        assertEquals(33_000, capacity.toolSchemaTokens)
        assertEquals(5_000, capacity.toolResultTokens)
        assertEquals(0, capacity.checkpointTokens)
        // 差值法：40_000 − (300 + 33_000 + 5_000) = 1_700
        assertEquals(1_700, capacity.systemMemoryTokens)
        assertEquals(
            capacity.usedTokens,
            capacity.historyTokens + capacity.itemizedTokens + capacity.systemMemoryTokens,
        )
    }

    @Test
    fun `占用低于分段合计时差值归零而不是负数`() {
        // 本地估算高于服务端计数时（tokenizer 差异）不允许出现负的「系统与记忆」
        val capacity = computeContextCapacity(
            usedTokens = 100,
            windowTokens = 1_000,
            segments = listOf(seg(PromptSegmentKind.TOOL_SCHEMA, 5_000)),
            sentHistoryCount = 0,
            totalMessageCount = 0,
        )
        assertEquals(0, capacity.systemMemoryTokens)
    }

    @Test
    fun `轮数按问答对切分且不把工具输出算进每轮`() {
        // 两轮：第一轮带两条工具输出（工具循环不另算一轮），第二轮只有一轮问答
        val history = listOf(
            user(100),
            assistant(50),
            seg(PromptSegmentKind.TOOL_RESULT, 9_000, role = "tool"),
            assistant(150),
            user(200),
            assistant(300),
        )
        val rounds = perRoundTokens(history)
        assertEquals(listOf(300, 500), rounds)
    }

    @Test
    fun `每轮取中位数以抵抗一条超长消息`() {
        // 三轮：10 / 20 / 3000（一条长粘贴）——平均值 1010 会把预测压低十倍
        val history = listOf(user(10), user(20), user(3_000))
        assertEquals(20, medianOrNull(perRoundTokens(history)))
        // 偶数个取中间两个的平均
        assertEquals(30, medianOrNull(listOf(10, 20, 40, 60)))
        assertNull(medianOrNull(emptyList()))
    }

    @Test
    fun `样本不足三轮时不预测`() {
        val segments = listOf(user(1_000), assistant(1_000), user(1_000), assistant(1_000))
        val capacity = computeContextCapacity(
            usedTokens = 4_000,
            windowTokens = 100_000,
            segments = segments,
            sentHistoryCount = 4,
            totalMessageCount = 4,
        )
        assertEquals(2, capacity.roundSampleCount)
        assertNull(capacity.estimatedRounds)

        // 补到三轮就能预测：剩 96_000 / 2_000 = 48 轮
        val three = segments + listOf(user(1_000), assistant(1_000))
        val predicted = computeContextCapacity(
            usedTokens = 6_000,
            windowTokens = 100_000,
            segments = three,
            sentHistoryCount = 6,
            totalMessageCount = 6,
        )
        assertEquals(3, predicted.roundSampleCount)
        assertEquals(47, predicted.estimatedRounds)
    }

    @Test
    fun `警戒线与余量口径与输入栏共用同一阈值`() {
        val below = computeContextCapacity(79_000, 100_000, emptyList(), 0, 0)
        assertEquals(false, below.isWarning)
        assertEquals(21_000, below.remainingTokens)

        val at = computeContextCapacity(80_000, 100_000, emptyList(), 0, 0)
        assertEquals(true, at.isWarning)

        // 超限时余量为 0 而不是负数、比例封顶 1
        val over = computeContextCapacity(120_000, 100_000, emptyList(), 0, 0)
        assertEquals(0, over.remainingTokens)
        assertTrue(over.fraction == 1f)
    }

    @Test
    fun `窗口未知时不报警也不预测`() {
        val capacity = computeContextCapacity(
            usedTokens = 1_000,
            windowTokens = 0,
            segments = listOf(user(500), user(500)),
            sentHistoryCount = 2,
            totalMessageCount = 2,
        )
        assertEquals(false, capacity.isWarning)
        assertEquals(0f, capacity.fraction)
        assertEquals(0, capacity.remainingTokens)
    }

    @Test
    fun `首次回复前本地固定开销高于服务端锚点时取本地兜底并标记`() {
        // 真实场景：空会话 + 开着工具 ⇒ usage 还没有（0），但工具 schema 4.3K 每轮都发。
        // 若直接用锚点 0，明细会显示「合计 0」而单列项 4.3K —— 自相矛盾。
        val segments = listOf(
            seg(PromptSegmentKind.SYSTEM, 3_000, role = "system"),
            seg(PromptSegmentKind.TOOL_SCHEMA, 4_300),
        )
        val capacity = computeContextCapacity(
            usedTokens = 0,
            windowTokens = 256_000,
            segments = segments,
            sentHistoryCount = 0,
            totalMessageCount = 0,
            localTotalTokens = 7_300,
        )

        assertEquals(7_300, capacity.usedTokens)
        assertEquals(true, capacity.usesLocalFloor)
        assertEquals(3_000, capacity.systemMemoryTokens) // 系统提示落在差值里，不被吞掉
        assertEquals(
            capacity.usedTokens,
            capacity.historyTokens + capacity.itemizedTokens + capacity.systemMemoryTokens,
        )

        // 有 usage 且更高时以锚点为准（常态）
        val anchored = computeContextCapacity(
            usedTokens = 20_000,
            windowTokens = 256_000,
            segments = segments,
            sentHistoryCount = 0,
            totalMessageCount = 0,
            localTotalTokens = 7_300,
        )
        assertEquals(20_000, anchored.usedTokens)
        assertEquals(false, anchored.usesLocalFloor)
    }
}
