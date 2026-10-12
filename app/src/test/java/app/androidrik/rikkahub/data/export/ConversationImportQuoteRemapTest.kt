package app.androidrik.rikkahub.data.export

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * #1906：导入时引用必须跟着 messageId 一起重映射。
 *
 * 导入语义是「新建而非覆盖」，所有 message id 都会换新——引用若不同步改写，导入后
 * **每一条引用都指向不存在的 id**（UI 上引用卡片还在显示快照，但定位全失效）。
 * 这是 `dc919e07`（remap 漏清 folderId）同一类错误：换 id 的操作要成体系过一遍
 * 所有指向外部对象的引用字段。
 */
class ConversationImportQuoteRemapTest {

    private val targetAssistant = Uuid.random()

    private fun quotedMessage(text: String, quoteOf: UIMessage, quoteText: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
        annotations = listOf(
            UIMessageAnnotation.QuotedMessage(
                messageId = quoteOf.id,
                role = quoteOf.role,
                content = quoteText,
            )
        ),
    )

    /** target 消息引用 source 消息；source 在 target 之前。 */
    private fun conversationWithQuote(): Conversation {
        val source = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("original answer")))
        val target = quotedMessage("follow up", source, "original answer")
        return Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "引用会话",
            messageNodes = listOf(MessageNode.of(source), MessageNode.of(target)),
        )
    }

    private fun Conversation.quoteOf(index: Int): UIMessageAnnotation.QuotedMessage =
        messageNodes[index].currentMessage.annotations
            .filterIsInstance<UIMessageAnnotation.QuotedMessage>()
            .single()

    @Test
    fun `quote id is rewritten to the new message id`() {
        val source = conversationWithQuote()
        val result = source.remapForImport(targetAssistant)

        val oldQuote = source.quoteOf(1)
        val newQuote = result.quoteOf(1)
        val newSourceId = result.messageNodes[0].currentMessage.id

        assertNotEquals("引用的 messageId 必须换", oldQuote.messageId, newQuote.messageId)
        assertEquals("引用应指向本次导入的新消息 id", newSourceId, newQuote.messageId)
    }

    @Test
    fun `quote snapshot text is never rewritten`() {
        val source = conversationWithQuote()
        val result = source.remapForImport(targetAssistant)
        // 快照是发送依据；改写它会造假数据
        assertEquals("original answer", result.quoteOf(1).content)
    }

    @Test
    fun `forward reference is remapped too`() {
        // 引用目标排在引用者之后：单遍边生成边查表会漏掉这类前向引用
        val target = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("ask")))
        val later = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer")))
        val quoted = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("again")),
            annotations = listOf(
                UIMessageAnnotation.QuotedMessage(messageId = later.id, role = later.role, content = "answer")
            ),
        )
        val source = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "前向引用",
            messageNodes = listOf(MessageNode.of(target), MessageNode.of(later), MessageNode.of(quoted)),
        )
        val result = source.remapForImport(targetAssistant)
        assertEquals(
            result.messageNodes[1].currentMessage.id,
            result.quoteOf(2).messageId,
        )
    }

    @Test
    fun `quote pointing at a message inside an unselected branch is remapped`() {
        // 被引用消息躺在未选中的候选里（同节点多分支）：映射必须覆盖全部候选
        val alt = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("alt branch")))
        val selected = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("selected branch")))
        val branchNode = MessageNode(
            id = Uuid.random(),
            messages = listOf(selected, alt),
            selectIndex = 0,
        )
        val quoted = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("referring to alt")),
            annotations = listOf(
                UIMessageAnnotation.QuotedMessage(messageId = alt.id, role = alt.role, content = "alt branch")
            ),
        )
        val source = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "分支引用",
            messageNodes = listOf(branchNode, MessageNode.of(quoted)),
        )
        val result = source.remapForImport(targetAssistant)
        val remappedAltId = result.messageNodes[0].messages[1].id
        assertEquals(remappedAltId, result.quoteOf(1).messageId)
    }

    @Test
    fun `dangling quote keeps its original id and still imports`() {
        // 引用目标已不在导出文件里（被删除/被压缩截断）——保留原 id，不指向本次导入的任何消息
        val danglingId = Uuid.random()
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("stale reference")),
            annotations = listOf(
                UIMessageAnnotation.QuotedMessage(messageId = danglingId, role = MessageRole.USER, content = "gone")
            ),
        )
        val source = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "悬空引用",
            messageNodes = listOf(MessageNode.of(message)),
        )
        val result = source.remapForImport(targetAssistant)
        val quote = result.quoteOf(0)
        assertEquals("悬空引用不该被乱指到别的消息", danglingId, quote.messageId)
        assertEquals("gone", quote.content)
        assertTrue(
            "不得指向本次导入的任何消息",
            result.messageNodes.none { it.currentMessage.id == quote.messageId },
        )
    }

    @Test
    fun `messages without quotes are unaffected`() {
        val plain = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("no quote")))
        val source = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "无引用",
            messageNodes = listOf(MessageNode.of(plain)),
        )
        val result = source.remapForImport(targetAssistant)
        assertTrue(result.messageNodes.single().currentMessage.annotations.isEmpty())
    }
}
