package app.androidrik.search

import android.content.Context
import androidx.compose.runtime.Composable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.ai.util.KeyRoulette
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.uuid.Uuid

interface SearchService<T : SearchServiceOptions> {
    val name: String

    fun parameters(options: T): InputSchema?

    fun scrapingParameters(options: T): InputSchema?

    @Composable
    fun Description()

    suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<SearchResult>

    suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<ScrapedResult>

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : SearchServiceOptions> getService(options: T): SearchService<T> {
            return when (options) {
                is SearchServiceOptions.TavilyOptions -> TavilySearchService
                is SearchServiceOptions.ExaOptions -> ExaSearchService
                is SearchServiceOptions.ZhipuOptions -> ZhipuSearchService
                is SearchServiceOptions.DoubaoOptions -> DoubaoSearchService
                is SearchServiceOptions.BingLocalOptions -> BingSearchService
                is SearchServiceOptions.SearXNGOptions -> SearXNGService
                is SearchServiceOptions.LinkUpOptions -> LinkUpService
                is SearchServiceOptions.BraveOptions -> BraveSearchService
                is SearchServiceOptions.MetasoOptions -> MetasoSearchService
                is SearchServiceOptions.OllamaOptions -> OllamaSearchService
                is SearchServiceOptions.PerplexityOptions -> PerplexitySearchService
                is SearchServiceOptions.FirecrawlOptions -> FirecrawlSearchService
                is SearchServiceOptions.JinaOptions -> JinaSearchService
                is SearchServiceOptions.BochaOptions -> BochaSearchService
                is SearchServiceOptions.RikkaHubOptions -> RikkaHubSearchService
                is SearchServiceOptions.GrokOptions -> GrokSearchService
                is SearchServiceOptions.TinyfishOptions -> TinyfishSearchService
                is SearchServiceOptions.SerperOptions -> SerperSearchService
                is SearchServiceOptions.CustomJsOptions -> CustomJsSearchService
            } as SearchService<T>
        }

        @Volatile
        internal var httpClient: OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        @Volatile
        internal var keyRoulette: KeyRoulette = KeyRoulette.default()

        fun init(client: OkHttpClient, context: Context? = null) {
            httpClient = client
            keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
        }

        internal val json by lazy {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
    }
}

@Serializable
data class SearchCommonOptions(
    val resultSize: Int = 10
)

@Serializable
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
    val images: List<String> = emptyList(),
    /** 本地检索时间，非发布日期 */
    val retrievedAt: String? = null,
) {
    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
        /** 服务商提供的发布日期；null = 未提供 */
        val publishedDate: String? = null,
        val highlights: List<String> = emptyList(),
    )
}

@Serializable
data class ScrapedResult(
    val urls: List<ScrapedResultUrl>,
    /** 本地检索时间，非发布日期 */
    val retrievedAt: String? = null,
)

@Serializable
data class ScrapedResultUrl(
    val url: String,
    val content: String,
    val metadata: ScrapedResultMetadata? = null,
)

@Serializable
data class ScrapedResultMetadata(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
    /** 服务商提供的发布日期；null = 未提供 */
    val publishedDate: String? = null,
)

@Serializable
sealed class SearchServiceOptions {
    abstract val id: Uuid

    /** 兼容旧单 key 字段（部分服务无 key，默认空）。@Transient：子类型覆盖字段各自序列化，避免层级重名 */
    @Transient
    open val apiKey: String = ""

    /** 多 key（结构化，每 key 可独立启停）；空时回退 [apiKey]。@Transient 同上 */
    @Transient
    open val apiKeys: List<ApiKeyEntry> = emptyList()

    open val displayName: String
        get() = TYPES[this::class] ?: "Unknown"

    companion object {
        val DEFAULT = BingLocalOptions()

        val TYPES = mapOf(
            BingLocalOptions::class to "Bing",
            RikkaHubOptions::class to "RikkaHub",
            ZhipuOptions::class to "智谱",
            DoubaoOptions::class to "豆包",
            TavilyOptions::class to "Tavily",
            ExaOptions::class to "Exa",
            SearXNGOptions::class to "SearXNG",
            LinkUpOptions::class to "LinkUp",
            BraveOptions::class to "Brave",
            MetasoOptions::class to "秘塔",
            OllamaOptions::class to "Ollama",
            PerplexityOptions::class to "Perplexity",
            FirecrawlOptions::class to "Firecrawl",
            JinaOptions::class to "Jina",
            BochaOptions::class to "博查",
            GrokOptions::class to "Grok",
            TinyfishOptions::class to "Tinyfish",
            SerperOptions::class to "Serper",
            CustomJsOptions::class to "Custom JS",
        )
    }

