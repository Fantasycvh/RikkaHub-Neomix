package app.androidrik.rikkahub.ui.pages.chat

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * #1906-B：引用块跳转的目标下标计算。
 *
 * 两个坑都在这里钉住：
 * - **按 messageId 查找**，不能用完整节点列表下标——压缩折叠后渲染的是 `windowNodes()`
 *   （边界之后的尾巴），两个下标空间不一致；
 * - **检查点行偏移**：折叠/展开态下检查点行都恒为列表第一项，命中下标要 +1。
 */
class QuoteJumpTargetIndexTest {

    private fun node(vararg messageIds: Uuid): MessageNode =
        MessageNode(
            id = Uuid.random(),
            messages = messageIds.map { id ->
                UIMessage(id = id, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("m")))
            },
        )

    @Test
    fun `finds the node holding the target message`() {
        val a = Uuid.random(); val b = Uuid.random(); val c = Uuid.random()
        val nodes = listOf(node(a), node(b), node(c))
        assertEquals(2, nodes.quoteJumpTargetIndex(c, hasCheckpointRow = false))
    }

    @Test
    fun `adds one for the checkpoint row in both collapsed and expanded states`() {
        val a = Uuid.random(); val b = Uuid.random()
        val nodes = listOf(node(a), node(b))
        assertEquals(0, nodes.quoteJumpTargetIndex(a, hasCheckpointRow = false))
        assertEquals(1, nodes.quoteJumpTargetIndex(a, hasCheckpointRow = true))
    }

    @Test
    fun `finds the node by any candidate message not just the selected one`() {
        // 被引用的消息可能躺在未选中的候选分支里：查找必须覆盖 node.messages 全部候选
        val selected = Uuid.random()
        val alt = Uuid.random()
        val nodes = listOf(node(selected, alt))
        assertEquals(0, nodes.quoteJumpTargetIndex(alt, hasCheckpointRow = false))
    }

    @Test
    fun `missing target reports minus one so the caller can expand or give up`() {
        val present = Uuid.random()
        val nodes = listOf(node(present))
        assertEquals(-1, nodes.quoteJumpTargetIndex(Uuid.random(), hasCheckpointRow = false))
        assertEquals(-1, nodes.quoteJumpTargetIndex(Uuid.random(), hasCheckpointRow = true))
    }

    @Test
    fun `windowed list locating a quoted message absent from the collapsed tail reports minus one`() {
        // 模拟压缩折叠：完整列表 3 个节点，窗口只剩尾部 1 个；引用指向最早那条
        val early = Uuid.random(); val mid = Uuid.random(); val tail = Uuid.random()
        val full = listOf(node(early), node(mid), node(tail))
        val window = full.drop(2)
        assertEquals(
            "引用指向折叠历史里的消息：窗口内应报 -1，触发调用方展开",
            -1,
            window.quoteJumpTargetIndex(early, hasCheckpointRow = true),
        )
        assertEquals(1, window.quoteJumpTargetIndex(tail, hasCheckpointRow = true))
    }
}
