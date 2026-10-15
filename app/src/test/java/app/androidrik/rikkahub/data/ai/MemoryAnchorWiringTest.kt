package app.androidrik.rikkahub.data.ai

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 锚点的**纪律守卫**：AI 不得自填锚点。
 *
 * 为什么用源码扫描而不是行为测试：要钉住的是一条「**不能有这个参数**」的约束 ——
 * 它没有编译错误、也没有运行时异常，只能靠"源码里不存在"来表达。一旦有人给
 * `addMemory` 加上 `isAnchor` 参数、或在 `memory_tool` 里把它暴露给模型，
 * 模型就能把自己写的记忆全标成锚点 ⇒ **system 块每次写入都变 ⇒ 每次写入全量失效**
 * （正是本次改造要根治的问题，实测命中率 98% → 0%）。
 *
 * 依据：bigeye 的同款安全约束（`fragment_store.py:965-971` 白名单 +
 * `tools/memory_tools.py:64`「不接受 AI 自填」）。
 * 见 `docs/design/design-memory-injection-tiering.md`。
 */
class MemoryAnchorWiringTest {

    private fun srcRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    private fun source(relative: String): String =
        File(srcRoot(), relative).readText()

    /** `setAnchor` 是**唯一**能设置锚点的入口（用户路径）。 */
    @Test
    fun `setAnchor is the only anchor-writing entry point`() {
        val repo = source("app/androidrik/rikkahub/data/repository/MemoryRepository.kt")
        assertTrue(
            "缺少 setAnchor —— 用户将没有任何途径固化记忆（L1 会退化成死代码）",
            repo.contains("suspend fun setAnchor("),
        )
    }

    /**
     * `addMemory`（模型的写入路径）**不得**接受锚点参数。
     *
     * 注意断言的是参数表而不是函数体注释 —— 注释里出现 `isAnchor` 是允许的（正说明纪律）。
     */
    @Test
    fun `addMemory must not accept an anchor parameter`() {
        val repo = source("app/androidrik/rikkahub/data/repository/MemoryRepository.kt")
        val start = repo.indexOf("suspend fun addMemory(")
        assertTrue("找不到 addMemory", start >= 0)
        val params = repo.substring(start).substringBefore("): ")
        assertFalse(
            "addMemory 不得接受 isAnchor：模型写入路径一旦能设置锚点，" +
                "system 块就每次写入都变，前缀缓存全废",
            params.contains("isAnchor"),
        )
    }

    /** 锚点标志**不得**出现在对外序列化里（否则模型看得见、会试图影响它）。 */
    @Test
    fun `isAnchor is not serialized to the model`() {
        val model = source("app/androidrik/rikkahub/data/model/Assistant.kt")
        val anchorIdx = model.indexOf("val isAnchor: Boolean = false")
        assertTrue("找不到 AssistantMemory.isAnchor", anchorIdx >= 0)
        // 它前面最近的注解必须是 @Transient（不是 @Serializable 暴露给模型的字段）
        val before = model.substring(0, anchorIdx)
        val lastAnnotation = before.trimEnd().substringAfterLast('\n').trim()
        assertTrue(
            "isAnchor 必须是 @Transient（模型不该看到或设置锚点标志），实际前一行是：$lastAnnotation",
            lastAnnotation.startsWith("@Transient"),
        )
    }
}
