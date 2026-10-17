package app.androidrik.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.search.SearchResult.SearchResultItem
import app.androidrik.search.SearchService.Companion.httpClient
import app.androidrik.search.SearchService.Companion.json
import app.androidrik.search.SearchService.Companion.keyRoulette
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepSeek 官方搜索提供方：走 DeepSeek 独立的 Search + Fetch API（与聊天 API **同一个 key**）。
 *
 * - Search: `POST https://api.deepseek.com/v1/tools/search`
 * - Fetch: `POST https://api.deepseek.com/v1/tools/fetch`
 *
 * ## 字段映射（**本实现的核心判据**）
 *
 * 文档把两条文案字段分得很清楚：`snippet`（100~200 字符，**给前端展现**）与
 * `summary`（600~800 字符，**给模型阅读**）。所以 [SearchResultItem.text] 取 `summary`
 * （缺则回退 `snippet`）——`text` 是模型唯一读到的正文，取 `snippet` 会把模型可见信息
 * 砍掉一大截；`highlights` 再放一份 `snippet`（前端/引用展示用）。
 *
 * ## need_content 为什么写死 false
 *
 * 本仓的 `search` 工具交给模型的字段只有 `text` / `highlights` / `publishedDate`
 * （见 [SearchResultItem]），没有承载「网页正文」的位置；而 `need_content=true` 会让 `content`
 * 返回整页正文，直接把响应体撑大（按次计费不变，但耗时/流量显著上升），搜索阶段又用不上。
 * 既然契约里没有消费 `content` 的地方，就不把这个开关暴露给模型——真实正文需求由
 * `scrape`（Fetch 接口，本文件已实现）承担。等契约新增正文位再放开。
 *
 * ## 重试（文档明确）
 *
 * 408 / 429 / 5xx 等待后重试；**402 不重试**（余额不足，等多久都没用，直接给用户可辨识的提示）；
 * 其它 4xx 不重试（请求非法、key 无效、Fetch 被安全策略拒绝等重试无用）。
 *
 * ## 限流注意（**不要**拿多 key 去规避）
 *
 * QPS 限制（Search 5 / Fetch 5）是**账号粒度**、与用哪个 key 无关，所以这里**不做**
 * 「撞 429 就换 key 重试」——换 key 不可能绕过账号级 QPS，只会白烧一次请求。
 * [keyRoulette] 在这里只承担既有语义（多 key 时按优先级选一个 key），不是限流对策。
 */
object DeepSeekSearchService : SearchService<SearchServiceOptions.DeepSeekOptions> {
    override val name: String = "DeepSeek"

    private const val SEARCH_URL = "https://api.deepseek.com/v1/tools/search"
    private const val FETCH_URL = "https://api.deepseek.com/v1/tools/fetch"

    /** 文档：`count` 1~50（本仓 [SearchCommonOptions.resultSize] 可能超出，必须夹紧） */
    private const val MIN_COUNT = 1
    private const val MAX_COUNT = 50

    /** 文档：Fetch 的 `url` ≤ 8192 字符 */
    private const val MAX_FETCH_URL_LENGTH = 8192

    /** 重试退避（下标 = 第几次重试）。长度即最多额外重试次数：1 次 = 直接失败 */
    private val RETRY_DELAY_MILLIS = longArrayOf(500L, 1500L)

