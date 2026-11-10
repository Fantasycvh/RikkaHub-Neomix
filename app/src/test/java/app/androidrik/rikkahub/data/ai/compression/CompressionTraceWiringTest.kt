package app.androidrik.rikkahub.data.ai.compression

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 痕迹附录的**接线守卫**（源码扫描，照 `CompressionLadderWiringTest` 同款）：
 * 纯函数与判据都有单测，但 `compressToCheckpoint` 不调用的话功能静默失效，
 * 编译期与纯函数单测都发现不了。
 */
class CompressionTraceWiringTest {

    private fun read(relative: String): String {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
        val file = root.resolve(relative)
        assertTrue("源文件不存在：${file.path}", file.isFile)
        return file.readText()
    }

    @Test
    fun `compressToCheckpoint attaches the trace appendix to the persisted checkpoint body`() {
        val source = read("app/androidrik/rikkahub/service/ChatService.kt")

        assertTrue("必须调用 attachTraceSection（否则痕迹不落库）", source.contains("attachTraceSection("))
        assertTrue("附录必须来自本段被压缩消息", source.contains("messages = messagesToCompress"))
        assertTrue("预算必须按摘要目标 token 判（超了整节不接）", source.contains("targetTokens = targetTokens"))
        assertTrue("落库正文必须是带附录的版本", source.contains("content = checkpointBody"))
    }

    @Test
    fun `prior checkpoint trace appendix is stripped before it reaches the model`() {
        val source = read("app/androidrik/rikkahub/service/ChatService.kt")

        assertTrue(
            "prior 注入前必须摘掉旧附录（否则模型抄进新摘要、层层叠成 N 份）",
            source.contains("stripTraceSection(priorSummary)"),
        )
    }

    @Test
    fun `appendix is attached after the coverage-gated ladder`() {
        val source = read("app/androidrik/rikkahub/service/ChatService.kt")

        val ladderIndex = source.indexOf("val ladder = runCompressionLadder(")
        val attachIndex = source.indexOf("val checkpointBody = attachTraceSection(")
        assertTrue("两个调用点都必须存在", ladderIndex >= 0 && attachIndex >= 0)
        assertTrue(
            "覆盖率闸的分母只含原文正文：附录必须在阶梯跑完之后才拼（顺序反了会把清单算进逐字命中率）",
            ladderIndex < attachIndex,
        )
    }
}
