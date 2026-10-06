package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 请求消息处理链的源码级守卫。
 *
 * **起因（2026-10-06 事故）**：`GenerationHandler.generateInternal` 里
 *
 * ```kotlin
 * val internalMessages = buildInternalMessages(...)
 * internalMessages.transforms(transformers = transformers, ...)   // ← 裸语句，产物被丢弃
 * providerImpl.streamText(messages = internalMessages, ...)       // ← 发的是「未处理」列表
 * ```
 *
 * 即：时间提醒 / 模式注入 / 世界书 / ST 宏 / 占位符 / 文档转提示 / OCR / 引用展开 / 助手模板 /
 * workspace 提醒**整条输入链的产物被丢掉**，真实请求发的是处理前的列表；而拼装预览
 * （`PromptInspector`）那一份有 `val transformed =`，照常显示注入 ⇒ **「预览里有、实发里没有」**。
 * 编译期与应用层单测都拦不住（类型合法、无异常、只是值没被用），故固化成源码扫描。
 *
 * 两条不变量：
 * 1. **不许把 `.transforms(...)` 写成裸语句**——链的产物必须被接住（赋给变量）。
 * 2. **未经处理链的列表不得发给 provider**——`GenerationHandler` 里不许把 `internalMessages`
 *    直接塞进 `messages =`。
 */
class RequestMessagePipelineGuardTest {

    private fun srcRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    private val generationHandler by lazy {
        srcRoot().resolve("app/androidrik/rikkahub/data/ai/GenerationHandler.kt")
    }
    private val promptInspector by lazy {
        srcRoot().resolve("app/androidrik/rikkahub/data/ai/PromptInspector.kt")
    }

    /** 赋值型调用：`val requestMessages = internalMessages.transforms(` */
    private val assignedTransforms =
        Regex("""^\s*val\s+(\w+)\s*=\s*\w+\.transforms\($""", RegexOption.MULTILINE)

    /** 疑似裸语句调用：`internalMessages.transforms(`（无 `val x =` 前缀）——正是事故形态 */
    private val bareStatementTransforms = Regex("""^\s*[A-Za-z_]\w*\.transforms\($""")

    /**
     * 判断某行是否为「被丢弃的裸语句」。
     *
     * 区别裸语句与**实参**位置：`emit(GenerationChunk.Messages(\n  messages.transforms(` 里
     * 上一非空行以 `(` 结尾 ⇒ 是实参（值被用掉），不算丢弃。
     */
    private fun isDiscardedStatement(lines: List<String>, index: Int): Boolean {
        if (!bareStatementTransforms.matches(lines[index])) return false
        val previousNonBlank = lines.take(index).lastOrNull { it.isNotBlank() } ?: return true
        return !previousNonBlank.trimEnd().endsWith("(")
    }

    @Test
    fun `transform chain result must be assigned not discarded`() {
        listOf(generationHandler, promptInspector).forEach { file ->
            assertTrue("源文件不存在：${file.path}", file.isFile)
            val lines = file.readLines()
            val offenders = lines.withIndex()
                .filter { (index, _) -> isDiscardedStatement(lines, index) }
                .map { (index, line) -> "${file.name}:${index + 1}  ${line.trim()}" }
            assertEquals(
                "输入处理链的产物被丢弃（必须写成 `val x = ...transforms(...)`）：$offenders",
                emptyList<String>(),
                offenders,
            )
        }
    }

    @Test
    fun `provider never receives the untransformed list`() {
        assertTrue("源文件不存在：${generationHandler.path}", generationHandler.isFile)
        val source = generationHandler.readText()

        val assigned = assignedTransforms.findAll(source).map { it.groupValues[1] }.toList()
        assertEquals("GenerationHandler 里应恰有一处「接住」的输入链调用", 1, assigned.size)
        val requestMessages = assigned.single()

        assertTrue(
            "provider 调用应使用处理后的 $requestMessages",
            source.contains("messages = $requestMessages"),
        )
        assertTrue(
            "未经处理链的 internalMessages 不得直接发给 provider",
            !source.contains("messages = internalMessages"),
        )
    }
}