    @Serializable
    @SerialName("bing_local")
    class BingLocalOptions(
        override val id: Uuid = Uuid.random()
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("zhipu")
    data class ZhipuOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("doubao")
    data class DoubaoOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
        val mode: DoubaoSearchMode = DoubaoSearchMode.CUSTOM,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("tavily")
    data class TavilyOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
        val depth: String = "advanced",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("exa")
    data class ExaOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("searxng")
    data class SearXNGOptions(
        override val id: Uuid = Uuid.random(),
        val url: String = "",
        val engines: String = "",
        val language: String = "",
        val username: String = "",
        val password: String = "",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("linkup")
    data class LinkUpOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
        val depth: String = "standard",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("brave")
    data class BraveOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("metaso")
    data class MetasoOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("ollama")
    data class OllamaOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("perplexity")
    data class PerplexityOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
        val maxTokens: Int? = null,
        val maxTokensPerPage: Int? = null,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("firecrawl")
    data class FirecrawlOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("jina")
    data class JinaOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
        val searchUrl: String = "https://s.jina.ai/",
        val scrapeUrl: String = "https://r.jina.ai/",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("bocha")
    data class BochaOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
        val summary: Boolean = true,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("rikkahub")
    data class RikkaHubOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
        val depth: String = "standard",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("grok")
    data class GrokOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
        val model: String = "grok-4-1-fast-non-reasoning",
        val customUrl: String = "https://api.x.ai/v1/responses",
        val systemPrompt: String = "You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user's query. Provide a comprehensive answer with citations.",
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("tinyfish")
    data class TinyfishOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("serper")
    data class SerperOptions(
        override val id: Uuid = Uuid.random(),
        override val apiKey: String = "",
        override val apiKeys: List<ApiKeyEntry> = emptyList(),
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("custom_js")
    data class CustomJsOptions(
        override val id: Uuid = Uuid.random(),
        val name: String = "",
        val searchScript: String = DEFAULT_SEARCH_SCRIPT,
        val scrapeScript: String = "",
    ) : SearchServiceOptions() {
        override val displayName: String
            get() = name.ifBlank { "Custom JS" }
        companion object {
            const val DEFAULT_SCRAPE_SCRIPT = """// Implement scrape(urls) function
// urls is an array of URL strings
// Use fetch(url, options?) for HTTP requests
// fetch() returns { status, ok, text(), json() }
// Return { urls: [{ url, content, metadata?: { title?, description?, language? } }] }

function scrape(urls) {
  return {
    urls: urls.map(function(url) {
      const res = fetch(url);
      const body = res.text();
      return { url: url, content: body };
    })
  };
}"""

            const val DEFAULT_SEARCH_SCRIPT = """// Implement search(query, resultSize) function
// Use fetch(url, options?) for HTTP requests
// fetch() returns { status, ok, text(), json() }
// Return { items: [{ title, url, text }], answer?: string }

function search(query, resultSize) {
  const encoded = encodeURIComponent(query);
  const res = fetch("https://example.com/search?q=" + encoded + "&limit=" + resultSize);
  const data = res.json();
  return {
    items: data.results.map(function(r) {
      return { title: r.title, url: r.url, text: r.snippet };
    })
  };
}"""
        }
    }
}

/**
 * 豆包搜索模式：
 * - GLOBAL（global_search）：全球搜索——精选文档，上限 20 条（DocCount），带图片数控制（MaxImageCountPerDoc），适合综合摘要/快速答案
 * - CUSTOM（web_search）：自定义 Web 搜索——常规网页搜索，上限 50 条（Count），可关查询重写（QueryControl.QueryRewrite=false），适合需要更多候选/完整网页列表
 * 日常默认 CUSTOM（更通用），需要精炼带图摘要可切 GLOBAL。
 */
@Serializable
enum class DoubaoSearchMode {
    @SerialName("global")
    GLOBAL,

    @SerialName("custom")
    CUSTOM,
}

internal suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}


// ---------- 搜索多 key（与服务端 provider 侧同款语义） ----------

/** 多 key 统一读取：enabled 过滤后回退旧单 key 字段（与 ProviderSetting.effectiveApiKeys 一致） */
val SearchServiceOptions.effectiveApiKeys: String
    get() {
        val keys = apiKeys.filter { it.enabled }.map { it.key }.filter { it.isNotBlank() }.distinct()
        return if (keys.isNotEmpty()) keys.joinToString("\n") else apiKey
    }

/** 拆分 key 列表（空白/逗号分隔，与 KeyRoulette.splitKey / 桌面版 splitSearchApiKeys 同规则） */
fun splitSearchApiKeys(keys: String): List<String> =
    keys.split("[\\s,]+".toRegex()).map { it.trim() }.filter { it.isNotBlank() }.distinct()

/**
 * 换 key 轮转：去掉首个生效 key（列表顺序即优先级，首个=刚失败的那个），
 * 剩余 key 写回副本（apiKeys 结构化 + apiKey 旧字段同步，兼容旧字符串多 key）。
 * 剩余 ≤1 个时返回 null（无可切换）。仅内存副本，不落盘。
 */
fun <T : SearchServiceOptions> T.withNextKey(): T? {
    val keys = splitSearchApiKeys(effectiveApiKeys)
    if (keys.size <= 1) return null
    val remaining = keys.drop(1)
    val entries = remaining.map { ApiKeyEntry(it) }
    val legacy = remaining.joinToString("\n")
    @Suppress("UNCHECKED_CAST")
    return when (this) {
        is SearchServiceOptions.BingLocalOptions -> null
        is SearchServiceOptions.SearXNGOptions -> null
        is SearchServiceOptions.CustomJsOptions -> null
        is SearchServiceOptions.ZhipuOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.DoubaoOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.TavilyOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.ExaOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.LinkUpOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.BraveOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.MetasoOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.OllamaOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.PerplexityOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.FirecrawlOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.JinaOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.BochaOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.RikkaHubOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.GrokOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.TinyfishOptions -> copy(apiKeys = entries, apiKey = legacy)
        is SearchServiceOptions.SerperOptions -> copy(apiKeys = entries, apiKey = legacy)
    } as T?
}
