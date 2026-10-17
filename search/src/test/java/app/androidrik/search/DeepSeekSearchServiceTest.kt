package app.androidrik.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek 搜索提供方（独立 Search + Fetch API）的契约守卫。
 *
 * ## 为什么这些断言值得存在
 *
 * 1. **`text` 必须取 `summary`**：文档把 `snippet`（100~200 字符，给前端）和 `summary`
 *    （600~800 字符，给模型）分开；取错了**不会报错**，只会让模型看到的内容质量悄悄掉一截。
 *    所以用例里 `summary` 与 `snippet` 写成不同值，断言取到的是 `summary`（把它改成 `snippet`
 *    必须变红）。
 * 2. **空结果 = 成功**：`results: []` 是「没搜到」不是错误，误判会让模型以为搜索挂了。
 * 3. **可空字段不炸**：`site_name` / `site_icon` / `date` 都可能为 null。
 * 4. **错误要有服务端原文**：只说 `response failed #400` 用户没法自救。
 * 5. **重试规则**：408/429/5xx 重试；**402 不重试**（余额不足，等/换 key 都无用）。
 *
 * HTTP 不打桩库：给 [OkHttpClient] 装一个直接返回假响应的 interceptor，通过既有的
 * `SearchService.init` 注入，整条 `search()` / `scrape()` 路径（请求构造、响应映射、
 * 错误分类、重试）都真跑一遍。
 */
class DeepSeekSearchServiceTest {

    /** 用例开始前的全局 client（[SearchService] 里是可变单例，跑完必须还原） */
    private val originalClient = SearchService.httpClient

    @After
    fun restoreHttpClient() {
        SearchService.init(originalClient, null)
    }

    // ---------------- Search：正常响应 ----------------

    @Test
    fun `search maps summary to text and keeps snippet as highlight`() {
        val server = FakeServer(200 to """{
              "results": [
                {
                  "rerank_score": 2.1,
                  "title": "Kotlin Coroutines",
                  "url": "https://example.com/a",
                  "snippet": "SNIPPET-A: one short line for the UI",
                  "summary": "SUMMARY-A: the long text the model is supposed to read",
                  "content": "",
                  "site_name": "example",
                  "site_icon": "https://example.com/favicon.ico",
                  "date": "2024-05-01T10:00:00Z",
                  "authority": 3
                },
                {
                  "rerank_score": 1.9,
                  "title": "Coroutines Guide",
                  "url": "https://example.com/b",
                  "snippet": "SNIPPET-B",
                  "summary": "SUMMARY-B",
                  "content": "",
                  "site_name": "example",
                  "site_icon": null,
                  "date": "2024-06-02T08:30:00Z",
                  "authority": 2
                }
              ]
            }"""
        )

        val result = search(server)

        val searchResult = result.getOrThrow()
        assertEquals(2, searchResult.items.size)
        assertNull("该 API 不返回合成答案", searchResult.answer)

        val first = searchResult.items[0]
        assertEquals("Kotlin Coroutines", first.title)
        assertEquals("https://example.com/a", first.url)
        // 核心判据：text 取 summary（给模型），不是 snippet（只适合前端）
        assertEquals("SUMMARY-A: the long text the model is supposed to read", first.text)
        assertTrue(
            "text 绝不能等于 snippet —— 取错字段不会报错，只会让模型看到的内容质量下降",
            first.text != "SNIPPET-A: one short line for the UI",
        )
        // snippet 另存 highlights 供前端/引用展示
        assertEquals(listOf("SNIPPET-A: one short line for the UI"), first.highlights)
        // date 原样透传（UTC RFC 3339）
        assertEquals("2024-05-01T10:00:00Z", first.publishedDate)

        assertEquals("SUMMARY-B", searchResult.items[1].text)
        assertEquals(listOf("SNIPPET-B"), searchResult.items[1].highlights)
        assertEquals("2024-06-02T08:30:00Z", searchResult.items[1].publishedDate)
    }

    @Test
    fun `search falls back to snippet when summary is empty`() {
        val server = FakeServer(200 to """{
              "results": [
                {
                  "title": "Only snippet",
                  "url": "https://example.com/only-snippet",
                  "snippet": "SNIPPET-ONLY",
                  "summary": "",
                  "content": ""
                }
              ]
            }"""
        )

        val first = search(server).getOrThrow().items.single()

        assertEquals("summary 为空时必须回退到 snippet，而不是给模型一个空 text", "SNIPPET-ONLY", first.text)
        assertEquals(listOf("SNIPPET-ONLY"), first.highlights)
    }

