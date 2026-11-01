package app.androidrik.ai.streaming

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.providers.openai.ResponseAPI
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.isContentFilterTruncation
import app.androidrik.ai.ui.isOutputLimitTruncation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * OpenAI **Responses API** 流式结束语义的真实事件序列回归（审计 AI-2）。
 *
 * 回归背景：`onEvent` 早先只认 `[DONE]` 与 `response.completed`，其余事件全丢。于是：
 * 1. `response.incomplete`（撞输出上限的**规范**信号，`incomplete_details.reason=max_output_tokens`）
 *    没有显式结束标记、也没有 finishReason ⇒ 下游把它当「裸断流」**无条件自动续写**（最多 3 次），
 *    一次截断放大成 4 次计费请求，且用户看不到「已达输出上限」；
 * 2. `response.failed` / `error` 被静默吞掉 ⇒ 生成“成功”但只有半截内容，用户拿不到失败原因。
 *
 * P1（#1648 移植）后流接口从 `MessageChunk` 换成 `StreamChunk`：**显式结束**由一条
 * `StreamChunk.Finish` 表达（旧的 `MessageChunk.explicitEnd` 标记已删除），
 * finishReason / usage / 内容增量分别落在 `Finish`、`Usage`、`TextDelta` 上。
 * 本测试用 MockWebServer 走真实 SSE 连接，锁的是「事件序列 → provider 的 Flow 语义」。
 */
class ResponseAPIStreamFinishTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun sseEvent(event: String, data: String): String = "event: $event\ndata: $data"

    private fun enqueueSse(vararg events: String) {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = Headers.Builder().add("Content-Type", "text/event-stream").build(),
                body = events.joinToString("") { "$it\n\n" },
            )
        )
    }

    private fun api(): ResponseAPI = ResponseAPI(OkHttpClient())

    private fun setting(): ProviderSetting.OpenAI = ProviderSetting.OpenAI(
        name = "responses-stream-test",
        apiKey = "sk-test",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        useResponseApi = true,
    )

    private fun params(): TextGenerationParams = TextGenerationParams(model = Model(modelId = "gpt-5"))

    private fun collect(): List<StreamChunk> = runBlocking {
        withTimeout(20_000) {
            api().streamText(setting(), listOf(UIMessage.user("hi")), params()).toList()
        }
    }

    /** 收集直到流失败，同时保留失败前已送达的 chunk（用于断言 usage/内容没被吞掉）。 */
    private fun collectUntilFailure(): Pair<List<StreamChunk>, Throwable?> {
        val chunks = mutableListOf<StreamChunk>()
        val error = runCatching {
            runBlocking {
                withTimeout(20_000) {
                    api().streamText(setting(), listOf(UIMessage.user("hi")), params())
                        .collect { chunks.add(it) }
                }
            }
        }.exceptionOrNull()
        return chunks to error
    }

    /** provider 是否给出**显式结束**（P1 后即收到了 Finish 事件）。 */
    private fun hasExplicitEnd(chunks: List<StreamChunk>): Boolean =
        chunks.any { it is StreamChunk.Finish }

    private fun finishes(chunks: List<StreamChunk>): List<StreamChunk.Finish> =
        chunks.filterIsInstance<StreamChunk.Finish>()

    private fun finishReasons(chunks: List<StreamChunk>): List<String> =
        finishes(chunks).mapNotNull { it.finishReason }

    private fun usages(chunks: List<StreamChunk>) =
        chunks.filterIsInstance<StreamChunk.Usage>().map { it.usage }

    private fun textOf(chunks: List<StreamChunk>): String =
        chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }

    @Test
    fun `response completed is explicit end with usage`() {
        enqueueSse(
            sseEvent(
                "response.output_text.delta",
                """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"delta":"Hi"}""",
            ),
            sseEvent(
                "response.completed",
                """{"type":"response.completed","response":{"id":"resp_1","status":"completed",""" +
                    """"usage":{"input_tokens":5,"output_tokens":2,"total_tokens":7}}}""",
            ),
        )

        val chunks = collect()

        assertEquals("Hi", textOf(chunks))
        assertTrue(hasExplicitEnd(chunks))
        // 正常完成：Finish.finishReason 取 wire 上的 status（"completed" 不命中截断判定）
        assertEquals(listOf("completed"), finishReasons(chunks))
        assertEquals("resp_1", finishes(chunks).single().responseId)
        assertEquals(7, usages(chunks).last().totalTokens)
    }

    @Test
    fun `response incomplete max_output_tokens ends explicitly and keeps reason`() {
        enqueueSse(
            sseEvent(
                "response.output_text.delta",
                """{"type":"response.output_text.delta","item_id":"msg_2","output_index":0,"delta":"partial"}""",
            ),
            sseEvent(
                "response.incomplete",
                """{"type":"response.incomplete","response":{"id":"resp_2","status":"incomplete",""" +
                    """"incomplete_details":{"reason":"max_output_tokens"},""" +
                    """"usage":{"input_tokens":5,"output_tokens":64,"total_tokens":69}}}""",
            ),
        )

        val chunks = collect()

        // 核心回归 ①：绝对不能是「裸断流」（否则下游无条件自动续写 3 次）
        assertTrue(hasExplicitEnd(chunks))
        // 核心回归 ②：reason 必须到达下游，才能显示「已达输出上限」并走“只提示不自动续写”策略。
        // 解码器给出 #1648 的 "incomplete:<reason>" 拼装格式；本仓 helper 会归一后判定，
        // 故这里既要锁 wire 格式，也要锁「下游真的认得出截断」。
        val reason = finishReasons(chunks).single()
        assertEquals("incomplete:max_output_tokens", reason)
        assertTrue("撞输出上限必须能被 isOutputLimitTruncation 识别", isOutputLimitTruncation(reason))
        // 内容与 usage 照常保留
        assertEquals("partial", textOf(chunks))
        assertEquals(69, usages(chunks).last().totalTokens)
    }

    @Test
    fun `response incomplete content_filter keeps content_filter reason`() {
        enqueueSse(
            sseEvent(
                "response.incomplete",
                """{"type":"response.incomplete","response":{"status":"incomplete",""" +
                    """"incomplete_details":{"reason":"content_filter"}}}""",
            ),
        )

        val chunks = collect()

        assertTrue(hasExplicitEnd(chunks))
        val reason = finishReasons(chunks).single()
        assertEquals("incomplete:content_filter", reason)
        assertTrue("内容拦截必须能被 isContentFilterTruncation 识别", isContentFilterTruncation(reason))
        assertFalse("内容拦截不得同时被判成输出上限", isOutputLimitTruncation(reason))
    }

    @Test
    fun `response incomplete without reason still ends explicitly`() {
        // 缺 incomplete_details 的兼容形状：finishReason 兜底非空，
        // 至少保住「显式结束」，不会退化成会被自动续写的裸断流。
        enqueueSse(
            sseEvent(
                "response.incomplete",
                """{"type":"response.incomplete","response":{"id":"resp_x","status":"incomplete"}}""",
            ),
        )

        val chunks = collect()

        assertTrue(hasExplicitEnd(chunks))
        assertEquals("incomplete", finishReasons(chunks).single())
    }

    @Test
    fun `response failed propagates error and keeps emitted usage`() {
        enqueueSse(
            sseEvent(
                "response.output_text.delta",
                """{"type":"response.output_text.delta","item_id":"msg_3","output_index":0,"delta":"half"}""",
            ),
            sseEvent(
                "response.failed",
                """{"type":"response.failed","response":{"id":"resp_3","status":"failed",""" +
                    """"error":{"code":"server_error","message":"upstream exploded"},""" +
                    """"usage":{"input_tokens":5,"output_tokens":3,"total_tokens":8}}}""",
            ),
        )

        val (chunks, error) = collectUntilFailure()

        assertNotNull(error)
        // 失败原因必须原样到达用户/上游（不能被静默丢弃成半截成功回复）
        assertTrue(error!!.message.orEmpty().contains("upstream exploded"))
        // 失败也可能已计费：usage 要在 close(error) 之前送出
        assertEquals(8, usages(chunks).last().totalTokens)
        // 内容已流出（上游按半截输出策略处理），但绝不能被当成「显式结束」
        assertEquals("half", textOf(chunks))
        assertFalse(hasExplicitEnd(chunks))
    }

    @Test
    fun `error event propagates message instead of silent truncation`() {
        enqueueSse(
            sseEvent(
                "error",
                """{"type":"error","error":{"type":"overloaded_error","message":"service overloaded"},""" +
                    """"sequence_number":1}""",
            ),
        )

        val (_, error) = collectUntilFailure()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("service overloaded"))
    }

    // ---- SSE event 名 / JSON type 只出现一种的兼容网关 ----
    // 解码器只读 JSON 的 type；若网关只发 `event:` 而 payload 没 type，
    // 不把归一后的类型补进 payload 就会 error("chunk type not found") ⇒ 整条流失败。

    @Test
    fun `sse event name only payload without json type still parses`() {
        enqueueSse(
            sseEvent("response.output_text.delta", """{"item_id":"msg_1","output_index":0,"delta":"Hi"}"""),
            sseEvent(
                "response.completed",
                """{"response":{"id":"resp_1","status":"completed","usage":{""" +
                    """"input_tokens":5,"output_tokens":2,"total_tokens":7}}}""",
            ),
        )

        val chunks = collect()

        assertEquals("Hi", textOf(chunks))
        assertTrue(hasExplicitEnd(chunks))
        assertEquals(7, usages(chunks).last().totalTokens)
    }

    @Test
    fun `sse event name only incomplete still keeps reason and explicit end`() {
        enqueueSse(
            sseEvent(
                "response.incomplete",
                """{"response":{"status":"incomplete",""" +
                    """"incomplete_details":{"reason":"max_output_tokens"}}}""",
            ),
        )

        val chunks = collect()

        assertTrue(hasExplicitEnd(chunks))
        assertEquals("incomplete:max_output_tokens", finishReasons(chunks).single())
    }

    @Test
    fun `sse event name only failed still propagates error`() {
        enqueueSse(
            sseEvent(
                "response.failed",
                """{"response":{"status":"failed","error":{"message":"boom from gateway"}}}""",
            ),
        )

        val (chunks, error) = collectUntilFailure()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("boom from gateway"))
        assertFalse(hasExplicitEnd(chunks))
    }

    @Test
    fun `blank sse event name falls back to json type`() {
        // 部分网关发 `event:` 空名（空字符串与缺失同样对待）⇒ 必须回退 JSON 的 type，
        // 否则结束判定失效、退化成裸断流。
        enqueueSse(
            "event: \ndata: {\"type\":\"response.output_text.delta\",\"item_id\":\"m\",\"delta\":\"Hi\"}",
            "event: \ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"total_tokens\":3}}}",
        )

        val chunks = collect()

        assertEquals("Hi", textOf(chunks))
        assertTrue(hasExplicitEnd(chunks))
    }
}
