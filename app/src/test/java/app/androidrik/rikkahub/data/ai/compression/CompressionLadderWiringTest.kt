package app.androidrik.rikkahub.data.ai.compression

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 压缩失败阶梯的**接线守卫**（源码扫描，照 `ThinkingDegenerationWiringTest` 同款）：
 * 判据与编排都有单测覆盖，但 `compressToCheckpoint` 不接阶梯的话功能静默失效，
 * 编译期与运行时单测都发现不了。
 */
class CompressionLadderWiringTest {

    private fun read(relative: String): String {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
        val file = root.resolve(relative)
        assertTrue("源文件不存在：${file.path}", file.isFile)
        return file.readText()
    }

    @Test
    fun `compressToCheckpoint runs the ladder with all three tiers`() {
        val source = read("app/androidrik/rikkahub/service/ChatService.kt")

        assertTrue("compressToCheckpoint 必须走阶梯（否则覆盖率闸不生效）", source.contains("runCompressionLadder("))
        assertTrue("C2 单步档必须受原文长度上限约束", source.contains("COMPRESS_SINGLE_STEP_MAX_CHARS"))
        assertTrue("C4 兜底必须由 buildTruncatedFallback 构造", source.contains("buildTruncatedFallback(sourceText)"))
        assertTrue("降档落库必须打警告（C4 诚实声明原则）", source.contains("compressToCheckpoint fell to tier="))
    }

    @Test
    fun `default compress prompt carries the first person rule`() {
        val source = read("app/androidrik/rikkahub/data/ai/prompts/CompressPrompt.kt")
        assertTrue("第一人称规则（D147）必须进默认提示词", source.contains("first person"))
        assertTrue(source.contains("own memory"))
    }
}
