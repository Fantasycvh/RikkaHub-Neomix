package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流结束语义（`docs/peer-apps-benchmark.md` §四.7 B）：
 * - [isOutputLimitTruncation]：撞 token 上限（模型被掐断，续写有意义）
 * - [isContentFilterTruncation]：被内容安全策略掐断（续写无意义）
 * - **显式结束 = 收到 [StreamChunk.Finish]**：旧的 `MessageChunk.explicitEnd`/`terminalChunk()`
 *   已随 P1 收尾删除，现在由流事件本身表达（[StreamChunkHandler.finished]）；没见到 Finish
 *   就关流即裸断流（消费方判 `finishIncomplete` 并自动续写）。
 */
class StreamFinishSemanticsTest {

    @Test
    fun `output limit truncation covers every provider naming`() {
        // OpenAI chat/completions
        assertTrue(isOutputLimitTruncation("length"))
        // Anthropic
        assertTrue(isOutputLimitTruncation("max_tokens"))
        // OpenAI Responses API
        assertTrue(isOutputLimitTruncation("max_output_tokens"))
        // Google（大写）
        assertTrue(isOutputLimitTruncation("MAX_TOKENS"))
        // 常见兼容层别名
        assertTrue(isOutputLimitTruncation("token_limit"))
        // 大小写不敏感
        assertTrue(isOutputLimitTruncation("LENGTH"))
    }

    @Test
    fun `normal stop reasons are not truncation`() {
        assertFalse(isOutputLimitTruncation("stop"))
        assertFalse(isOutputLimitTruncation("end_turn"))
        assertFalse(isOutputLimitTruncation("STOP"))
        assertFalse(isOutputLimitTruncation("tool_calls"))
        assertFalse(isOutputLimitTruncation(null))
    }

    @Test
    fun `content filter detection is separate from output limit`() {
        assertTrue(isContentFilterTruncation("content_filter"))
        assertTrue(isContentFilterTruncation("SAFETY"))
        assertTrue(isContentFilterTruncation("PROHIBITED_CONTENT"))
        assertTrue(isContentFilterTruncation("refusal"))
        // 两类必须互斥：上限截断可续写、内容拦截续写无意义
        assertFalse(isContentFilterTruncation("length"))
        assertFalse(isOutputLimitTruncation("SAFETY"))
        assertFalse(isContentFilterTruncation(null))
    }

    @Test
    fun `Finish is the only explicit end marker and does not pollute parts`() {
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("hi"))
        messages = handler.handle(messages, StreamChunk.TextStart("t-1"))
        messages = handler.handle(messages, StreamChunk.TextDelta("t-1", "Hi"))

        // 只收到正文、还没收到 Finish：此时不能算显式结束
        assertFalse(handler.finished)
        assertNull(messages.last().finishReason)

        messages = handler.handle(
            messages,
            StreamChunk.Finish(finishReason = "stop", responseId = "resp-1", model = "test-model"),
        )

        // Finish 只盖终态（finished/finishReason/finishedAt），不追加任何 part
        assertTrue(handler.finished)
        val assistant = messages.last()
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        assertEquals(listOf<UIMessagePart>(UIMessagePart.Text("Hi")), assistant.parts)
        assertEquals("stop", assistant.finishReason)
        assertFalse("收到 Finish 即正常收尾，不是裸断流", assistant.finishIncomplete)
        assertTrue(assistant.finishedAt != null)
    }

    @Test
    fun `bare close without Finish stays unfinished`() {
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("hi"))
        messages = handler.handle(messages, StreamChunk.TextDelta("t-1", "Hi"))
        messages = handler.handle(messages, StreamChunk.TextEnd("t-1"))

        // 流关了但没见到 Finish = 裸断流：消费方据此提示/续写，而不是当正常收尾
        assertFalse(handler.finished)
        assertEquals("Hi", messages.last().toText())
        assertNull(messages.last().finishReason)
    }

    @Test
    fun `finish state defaults keep old persisted messages decodable`() {
        // 旧会话的 JSON 没有这两个字段 ⇒ 反序列化必须回落默认值（零迁移的前提）
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        assertEquals(null, message.finishReason)
        assertFalse(message.finishIncomplete)
    }
}
