package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流结束语义（`docs/peer-apps-benchmark.md` §四.7 B）：
 * - [isOutputLimitTruncation]：撞 token 上限（模型被掐断，续写有意义）
 * - [isContentFilterTruncation]：被内容安全策略掐断（续写无意义）
 * - [terminalChunk] / [MessageChunk.explicitEnd]：区分「显式结束」与「裸断流」
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
    fun `terminal chunk is an empty marker`() {
        val chunk = terminalChunk()
        assertTrue(chunk.explicitEnd)
        assertTrue(chunk.choices.isEmpty())
        // 消费方对空 choices 必须无副作用（handleMessageChunk 原样返回）
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))))
        assertEquals(messages, messages.handleMessageChunk(chunk))
    }

    @Test
    fun `finish state defaults keep old persisted messages decodable`() {
        // 旧会话的 JSON 没有这两个字段 ⇒ 反序列化必须回落默认值（零迁移的前提）
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        assertEquals(null, message.finishReason)
        assertFalse(message.finishIncomplete)
    }
}
