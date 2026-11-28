package app.androidrik.rikkahub.ui.hooks

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatInputStateDraftTest {

    private val imgA = UIMessagePart.Image("file:///files/upload/a.png")
    private val imgB = UIMessagePart.Image("file:///files/upload/b.png")
    private val imgNew = UIMessagePart.Image("file:///files/upload/a-new.png")
    private val docMissing = UIMessagePart.Document("file:///files/upload/missing.pdf", "missing.pdf", "application/pdf")

    @Test
    fun `toDraft与restoreDraft完整往返编辑态分段下标替换映射与keepFollowing`() {
        val state = ChatInputState()
        val msgId = Uuid.random()
        val toolPart = UIMessagePart.Tool(toolCallId = "t1", toolName = "search", input = "{}")
        val originalParts = listOf(
            imgA,
            UIMessagePart.Text("首段"),
            toolPart,
            UIMessagePart.Text("尾段"),
            imgB,
        )
        state.editingMessage = msgId
        state.setContents(originalParts, textPartIndex = 1)
        state.editKeepFollowing = false
        state.replaceImage(imgA, imgNew.url)
        state.setMessageText("修改后的首段")

        val draft = state.toDraft()
        val restored = ChatInputState()
        restored.restoreDraft(draft)

        assertEquals(msgId, restored.editingMessage)
        assertFalse(restored.editKeepFollowing)
        assertEquals(
            listOf(
                imgNew,
                UIMessagePart.Text("修改后的首段"),
                toolPart,
                UIMessagePart.Text("尾段"),
                imgB,
            ),
            restored.getContents(),
        )
        // 原附件不允许就地删盘，替换后的新图允许就地删盘
        assertFalse(restored.shouldDeleteFileOnRemove(imgA))
        assertFalse(restored.shouldDeleteFileOnRemove(imgB))
        assertTrue(restored.shouldDeleteFileOnRemove(imgNew))
    }

    @Test
    fun `restoreDraft过滤磁盘不存在的本地附件并保留引用与技能`() {
        val quote = UIMessageAnnotation.QuotedMessage(
            messageId = Uuid.random(),
            role = MessageRole.USER,
            content = "quoted",
        )
        val state = ChatInputState()
        state.setMessageText("草稿正文")
        state.messageContent = listOf(imgA, docMissing)
        state.pendingQuote = quote
        state.pendingSkills = listOf("skill-a", "skill-b")

        val draft = state.toDraft()
        val restored = ChatInputState()
        restored.restoreDraft(draft) { url -> url != docMissing.url }

        assertEquals("草稿正文", restored.textContent.text.toString())
        assertEquals(listOf(imgA), restored.messageContent)
        assertEquals(quote, restored.pendingQuote)
        assertEquals(listOf("skill-a", "skill-b"), restored.pendingSkills)
        assertFalse(restored.isEditing())
    }

    @Test
    fun `reconcileDeletedEditingTarget 未修改则清空_已修改则退出编辑态保留内容`() {
        val originalParts = listOf(imgA, UIMessagePart.Text("原内容"))

        val untouched = ChatInputState()
        untouched.editingMessage = Uuid.random()
        untouched.setContents(originalParts)
        untouched.reconcileDeletedEditingTarget()
        assertFalse(untouched.isEditing())
        assertTrue(untouched.isEmpty())

        val edited = ChatInputState()
        edited.pendingQuote = UIMessageAnnotation.QuotedMessage(
            messageId = Uuid.random(),
            role = MessageRole.USER,
            content = "旧引用不应残留",
        )
        edited.editingMessage = Uuid.random()
        edited.setContents(originalParts)
        assertNull(edited.pendingQuote)
        edited.setMessageText("用户改写了很长一段")
        edited.reconcileDeletedEditingTarget()
        assertFalse(edited.isEditing())
        assertNull(edited.pendingQuote)
        assertEquals("用户改写了很长一段", edited.textContent.text.toString())
        assertEquals(listOf(imgA), edited.messageContent)
    }

    @Test
    fun `exitEditingMode 清理全部编辑内部字段`() {
        val state = ChatInputState()
        state.editingMessage = Uuid.random()
        state.setContents(listOf(imgA, UIMessagePart.Text("原文")))
        state.editKeepFollowing = false
        state.replaceImage(imgA, imgNew.url)

        state.exitEditingMode()

        assertNull(state.editingMessage)
        assertTrue(state.editKeepFollowing)
        assertTrue(state.shouldDeleteFileOnRemove(imgA))
        assertEquals(listOf(UIMessagePart.Text("原文"), imgNew), state.getContents())
    }
}
