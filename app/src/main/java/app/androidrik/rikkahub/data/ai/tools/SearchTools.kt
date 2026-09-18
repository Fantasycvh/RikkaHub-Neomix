package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.utils.JsonInstantPretty
import app.androidrik.rikkahub.utils.toLocalString
import app.androidrik.search.SearchService
import app.androidrik.search.SearchServiceOptions
import app.androidrik.search.effectiveApiKeys
import app.androidrik.search.isServiceUsable
import app.androidrik.search.splitSearchApiKeys
import app.androidrik.search.withNextKey
import app.androidrik.rikkahub.data.ai.isKeySwitchingError
import java.time.LocalDate
import kotlin.time.Clock
import kotlin.uuid.Uuid

fun createSearchTools(settings: Settings): Set<Tool> {
    // 停用降级：当前选中的搜索服务被停用 ⇒ 不注册搜索工具（等价于该会话关闭联网搜索），
    // 而不是让模型调用时才失败。用户重新启用或换选服务后，下一次生成自动恢复。
    if (!settings.searchServices.isServiceUsable(settings.searchServiceSelected)) {
        return emptySet()
    }
    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = """
                    Search the web for up-to-date or specific information.
                    Use this when the user asks for the latest news, current facts, or needs verification.
                    Do not treat result order as proof of freshness. Prefer primary sources and inspect
                    each result's title, URL, publication date, and content before making a current claim.
                    Use the optional publication-date and domain filters only when they match the question.
                    If a date or primary source is missing, or sources conflict, run another focused search
                    or use scrape_web to verify the most relevant source before answering.
                    Generate focused keywords and run multiple searches if needed.
                    Today is ${LocalDate.now().toLocalString(true)}.

                    Response format:
                    - retrievedAt is the local retrieval time, never a publication date
                    - items[].id (short id), index, title, url, publishedDate (if supplied), highlights (if supplied), text
                    - images[]: image urls related to the query (may be empty)

                    Citations:
                    - After using results, add `[citation,domain](id)` after the sentence.
                    - Multiple citations are allowed.
                    - If no results are cited, omit citations.

                    Images:
                    - When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.
                    - Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).
                    - Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.

                    Example:
                    The capital of France is Paris. [citation,example.com](abc123)
                    The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
                    """.trimIndent(),
                parameters = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    service.parameters(options)
                },
                execute = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    val result = withSearchKeyFailover(options) { current ->
                        service.search(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = current,
                        )
                    }
                    val searchResult = result.getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                    val results =
                        JsonInstantPretty.encodeToJsonElement(searchResult).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["items"] =
                                JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                    JsonObject(item.jsonObject.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                    })
                                })
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT })
        val service = SearchService.getService(options)
        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Avoid using it for common questions unless the user asks.
                        """.trimIndent(),
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        service.scrapingParameters(options)
                    },
                    execute = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        val result = withSearchKeyFailover(options) { current ->
                            service.scrape(
                                params = it.jsonObject,
                                commonOptions = settings.searchCommonOptions,
                                serviceOptions = current,
                            )
                        }
                        val scraped = result.getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                        val payload = JsonInstantPretty.encodeToJsonElement(scraped).jsonObject
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                ))
        }
    }
}

/**
 * 搜索 key 失败转移：请求遇"换 key 可解决"错误（401/403/429/余额耗尽/限流，复用生成侧
 * isKeySwitchingError 分类）且还有下一个 key 时，轮转副本重试（去首个生效 key，顺序=优先级）。
 * 每 key 至多试一次：剩 1 个 key 时不再切换，错误原样上抛（Result.failure）。
 * 单 key 用户零开销（effectiveApiKeys 拆分 ≤1 → 直接返回原结果）。
 */
private suspend fun <T : SearchServiceOptions, R> withSearchKeyFailover(
    options: T,
    block: suspend (T) -> Result<R>,
): Result<R> {
    var current = options
    while (true) {
        val result = block(current)
        val error = result.exceptionOrNull()
        if (error != null && isSearchKeySwitchable(current, error)) {
            val rotated = current.withNextKey() ?: return result
            android.util.Log.w("SearchTools", "search key error, switching key (${splitSearchApiKeys(rotated.effectiveApiKeys).size} left): ${error.message}")
            current = rotated
            continue
        }
        return result
    }
}

private fun <T : SearchServiceOptions> isSearchKeySwitchable(options: T, error: Throwable): Boolean {
    if (!isKeySwitchingError(error)) return false
    return splitSearchApiKeys(options.effectiveApiKeys).size > 1
}
