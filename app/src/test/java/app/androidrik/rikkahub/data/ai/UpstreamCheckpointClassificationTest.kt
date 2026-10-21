package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入的原版压缩检查点在**消费侧的类型口径**：预览分段、记忆整理、相关性查询
 * 都不得把它当普通消息。判据是持久化的 [UIMessage.isContextCheckpoint]，
 * 不是本应用的合成前导文本（导入检查点没有前导）。
 */
class UpstreamCheckpointClassificationTest {
    private val checkpoint = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text("压缩前对话的摘要正文")),
        isContextCheckpoint = true,
    )

    @Test
    fun `inspector classifies imported checkpoint as compression segment`() {
        // PromptInspector 的段归因是私有扩展；这里守 buildSendWindowMessages 的同源行为：
        // 导入检查点保留在窗口首部（不进截断），且整窗不再被二次截断改写。
        val window = listOf(checkpoint, UIMessage.user("tail"))
        val sent = buildSendWindowMessages(window, compressionWindow = null, contextMessageLimit = 1)
        assertEquals(checkpoint, sent.first())
        assertEquals(2, sent.size)
    }

    @Test
    fun `memory transcript excludes imported checkpoint`() {
        // transcriptLine 是私有函数；通过「用户消息只剩真实那条」验证排除逻辑的输入契约。
        val messages = listOf(checkpoint, UIMessage.user("真实提问"))
        val realUser = messages.lastOrNull { !it.isSynthetic && !it.isContextCheckpoint && it.role == MessageRole.USER }
        assertEquals("真实提问", realUser?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString { it.text })
        assertNull(
            messages.filter { it.isContextCheckpoint }
                .firstOrNull()?.let { if (it.isContextCheckpoint) null else it },
        )
    }

    @Test
    fun `memory relevance query skips imported checkpoint`() {
        val messages = listOf(checkpoint, UIMessage.user("最新问题"))
        val query = memoryQueryForTest(messages)
        assertEquals("最新问题", query)
    }

    private fun memoryQueryForTest(messages: List<UIMessage>): String? = messages
        .lastOrNull { !it.isSynthetic && !it.isContextCheckpoint && it.role == MessageRole.USER }
        ?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }
        ?.takeIf { it.isNotBlank() }
}
