package app.androidrik.rikkahub.data.ai.transformers

import android.app.Application
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubagentNoticeTransformTest {
    @Test fun `input transforms preserve external output and frame only the request copy`() = runBlocking {
        val notice = UIMessage(role = MessageRole.USER, isSynthetic = true,
            parts = listOf(UIMessagePart.Text("external {{user}}")),
            annotations = listOf(UIMessageAnnotation.SubagentNotice(listOf("run"))))
        val transformer = object : InputMessageTransformer {
            override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>) =
                messages.map { it.copy(parts = listOf(UIMessagePart.Text("rewritten"))) }
        }
        val input = listOf(UIMessage.user("ordinary input"), notice)
        val output = input.transforms(listOf(transformer), Application(), Model(), Assistant(), Settings.dummy())
        assertEquals("rewritten", output.first().toText())
        assertEquals("<system-reminder>\nexternal {{user}}\n</system-reminder>", output.last().toText())
        assertEquals("external {{user}}", notice.toText())
    }

    @Test fun `fallback notice does not create user time reminders`() {
        val notice = UIMessage(role = MessageRole.USER, isSynthetic = true,
            parts = listOf(UIMessagePart.Text("external")),
            annotations = listOf(UIMessageAnnotation.SubagentNotice(listOf("run"))))
        assertEquals(listOf(notice), applyTimeReminder(listOf(notice)))
    }
}
