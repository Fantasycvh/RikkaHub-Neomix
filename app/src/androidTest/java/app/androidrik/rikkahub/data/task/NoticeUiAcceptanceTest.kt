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
