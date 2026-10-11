package app.androidrik.rikkahub.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ST 世界书导入的保真测试。
 *
 * 背景（私有仓记录·前期调研 §六 6.3）：原实现有一处**事实性错误**——
 * 注释断言「SillyTavern 格式不支持 useRegex」并硬写 `useRegex = false`，而 ST 的 key
 * 支持 `/pattern/flags`（`parseRegexFromString`，`world-info.js:2901`，且正则**优先于**
 * 其它匹配选项）。这会让带正则键的条目按**字面量**匹配 → 触发条件整体改变。
 */
class SillyTavernKeywordParseTest {

    @Test
    fun `plain keywords stay literal`() {
        val parsed = parseStKeywords(listOf("apple", "banana"))
        assertFalse(parsed.useRegex)
        assertEquals(listOf("apple", "banana"), parsed.keywords)
    }

    @Test
    fun `regex keyword enables regex mode and is unwrapped`() {
        val parsed = parseStKeywords(listOf("/\\bsword\\b/i"))
        assertTrue("regex form must enable useRegex", parsed.useRegex)
        assertEquals(listOf("\\bsword\\b"), parsed.keywords)
    }

    @Test
    fun `mixed list keeps all keywords and enables regex`() {
        // fork 只有一个 useRegex 开关：混合列表无法精确表达，宁可整体走正则语义也不要丢正则
        val parsed = parseStKeywords(listOf("literal", "/^magic$/"))
        assertTrue(parsed.useRegex)
        assertEquals(listOf("literal", "^magic$"), parsed.keywords)
    }

    @Test
    fun `escaped slash inside pattern is restored`() {
        val parsed = parseStKeywords(listOf("""/a\/b/"""))
        assertTrue(parsed.useRegex)
        assertEquals(listOf("a/b"), parsed.keywords)
    }

    @Test
    fun `unescaped inner slash is not treated as regex`() {
        // ST 自己也会拒绝这种 pattern（其它正则引擎解析失败）→ 保持字面量
        val parsed = parseStKeywords(listOf("/a/b/"))
        assertFalse(parsed.useRegex)
        assertEquals(listOf("/a/b/"), parsed.keywords)
    }

    @Test
    fun `empty slash pair is not regex`() {
        // ST 的形态要求两斜杠之间至少一个字符（`+?`）→ `//` 不算正则
        val parsed = parseStKeywords(listOf("//"))
        assertFalse(parsed.useRegex)
        assertEquals(listOf("//"), parsed.keywords)
    }

    @Test
    fun `date like keyword is not mistaken for regex`() {
        val parsed = parseStKeywords(listOf("2026/09/17"))
        assertFalse("plain text with slashes must stay literal", parsed.useRegex)
    }
}

/**
 * ST 位置映射的保真测试。
 *
 * 修正记录（私有仓记录·前期调研 §六 6.3）：原实现把 `ANTop(2)` 与 `ANBottom(3)`
 * **都**映射为 `TOP_OF_CHAT`。核实 ST：两者是包在**作者注释**前后（`world-info.js:5270`），
 * 而作者注释默认在 **in-chat depth 4**（`authors-note.js:272-273`）——不是「对话最开头」。
 * 映射到 `TOP_OF_CHAT` 会把内容挪到与真实位置相反的一端。
 */
class SillyTavernPositionMapTest {

    @Test
    fun `before and after char map to system prompt sides`() {
        assertEquals(
            app.androidrik.rikkahub.data.model.InjectionPosition.BEFORE_SYSTEM_PROMPT,
            mapSillyTavernPosition(0),
        )
        assertEquals(
            app.androidrik.rikkahub.data.model.InjectionPosition.AFTER_SYSTEM_PROMPT,
            mapSillyTavernPosition(1),
        )
    }

    @Test
    fun `author note positions map to depth not chat top`() {
        val anTop = mapSillyTavernPosition(2)
        val anBottom = mapSillyTavernPosition(3)
        assertEquals(app.androidrik.rikkahub.data.model.InjectionPosition.AT_DEPTH, anTop)
        assertEquals(app.androidrik.rikkahub.data.model.InjectionPosition.AT_DEPTH, anBottom)
        // 回归防线：绝不能回到 TOP_OF_CHAT（那会把内容挪到对话最开头）
        assertTrue(
            anTop != app.androidrik.rikkahub.data.model.InjectionPosition.TOP_OF_CHAT &&
                anBottom != app.androidrik.rikkahub.data.model.InjectionPosition.TOP_OF_CHAT,
        )
    }

    @Test
    fun `at depth maps to depth`() {
        assertEquals(
            app.androidrik.rikkahub.data.model.InjectionPosition.AT_DEPTH,
            mapSillyTavernPosition(4),
        )
    }

    @Test
    fun `unsupported positions fall back to after system prompt`() {
        // EM 锚点(5/6)与 outlet(7) fork 无对应概念
        listOf(5, 6, 7, 99).forEach { position ->
            assertEquals(
                "position $position should fall back",
                app.androidrik.rikkahub.data.model.InjectionPosition.AFTER_SYSTEM_PROMPT,
                mapSillyTavernPosition(position),
            )
        }
    }
}
