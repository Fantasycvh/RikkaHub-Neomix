package app.androidrik.rikkahub.data.datastore

import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索提供方**持久化**加载：已退役的 `rikkahub` 必须在多态解码前剔除。
 *
 * 为什么不是「直接删子类就完事」：聊天 provider 的内置项**不进** `providers` 键、每次加载由
 * `refillBuiltIns` 从 `DEFAULT_PROVIDERS` 回填，删默认项不影响已存数据；搜索提供方则是
 * **整份多态列表落盘**在 `search_services` 单 key 里，`RikkaHubOptions` 的 `@SerialName("rikkahub")`
 * 一旦从 sealed 层删掉，存量 JSON 会在 `decodeFromString` 抛 `SerializationException`
 * ⇒ **整份 settings 加载失败**。本测试钉住的就是「解码前剔除」这一步。
 *
 * 另一半是**索引语义**：`searchServiceSelected` 是完整列表索引，剔掉一个元素后其后的位置
 * 全部左移，所以被剔项在选中项之前时必须减一，否则会静默切到**另一个**提供方。
 */
class SearchServicesLoadTest {

    /** 旧版本写入的 `rikkahub` 元素（子类已删，只剩这段 JSON 是合法输入）。 */
    private val rikkahubJson =
        """{"type":"rikkahub","id":"2f0d3a58-4b7c-4c1e-9a5f-3d0f4e6a7b81","enabled":true,""" +
            """"apiKey":"legacy-key","apiKeys":[],"depth":"standard"}"""

    private val tavily = SearchServiceOptions.TavilyOptions()
    private val exa = SearchServiceOptions.ExaOptions()

    private fun encode(option: SearchServiceOptions): String =
        JsonInstant.encodeToString(SearchServiceOptions.serializer(), option)

    @Test
    fun `mixed json keeps normal entries and drops rikkahub`() {
        val raw = "[$rikkahubJson,${encode(tavily)},${encode(exa)}]"

        val result = loadSearchServices(raw, storedSelected = 0)

        // 加载成功（不抛 SerializationException）且只剩两个正常项
        assertEquals(2, result.services.size)
        assertEquals(listOf("Tavily", "Exa"), result.services.map { it.displayName })
        // id 原样保留：选中/编辑都靠它，重建实例会丢关联
        assertEquals(listOf(tavily.id, exa.id), result.services.map { it.id })
        assertEquals(0, result.selected)
    }

    @Test
    fun `only rikkahub falls back to the default provider`() {
        val result = loadSearchServices("[$rikkahubJson]", storedSelected = 0)

        // 与「search_services 键不存在」时同一行为
        assertEquals(listOf(SearchServiceOptions.DEFAULT), result.services)
        assertEquals("Bing", result.services.single().displayName)
        assertEquals(0, result.selected)
    }

    @Test
    fun `removed entry before the selected index shifts it left`() {
        val raw = "[$rikkahubJson,${encode(tavily)},${encode(exa)}]"

        // 用户在旧版本里选中的是 Exa（旧列表索引 2）
        val result = loadSearchServices(raw, storedSelected = 2)

        // 剔除 rikkahub 后 Exa 移到索引 1；若照抄旧索引会越界，若只 clamp 会落回 Tavily（隔壁）
        assertEquals(1, result.selected)
        assertEquals(exa.id, result.services[result.selected].id)
        assertEquals("Exa", result.services[result.selected].displayName)
    }

    @Test
    fun `removed entry at the selected position keeps pointing at its successor`() {
        val raw = "[${encode(tavily)},$rikkahubJson,${encode(exa)}]"

        // 选中的正是将被剔除的那一项 ⇒ 索引不变，落到原来的下一项（不越界）
        val result = loadSearchServices(raw, storedSelected = 1)

        assertEquals(1, result.selected)
        assertEquals(listOf("Tavily", "Exa"), result.services.map { it.displayName })
        assertEquals(exa.id, result.services[result.selected].id)
    }

    @Test
    fun `removed entry after the selected index leaves it unchanged`() {
        val raw = "[${encode(tavily)},${encode(exa)},$rikkahubJson]"

        val result = loadSearchServices(raw, storedSelected = 0)

        assertEquals(0, result.selected)
        assertEquals(tavily.id, result.services[result.selected].id)
    }

    @Test
    fun `out of range selected index is clamped to a valid slot`() {
        // 无剔除时的越界
        assertEquals(1, loadSearchServices("[${encode(tavily)},${encode(exa)}]", storedSelected = 99).selected)
        // 负数
        assertEquals(0, loadSearchServices("[${encode(tavily)},${encode(exa)}]", storedSelected = -7).selected)
        // 剔除后列表变短：越界索引落到剔除后的最后一项，而不是指向不存在的槽位
        val shrunk = loadSearchServices("[$rikkahubJson,${encode(tavily)}]", storedSelected = 99)
        assertEquals(1, shrunk.services.size)
        assertEquals(0, shrunk.selected)
    }

    @Test
    fun `malformed json falls back to the default provider without throwing`() {
        listOf(
            "{not json",
            "[{\"type\":",
            "",
            "null",
            "{\"type\":\"rikkahub\"}", // 不是数组
        ).forEach { raw ->
            val result = loadSearchServices(raw, storedSelected = 5)
            assertEquals("raw=$raw", listOf(SearchServiceOptions.DEFAULT), result.services)
            assertEquals("raw=$raw", 0, result.selected)
        }
    }

    @Test
    fun `unknown polymorphic type falls back instead of crashing the settings load`() {
        val raw = """[{"type":"no_such_provider","id":"2f0d3a58-4b7c-4c1e-9a5f-3d0f4e6a7b81"}]"""

        val result = loadSearchServices(raw, storedSelected = 0)

        assertEquals(listOf(SearchServiceOptions.DEFAULT), result.services)
        assertEquals(0, result.selected)
    }

    @Test
    fun `missing key keeps the previous default behaviour`() {
        val result = loadSearchServices(null, storedSelected = 0)

        assertEquals(listOf(SearchServiceOptions.DEFAULT), result.services)
        assertEquals(0, result.selected)
    }

    @Test
    fun `retired type is exactly the rikkahub serial name`() {
        // 防止日后有人「顺手」把别的提供方塞进退役集合却没同步过滤逻辑
        assertEquals(setOf("rikkahub"), RETIRED_SEARCH_SERVICE_TYPES)
        assertFalse(RETIRED_SEARCH_SERVICE_TYPES.contains("tavily"))
        assertTrue(
            "退役类型必须能被 type 字段识别",
            JsonInstant.parseToJsonElement(rikkahubJson).isRetiredSearchService(),
        )
        assertFalse(JsonInstant.parseToJsonElement(encode(tavily)).isRetiredSearchService())
    }
}
