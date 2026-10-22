package app.androidrik.rikkahub.utils

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TokenCountingTest {

    @Test
    fun `parallel counting returns exactly the sequential values`() = runBlocking {
        val texts = buildList {
            repeat(40) { index ->
                add("第 $index 段文本，with some ascii words and punctuation: (a, b) $index")
                add("")
            }
            add("x".repeat(5_000))
        }
        assertEquals(texts.map { estimateTokens(it) }, countTokensInParallel(texts))
    }

    @Test
    fun `single and empty inputs do not spawn parallel work`() = runBlocking {
        assertEquals(emptyList<Int>(), countTokensInParallel(emptyList()))
        assertEquals(listOf(estimateTokens("hello")), countTokensInParallel(listOf("hello")))
    }

    @Test
    fun `conversation texts use the same units as preview segments`() {
        val message = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("first"),
                UIMessagePart.Text("second"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "search",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("tool output")),
                ),
            ),
        )
        // 预览把一条消息的多个 Text part 用 "\n" 拼成一段，工具输出另成一段
        assertEquals(listOf("first\nsecond", "tool output"), conversationTokenTexts(listOf(message)))
    }

    @Test
    fun `blank output and empty text are skipped`() {
        val message = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(""),
                UIMessagePart.Tool(
                    toolCallId = "call-2",
                    toolName = "noop",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("   ")),
                ),
            ),
        )
        assertTrue(conversationTokenTexts(listOf(message)).isEmpty())
    }
}
