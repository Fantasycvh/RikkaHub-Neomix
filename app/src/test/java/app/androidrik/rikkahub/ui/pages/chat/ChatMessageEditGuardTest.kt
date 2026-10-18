package app.androidrik.rikkahub.ui.pages.chat

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.toMessageNode
import java.io.File
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * ④ 「生成中不许编辑消息」的守卫 + 编辑落点的纯函数变换（2026-10-18 核查项）。
 *
 * 两个缺陷形态（与相邻的 `onDelete` 对比出来的）：
 * 1. `onUpdateMessage` **没有**守卫：`onDelete` 会拦「生成中删除」，编辑却直接落库；
 * 2. 落点是 `vm.updateConversation(conversation.copy(...))` —— 拿 Compose 回调闭包里的
 *    **快照整对象替换**当前会话。生成中每一块流式内容都在往同一个节点上回写，
 *    替换会把在途正文一并冲掉（即便不在生成中，也会回滚期间发生的其它字段改动）。
 *
 * 守卫抽成 [rejectMessageEditWhileGenerating]、落点抽成 [Conversation.withReplacedNode]，
 * 两者都能在 JVM 单测里证伪；接线本身用源码扫描钉住（Compose 回调单测够不到）。
 */
class ChatMessageEditGuardTest {

    // ---- 守卫：生成中拒绝、空闲放行 ----

    @Test
    fun `editing is allowed when nothing is generating`() {
        var rejected = 0

        val blocked = rejectMessageEditWhileGenerating(
            loadingJob = null,
            onReject = { rejected++ },
        )

        assertFalse("空闲时必须放行编辑", blocked)
        assertEquals("放行时不得弹错误提示", 0, rejected)
    }

    @Test
    fun `editing is blocked and reported while generating`() {
        val running = Job()
        var rejected = 0

        val blocked = rejectMessageEditWhileGenerating(
            loadingJob = running,
            onReject = { rejected++ },
        )

        assertTrue("生成中必须拒绝编辑（与 onDelete 同款）", blocked)
        assertEquals("必须给出可见提示，否则用户以为点了没反应", 1, rejected)
        running.cancel()
    }

    // ---- 落点：只换目标节点，其余一律取自当前内存态 ----

    private fun conversationWithLiveContent(): Conversation {
        val conversation = Conversation(assistantId = Uuid.random(), title = "标题", messageNodes = emptyList())
        return conversation.copy(
            messageNodes = listOf(
                UIMessage.user("问题").toMessageNode(),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("流式收到的正文")),
                ).toMessageNode(),
                UIMessage.user("追问").toMessageNode(),
            ),
            chatSuggestions = listOf("建议 A"),
        )
    }

    @Test
    fun `replacing a node keeps every other node instance untouched`() {
        val current = conversationWithLiveContent()
        val target = current.messageNodes[1]
        val edited = target.copy(
            messages = listOf(target.messages[0].copy(parts = listOf(UIMessagePart.Text("用户改过的正文")))),
        )

        val result = current.withReplacedNode(edited)

        assertEquals("目标节点被替换", edited, result.messageNodes[1])
        assertSame("其它节点必须是同一实例（不能重建）", current.messageNodes[0], result.messageNodes[0])
        assertSame("其它节点必须是同一实例（不能重建）", current.messageNodes[2], result.messageNodes[2])
        assertEquals("节点数不变", 3, result.messageNodes.size)
        // 元数据也必须保留：编辑消息不该回滚标题/建议
        assertEquals("标题", result.title)
        assertEquals(listOf("建议 A"), result.chatSuggestions)
    }

    /**
     * 旧写法的丢数据形态：编辑时用的是**回调闭包里的旧快照**，而生成已把更新的内容写进内存。
     *
     * 纯函数落点从 `current` 出发 ⇒ 编辑只改目标节点，其余节点仍是内存里的最新内容。
     */
    @Test
    fun `editing derives from the current conversation, not a stale snapshot`() {
        val staleSnapshot = conversationWithLiveContent()
        // 生成继续：同一个助手节点又收到了更多正文
        val target = staleSnapshot.messageNodes[1]
        val live = staleSnapshot.copy(
            messageNodes = staleSnapshot.messageNodes.map { node ->
                if (node.id == target.id) {
                    node.copy(
                        messages = node.messages.map {
                            it.copy(parts = it.parts + UIMessagePart.Text("（后续追加）"))
                        },
                    )
                } else {
                    node
                }
            },
        )
        // 用户在编辑一个**旧快照**里的节点
        val editedFromStale = staleSnapshot.messageNodes[1].copy(isFavorite = true)

        val result = live.withReplacedNode(editedFromStale)

        assertEquals("编辑必须作用在最新内存态上", 3, result.messageNodes.size)
        assertSame(
            "非目标节点绝不能被旧快照的副本替换",
            live.messageNodes[0],
            result.messageNodes[0],
        )
        assertEquals("目标节点按编辑内容替换", true, result.messageNodes[1].isFavorite)
        assertSame("未参与编辑的节点仍是内存里的实例", live.messageNodes[2], result.messageNodes[2])
    }

    @Test
    fun `replacing an unknown node is a no-op`() {
        val current = conversationWithLiveContent()
        val ghost = UIMessage.user("别的会话里的消息").toMessageNode()

        val result = current.withReplacedNode(ghost)

        assertSame("目标不存在时原样返回（不追加节点）", current, result)
        assertEquals(3, result.messageNodes.size)
    }

    // ---- 接线：ChatPage 的 onUpdateMessage 必须走守卫 + 纯函数落点 ----

    private fun chatPageSource(): String {
        val file = listOf(
            "src/main/java/app/androidrik/rikkahub/ui/pages/chat/ChatPage.kt",
            "app/src/main/java/app/androidrik/rikkahub/ui/pages/chat/ChatPage.kt",
        ).map(::File).firstOrNull { it.exists() }
            ?: error("找不到 ChatPage.kt（工作目录=${File(".").absolutePath}）")
        return file.readText().replace("\r\n", "\n")
    }

    @Test
    fun `onUpdateMessage is guarded and uses the pure transform`() {
        val source = chatPageSource()
        val start = source.indexOf("onUpdateMessage = {")
        assertTrue("ChatPage 里必须还有 onUpdateMessage 回调", start >= 0)
        val end = source.indexOf("onTranslate = {", start)
        assertTrue("找不到 onUpdateMessage 的回调边界", end > start)
        val body = source.substring(start, end)

        assertTrue(
            "onUpdateMessage 必须带生成中守卫（与 onDelete 对齐）：$body",
            "rejectMessageEditWhileGenerating(" in body,
        )
        assertTrue(
            "落点必须是基于 current 的纯函数变换：$body",
            "vm.updateMessageNode(" in body,
        )
        assertFalse(
            "不得再用回调闭包里的 conversation 快照整对象替换：$body",
            "vm.updateConversation(" in body,
        )
    }
}
