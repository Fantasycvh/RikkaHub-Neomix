package app.androidrik.rikkahub.service

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * #1906：引用随队列项流转。
 *
 * 为什么单独测：生成期间点发送走的是**入队**路径，队列项若不带注解，引用会在入队这步
 * 静默丢掉（UI 上引用卡片明明还在输入框上方）。这条通路不经过 UI，坏了不会有任何提示。
 */
class MessageQueueQuoteTest {

    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    private fun quote(content: String = "quoted") = UIMessageAnnotation.QuotedMessage(
        messageId = Uuid.random(),
        role = MessageRole.ASSISTANT,
        content = content,
    )

    @Test
    fun `enqueue carries annotations to the dispatched item`() {
        val queue = MessageQueue()
        val annotation = quote()
        queue.enqueue(text("hello"), annotations = listOf(annotation))
        assertEquals(listOf(annotation), queue.takeNext()!!.annotations)
    }

    @Test
    fun `enqueue defaults to no annotations`() {
        val queue = MessageQueue()
        queue.enqueue(text("hello"))
        assertTrue(queue.takeNext()!!.annotations.isEmpty())
    }

    @Test
    fun `editing parts preserves the original quote`() {
        val queue = MessageQueue()
        val annotation = quote()
        queue.enqueue(text("original"), annotations = listOf(annotation))
        val id = queue.state.value.messages.single().id

        queue.beginEdit(id)
        // 只改文本、Annotations 传 null ⇒ 引用保留（编辑文本不等于撤回引用）
        queue.finishEdit(id, text("edited"))

        val dispatched = queue.takeNext()!!
        assertEquals(text("edited"), dispatched.parts)
        assertEquals(listOf(annotation), dispatched.annotations)
    }

    @Test
    fun `saving an edit can remove the quote explicitly`() {
        val queue = MessageQueue()
        queue.enqueue(text("original"), annotations = listOf(quote()))
        val id = queue.state.value.messages.single().id

        queue.beginEdit(id)
        queue.finishEdit(id, text("edited"), annotations = emptyList())

        assertTrue(queue.takeNext()!!.annotations.isEmpty())
    }

    @Test
    fun `cancelling an edit leaves the original quote untouched`() {
        val queue = MessageQueue()
        val annotation = quote()
        queue.enqueue(text("original"), annotations = listOf(annotation))
        val id = queue.state.value.messages.single().id

        queue.beginEdit(id)
        // 取消编辑：parts 传 null ⇒ 正文与注解都原样保留
        queue.finishEdit(id)

        assertEquals(listOf(annotation), queue.takeNext()!!.annotations)
    }

    @Test
    fun `annotations are per item and do not leak to siblings`() {
        val queue = MessageQueue()
        queue.enqueue(text("quoted one"), annotations = listOf(quote("a")))
        queue.enqueue(text("plain"))

        assertEquals(1, queue.takeNext()!!.annotations.size)
        assertTrue(queue.takeNext()!!.annotations.isEmpty())
    }
}
