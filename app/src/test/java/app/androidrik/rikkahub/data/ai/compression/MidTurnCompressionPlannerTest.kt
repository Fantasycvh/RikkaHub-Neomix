package app.androidrik.rikkahub.data.ai.compression

import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中途压缩的判定与切分点（上游 `bcc649abc` 语义）：
 *
 * - 阈值判定与轮前自动压缩同口径（[shouldAutoCompress]）；
 * - 同一条回复只试一次（压缩失败也要继续写原回复，不能每批工具都重试）；
 * - 压缩后第一步又超阈值 ⇒ 本轮放弃（防抖）；
 * - 切分点 = 窗口全部（含当前回复），不保留尾部原文。
 */
class MidTurnCompressionPlannerTest {

    private val windowTokens = 100_000
    private val aboveThreshold = 85_000 // 85% > 默认 80%
    private val belowThreshold = 50_000

    @Test
    fun `below threshold skips`() {
        val decision = decideMidTurnCompression(
            usedTokens = belowThreshold,
            windowTokens = windowTokens,
            thresholdPercent = DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT,
            replyAlreadyTried = false,
            compressionUseless = false,
            stepsSinceCompression = null,
        )
        assertEquals(MidTurnCompressionDecision.Skip, decision)
    }

    @Test
    fun `at or above threshold compresses`() {
        val decision = decideMidTurnCompression(
            usedTokens = aboveThreshold,
            windowTokens = windowTokens,
            thresholdPercent = DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT,
            replyAlreadyTried = false,
            compressionUseless = false,
            stepsSinceCompression = null,
        )
        assertEquals(MidTurnCompressionDecision.Compress, decision)
    }

    @Test
    fun `same reply is never retried even when still above threshold`() {
        val decision = decideMidTurnCompression(
            usedTokens = aboveThreshold,
            windowTokens = windowTokens,
            thresholdPercent = DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT,
            replyAlreadyTried = true,
            compressionUseless = false,
            stepsSinceCompression = null,
        )
        assertEquals(MidTurnCompressionDecision.Skip, decision)
    }

    @Test
    fun `first check after a successful compression gives up for the turn`() {
        // 压缩后第 1 步的占用仍超阈值 ⇒ 摘要 + 系统提示词自身就装不下，本轮再压也没用
        val decision = decideMidTurnCompression(
            usedTokens = aboveThreshold,
            windowTokens = windowTokens,
            thresholdPercent = DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT,
            replyAlreadyTried = false,
            compressionUseless = false,
            stepsSinceCompression = 1,
        )
        assertEquals(MidTurnCompressionDecision.GiveUp, decision)
    }

    @Test
    fun `first check after a successful compression that is below threshold does not give up`() {
        // 判据顺序的回归点：压缩生效后占用本就会掉到阈值下，先判防抖会误判成「摘要自身超窗」、
        // 把整轮压缩能力关掉
        val decision = decideMidTurnCompression(
            usedTokens = belowThreshold,
            windowTokens = windowTokens,
            thresholdPercent = DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT,
            replyAlreadyTried = false,
            compressionUseless = false,
            stepsSinceCompression = 1,
        )
        assertEquals(MidTurnCompressionDecision.Skip, decision)
    }

    @Test
    fun `later steps after compression may compress again`() {
        val decision = decideMidTurnCompression(
            usedTokens = aboveThreshold,
            windowTokens = windowTokens,
            thresholdPercent = DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT,
            replyAlreadyTried = false,
            compressionUseless = false,
            stepsSinceCompression = 2,
        )
        assertEquals(MidTurnCompressionDecision.Compress, decision)
    }

    @Test
    fun `useless flag wins over threshold`() {
        val decision = decideMidTurnCompression(
            usedTokens = aboveThreshold,
            windowTokens = windowTokens,
            thresholdPercent = DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT,
            replyAlreadyTried = false,
            compressionUseless = true,
            stepsSinceCompression = null,
        )
        assertEquals(MidTurnCompressionDecision.Skip, decision)
    }

    @Test
    fun `unknown window never compresses`() {
        val decision = decideMidTurnCompression(
            usedTokens = 0,
            windowTokens = 0,
            thresholdPercent = DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT,
            replyAlreadyTried = false,
            compressionUseless = false,
            stepsSinceCompression = null,
        )
        assertEquals(MidTurnCompressionDecision.Skip, decision)
    }

    @Test
    fun `mid-turn plan compresses the whole window including the current reply`() {
        val nodes = listOf(
            MessageNode(messages = listOf(UIMessage.user("q"))),
            MessageNode(messages = listOf(UIMessage.assistant("a"))),
        )

        val plan = midTurnCompressionPlan(nodes)

        // 判据：撑爆窗口的工具输出就在当前回复里 ⇒ 不保留尾部，边界 = 最后一条节点
        assertEquals(nodes, plan.toCompress)
        assertTrue(plan.toKeep.isEmpty())
        assertEquals(nodes.last(), plan.toCompress.last())
        assertTrue(plan.shouldCompress)
    }

    @Test
    fun `empty window has nothing to compress`() {
        val plan = midTurnCompressionPlan(emptyList())

        assertFalse(plan.shouldCompress)
    }
}
