package app.androidrik.rikkahub.ui.components.ui

import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertTrue
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.GenerationPhase
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.floating.ChatFloatingPanel
import app.androidrik.rikkahub.ui.floating.FloatingChatUiState
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatFloatingPanelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule
    val rule = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { activityRule ->
        var activity: SketchTestHostActivity? = null
        activityRule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test
    fun markdownAndIndependentSubagentApprovalRenderWithoutOrdinaryApproval() {
        val tool = UIMessagePart.Tool(
            toolCallId = "floating-child", toolName = "workspace_exec", input = "{\"command\":\"pwd\"}",
            approvalState = ToolApprovalState.Pending,
        )
        val ordinary = AtomicInteger()
        val child = AtomicInteger()
        val state = FloatingChatUiState(
            conversationId = Uuid.random(), title = "Floating test", generating = true,
            processingStatus = null, phase = GenerationPhase.Waiting, hasReply = true,
            preview = "# Preview\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n[link](https://example.com)\n\n```kotlin\nval x = 1\n```",
            previewIsReasoning = false, pendingTools = emptyList(), queuedCount = 0,
            queuePaused = false, error = null,
            subagentApprovals = listOf(SubagentApprovalQueue.Request("run-test", "Child agent", tool)),
        )
        rule.setContent {
            CompositionLocalProvider(LocalSettings provides Settings()) {
                RikkahubTheme {
                    ChatFloatingPanel(
                        state = state, maxHeight = 900.dp,
                        onCollapse = {}, onDismiss = {}, onOpenConversation = {},
                        onToolApproval = { _, _ -> ordinary.incrementAndGet() },
                        onStop = {}, onSend = {}, onResumeQueue = {},
                        onSubagentApproval = { _, approved -> if (approved) child.incrementAndGet() },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(app.androidrik.rikkahub.R.string.chat_message_tool_approve)).performClick()
        assertEquals(1, child.get())
        assertEquals(0, ordinary.get())
    }

    @Test
    fun resizeHandleAndResetAreInteractiveAtCompactSize() {
        val resized = AtomicInteger()
        val reset = AtomicInteger()
        val finished = AtomicInteger()
        rule.setContent {
            CompositionLocalProvider(LocalSettings provides Settings()) {
                RikkahubTheme {
                    ChatFloatingPanel(
                        state = FloatingChatUiState(
                            conversationId = Uuid.random(), title = "Resize", generating = false,
                            processingStatus = null, phase = GenerationPhase.Waiting, hasReply = false,
                            preview = "", previewIsReasoning = false, pendingTools = emptyList(),
                            queuedCount = 0, queuePaused = false, error = null,
                        ), maxHeight = 400.dp, panelWidth = 340.dp,
                        onCollapse = {}, onDismiss = {}, onOpenConversation = {},
                        onToolApproval = { _, _ -> }, onStop = {}, onSend = {}, onResumeQueue = {},
                        onResize = { _, _ -> resized.incrementAndGet() },
                        onResizeFinished = { finished.incrementAndGet() },
                        onResetSize = { reset.incrementAndGet() },
                    )
                }
            }
        }
        rule.onNodeWithContentDescription(context.getString(app.androidrik.rikkahub.R.string.floating_chat_resize))
            .performTouchInput { swipe(bottomRight - Offset(5f, 5f), topLeft + Offset(5f, 5f), 400) }
        assertTrue(resized.get() > 0)
        assertEquals(1, finished.get())
        rule.onNodeWithText(context.getString(app.androidrik.rikkahub.R.string.floating_chat_reset_size)).performClick()
        assertEquals(1, reset.get())
    }
}
