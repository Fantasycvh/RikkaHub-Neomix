package app.androidrik.rikkahub.service

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamCheckpointPolicyTest {

    @Test
    fun `streamCheckpointIntervalMs adapts to message size`() {
        assertEquals(2_000L, streamCheckpointIntervalMs(null))
        assertEquals(2_000L, streamCheckpointIntervalMs(UIMessage.assistant("short reply")))
        assertEquals(
            4_000L,
            streamCheckpointIntervalMs(UIMessage.assistant("x".repeat(STREAM_CHECKPOINT_LARGE_CHARS))),
        )
        assertEquals(
            4_000L,
            streamCheckpointIntervalMs(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning("r".repeat(20_000)),
                        UIMessagePart.Text("t".repeat(12_000)),
                    ),
                )
            ),
        )
    }
}
