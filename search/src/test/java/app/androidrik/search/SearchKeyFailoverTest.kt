package app.androidrik.search

import app.androidrik.ai.provider.ApiKeyEntry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchKeyFailoverTest {

    // ---------- effectiveApiKeys ----------

    @Test
    fun `effectiveApiKeys filters enabled and falls back to legacy apiKey`() {
        // 结构化列表优先：enabled 过滤
        val options = SearchServiceOptions.TavilyOptions().copy(
            apiKey = "legacy",
            apiKeys = listOf(ApiKeyEntry("k1"), ApiKeyEntry("k2", enabled = false), ApiKeyEntry("  ")),
        )
        assertEquals(listOf("k1"), splitSearchApiKeys(options.effectiveApiKeys))
        // 列表空/全禁用 → 回退旧单 key 字段
        val emptyList = SearchServiceOptions.ZhipuOptions().copy(apiKey = "old", apiKeys = emptyList())
        assertEquals("old", emptyList.effectiveApiKeys)
        // 无 key 服务（Bing 本地）→ 空
        val bing = SearchServiceOptions.BingLocalOptions()
        assertEquals("", bing.effectiveApiKeys)
    }

    @Test
    fun `splitSearchApiKeys matches KeyRoulette split rule`() {
        assertEquals(listOf("a", "b"), splitSearchApiKeys("a,b"))
        assertEquals(listOf("a", "b"), splitSearchApiKeys("a\n b "))
        assertEquals(listOf("a"), splitSearchApiKeys("a, a")) // 去重
        assertEquals(emptyList<String>(), splitSearchApiKeys(""))
    }

    // ---------- withNextKey ----------

    @Test
    fun `withNextKey drops first enabled key in priority order`() {
        val options = SearchServiceOptions.TavilyOptions().copy(
            apiKeys = listOf(ApiKeyEntry("k1"), ApiKeyEntry("k2"), ApiKeyEntry("k3")),
        )
        val rotated = options.withNextKey()!!
        assertEquals(listOf("k2", "k3"), splitSearchApiKeys(rotated.effectiveApiKeys))
        val again = rotated.withNextKey()!!
        assertEquals(listOf("k3"), splitSearchApiKeys(again.effectiveApiKeys))
        assertNull(again.withNextKey())
    }

    @Test
    fun `withNextKey skips disabled keys and supports legacy multi-key string`() {
        // 禁用 key 不参与；首个启用的才是"刚失败"的
        val disabled = SearchServiceOptions.ZhipuOptions().copy(
            apiKeys = listOf(ApiKeyEntry("k1", enabled = false), ApiKeyEntry("k2"), ApiKeyEntry("k3")),
        )
        assertEquals(listOf("k3"), splitSearchApiKeys(disabled.withNextKey()!!.effectiveApiKeys))
        // 旧字符串多 key 形态（apiKeys 空，legacy 里塞多个）
        val legacy = SearchServiceOptions.ExaOptions().copy(apiKey = "k1\nk2")
        assertEquals(listOf("k2"), splitSearchApiKeys(legacy.withNextKey()!!.effectiveApiKeys))
        // 轮转副本同步 apiKey 旧字段（备份兼容）
        val rotatedLegacy = legacy.withNextKey()!!
        assertEquals("k2", (rotatedLegacy as SearchServiceOptions.ExaOptions).apiKey)
    }

    @Test
    fun `withNextKey returns null for single empty or keyless services`() {
        assertNull(SearchServiceOptions.TavilyOptions().withNextKey())
        assertNull(SearchServiceOptions.TavilyOptions().copy(apiKey = "only").withNextKey())
        assertNull(SearchServiceOptions.BingLocalOptions().withNextKey())
        assertNull(SearchServiceOptions.SearXNGOptions().withNextKey())
    }

    @Test
    fun `serialization round-trip keeps apiKeys and apiKey fields`() {
        val options = SearchServiceOptions.TavilyOptions().copy(
            apiKey = "legacy",
            apiKeys = listOf(ApiKeyEntry("k1"), ApiKeyEntry("k2", enabled = false)),
        )
        val json = SearchService.json.encodeToString(
            app.androidrik.search.SearchServiceOptions.TavilyOptions.serializer(),
            options,
        )
        val restored = SearchService.json.decodeFromString(
            app.androidrik.search.SearchServiceOptions.TavilyOptions.serializer(),
            json,
        )
        assertEquals(options, restored)
        // id 是随机生成的，仅当 copy 未动 id 时 round-trip 一致
        assertEquals(options.id, restored.id)
    }
}
