package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.PromptInjection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TailInjectionTest {
    private val tail = PromptInjection.ModeInjection(
        content = "Tail reminder",
        position = InjectionPosition.END_OF_CHAT,
    )

    private fun apply(
        messages: List<UIMessage>,
        entries: List<PromptInjection.ModeInjection> = listOf(tail),
    ) = transformMessages(
        messages = messages,
        assistant = Assistant(modeInjectionIds = entries.map { it.id }.toSet()),
        modeInjections = entries,
        lorebooks = emptyList(),
    )

    private fun UIMessage.text() = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    @Test
    fun `tail preserves entire prefix and appends synthetic user message`() {
        val messages = listOf(UIMessage.system("System"), UIMessage.user("Question"))
        val result = apply(messages)
        assertEquals(messages, result.dropLast(1))
        assertEquals(tail.content, result.last().text())
        assertEquals(MessageRole.USER, result.last().role)
        assertTrue(result.last().isSynthetic)
        assertEquals(2, messages.size)
    }

    @Test
    fun `tail works without system and with empty history`() {
        val user = UIMessage.user("Question")
        assertEquals(listOf(user), apply(listOf(user)).dropLast(1))
        assertEquals(tail.content, apply(emptyList()).single().text())
    }

    @Test
    fun `tail merges in descending priority regardless of stored role`() {
        val low = tail.copy(priority = 1, content = "Low", role = MessageRole.ASSISTANT)
        val high = PromptInjection.ModeInjection(
            priority = 8,
            content = "High",
            role = MessageRole.SYSTEM,
            position = InjectionPosition.END_OF_CHAT,
        )
        val result = apply(emptyList(), listOf(low, high))
        assertEquals(1, result.size)
        assertEquals("High\nLow", result.single().text())
        assertEquals(MessageRole.USER, result.single().role)
    }

    @Test
    fun `bottom and depth retain before-last semantics while tail follows all of them`() {
        val user = UIMessage.user("Question")
        val bottom = PromptInjection.ModeInjection(content = "Before", position = InjectionPosition.BOTTOM_OF_CHAT)
        val depth = PromptInjection.ModeInjection(content = "Depth", position = InjectionPosition.AT_DEPTH, injectDepth = 0)
        val result = apply(listOf(user), listOf(tail, bottom, depth))
        assertEquals(listOf("Before", "Depth", "Question", "Tail reminder"), result.map { it.text() })
        assertEquals(user, result[result.lastIndex - 1])
    }

    @Test
    fun `tool loop uses live results and does not accumulate tail messages`() {
        val history = listOf(UIMessage.system("System"), UIMessage.user("Question"))
        val first = apply(history)
        val toolCall = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(
                toolCallId = "call-1", toolName = "read_file", input = "{}",
                output = listOf(UIMessagePart.Text("Fresh result")),
            )),
        )
        val live = history + toolCall
        repeat(3) {
            val next = apply(live)
            assertEquals(live, next.dropLast(1))
            assertEquals(toolCall, next[next.lastIndex - 1])
            assertEquals(1, next.count { it.text() == tail.content })
        }
        assertEquals(history, first.dropLast(1))
    }

    @Test
    fun `disabled tail and unbound tail are omitted`() {
        val messages = listOf(UIMessage.user("Question"))
        assertEquals(messages, apply(messages, listOf(tail.copy(enabled = false))))
        assertEquals(messages, transformMessages(messages, Assistant(), listOf(tail), emptyList()))
    }

    @Test
    fun `tail position survives serialization and old default remains unchanged`() {
        val encoded = Json.encodeToString(tail)
        assertTrue(encoded.contains("end_of_chat"))
        assertEquals(tail, Json.decodeFromString<PromptInjection.ModeInjection>(encoded))
        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT,
            Json.decodeFromString<PromptInjection.ModeInjection>("{}").position)
    }
}
