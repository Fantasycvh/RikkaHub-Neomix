package app.androidrik.ai.streaming

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.providers.ClaudeProvider
import app.androidrik.ai.ui.MessageChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
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
import org.junit.Assert.assertNull
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
 * 为什么用 MockWebServer 而不是直接调解析函数：`onEvent` 是 `EventSourceListener` 的回调，
 * 只有走**真实 SSE 连接**才能证明「事件序列 → MessageChunk 流」这条链路（事件名、JSON 形状、
 * 终止事件顺序）真的接通了。事件形状取自 claude-api 技能的 Anthropic Messages API 流式示例：
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

    private fun collect(): List<MessageChunk> = runBlocking {
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

    private fun finishReasons(chunks: List<MessageChunk>): List<String> =
        chunks.flatMap { it.choices }.mapNotNull { it.finishReason }

    private fun textOf(chunks: List<MessageChunk>): String =
        chunks.flatMap { it.choices }
            .mapNotNull { it.delta }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }

    @Test
    fun `message_delta stop_reason max_tokens reaches finishReason`() {
        enqueueSse(
            sseEvent("message_start", MESSAGE_START),
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
        assertTrue(chunks.any { it.explicitEnd })

        // message_delta 的 usage 也要带来（output_tokens=9）
        assertEquals(9, chunks.mapNotNull { it.usage }.last().completionTokens)
        // 早期 chunk（正文增量）不应带 finishReason
        assertNull(chunks.first().choices.first().finishReason)
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
        assertTrue(chunks.any { it.explicitEnd })
        assertEquals("done", textOf(chunks))
    }

    @Test
    fun `stop_reason survives even when gateway omits the SSE event name`() {
        // 少数中转只发 `data:` 行（不带 `event:`），此时 okhttp 给 listener 的 type 是 null。
        // 解析层兜底读 JSON 的 type 字段，否则 message_stop 认不出来 ⇒ 显式结束丢失。
        enqueueSse(
            sseData("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}"""),
            sseData("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":4}}"""),
            sseData("""{"type":"message_stop"}"""),
        )

        val chunks = collect()

        assertEquals(listOf("max_tokens"), finishReasons(chunks))
        assertTrue(chunks.any { it.explicitEnd })
        assertEquals("Hi", textOf(chunks))
    }

    private companion object {
        /** message_start 只需要 usage 形状成立；content 缺失不影响本测试（parseMessage 会忽略）。 */
        const val MESSAGE_START =
            """{"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":12,"output_tokens":1}}}"""
    }
}