    @Test
    fun `search treats empty results as success`() {
        val server = FakeServer(200 to """{"results": []}""")

        val result = search(server)

        assertTrue("无匹配（results 为空数组）是成功，不是错误", result.isSuccess)
        assertTrue(result.getOrThrow().items.isEmpty())
        assertEquals("空结果只应请求一次", 1, server.requestCount)
    }

    @Test
    fun `search tolerates null site fields and date`() {
        val server = FakeServer(200 to """{
              "results": [
                {
                  "title": "No metadata",
                  "url": "https://example.com/no-metadata",
                  "snippet": "SNIPPET",
                  "summary": "SUMMARY",
                  "content": "",
                  "site_name": null,
                  "site_icon": null,
                  "date": null
                }
              ]
            }"""
        )

        val item = search(server).getOrThrow().items.single()

        assertEquals("No metadata", item.title)
        assertEquals("SUMMARY", item.text)
        assertNull("date 为 null 时不能造出一个发布日期", item.publishedDate)
        assertEquals("site_name/site_icon 为 null 不影响 snippet 落进 highlights", listOf("SNIPPET"), item.highlights)
    }

    @Test
    fun `search ignores unknown fields`() {
        val server = FakeServer(200 to """{
              "request_id": "req-123",
              "cost": {"search": 1},
              "results": [
                {
                  "title": "Known",
                  "url": "https://example.com/known",
                  "snippet": "S",
                  "summary": "M",
                  "content": "",
                  "brand_new_field": {"nested": [1, 2, 3]},
                  "another_unknown": "x"
                }
              ]
            }"""
        )

        val item = search(server).getOrThrow().items.single()

        assertEquals("Known", item.title)
        assertEquals("M", item.text)
    }

    // ---------------- Search：请求体 ----------------

    @Test
    fun `search clamps count into the documented 1 to 50 range and never asks for content`() {
        val tooMany = FakeServer(200 to """{"results": []}""")
        search(tooMany, resultSize = 2000)
        val bigBody = tooMany.requestBodies.single()
        assertTrue("resultSize 必须夹到 50，实际请求体：$bigBody", bigBody.contains("\"count\":50"))

        val tooFew = FakeServer(200 to """{"results": []}""")
        search(tooFew, resultSize = 0)
        val smallBody = tooFew.requestBodies.single()
        assertTrue("resultSize 必须夹到 1，实际请求体：$smallBody", smallBody.contains("\"count\":1"))

        // 正文（content）字段对搜索阶段无用且会撑大响应体，固定不取
        assertTrue(
            "need_content 必须为 false（本仓契约里没有承载正文的位置），实际请求体：$bigBody",
            bigBody.contains("\"need_content\":false"),
        )
    }

    @Test
    fun `search passes the query through untouched including search syntax`() {
        val server = FakeServer(200 to """{"results": []}""")
        val query = """site:developer.android.com -site:medium.com -"old post" "exact phrase" lang:en after:2024-01-01"""

        search(server, query = query)

        val body = server.requestBodies.single()
        assertTrue(
            "搜索语法必须原样透传（服务端解析 site:/lang:/after: 等），实际请求体：$body",
            body.contains(JsonPrimitive(query).toString()),
        )
    }

    @Test
    fun `search fails without any request when the query is blank`() {
        val server = FakeServer(200 to """{"results": []}""")

        val result = search(server, query = "   ")

        assertTrue(result.isFailure)
        assertEquals("参数非法时不应白花一次计费请求", 0, server.requestCount)
    }

    // ---------------- 错误与重试 ----------------

