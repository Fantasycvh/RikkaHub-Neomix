package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationPhaseTest {
    private fun phaseOf(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()).generationPhase()

    @Test
    fun `unexecuted tool wins and drops the MCP server prefix`() {
        val phase = phaseOf(
            UIMessagePart.Text("let me check"),
            UIMessagePart.Tool(toolCallId = "1", toolName = "server__search", input = "{\"q\":1}"),
        )
        assertEquals(GenerationPhase.RunningTool(toolName = "search", input = "{\"q\":1}"), phase)
    }

    @Test
    fun `unfinished reasoning is thinking`() {
        assertEquals(
            GenerationPhase.Thinking("hmm"),
            phaseOf(UIMessagePart.Reasoning(reasoning = "hmm", finishedAt = null)),
        )
    }

    @Test
    fun `text after finished reasoning and executed tool is writing`() {
        val phase = phaseOf(
            UIMessagePart.Reasoning(reasoning = "done"),
            UIMessagePart.Tool(
                toolCallId = "1", toolName = "search", input = "{}",
                output = listOf(UIMessagePart.Text("result")),
            ),
            UIMessagePart.Text("answer"),
        )
        assertEquals(GenerationPhase.Writing("answer"), phase)
    }

    @Test
    fun `no content yet is waiting`() {
        assertEquals(GenerationPhase.Waiting, phaseOf())
    }

    @Test
    fun `last tool and last text are used instead of earlier parts`() {
        assertEquals(
            GenerationPhase.Writing("latest"),
            phaseOf(
                UIMessagePart.Tool(toolCallId = "1", toolName = "old", input = "{}"),
                UIMessagePart.Tool(
                    toolCallId = "2", toolName = "new", input = "{}",
                    output = listOf(UIMessagePart.Text("done")),
                ),
                UIMessagePart.Text("earlier"),
                UIMessagePart.Text("latest"),
            ),
        )
    }

    @Test
    fun `unfinished reasoning takes precedence over text`() {
        assertEquals(
            GenerationPhase.Thinking("still thinking"),
            phaseOf(UIMessagePart.Reasoning("still thinking", finishedAt = null), UIMessagePart.Text("draft")),
        )
    }
}
