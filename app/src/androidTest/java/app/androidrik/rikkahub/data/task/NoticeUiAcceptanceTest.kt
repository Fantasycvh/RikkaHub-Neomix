package app.androidrik.rikkahub.data.task

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import android.content.Intent
import app.androidrik.rikkahub.SketchTestHostActivity
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.toMessageNode
import app.androidrik.rikkahub.ui.components.message.ChatMessage
import org.junit.Rule
import org.junit.Test
import java.io.File

class NoticeUiAcceptanceTest {
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(
            InstrumentationRegistry.getInstrumentation().targetContext, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test fun systemCarrierShowsSourceCard() = render(MessageRole.SYSTEM)
    @Test fun userCarrierShowsTheSameSourceCard() = render(MessageRole.USER)

    @Test fun taskTreeShowsOwnedCommandsAndLetsUserApprove() {
        val request = app.androidrik.rikkahub.data.ai.SubagentApprovalQueue.Request("tree-run", "Research task",
            UIMessagePart.Tool("tree-call", "workspace_write_file", "{\"path\":\"report.md\"}",
                approvalState = app.androidrik.ai.ui.ToolApprovalState.Pending), "tree-parent")
        val card = TaskCard(TaskKey.Agent("tree-run"), TaskSource.AGENT, TaskState.WAITING_APPROVAL,
            "Research task", "Produce a report", 1, null, true, 1)
        val shell = TaskCard(TaskKey.Shell("root", "command"), TaskSource.SHELL, TaskState.RUNNING,
            "Owned shell command", null, 1, null, true)
        val snapshot = ConversationTaskSnapshot(listOf(ConversationTaskBranch(card,
            listOf("workspace_read_file"), listOf(request), listOf(shell))))
        var approved = false
        compose.setContent {
            MaterialTheme {
                app.androidrik.rikkahub.ui.pages.taskcenter.ConversationTasksContent(snapshot,
                    onOpen = {}, onCancel = {}, onDecision = { item, decision ->
                        approved = item === request && decision
                    })
            }
        }
        compose.onNodeWithText("本会话任务").assertIsDisplayed()
        compose.onNodeWithText("Owned shell command").assertIsDisplayed()
        compose.onNodeWithText("批准").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(approved) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "runtime-acceptance/p1b-task-tree.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(role: MessageRole) {
        val message = UIMessage(role = role, isSynthetic = true,
            parts = listOf(UIMessagePart.Text("RUNTIME_ACCEPTANCE_OK\nrun_id: ui-proof; status: completed")),
            annotations = listOf(UIMessageAnnotation.SubagentNotice(listOf("ui-proof"))))
        compose.setContent {
            MaterialTheme {
                ChatMessage(node = message.toMessageNode(), onFork = {}, onRegenerate = {}, onEdit = {},
                    onShare = {}, onDelete = {}, onUpdate = {})
            }
        }
        compose.onNodeWithText("子代理结果（1）").assertIsDisplayed()
        compose.onNodeWithText("RUNTIME_ACCEPTANCE_OK", substring = true).assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.filesDir, "runtime-acceptance/notice-${role.name.lowercase()}.png")
        image.parentFile!!.mkdirs()
        image.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
