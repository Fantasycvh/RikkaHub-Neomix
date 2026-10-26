package app.androidrik.rikkahub.data.ai.compression

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 压缩失败阶梯与覆盖率闸（`design-multi-pass-compression.md` §3.5）。
 *
 * 覆盖率闸的**豁免面**与**取消语义**是重点：纯散文不误伤、空白摘要永不过闸、
 * 用户取消必须传播（绝不能退到 C4 把截断文本当摘要落库）。
 */
class CompressionLadderTest {

    private val factRichSource = buildString {
        appendLine("部署在 192.168.1.100:8080，版本 2.5.1，日志路径 var/log/app.log")
        appendLine("处理了 12000 行，成功率 87.5%，包大小 63,262 字节，超时 30000 毫秒")
        appendLine("配置文件 settings.gradle 里的版本号 1.0.28 也同步改了")
    }

    // ---- extractHardFacts ----

    @Test
    fun `extracts paths versions percents and big numbers`() {
        val facts = extractHardFacts(factRichSource)
        assertTrue("路径: $facts", facts.contains("var/log/app.log"))
        assertTrue(facts.contains("192.168.1.100"))
        assertTrue(facts.contains("1.0.28"))
        assertTrue(facts.contains("2.5.1"))
        assertTrue(facts.contains("87.5%"))
        assertTrue(facts.contains("12000"))
        assertTrue(facts.contains("63,262"))
        assertFalse("单个位数不算硬事实", facts.contains("5"))
    }

    @Test
    fun `nested facts are deduplicated keeping the longest`() {
        val facts = extractHardFacts("版本 1.0.28 与旧版 1.0 和 1.0.28-beta 的路径 a/b/c 与 b/c")
        assertFalse("1.0 被 1.0.28 包含 ⇒ 剔除", facts.contains("1.0"))
        assertTrue(facts.contains("1.0.28-beta"))
        assertTrue(facts.contains("a/b/c"))
    }

    @Test
    fun `limit caps the fact list`() {
        val text = (1..200).joinToString(" ") { "路径 dir$it/file$it.txt" }
        assertEquals(64, extractHardFacts(text).size)
        assertEquals(5, extractHardFacts(text, limit = 5).size)
    }

    // ---- coveragePassed / coverageRatio ----

    @Test
    fun `gate passes when summary keeps the facts`() {
        val summary = "我把服务部署在 192.168.1.100:8080（版本 2.5.1），日志仍在 var/log/app.log，" +
            "处理了 12000 行（成功率 87.5%），包 63,262 字节、超时 30000 毫秒，settings.gradle 的 1.0.28 同步改了"
        assertTrue(coveragePassed(factRichSource, summary))
    }

    @Test
    fun `gate fails when summary drops the facts`() {
        assertTrue(extractHardFacts(factRichSource).size >= MIN_HARD_FACTS)
        assertFalse(coveragePassed(factRichSource, "摘要写得很好但把所有数字和路径都丢了"))
    }

    @Test
    fun `gate fails below the threshold`() {
        // 9 条事实只留 2 条 ⇒ ratio < 0.8
        val src = "路径 var/log/app.log 与 var/log/b.log，版本 2.5.1 与 1.0.28，数量 12000 与 30000，比例 87.5%"
        val facts = extractHardFacts(src)
        assertTrue("前提: $facts", facts.size >= MIN_HARD_FACTS)
        val partial = "只提到 var/log/app.log 和 2.5.1"
        assertFalse(coveragePassed(src, partial))
    }

    @Test
    fun `few facts are exempt`() {
        assertTrue("纯散文（无可核对事实）豁免", coveragePassed("就是想聊聊天，说今天天气不错", "聊了天气"))
        assertTrue("事实 < 4 条豁免", coveragePassed("版本 2.5.1 上线了，一共 3 处改动", "聊了 2.5.1 上线"))
    }

    @Test
    fun `blank summary never passes even when exempt`() {
        assertFalse(coveragePassed("没有数字的散文", ""))
        assertFalse(coveragePassed("没有数字的散文", "   \n  "))
        assertFalse(coveragePassed(factRichSource, ""))
    }

    @Test
    fun `coverage decision carries diagnostics`() {
        val decision = coverageDecision(factRichSource, "全丢了")
        assertFalse(decision.passed)
        assertFalse(decision.exempt)
        assertTrue(decision.facts.isNotEmpty())
        val exempt = coverageDecision("就一句散文", "随便什么")
        assertTrue(exempt.passed)
        assertTrue(exempt.exempt)
    }

