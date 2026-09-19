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
    fun `tail groups by role - system first, user merged, assistant ignored`() {
        // #1542 分段式系统提示：尾部按 role 分组（SYSTEM 段在前、USER 段在后）；
        // ASSISTANT 防御性忽略（防预填充）——取代旧的「无视存储 role 全并 user」行为。
        val userEntry = tail.copy(priority = 1, content = "Low")
        val systemEntry = PromptInjection.ModeInjection(
            priority = 8,
            content = "High",
            role = MessageRole.SYSTEM,
            position = InjectionPosition.END_OF_CHAT,
        )
        val assistantEntry = tail.copy(priority = 4, content = "prefill", role = MessageRole.ASSISTANT)
        val result = apply(emptyList(), listOf(userEntry, systemEntry, assistantEntry))
        assertEquals(2, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertEquals("High", result[0].text())
        assertEquals(MessageRole.USER, result[1].role)
        assertEquals("Low", result[1].text())
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

    // ---- #1542 分段式系统提示：尾部 SYSTEM 角色 ----

    private fun systemTail(content: String) = PromptInjection.ModeInjection(
        content = content,
        position = InjectionPosition.END_OF_CHAT,
        role = MessageRole.SYSTEM,
    )

    @Test
    fun `system tail appends a system message after history`() {
        val result = apply(
            messages = listOf(UIMessage.user("q")),
            entries = listOf(systemTail("尾部系统指令")),
        )
        val last = result.last()
        assertEquals(MessageRole.SYSTEM, last.role)
        assertTrue(last.isSynthetic)
        assertEquals("尾部系统指令", last.text())
        // 前缀不变
        assertEquals("q", result.first().text())
    }

    @Test
    fun `mixed system and user tails emit system first then user`() {
        val result = apply(
            messages = listOf(UIMessage.user("q")),
            entries = listOf(
                systemTail("SYS-段"),
                PromptInjection.ModeInjection(
                    content = "USER-段",
                    position = InjectionPosition.END_OF_CHAT,
                ),
            ),
        )
        val roles = result.drop(1).map { it.role }
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), roles)
        assertEquals("SYS-段", result[1].text())
        assertEquals("USER-段", result[2].text())
    }

    @Test
    fun `assistant tail is defensively ignored`() {
        val result = apply(
            messages = listOf(UIMessage.user("q")),
            entries = listOf(
                PromptInjection.ModeInjection(
                    content = "prefill",
                    position = InjectionPosition.END_OF_CHAT,
                    role = MessageRole.ASSISTANT,
                ),
            ),
        )
        // 无尾部消息（防预填充）
        assertEquals(1, result.size)
    }

    @Test
    fun `multi step does not accumulate system tail`() {
        // 与旧 user 尾部同款模拟：live 历史不含尾部注入（transformer 产物不回写），
        // 每轮从同一 live 重新组装，尾部恰一条 system 消息
        val history = listOf(UIMessage.user("q"))
        val entries = listOf(systemTail("SYS"))
        repeat(3) {
            val next = apply(history, entries)
            assertEquals(history, next.dropLast(1))
            assertEquals(1, next.count { it.role == MessageRole.SYSTEM && it.isSynthetic })
        }
    }

}