package app.androidrik.rikkahub.ui.hooks

import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 编辑态下的图片替换（issue #2152-B）。
 *
 * [MergeEditedPartsTest] 钉的是纯合并逻辑，这里钉 [ChatInputState] 自己的记录：
 * 替换要保住位置、旧文件不能因为「不在输入框里了」被当成临时文件删掉。
 */
class ChatInputStateReplaceTest {

    private val imageA = UIMessagePart.Image("file:///files/upload/a.png")
    private val imageB = UIMessagePart.Image("file:///files/upload/b.png")
    private val imageNew = UIMessagePart.Image("file:///files/upload/a-new.png")

    private fun editingState(parts: List<UIMessagePart>): ChatInputState {
        val state = ChatInputState()
        state.editingMessage = Uuid.random()
        state.setContents(parts)
        return state
    }

    @Test
    fun `替换图片后位置不变`() {
        val state = editingState(listOf(imageA, UIMessagePart.Text("文本"), imageB))

        state.replaceImage(imageA, imageNew.url)

        assertEquals(
            listOf(imageNew, UIMessagePart.Text("文本"), imageB),
            state.getContents(),
        )
    }

    @Test
    fun `替换不删旧文件_新文件按临时新增处理`() {
        val state = editingState(listOf(imageA, UIMessagePart.Text("文本")))

        state.replaceImage(imageA, imageNew.url)

        // 旧图可能仍被历史分支引用 ⇒ 不允许就地删盘
        assertFalse(state.shouldDeleteFileOnRemove(imageA))
        // 新图是本次新写入 files/upload 的 ⇒ 移除时应当清理
        assertTrue(state.shouldDeleteFileOnRemove(imageNew))
    }

    @Test
    fun `替换后再移除新图_原图也不复活`() {
        val state = editingState(listOf(imageA, UIMessagePart.Text("文本")))
        state.replaceImage(imageA, imageNew.url)

        state.messageContent = state.messageContent.filterNot { it == imageNew }

        assertEquals(listOf(UIMessagePart.Text("文本")), state.getContents())
    }

    @Test
    fun `删除既有附件同样不允许就地删盘`() {
        val state = editingState(listOf(imageA, UIMessagePart.Text("文本"), imageB))

        state.messageContent = state.messageContent.filterNot { it == imageB }

        assertFalse(state.shouldDeleteFileOnRemove(imageB))
        assertEquals(listOf(imageA, UIMessagePart.Text("文本")), state.getContents())
    }

    @Test
    fun `新消息里替换图片不需要保位置映射`() {
        val state = ChatInputState()
        state.messageContent = listOf(imageA, imageB)

        state.replaceImage(imageA, imageNew.url)

        assertEquals(listOf(imageNew, imageB), state.getContents())
        // 新消息（非编辑态）下所有附件都是临时文件
        assertTrue(state.shouldDeleteFileOnRemove(imageNew))
    }
}
