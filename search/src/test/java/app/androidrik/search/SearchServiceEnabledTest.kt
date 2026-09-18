package app.androidrik.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索服务启停开关。
 *
 * 三个重点：
 * 1. **零迁移**：`enabled` 是后加字段，19 个子类型的旧 JSON（无该键）都必须读回 `true`；
 * 2. **索引语义**：`searchServiceSelected` 是完整列表索引，隐藏停用项后回调拿到的必须是
 *    真实索引（否则选错服务）——`enabledServicesWithIndex` 就是为这个存在的；
 * 3. **降级判据**：选中项被停用 ⇒ [isServiceUsable] 为 false ⇒ 使用点不注册搜索工具。
 */
class SearchServiceEnabledTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    /** 覆盖全部 19 个子类型，防止日后新增子类型时漏掉 `enabled` 声明。 */
    private fun allTypes(): List<SearchServiceOptions> = listOf(
        SearchServiceOptions.BingLocalOptions(),
        SearchServiceOptions.ZhipuOptions(),
        SearchServiceOptions.DoubaoOptions(),
        SearchServiceOptions.TavilyOptions(),
        SearchServiceOptions.ExaOptions(),
        SearchServiceOptions.SearXNGOptions(),
        SearchServiceOptions.LinkUpOptions(),
        SearchServiceOptions.BraveOptions(),
        SearchServiceOptions.MetasoOptions(),
        SearchServiceOptions.OllamaOptions(),
        SearchServiceOptions.PerplexityOptions(),
        SearchServiceOptions.FirecrawlOptions(),
        SearchServiceOptions.JinaOptions(),
        SearchServiceOptions.BochaOptions(),
        SearchServiceOptions.RikkaHubOptions(),
        SearchServiceOptions.GrokOptions(),
        SearchServiceOptions.TinyfishOptions(),
        SearchServiceOptions.SerperOptions(),
        SearchServiceOptions.CustomJsOptions(),
    )

    @Test
    fun `every subtype defaults to enabled`() {
        assertEquals(19, allTypes().size)
        allTypes().forEach { assertTrue(it.displayName + " 应默认启用", it.enabled) }
    }

    @Test
    fun `every subtype round trips the disabled state`() {
        allTypes().forEach { service ->
            val encoded = json.encodeToString(SearchServiceOptions.serializer(), service.withEnabled(false))
            val decoded = json.decodeFromString(SearchServiceOptions.serializer(), encoded)
            assertFalse(service.displayName + " 停用状态应持久化", decoded.enabled)
            // id 不能变，否则选中项会指错
            assertEquals(service.id, decoded.id)
        }
    }

    @Test
    fun `every subtype survives old json without the enabled key`() {
        // 用当前序列化产物去掉 enabled 键，模拟旧版本写入的数据
        allTypes().forEach { service ->
            val element = json.encodeToJsonElement(SearchServiceOptions.serializer(), service).jsonObject
            val legacy = JsonObject(element.filterKeys { it != "enabled" })
            val decoded = json.decodeFromString(SearchServiceOptions.serializer(), legacy.toString())
            assertTrue(service.displayName + " 旧数据应读回启用", decoded.enabled)
        }
    }

    @Test
    fun `withEnabled is identity when unchanged and flips otherwise`() {
        val service = SearchServiceOptions.TavilyOptions()
        assertTrue(service.withEnabled(true) === service)
        assertFalse(service.withEnabled(false).enabled)
        assertTrue(service.withEnabled(false).withEnabled(true).enabled)
    }

    @Test
    fun `withEnabled preserves subtype specific options`() {
        val grok = SearchServiceOptions.GrokOptions(model = "grok-x", customUrl = "https://x").withEnabled(false)
        assertEquals("grok-x", (grok as SearchServiceOptions.GrokOptions).model)
        assertEquals("https://x", grok.customUrl)
        val doubao = SearchServiceOptions.DoubaoOptions(mode = DoubaoSearchMode.GLOBAL).withEnabled(false)
        assertEquals(DoubaoSearchMode.GLOBAL, (doubao as SearchServiceOptions.DoubaoOptions).mode)
    }

    @Test
    fun `enabledServicesWithIndex reports real indices of the full list`() {
        val services = listOf(
            SearchServiceOptions.TavilyOptions(),
            SearchServiceOptions.ExaOptions().withEnabled(false),
            SearchServiceOptions.BraveOptions(),
        )
        val visible = services.enabledServicesWithIndex()
        // 可见项 = Tayvly(0) / Brave(2)：第二个可见项的真实索引必须是 2、不是显示下标 1
        assertEquals(listOf(0, 2), visible.map { it.first })
        assertEquals(listOf("Tavily", "Brave"), visible.map { it.second.displayName })
    }

    @Test
    fun `isServiceUsable reflects existence and enabled state`() {
        val services = listOf(
            SearchServiceOptions.TavilyOptions(),
            SearchServiceOptions.ExaOptions().withEnabled(false),
        )
        assertTrue(services.isServiceUsable(0))
        assertFalse(services.isServiceUsable(1))   // 停用
        assertFalse(services.isServiceUsable(99))  // 越界（列表为空/被删光）
        assertFalse(emptyList<SearchServiceOptions>().isServiceUsable(0))
    }

    @Test
    fun `disabled state does not leak into a list of options`() {
        val services = listOf(SearchServiceOptions.TavilyOptions().withEnabled(false), SearchServiceOptions.ExaOptions())
        assertEquals(1, services.enabledServicesWithIndex().size)
        val encoded = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SearchServiceOptions.serializer()), services)
        val decoded = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SearchServiceOptions.serializer()), encoded)
        assertFalse(decoded[0].enabled)
        assertTrue(decoded[1].enabled)
        // 顺带确认 jsonArray 解析路径不会把 enabled 丢掉（Settings 侧走同一序列化器）
        val raw = Json.parseToJsonElement(encoded).jsonArray
        assertEquals(false, raw[0].jsonObject["enabled"]?.jsonPrimitive?.content?.toBoolean())
    }
}