    // ---- runCompressionLadder（阶梯编排）----

    private fun source() = "路径 var/log/app.log，版本 2.5.1，数量 12000，比例 87.5%"

    private fun goodSummary() = "保留了 var/log/app.log、2.5.1、12000、87.5% 的完整摘要"

    @Test
    fun `chunked tier wins when coverage passes and single is never called`() = runBlocking {
        var singleCalled = false
        val result = runCompressionLadder(
            sourceText = source(),
            chunkedSummarize = { goodSummary() },
            singleSummarize = { singleCalled = true; error("C2 不应被调") },
            truncatedFallback = "FB",
        )
        assertEquals(CompressionTier.CHUNKED, result.tier)
        assertEquals(goodSummary(), result.summary)
        assertFalse(singleCalled)
    }

    @Test
    fun `falls to single when chunked output fails the gate`() = runBlocking {
        val result = runCompressionLadder(
            sourceText = source(),
            chunkedSummarize = { "数字全丢的摘要" },
            singleSummarize = { goodSummary() },
            truncatedFallback = "FB",
        )
        assertEquals(CompressionTier.SINGLE, result.tier)
        assertEquals(goodSummary(), result.summary)
    }

    @Test
    fun `exception in chunked counts as tier failure not abort`() = runBlocking {
        val result = runCompressionLadder(
            sourceText = source(),
            chunkedSummarize = { throw IllegalStateException("provider down") },
            singleSummarize = { goodSummary() },
            truncatedFallback = "FB",
        )
        assertEquals(CompressionTier.SINGLE, result.tier)
    }

    @Test
    fun `falls to truncated when both llm tiers fail`() = runBlocking {
        val result = runCompressionLadder(
            sourceText = source(),
            chunkedSummarize = { "数字全丢" },
            singleSummarize = { "还是全丢" },
            truncatedFallback = "FB",
        )
        assertEquals(CompressionTier.TRUNCATED, result.tier)
        assertEquals("FB", result.summary)
    }

    @Test
    fun `single is skipped when null and chunked fails`() = runBlocking {
        val result = runCompressionLadder(
            sourceText = source(),
            chunkedSummarize = { throw IllegalStateException("down") },
            singleSummarize = null,
            truncatedFallback = "FB",
        )
        assertEquals(CompressionTier.TRUNCATED, result.tier)
    }

    @Test
    fun `blank output is treated as tier failure`() = runBlocking {
        val result = runCompressionLadder(
            sourceText = source(),
            chunkedSummarize = { "   " },
            singleSummarize = { goodSummary() },
            truncatedFallback = "FB",
        )
        assertEquals(CompressionTier.SINGLE, result.tier)
    }

    @Test
    fun `cancellation propagates instead of falling to truncated`() {
        // 用户取消压缩 ⇒ 必须抛出，绝不能退到 C4 把兜底文本当摘要落库
        try {
            runBlocking {
                runCompressionLadder(
                    sourceText = source(),
                    chunkedSummarize = { throw CancellationException("user cancelled") },
                    singleSummarize = { goodSummary() },
                    truncatedFallback = "FB",
                )
            }
            fail("CancellationException 应当传播")
        } catch (expected: CancellationException) {
            assertEquals("user cancelled", expected.message)
        }
    }

    // ---- buildTruncatedFallback ----

    @Test
    fun `fallback truncates at a line boundary and carries the notice`() {
        val text = (1..500).joinToString("\n") { "line $it with 12000 and var/log/app.log" }
        val fallback = buildTruncatedFallback(text, maxChars = 1000)
        assertTrue(fallback.length < 1200)
        assertTrue(fallback.contains(TRUNCATED_FALLBACK_NOTICE))
        assertTrue(fallback.startsWith("line 1 "))
        // 截断落在换行处 ⇒ 最后一行是完整行（每行都以路径结尾），不被切碎
        val head = fallback.substringBefore(TRUNCATED_FALLBACK_NOTICE).trimEnd()
        assertTrue("最后一行应完整: …${head.takeLast(40)}", head.lines().last().endsWith("var/log/app.log"))
    }

    @Test
    fun `fallback keeps short source verbatim plus notice`() {
        val text = "短原文 12000"
        val fallback = buildTruncatedFallback(text)
        assertTrue(fallback.startsWith(text))
        assertTrue(fallback.contains(TRUNCATED_FALLBACK_NOTICE))
    }
}
