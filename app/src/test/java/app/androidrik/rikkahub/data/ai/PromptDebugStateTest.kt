package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptDebugStateTest {

    private fun msg(text: String, role: MessageRole = MessageRole.USER) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    private val counter: (List<UIMessage>) -> Int = { it.sumOf { m -> m.parts.sumOf { p ->
        (p as? UIMessagePart.Text)?.text?.length ?: 10 } } }

    @Test
    fun `divergence locates the first differing message`() {
        val prev = listOf(msg("sys", MessageRole.SYSTEM), msg("hello"), msg("answer", MessageRole.ASSISTANT))
        val curr = listOf(msg("sys", MessageRole.SYSTEM), msg("hello2"), msg("answer2", MessageRole.ASSISTANT))
        val d = diagnosePrefixDivergence(prev, curr, counter)
        assertEquals(1, d.commonPrefixMessages)
        assertEquals(prev.size, d.previousSize)
        assertEquals(curr.size, d.currentSize)
        assertEquals("hello2", d.divergedMessagePreview!!.substringAfter("："))
    }

    @Test
    fun `identical prefixes report full common length`() {
        val prev = listOf(msg("sys", MessageRole.SYSTEM), msg("q"))
        val curr = listOf(msg("sys", MessageRole.SYSTEM), msg("q"), msg("a", MessageRole.ASSISTANT))
        val d = diagnosePrefixDivergence(prev, curr, counter)
        assertEquals(2, d.commonPrefixMessages)
        // 纯追加（本次更长）：preview 指向新增的第一条消息
        assertEquals("a", d.divergedMessagePreview!!.substringAfter("："))
    }

    @Test
    fun `fully identical requests have no divergence preview`() {
        val msgs = listOf(msg("sys", MessageRole.SYSTEM), msg("q"))
        val d = diagnosePrefixDivergence(msgs, msgs, counter)
        assertEquals(2, d.commonPrefixMessages)
        assertNull(d.divergedMessagePreview)
    }

    @Test
    fun `tool output change counts as divergence at that message`() {
        fun assistantWithTool(output: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(toolCallId = "t1", toolName = "search", input = "{}", output = listOf(UIMessagePart.Text(output)))
            )
        )
        val prev = listOf(assistantWithTool("old result"))
        val curr = listOf(assistantWithTool("new result"))
        val d = diagnosePrefixDivergence(prev, curr, counter)
        assertEquals(0, d.commonPrefixMessages)
        assertEquals(0, d.commonPrefixTokens)
    }

    @Test
    fun `record keeps previous and current`() {
        val key = "test-key"
        PromptDebugState.recordRequest(key, listOf(msg("first")))
        PromptDebugState.recordRequest(key, listOf(msg("first"), msg("second", MessageRole.ASSISTANT)))
        val (current, previous) = PromptDebugState.latestRequest(key)!!
        assertEquals(2, current.size)
        assertEquals(1, previous!!.size)
    }

    @Test
    fun `fold state round trips per conversation and is independent`() {
        PromptDebugState.setFolded("conv-a", 3, true)
        PromptDebugState.setFolded("conv-a", 1, false)
        assertTrue(PromptDebugState.foldState("conv-a", 3))
        assertEquals(false, PromptDebugState.foldState("conv-a", 1))
        assertEquals(false, PromptDebugState.foldState("conv-b", 3))
    }
}
