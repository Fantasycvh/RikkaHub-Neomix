package app.androidrik.rikkahub.ui.pages.setting.search

import app.androidrik.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingSearchMatcherTest {

    private fun entry(
        id: String,
        title: String,
        description: String? = null,
        keywords: List<String> = emptyList(),
    ): SettingSearchEntry = SettingSearchEntry(
        id = id,
        title = title.hashCode(),
        description = description?.hashCode(),
        pageTitle = "page".hashCode(),
        section = null,
        route = Screen.Setting,
        keywords = keywords,
    )

    private val strings = hashMapOf<Int, String>()

    private fun register(entry: SettingSearchEntry, title: String, description: String? = null): SettingSearchEntry {
        strings[entry.title] = title
        entry.description?.let { strings[it] = description ?: "" }
        return entry
    }

    private fun search(query: String, entries: List<SettingSearchEntry>): List<SettingSearchHit> =
        SettingSearchMatcher.search(
            query = query,
            entries = entries,
            titleOf = { strings[it] ?: "" },
            descriptionOf = { strings[it] },
        )

    @Test
    fun `blank query returns nothing`() {
        val e = register(entry("a", "主题"), "主题")
        assertTrue(search("", listOf(e)).isEmpty())
        assertTrue(search("   ", listOf(e)).isEmpty())
    }

    @Test
    fun `chinese substring matches title`() {
        val e = register(entry("theme", "主题设置"), "主题设置")
        assertEquals(listOf("theme"), search("主题", listOf(e)).map { it.entry.id })
    }

    @Test
    fun `english match is case insensitive`() {
        val e = register(entry("theme", "Theme"), "Theme")
        assertEquals(listOf("theme"), search("THEME", listOf(e)).map { it.entry.id })
    }

    @Test
    fun `keywords are searched`() {
        val e = register(entry("theme", "主题设置", keywords = listOf("theme", "zhuti")), "主题设置")
        assertEquals(listOf("theme"), search("zhuti", listOf(e)).map { it.entry.id })
        assertEquals(listOf("theme"), search("theme", listOf(e)).map { it.entry.id })
    }

    @Test
    fun `description is searched but ranked below title`() {
        val titleHit = register(entry("a", "深色模式"), "深色模式")
        val descHit = register(
            entry("b", "外观", description = "深色模式跟随系统"),
            "外观",
            description = "深色模式跟随系统",
        )
        val ids = search("深色", listOf(titleHit, descHit)).map { it.entry.id }
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `multiple tokens require all to match`() {
        val both = register(entry("both", "字体大小"), "字体大小", description = "调整聊天字体大小")
        val onlyFirst = register(entry("one", "字体样式"), "字体样式")
        val ids = search("字体 大小", listOf(both, onlyFirst)).map { it.entry.id }
        assertEquals(listOf("both"), ids)
    }

    @Test
    fun `exact title ranks before prefix and contains`() {
        val exact = register(entry("exact", "通知"), "通知")
        val prefix = register(entry("prefix", "通知设置"), "通知设置")
        val contains = register(entry("contains", "消息通知"), "消息通知")
        val ids = search("通知", listOf(contains, prefix, exact)).map { it.entry.id }
        assertEquals(listOf("exact", "prefix", "contains"), ids)
    }

    @Test
    fun `no match returns empty list`() {
        val e = register(entry("theme", "主题设置"), "主题设置")
        assertTrue(search("完全不存在的词", listOf(e)).isEmpty())
    }

    @Test
    fun `entries without description still match by title and keyword`() {
        val e = register(entry("mcp", "MCP", keywords = listOf("mcp 服务")), "MCP")
        assertEquals(listOf("mcp"), search("mcp", listOf(e)).map { it.entry.id })
    }

    @Test
    fun `surrounding whitespace in query is ignored`() {
        val e = register(entry("theme", "主题设置"), "主题设置")
        assertEquals(listOf("theme"), search("  主题  ", listOf(e)).map { it.entry.id })
    }

    @Test
    fun `limit caps result size`() {
        val entries = (1..30).map { register(entry("e$it", "主题 $it"), "主题 $it") }
        val hits = SettingSearchMatcher.search(
            query = "主题",
            entries = entries,
            titleOf = { strings[it] ?: "" },
            descriptionOf = { strings[it] },
            limit = 10,
        )
        assertEquals(10, hits.size)
    }
}
