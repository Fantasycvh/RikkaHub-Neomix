package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 工具执行耗时（`UIMessagePart.Tool.startedAt/finishedAt`）与轮级步数（`UIMessage.stepCount`）
 * 的序列化兼容与保留（上游 8bb488e3a / 660b5d65f 移植）。
 *
 * 关键不变量：旧消息 JSON（无这两个字段）必须能反序列化——可空缺省即兼容，免迁移。
 */
class ToolTimingAndStepCountTest {
    private val started = LocalDateTime(year = 2026, monthNumber = 10, dayOfMonth = 26, hour = 12, minute = 0, second = 0)
    private val finished = LocalDateTime(year = 2026, monthNumber = 10, dayOfMonth = 26, hour = 12, minute = 0, second = 2)

    @Test
    fun `tool part serializes timestamps`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "workspace_shell",
            input = "{}",
            startedAt = started,
            finishedAt = finished,
        )
        val decoded = JsonInstant.decodeFromString<UIMessagePart.Tool>(JsonInstant.encodeToString(tool))
        assertEquals(started, decoded.startedAt)
        assertEquals(finished, decoded.finishedAt)
    }

    @Test
    fun `legacy tool json without timestamps decodes with nulls`() {
        // 旧版本落库的 tool part：没有 startedAt/finishedAt 字段
        val legacy = """{"toolCallId":"call_1","toolName":"t","input":"{}","output":[]}"""
        val decoded = JsonInstant.decodeFromString<UIMessagePart.Tool>(legacy)
        assertNull(decoded.startedAt)
        assertNull(decoded.finishedAt)
    }

    @Test
    fun `merge preserves timing from either side`() {
        val base = UIMessagePart.Tool(toolCallId = "c", toolName = "a", input = "{}")
        val delta = UIMessagePart.Tool(toolCallId = "c", toolName = "b", input = "x", startedAt = started, finishedAt = finished)
        val merged = base.merge(delta)
        assertEquals(started, merged.startedAt)
        assertEquals(finished, merged.finishedAt)
        // 反向：基上有时不丢
        val merged2 = delta.merge(base)
        assertEquals(started, merged2.startedAt)
        assertEquals(finished, merged2.finishedAt)
    }

    @Test
    fun `message stepCount defaults to zero and survives copy`() {
        val message = UIMessage(role = app.androidrik.ai.core.MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi")))
        assertEquals(0, message.stepCount)
        val stamped = message.copy(stepCount = 3)
        assertEquals(3, stamped.stepCount)
        // chunk 合并走 copy ⇒ 既有字段保留（handleMessageChunk 的合并语义）
        val copied = stamped.copy(parts = stamped.parts)
        assertEquals(3, copied.stepCount)
    }

    @Test
    fun `message json roundtrips stepCount and legacy json defaults to zero`() {
        val message = UIMessage(
            role = app.androidrik.ai.core.MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("hi")),
            stepCount = 4,
        )
        val decoded = JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(message))
        assertEquals(4, decoded.stepCount)

        val legacy = """{"role":"assistant","parts":[{"type":"text","text":"hi"}]}"""
        assertEquals(0, JsonInstant.decodeFromString<UIMessage>(legacy).stepCount)
    }
}