    @Test
    fun `search surfaces the server error message on 400 without retrying`() {
        val server = FakeServer(400 to """{
              "error": {
                "message": "Invalid request: query is required",
                "type": "invalid_request_error",
                "param": null,
                "code": "invalid_request_error"
              }
            }"""
        )

        val result = search(server)

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "错误信息必须含服务端 message（只给状态码用户没法自救），实际：$message",
            message.contains("Invalid request: query is required"),
        )
        assertTrue("错误信息应带状态码，实际：$message", message.contains("400"))
        assertEquals("其它 4xx 不重试", 1, server.requestCount)
    }

    @Test
    fun `search does not retry 402 and reports insufficient balance`() {
        // 假服务器先给 402 再给 200：只要发生重试，第二个响应就会让请求"成功"
        val server = FakeServer(402 to """{"error": {"message": "Insufficient Balance", "type": "insufficient_balance", "param": null, "code": "402"}}""",
            200 to """{"results": [{"title": "t", "url": "https://example.com/t", "snippet": "s", "summary": "m", "content": ""}]}""",
        )

        val result = search(server)

        assertTrue("402 余额不足必须失败", result.isFailure)
        assertEquals("402 绝不能重试（等多久都没用），重试就会白花一次", 1, server.requestCount)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("错误信息要可辨识为余额不足，实际：$message", message.contains("余额不足"))
        assertTrue("错误信息必须含服务端 message，实际：$message", message.contains("Insufficient Balance"))
    }

    @Test
    fun `search retries 5xx and succeeds`() {
        val server = FakeServer(503 to """{"error": {"message": "service unavailable", "type": "server_error"}}""",
            200 to """{"results": [{"title": "t", "url": "https://example.com/t", "snippet": "s", "summary": "m", "content": ""}]}""",
        )

        val result = search(server)

        assertTrue("5xx 是文档明确可重试的，等待后应重试", result.isSuccess)
        assertEquals("应先 503 再 200：共两次请求", 2, server.requestCount)
        assertEquals("m", result.getOrThrow().items.single().text)
    }

    // ---------------- Fetch ----------------

    @Test
    fun `scrape maps fetch title and content`() {
        val server = FakeServer(
            200 to """{"title": "Example Domain", "content": "# Example\n\nThis domain is for use in examples."}"""
        )

        val result = scrape(server, url = "https://example.com/page")

        val urls = result.getOrThrow().urls
        assertEquals(1, urls.size)
        assertEquals("https://example.com/page", urls.single().url)
        assertEquals("# Example\n\nThis domain is for use in examples.", urls.single().content)
        assertEquals("Example Domain", urls.single().metadata?.title)

        val body = server.requestBodies.single()
        assertTrue("Fetch 请求体是 {\"url\": ...}，实际：$body", body.contains("\"url\":\"https://example.com/page\""))
    }

    @Test
    fun `scrape rejects a url that is not http or carries credentials`() {
        val server = FakeServer(200 to """{"title": "t", "content": "c"}""")

        assertTrue(scrape(server, url = "ftp://example.com/file").isFailure)
        assertTrue(scrape(server, url = "https://user:pw@example.com/x").isFailure)
        assertTrue(scrape(server, url = "not a url").isFailure)
        assertEquals("非法 URL 不应发请求", 0, server.requestCount)
    }

    // ---------------- 注册与命名 ----------------

    @Test
    fun `service is registered and named DeepSeek`() {
        val options = SearchServiceOptions.DeepSeekOptions()

        assertSame(DeepSeekSearchService, SearchService.getService(options))
        assertEquals("DeepSeek", DeepSeekSearchService.name)
        assertEquals("DeepSeek", options.displayName)
        assertNotNull("DeepSeek 支持抓取，必须提供 scrapingParameters", DeepSeekSearchService.scrapingParameters(options))
        assertNotNull("搜索至少要有 query 参数", DeepSeekSearchService.parameters(options))
    }

    // ---------------- helpers ----------------

    private fun options() = SearchServiceOptions.DeepSeekOptions(apiKey = "test-key")

    private fun search(
        server: FakeServer,
        resultSize: Int = 10,
        query: String = "kotlin coroutines",
    ): Result<SearchResult> {
        install(server)
        return runBlocking {
            DeepSeekSearchService.search(
                params = JsonObject(mapOf("query" to JsonPrimitive(query))),
                commonOptions = SearchCommonOptions(resultSize = resultSize),
                serviceOptions = options(),
            )
        }
    }

    private fun scrape(server: FakeServer, url: String): Result<ScrapedResult> {
        install(server)
        return runBlocking {
            DeepSeekSearchService.scrape(
                params = JsonObject(mapOf("url" to JsonPrimitive(url))),
                commonOptions = SearchCommonOptions(),
                serviceOptions = options(),
            )
        }
    }

    private fun install(server: FakeServer) {
        SearchService.init(server.client, null)
    }

    /**
     * 假服务器：按顺序吐固定响应；最后一个响应会被重复使用（这样才测得出「重试了没有」）。
     * 顺带记下每个请求的 body，用来断言 `count` / `need_content` / 搜索语法透传。
     */
    private class FakeServer(vararg responses: Pair<Int, String>) {
        private val queue = ArrayDeque(responses.toList())

        val requestBodies = mutableListOf<String>()
        val requestCount: Int get() = requestBodies.size

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestBodies += request.body?.let { body ->
                    Buffer().also { body.writeTo(it) }.readUtf8()
                } ?: ""
                val (code, payload) =
                    if (queue.size > 1) queue.removeFirst() else queue.first()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "Error")
                    .body(payload.toResponseBody(JSON_MEDIA_TYPE))
                    .build()
            }
            .build()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