    /** 错误体里拿不到结构化 message 时的原文截断长度（防把整页 HTML 塞进提示） */
    private const val MAX_ERROR_BODY_CHARS = 500

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://platform.deepseek.com/api_keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.DeepSeekOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "search keyword; supports `site:`, `-site:`, `-word`, " +
                            "`-\"phrase\"`, `\"exact phrase\"`, `lang:`, `after:`, `before:` " +
                            "and is passed through to the server as-is"
                    )
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DeepSeekOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: error("query is required")

            val body = buildJsonObject {
                // 搜索语法（site: / -词 / "短语" / lang: / after: …）原样透传，服务端自己解析
                put("query", query)
                put("count", commonOptions.resultSize.coerceIn(MIN_COUNT, MAX_COUNT))
                // 见类注释：契约里没有承载 content 的位置，所以固定不取正文
                put("need_content", false)
            }

            val bodyRaw = executeWithRetry(
                url = SEARCH_URL,
                body = body.toString(),
                apiKey = apiKeyOf(serviceOptions),
                operation = "search",
            )

            val response = runCatching {
                json.decodeFromString<DeepSeekSearchResponse>(bodyRaw)
            }.getOrElse {
                error("Failed to decode DeepSeek search response: ${bodyRaw.take(MAX_ERROR_BODY_CHARS)}")
            }

            // 无匹配时 results 为空数组：这是成功，不是错误
            return@withContext Result.success(
                SearchResult(
                    // 该 API 不返回合成答案，只有结果列表
                    answer = null,
                    items = response.results.map { it.toSearchResultItem() },
                )
            )
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: error("url is required")
            validateFetchUrl(url)

            val body = buildJsonObject {
                put("url", url)
            }

            val bodyRaw = executeWithRetry(
                url = FETCH_URL,
                body = body.toString(),
                apiKey = apiKeyOf(serviceOptions),
                operation = "fetch",
            )

            val response = runCatching {
                json.decodeFromString<DeepSeekFetchResponse>(bodyRaw)
            }.getOrElse {
                error("Failed to decode DeepSeek fetch response: ${bodyRaw.take(MAX_ERROR_BODY_CHARS)}")
            }

            return@withContext Result.success(
                ScrapedResult(
                    urls = listOf(
                        ScrapedResultUrl(
                            url = url,
                            // Fetch 的 content 就是 Markdown 正文
                            content = response.content,
                            metadata = ScrapedResultMetadata(title = response.title.ifBlank { null }),
                        )
                    )
                )
            )
        }
    }

    /**
     * 文档约束的本地前置校验：必须是含 host 的 http/https URL、不能带用户名密码、≤8192 字符。
     * 提前失败比等服务端 400 更便宜（也不白占 QPS 额度）。
     */
    private fun validateFetchUrl(url: String) {
        if (url.length > MAX_FETCH_URL_LENGTH) {
            error("url is too long (max $MAX_FETCH_URL_LENGTH characters)")
        }
        val parsed = url.toHttpUrlOrNull()
            ?: error("url must be an absolute http/https url: $url")
        if (parsed.host.isBlank()) {
            error("url must contain a host: $url")
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            error("url must not contain credentials: $url")
        }
    }

    /**
     * 发请求 + 按文档的重试规则重试（408/429/5xx）；成功返回响应体原文，失败抛带状态码与
     * 服务端 message 的异常。
     */
    private suspend fun executeWithRetry(
        url: String,
        body: String,
        apiKey: String,
        operation: String,
    ): String {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            val (code, bodyRaw) = httpClient.newCall(request).execute().use { response ->
                response.code to response.body.string()
            }

            if (code in 200..299) return bodyRaw

            if (isRetryable(code) && attempt < RETRY_DELAY_MILLIS.size) {
                delay(RETRY_DELAY_MILLIS[attempt])
                attempt++
                continue
            }

            error(failureMessage(code, bodyRaw, operation))
        }
    }

    /** 文档：408、429、5xx 等待后重试；其余（含 402 / 401 / 403 / 404 / 413）不重试 */
    private fun isRetryable(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    /**
     * 可辨识的错误信息：OpenAI 兼容错误体（`error.message`/`type`/`code`）优先，
     * 解析不出结构化错误时退回响应原文。402 单独点明「余额不足且不重试」。
     */
    private fun failureMessage(code: Int, bodyRaw: String, operation: String): String {
        val detail = runCatching {
            json.decodeFromString<DeepSeekErrorEnvelope>(bodyRaw).error
        }.getOrNull()?.let { error ->
            listOfNotNull(error.message, error.type, error.code)
                .filter { it.isNotBlank() }
                .joinToString(" | ")
        }?.takeIf { it.isNotBlank() }
            ?: bodyRaw.take(MAX_ERROR_BODY_CHARS).ifBlank { "<empty body>" }

        val hint = when (code) {
            401 -> "DeepSeek API key 无效（请检查 key）"
            402 -> "DeepSeek 余额不足，请前往 DeepSeek 开放平台充值（该错误不重试）"
            403 -> "DeepSeek 拒绝该请求（Fetch 可能被安全策略拦截）"
            404 -> "DeepSeek 未取到可用内容"
            408 -> "DeepSeek 读超时"
            413 -> "DeepSeek 请求体过大"
            429 -> "DeepSeek 请求超出 QPS 限制（账号级 QPS，换 key 无效）"
            else -> "DeepSeek $operation 请求失败"
        }
        return "$hint（HTTP $code）：$detail"
    }

    /**
     * 多 key 选择沿用既有语义（[keyRoulette]）——**不是**限流对策：QPS 是账号级，
     * 换 key 绕不过去（见类注释）。
     */
    private fun apiKeyOf(options: SearchServiceOptions.DeepSeekOptions): String =
        keyRoulette.next(options.effectiveApiKeys, options.id.toString())

    /**
     * 单条结果映射。`text` = `summary`（给模型阅读，缺则回退 `snippet`）；
     * `highlights` = `snippet`（前端展现用）；`publishedDate` = `date` 原样（UTC RFC 3339）。
     */
    private fun DeepSeekSearchResultDto.toSearchResultItem(): SearchResultItem =
        SearchResultItem(
            title = title,
            url = url,
            text = summary.ifBlank { snippet },
            publishedDate = date?.takeIf { it.isNotBlank() },
            highlights = if (snippet.isBlank()) emptyList() else listOf(snippet),
        )

    @Serializable
    data class DeepSeekSearchResponse(
        val results: List<DeepSeekSearchResultDto> = emptyList(),
    )

    /**
     * Search 结果项。字段按文档；未知字段由 `ignoreUnknownKeys` 忽略，
     * `site_name` / `site_icon` / `date` 允许为 null。
     */
    @Serializable
    data class DeepSeekSearchResultDto(
        val title: String,
        val url: String,
        val snippet: String = "",
        val summary: String = "",
        /** `need_content=false` 时恒为空串（或缺失）——本实现不使用 */
        val content: String = "",
        @SerialName("site_name")
        val siteName: String? = null,
        @SerialName("site_icon")
        val siteIcon: String? = null,
        /** UTC RFC 3339 */
        val date: String? = null,
        /** -1 未知，≥3 极权威（本仓契约无此位，保留仅用于文档对齐） */
        val authority: Int = -1,
        /** 0~3，≥1.5 为优质（同上，不进入 [SearchResultItem]） */
        @SerialName("rerank_score")
        val rerankScore: Double = 0.0,
    )

    @Serializable
    data class DeepSeekFetchResponse(
        val title: String = "",
        /** Markdown 正文 */
        val content: String = "",
    )

    @Serializable
    data class DeepSeekErrorEnvelope(
        val error: DeepSeekErrorBody? = null,
    )

    @Serializable
    data class DeepSeekErrorBody(
        val message: String? = null,
        val type: String? = null,
        val code: String? = null,
    )
}
