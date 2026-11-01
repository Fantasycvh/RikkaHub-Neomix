package app.androidrik.ai.streaming

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.providers.ClaudeProvider
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.isContentFilterTruncation
import app.androidrik.ai.ui.isOutputLimitTruncation
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Claude **流式结束语义**的真实事件序列回归（审计 AI-1）。
 *
 * 回归背景：`streamText` 的 `MessageChunk` 早先恒填 `finishReason = null`——`message_delta` 里
 * 明明带着 `stop_reason`，但解析层从不读它。于是 Anthropic 的 `max_tokens`（撞输出上限）与
 * `refusal`（内容拦截）在下游完全不可见：截断/拦截横幅不显示、续写判定拿不到类型、
 * 用户也没有「继续生成」入口。
 *
 * P1（#1648 移植）后流接口换成 `StreamChunk`：`stop_reason` 落在终态
 * `StreamChunk.Finish.finishReason` 上（旧的 `MessageChunk.explicitEnd` 由「收到 Finish」替代）。
 * 仍用 MockWebServer 走**真实 SSE 连接**，证明「事件序列 → Finish 流」这条链路真的接通。
 *
 * 事件形状取自 Anthropic Messages API 流式示例：
 *
 * ```text
 * event: message_start       data: {"type":"message_start","message":{…,"usage":{…}}}
 * event: content_block_delta data: {"type":"content_block_delta","index":0,"delta":{…}}
 * event: message_delta       data: {"type":"message_delta","delta":{"stop_reason":"…"},"usage":{…}}
 * event: message_stop        data: {"type":"message_stop"}
 * ```
 */
class ClaudeStreamFinishReasonTest {

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

    private fun sseData(data: String): String = "data: $data"

    private fun enqueueSse(vararg events: String) {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = Headers.Builder().add("Content-Type", "text/event-stream").build(),
                body = events.joinToString("") { "$it\n\n" },
            )
        )
    }

    private fun collect(): List<StreamChunk> = runBlocking {
        withTimeout(20_000) {
            ClaudeProvider(OkHttpClient()).streamText(
                providerSetting = ProviderSetting.Claude(
                    name = "claude-stream-test",
                    apiKey = "sk-ant-test",
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                ),
                messages = listOf(UIMessage.user("hi")),
                params = TextGenerationParams(model = Model(modelId = "claude-sonnet-4-5")),
            ).toList()
        }
    }

    private fun finishes(chunks: List<StreamChunk>): List<StreamChunk.Finish> =
        chunks.filterIsInstance<StreamChunk.Finish>()

    private fun finishReasons(chunks: List<StreamChunk>): List<String> =
        finishes(chunks).mapNotNull { it.finishReason }

    private fun textOf(chunks: List<StreamChunk>): String =
        chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }

    private fun usages(chunks: List<StreamChunk>) =
        chunks.filterIsInstance<StreamChunk.Usage>().map { it.usage }

    @Test
    fun `message_delta stop_reason max_tokens reaches finishReason`() {
        enqueueSse(
            sseEvent("message_start", MESSAGE_START),
            sseEvent("content_block_start", CONTENT_BLOCK_START),
            sseEvent(
                "content_block_delta",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
            ),
            sseEvent(
                "message_delta",
                """{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":9}}""",
            ),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        )

        val chunks = collect()

        // 核心回归：stop_reason 必须一路传到下游（且只出现一次——只有 message_delta 携带它）
        assertEquals(listOf("max_tokens"), finishReasons(chunks))
        assertTrue(isOutputLimitTruncation(finishReasons(chunks).single()))

        // 正文照常送达
        assertEquals("Hello", textOf(chunks))

        // message_stop 是显式结束 ⇒ 不能退化成「裸断流」
        assertEquals(1, finishes(chunks).size)
        assertTrue("Finish 必须是最后一个事件", chunks.last() is StreamChunk.Finish)

        // message_delta 的 usage 也要带来（output_tokens=9）
        assertEquals(9, usages(chunks).last().completionTokens)
    }

    @Test
    fun `refusal stop_reason is content-filter truncation not output limit`() {
        enqueueSse(
            sseEvent("message_start", MESSAGE_START),
            sseEvent(
                "message_delta",
                """{"type":"message_delta","delta":{"stop_reason":"refusal"},"usage":{"output_tokens":0}}""",
            ),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        )

        val reason = finishReasons(collect()).single()

        assertEquals("refusal", reason)
        assertTrue(isContentFilterTruncation(reason))
        // 内容拦截与输出上限必须互斥：拦截时续写无意义，不能给「继续生成」
        assertFalse(isOutputLimitTruncation(reason))
    }

    @Test
    fun `normal end_turn is explicit end without truncation semantics`() {
        enqueueSse(
            sseEvent("message_start", MESSAGE_START),
            sseEvent("content_block_start", CONTENT_BLOCK_START),
            sseEvent(
                "content_block_delta",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"done"}}""",
            ),
            sseEvent(
                "message_delta",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""",
            ),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        )

        val chunks = collect()

        assertEquals(listOf("end_turn"), finishReasons(chunks))
        assertFalse(isOutputLimitTruncation("end_turn"))
        assertEquals(1, finishes(chunks).size)
        assertEquals("done", textOf(chunks))
    }

    @Test
    fun `stop_reason survives even when gateway omits the SSE event name`() {
        // 少数中转只发 `data:` 行（不带 `event:`），此时 okhttp 给 listener 的 type 是 null。
        // 解析层兜底读 JSON 的 type 字段，否则 message_stop 认不出来 ⇒ 显式结束丢失。
        enqueueSse(
            sseData("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
            sseData("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}"""),
            sseData("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":4}}"""),
            sseData("""{"type":"message_stop"}"""),
        )

        val chunks = collect()

        assertEquals(listOf("max_tokens"), finishReasons(chunks))
        assertEquals(1, finishes(chunks).size)
        assertEquals("Hi", textOf(chunks))
    }

    @Test
    fun `missing content_block_start is tolerated via inferred block`() {
        // 异常中转可能省略 content_block_start：旧解析容忍（按 delta 直接建 part），
        // 新解码器也必须懒创建 block 并补 TextStart，否则增量挂不到任何 part 上（丢正文）。
        enqueueSse(
            sseEvent("message_start", MESSAGE_START),
            sseEvent(
                "content_block_delta",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""",
            ),
            sseEvent(
                "message_delta",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""",
            ),
            sseEvent("message_stop", """{"type":"message_stop"}"""),
        )

        val chunks = collect()

        assertEquals("Hi", textOf(chunks))
        // 懒创建必须补 Start 事件（没有 Start 消费端不会建 Text part）
        assertEquals(1, chunks.filterIsInstance<StreamChunk.TextStart>().size)
        assertEquals(listOf("end_turn"), finishReasons(chunks))
        assertEquals(1, finishes(chunks).size)
    }

    private companion object {
        /** message_start 只需要 usage 形状成立；content 缺失不影响本测试（parseMessage 会忽略）。 */
        const val MESSAGE_START =
            """{"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":12,"output_tokens":1}}}"""

        /** 真实 Anthropic 流在 content_block_delta 之前一定先发 content_block_start。 */
        const val CONTENT_BLOCK_START =
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
    }
}
