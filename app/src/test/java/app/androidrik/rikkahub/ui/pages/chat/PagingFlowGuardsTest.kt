package app.androidrik.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话抽屉分页流的**形态护栏**，对应 2026-10-04 的一台真机崩溃：
 *
 * ```
 * java.lang.IllegalStateException: Attempt to collect twice from pageEventFlow,
 * which is an illegal operation. Did you forget to call Flow<PagingData<*>>.cachedIn(coroutineScope)?
 * ```
 *
 * 起因不是漏了 `cachedIn`（它一直在末尾），而是 `combine` 用**同一个** PagingData 重新包了一层：
 *
 * ```kotlin
 * .flatMapLatest { ... Pager ... }
 * .combine(customOrderActive) { pagingData, customOrder -> pagingData to customOrder }
 * .map { (pagingData, customOrder) -> pagingData.map {}.insertSeparators { ... } }   // ← 二次收集
 * .cachedIn(viewModelScope)
 * ```
 *
 * `PagingData` 承载的是**单次可收集**的 `PageFetcherSnapshot.pageEventFlow`，对它再套一层
 * `map`/`insertSeparators` 不会产生新快照；标志一变就拿着旧快照再收集一次 ⇒ 崩溃。
 * 而那条路径（某档首次落值，标志 false→true）是**每个自定义顺序用户都会走**的一步。
 *
 * 约束：**包装 PagingData 的运算符只能出现在创建 Pager 的那个 `flatMapLatest` 内**，
 * 非分页标志要作为 `flatMapLatest` 的 key（变化时新建 Pager ⇒ 新快照）。
 *
 * 扫描是文本级、括号配对的近似实现（不解析字符串/注释里的括号）；这两个文件里没有这类
 * 内容，够用且能真实拦住上面的形状。
 */
class PagingFlowGuardsTest {

    private fun source(path: String): String {
        val candidates = listOf("src/main/java/$path", "app/src/main/java/$path")
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 $path（测试工作目录=${File(".").absolutePath}）")
        return file.readText()
    }

    private fun vmSource(): String = source("app/androidrik/rikkahub/ui/pages/chat/ChatDrawerVM.kt")

    /** 取 `marker` 之后每个 `{ ... }` 块（含括号）的文本，按出现顺序。 */
    private fun braceBlocks(src: String, marker: String): List<String> {
        val blocks = mutableListOf<String>()
        var index = src.indexOf(marker)
        while (index >= 0) {
            val open = src.indexOf('{', index)
            if (open < 0) break
            var depth = 0
            var cursor = open
            while (cursor < src.length) {
                when (src[cursor]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                cursor++
            }
            blocks += src.substring(open, minOf(cursor + 1, src.length))
            index = src.indexOf(marker, cursor + 1)
        }
        return blocks
    }

    /** 只保留真实代码行（丢掉注释行），避免注释里的示例被当成违规。 */
    private fun codeLines(src: String): List<String> = src.lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }

    /** 唯一的「创建会话分页 Pager」的 flatMapLatest 块。 */
    private fun pagerBlock(): String {
        val blocks = braceBlocks(vmSource(), ".flatMapLatest").filter {
            it.contains("ConversationsOfAssistantPaging(") || it.contains("ConversationsOfFolderPaging(")
        }
        assertEquals("ChatDrawerVM 里应当只有一处创建会话分页 Pager 的 flatMapLatest", 1, blocks.size)
        return blocks.single()
    }

    @Test
    fun `PagingData 的包装必须发生在创建 Pager 的 flatMapLatest 内`() {
        val block = pagerBlock()
        assertTrue(
            "分页项包装必须在 Pager 块内：在流外包装会二次收集 pageEventFlow",
            block.contains("ConversationListItem.Item(it)"),
        )
        assertTrue(
            "insertSeparators 必须在 Pager 块内（每次都是新快照）",
            block.contains("insertSeparators"),
        )
        // 反向确认：整份文件里的 insertSeparators 调用（带类型参数）只有这一处
        val calls = codeLines(vmSource()).filter { it.contains(".insertSeparators<") }
        assertEquals(
            "insertSeparators 只应出现在创建 Pager 的 flatMapLatest 内：$calls",
            1,
            calls.size,
        )
    }

    @Test
    fun `禁止把 PagingData 交给 combine 换标志`() {
        val offenders = codeLines(vmSource()).filter { it.contains("combine(") && it.contains("pagingData") }
        assertTrue("分页流上不得用 combine 换非分页标志：$offenders", offenders.isEmpty())
        assertFalse(
            "旧形状（combine 出 pagingData to customOrder）会二次收集 pageEventFlow",
            vmSource().contains(".combine(customOrderActive) { pagingData"),
        )
    }

    @Test
    fun `自定义顺序标志是分页 flatMapLatest 的 key`() {
        assertTrue(
            "分页流应由 (助手, 文件夹, 自定义顺序) 三者共同驱动",
            Regex(
                """combine\(assistantIdFlow, _selectedFolderId, customOrderActive\) \{[\s\S]{0,120}?Triple\("""
            ).containsMatchIn(vmSource()),
        )
        assertTrue(
            "标志必须出现在 flatMapLatest 的入参里（变化 ⇒ 新建 Pager）",
            Regex("""\.flatMapLatest \{ \(assistantId, folderId, customOrder\) ->""").containsMatchIn(vmSource()),
        )
        assertTrue(
            "日期分组头开关必须取自该 key，而不是流外变量",
            pagerBlock().contains("val wantDateHeader = !customOrder"),
        )
    }
}
