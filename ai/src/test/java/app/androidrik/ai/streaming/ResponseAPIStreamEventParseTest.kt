package app.androidrik.ai.streaming

import app.androidrik.ai.provider.providers.openai.ResponseAPI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Responses API 流事件的**解析层**单测（审计 AI-2 的 parser 部分）。
 *
 * 与 [ResponseAPIStreamFinishTest]（经 MockWebServer 走真实 SSE + listener）互补：
 * - 这里锁 `parseResponseDelta` 对 `response.incomplete / completed / failed / error`
 *   的**返回契约**（finishReason/usage/id/是否 explicitEnd、未知/失败事件返回 null）；
 * - 真实 SSE 测试锁「事件序列 → Flow 语义」（explicitEnd、错误传播、usage 先于 close）。
 *
 * 契约要点：解析层**不发** `explicitEnd`（那是 listener/调用方的职责），只负责把
 * `incomplete_details.reason` 映射成 finishReason——否则 `max_output_tokens` 这类截断
 * 在下游看不见，且事件会被当裸断流。
 */
class ResponseAPIStreamEventParseTest {

    private lateinit var api: ResponseAPI
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    private fun parse(payload: String) =
        api.parseResponseDelta(json.parseToJsonElement(payload).jsonObject)

    @Test
    fun `response incomplete maps reason to finishReason and keeps usage`() {
        val chunk = parse(
            """{"type":"response.incomplete","response":{"id":"resp_1","status":"incomplete",""" +
                """"incomplete_details":{"reason":"max_output_tokens"},""" +
                """"usage":{"input_tokens":5,"output_tokens":64,"total_tokens":69}}}""",
        )

        requireNotNull(chunk)
        assertEquals("resp_1", chunk.id)
        // 终止标记由 listener 发；解析层只提供元数据
        assertFalse(chunk.explicitEnd)
        assertEquals("max_output_tokens", chunk.choices.single().finishReason)
        assertNull(chunk.choices.single().delta)
        assertEquals(69, chunk.usage?.totalTokens)
        assertEquals(5, chunk.usage?.promptTokens)
        assertEquals(64, chunk.usage?.completionTokens)
    }

    @Test
    fun `response incomplete content_filter maps reason`() {
        val chunk = parse(
            """{"type":"response.incomplete","response":{"status":"incomplete",""" +
                """"incomplete_details":{"reason":"content_filter"}}}""",
        )

        assertEquals("content_filter", chunk?.choices?.single()?.finishReason)
    }

    @Test
    fun `response incomplete without reason uses non-blank fallback`() {
        // 兼容形状：缺 incomplete_details 时不能给出 null/空 —— 否则下游既没有截断提示，
        // 也可能把「有类型的结束」误判成裸断流。
        val chunk = parse(
            """{"type":"response.incomplete","response":{"id":"resp_x","status":"incomplete"}}""",
        )

        val reason = chunk?.choices?.single()?.finishReason
        assertEquals("incomplete", reason)
        assertFalse(reason.isNullOrBlank())
    }

    @Test
    fun `response completed carries usage and no finishReason`() {
        val chunk = parse(
            """{"type":"response.completed","response":{"id":"resp_2","status":"completed",""" +
                """"usage":{"input_tokens":5,"output_tokens":2,"total_tokens":7}}}""",
        )

        requireNotNull(chunk)
        // completed 是正常结束：没有 stop_reason 语义，不能硬造一个（否则会误显示截断）
        assertTrue(chunk.choices.isEmpty())
        assertEquals(7, chunk.usage?.totalTokens)
    }

    @Test
    fun `failed and error events are not delta chunks - listener owns error propagation`() {
        // 失败事件带的是 error/usage，不是内容增量：解析层返回 null，
        // 由 onEvent 负责「先送 usage 再 close(error)」（见真实 SSE 测试）。
        assertNull(
            parse(
                """{"type":"response.failed","response":{"status":"failed",""" +
                    """"error":{"code":"server_error","message":"boom"}}}""",
            )
        )
        assertNull(
            parse(
                """{"type":"error","error":{"type":"overloaded_error","message":"overloaded"},""" +
                    """"sequence_number":1}""",
            )
        )
    }

    @Test
    fun `unknown event types stay ignored`() {
        assertNull(parse("""{"type":"response.output_text.done","item_id":"msg_1","text":"Hi"}"""))
        assertNull(parse("""{"type":"response.content_part.done","item_id":"msg_1"}"""))
    }
}
