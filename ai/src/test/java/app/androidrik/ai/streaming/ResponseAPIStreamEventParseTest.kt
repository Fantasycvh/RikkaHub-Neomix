package app.androidrik.ai.streaming

import app.androidrik.ai.provider.providers.openai.ResponseApiStreamDecoder
import app.androidrik.ai.provider.stream.DecodeResult
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.isContentFilterTruncation
import app.androidrik.ai.ui.isOutputLimitTruncation
import app.androidrik.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses API 流事件的**解析层**单测（审计 AI-2，P1 起由 [ResponseApiStreamDecoder] 驱动）。
 *
 * 与 [ResponseAPIStreamFinishTest]（经 MockWebServer 走真实 SSE + listener）互补：
 * - 这里锁解码器对 `response.incomplete / completed / failed / error` 的**事件契约**
 *   （Finish.finishReason / responseId / usage / completed 标志 / 是否抛错）；
 * - 真实 SSE 测试锁「事件序列 → Flow 语义」（Finish 存在性、错误传播、usage 先于 close）。
 *
 * 契约要点（移植 #1648 后的新形状，与旧 `parseResponseDelta` 的差异）：
 * - 解析层**不再产出** `explicitEnd` 标记：请求流上以一条 `StreamChunk.Finish` 表达，
 *   `DecodeResult.completed = true` 让传输层主动收尾；
 * - 截断原因被编码进 Finish.finishReason，格式是上游 #1648 的 `"incomplete:<reason>"`；
 *   本仓 `isOutputLimitTruncation()`/`isContentFilterTruncation()` 内部会
 *   `substringAfterLast(':')` 归一再判定，因此**前缀不影响**截断识别（见最后一个用例）。
 */
class ResponseAPIStreamEventParseTest {

    /** 每个用例一个解码器实例：解码器有状态（open part / toolCallId 映射）。 */
    private fun accept(payload: String, event: String? = null): DecodeResult =
        ResponseApiStreamDecoder().accept(SseEvent(event = event, data = payload))

    @Test
    fun `response incomplete maps reason to finishReason and keeps usage`() {
        val result = accept(
            """{"type":"response.incomplete","response":{"id":"resp_1","status":"incomplete",""" +
                """"incomplete_details":{"reason":"max_output_tokens"},""" +
                """"usage":{"input_tokens":5,"output_tokens":64,"total_tokens":69}}}""",
        )

        assertTrue(result.completed)
        // 终止标记：解码器产出 Finish（旧契约是 listener 发 explicitEnd，P1 后统一为 Finish）
        val finish = result.chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("resp_1", finish.responseId)
        // #1648 的拼装格式带 "incomplete:" 前缀；本仓 helper 归一后仍能识别为输出上限截断
        assertEquals("incomplete:max_output_tokens", finish.finishReason)
        assertTrue(isOutputLimitTruncation(finish.finishReason))
        val usage = result.chunks.filterIsInstance<StreamChunk.Usage>().single().usage
        assertEquals(69, usage.totalTokens)
        assertEquals(5, usage.promptTokens)
        assertEquals(64, usage.completionTokens)
    }

    @Test
    fun `response incomplete content_filter maps reason`() {
        val result = accept(
            """{"type":"response.incomplete","response":{"status":"incomplete",""" +
                """"incomplete_details":{"reason":"content_filter"}}}""",
        )

        assertEquals(
            "incomplete:content_filter",
            result.chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason,
        )
        assertTrue(
            isContentFilterTruncation(
                result.chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason,
            )
        )
    }

    @Test
    fun `response incomplete without reason uses non-blank fallback`() {
        // 兼容形状：缺 incomplete_details 时不能给出 null/空 —— 否则下游既没有截断提示，
        // 也可能把「有类型的结束」误判成裸断流。
        val result = accept(
            """{"type":"response.incomplete","response":{"id":"resp_x","status":"incomplete"}}""",
        )

        val finish = result.chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("incomplete", finish.finishReason)
        assertFalse(finish.finishReason.isNullOrBlank())
    }

    @Test
    fun `response completed carries usage and terminal finish`() {
        val result = accept(
            """{"type":"response.completed","response":{"id":"resp_2","status":"completed",""" +
                """"usage":{"input_tokens":5,"output_tokens":2,"total_tokens":7}}}""",
        )

        assertTrue(result.completed)
        // completed 是正常结束：Finish.finishReason 取 wire 上的 status（下游截断判定不命中）
        val finish = result.chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("completed", finish.finishReason)
        assertEquals("resp_2", finish.responseId)
        assertEquals(7, result.chunks.filterIsInstance<StreamChunk.Usage>().single().usage.totalTokens)
    }

    @Test
    fun `failed and error events abort the decoder with a provider error`() {
        // P1 后由解码器负责错误传播（旧实现解析层返回 null、listener 负责 close(error)）。
        val failed = ResponseApiStreamDecoder()
        val failedError = assertThrows(HttpException::class.java) {
            failed.accept(
                SseEvent(
                    event = "response.failed",
                    data = """{"type":"response.failed","response":{"status":"failed",""" +
                        """"error":{"code":"server_error","message":"boom"}}}""",
                )
            )
        }
        assertEquals("boom", failedError.message)
        // 失败后解码器已终止：onClosed 不得再补 Finish（否则失败会被伪装成正常收尾）
        assertTrue(failed.onClosed().isEmpty())

        val error = assertThrows(HttpException::class.java) {
            ResponseApiStreamDecoder().accept(
                SseEvent(
                    event = "error",
                    data = """{"type":"error","error":{"type":"overloaded_error","message":"overloaded"},""" +
                        """"sequence_number":1}""",
                )
            )
        }
        assertEquals("overloaded", error.message)
    }

    @Test
    fun `unknown event types stay ignored`() {
        assertTrue(
            accept("""{"type":"response.unknown.event","item_id":"msg_1"}""").chunks.isEmpty()
        )
        // 未开启的文本块收到 done 时不得凭空产出 TextEnd
        assertTrue(
            accept("""{"type":"response.output_text.done","item_id":"msg_1","text":"Hi"}""").chunks.isEmpty()
        )
        assertTrue(
            accept("""{"type":"response.content_part.done","item_id":"msg_1"}""").chunks.isEmpty()
        )
    }

    @Test
    fun `done marker closes the stream with Finish`() {
        val result = accept("[DONE]")
        assertTrue(result.completed)
        assertTrue(result.chunks.single() is StreamChunk.Finish)
    }

    @Test
    fun `finishReason helpers normalize the incomplete prefix`() {
        // 回归护栏：Responses API 的 reason 是 "incomplete:<reason>"（#1648 拼装格式），
        // 而下游（ContinuationPolicy / 截断横幅 / 空响应校验）用的是裸值判定。
        // 本仓 helper 必须归一前缀，否则撞输出上限不会续写、截断/拦截横幅也不显示。
        assertTrue(isOutputLimitTruncation("incomplete:max_output_tokens"))
        assertTrue(isContentFilterTruncation("incomplete:content_filter"))
        // 裸值/大小写/空白同样成立（归一后再判定）
        assertTrue(isOutputLimitTruncation(" max_output_tokens "))
        assertTrue(isOutputLimitTruncation("LENGTH"))
        assertTrue(isContentFilterTruncation("REFUSAL"))
        // 正常结束不得命中截断判定
        assertFalse(isOutputLimitTruncation("incomplete:completed"))
        assertFalse(isOutputLimitTruncation("completed"))
        assertFalse(isContentFilterTruncation("end_turn"))
        assertFalse(isOutputLimitTruncation(null))
    }
}
