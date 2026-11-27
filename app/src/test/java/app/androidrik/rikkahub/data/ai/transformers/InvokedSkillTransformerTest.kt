package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InvokedSkillTransformerTest {
    private val selection = UIMessageAnnotation.InvokedSkill("review", "Check the calculations.")
    private val user = UIMessage.user("Check this").copy(
        annotations = listOf(selection),
        parts = listOf(UIMessagePart.Text("Check this"), UIMessagePart.Image("file:///image.png")),
    )

    @Test
    fun `loads the saved choice for the active turn with attachments and synthetic reminders`() {
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(user))
        val messages = listOf(restored, UIMessage.user("Memory reminder").copy(isSynthetic = true))

        val request = expandInvokedSkill(messages)

        assertEquals(listOf(selection), restored.annotations)
        assertTrue((request.first().parts.first() as UIMessagePart.Text).text.endsWith(selection.instructions))
        assertEquals(restored.parts, request.first().parts.drop(1))
        assertSame(messages.last(), request.last())
        assertSame(request, expandInvokedSkill(request))
    }

    @Test
    fun `leaves older skill selections alone on ordinary and scheduled turns`() {
        val ordinary = listOf(user, UIMessage.user("New turn"))
        val scheduled = listOf(
            user,
            UIMessage.user("Scheduled turn").copy(
                isSynthetic = true,
                annotations = listOf(UIMessageAnnotation.ScheduledTrigger("run")),
            ),
        )

        assertSame(ordinary, expandInvokedSkill(ordinary))
        assertSame(scheduled, expandInvokedSkill(scheduled))
    }

    @Test
    fun `expands multiple stacked skills in order with deduplication including scheduled turns`() {
        val second = UIMessageAnnotation.InvokedSkill("summarize", "Keep bullets under 5 lines.")
        val multiUser = UIMessage.user("Analyze report").copy(
            isSynthetic = true,
            annotations = listOf(
                UIMessageAnnotation.ScheduledTrigger("run-1"),
                selection,
                second,
                selection,
            ),
        )

        val request = expandInvokedSkill(listOf(multiUser))
        val expanded = request.single()
        val promptPart = (expanded.parts.first() as UIMessagePart.Text).text

        assertTrue(promptPart.contains("review, summarize"))
        assertTrue(promptPart.indexOf("### Skill: review") < promptPart.indexOf("### Skill: summarize"))
        assertTrue(promptPart.contains(selection.instructions))
        assertTrue(promptPart.contains(second.instructions))
        assertEquals(listOf(UIMessageAnnotation.ScheduledTrigger("run-1")), expanded.annotations)
        assertEquals(multiUser.parts, expanded.parts.drop(1))
        assertSame(request, expandInvokedSkill(request))
    }
}
