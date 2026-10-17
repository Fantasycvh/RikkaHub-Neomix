package app.androidrik.rikkahub.utils

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话内图片收集 / 全屏预览起始页（issue #2152「上传过的图片可再次预览与编辑」）。
 *
 * 这里的纯逻辑决定两件事：全屏查看器能左右翻到哪些图、从哪一张开始。
 */
class ConversationImagesTest {

    private fun userMessage(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.USER, parts = parts.toList())

    private fun assistantMessage(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test
    fun `按消息顺序收集图片并去重保序`() {
        val messages = listOf(
            userMessage(UIMessagePart.Image("file:///a.png"), UIMessagePart.Text("hi")),
            assistantMessage(UIMessagePart.Text("reply")),
            userMessage(
                UIMessagePart.Image("file:///b.png"),
                UIMessagePart.Image("file:///a.png"), // 同一张再次上传：只保留第一次的位置
            ),
        )
        assertEquals(
            listOf("file:///a.png", "file:///b.png"),
            collectConversationImageUrls(messages),
        )
    }

    @Test
    fun `跳过空白 URL 的占位图片`() {
        val messages = listOf(
            userMessage(
                UIMessagePart.Image(""),
                UIMessagePart.Image("   "),
                UIMessagePart.Image("file:///ok.png"),
            )
        )
        assertEquals(listOf("file:///ok.png"), collectConversationImageUrls(messages))
    }

    @Test
    fun `工具输出里的图片不算会话图片`() {
        val messages = listOf(
            assistantMessage(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "generate_image",
                    input = "{}",
                    output = listOf(UIMessagePart.Image("images/generated.png")),
                )
            ),
            userMessage(UIMessagePart.Image("file:///uploaded.png")),
        )
        // 工具输出图的 URL 语义与消息附件不同（相对路径），混进轮播会让「点开的图」与「停在哪页」对不上
        assertEquals(listOf("file:///uploaded.png"), collectConversationImageUrls(messages))
    }

    @Test
    fun `多图会话点击任意一张都翻整个会话`() {
        val group = listOf("file:///a.png", "file:///b.png", "content://media/1")
        assertEquals(group, imagePreviewPages(group, "file:///b.png"))
        assertEquals(group, imagePreviewPages(group, "content://media/1"))
    }

    @Test
    fun `不在会话图片列表里的图片退化为单图预览`() {
        val group = listOf("file:///a.png")
        assertEquals(
            listOf("https://example.com/x.png"),
            imagePreviewPages(group, "https://example.com/x.png"),
        )
        // 会话只有一张图时也是单图
        assertEquals(listOf("file:///a.png"), imagePreviewPages(listOf("file:///a.png"), "file:///a.png"))
        assertEquals(emptyList<String>(), imagePreviewPages(group, null))
    }

    @Test
    fun `起始页等于被点击图片的下标`() {
        val group = listOf("file:///a.png", "file:///b.png", "file:///c.png")
        assertEquals(0, imagePreviewStartIndex(group, "file:///a.png"))
        assertEquals(2, imagePreviewStartIndex(group, "file:///c.png"))
    }

    @Test
    fun `起始页夹在合法范围内`() {
        val group = listOf("file:///a.png", "file:///b.png")
        assertEquals(0, imagePreviewStartIndex(group, "file:///not-in-group.png"))
        assertEquals(0, imagePreviewStartIndex(group, null))
        assertEquals(0, imagePreviewStartIndex(emptyList(), "file:///a.png"))
    }
}
