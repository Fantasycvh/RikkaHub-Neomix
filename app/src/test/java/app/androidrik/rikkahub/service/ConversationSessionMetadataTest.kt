package app.androidrik.rikkahub.service

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.toMessageNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 元数据编辑的统一落地原语（`ConversationSession.updateMetadata`）。
 *
 * 背景（`docs/upstream-analysis.md` 对 `4a3eefc1` 的评估）：置顶/移动助手这类只改标量字段的
 * 编辑，历史写法是「从 DB 读整对象 → 改字段 → 整对象写回」，对**活跃 session** 会用旧快照
 * 覆盖内存态 ⇒ 生成中途切助手/打星标会把已在流式接收的 messageNodes 换掉
 * （与「停止生成丢内容」同族）。
 *
 * 原语的两条不变量即本测试的断言目标：
 * 1. `update` 作用在**当前内存值**上 ⇒ 在途内容不丢；
 * 2. `persist` 只写对应列 ⇒ 落库侧也不会用旧快照盖新内容。
 */
class ConversationSessionMetadataTest {

    private fun newSession(scope: CoroutineScope, initial: Conversation) = ConversationSession(
        id = initial.id,
        initial = initial,
        scope = scope,
        onIdle = {},
    )

    /** 一个「正在生成」的会话：内存里有若干节点（模拟已流式收到的内容）。 */
    private fun generatingConversation(assistantId: Uuid): Conversation {
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)
        val node = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(app.androidrik.ai.ui.UIMessagePart.Text("streamed so far")),
        ).toMessageNode()
        return conversation.copy(messageNodes = conversation.messageNodes + node)
    }

    @Test
    fun `metadata update operates on live in-memory nodes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val assistantId = Uuid.random()
        val session = newSession(scope, generatingConversation(assistantId))
        val nodesBefore = session.state.value.messageNodes

        assertTrue("前置：内存里有在途内容", nodesBefore.isNotEmpty())

        session.updateMetadata(
            update = { it.copy(isPinned = true) },
            persist = { /* 落库在测试里只记录 */ },
        )

        // 核心断言：改元数据后，内存里的消息节点**一个都没变**
        assertEquals(nodesBefore, session.state.value.messageNodes)
        assertEquals(assistantId, session.state.value.assistantId)
        assertTrue(session.state.value.isPinned)
    }

    @Test
    fun `moving assistant keeps streamed nodes and clears folder`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = newSession(scope, generatingConversation(Uuid.random()))
        val nodesBefore = session.state.value.messageNodes
        val target = Uuid.random()

        session.updateMetadata(
            update = { it.copy(assistantId = target, folderId = null) },
            persist = { },
        )

        assertEquals(target, session.state.value.assistantId)
        assertNull("换助手要清空文件夹归属（否则会话在新助手下不可见）", session.state.value.folderId)
        // 在途内容必须原样保留 —— 这是旧写法会丢的东西
        assertEquals(nodesBefore, session.state.value.messageNodes)
    }

    @Test
    fun `persist receives the same value that landed in memory`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = newSession(scope, generatingConversation(Uuid.random()))
        var persisted: Conversation? = null

        session.updateMetadata(
            update = { it.copy(isPinned = true) },
            persist = { persisted = it },
        )

        // 内存与落库必须同值：若各取各的基线（历史上出现过「内存说已置顶、DB 说未置顶」），
        // 下一次整对象保存就会把 DB 覆盖回去
        assertEquals(session.state.value.isPinned, persisted?.isPinned)
        assertEquals(session.state.value.messageNodes, persisted?.messageNodes)
    }

    @Test
    fun `update applies on the latest value across sequential edits`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = newSession(scope, generatingConversation(Uuid.random()))

        // 连续两次置顶（模拟快速连点）：每次都基于**上一次的结果**取反
        session.updateMetadata(update = { it.copy(isPinned = !it.isPinned) }, persist = { })
        assertTrue(session.state.value.isPinned)
        session.updateMetadata(update = { it.copy(isPinned = !it.isPinned) }, persist = { })
        assertEquals("两次切换应回到原值，说明基于最新态而非同一快照", false, session.state.value.isPinned)
    }

    @Test
    fun `update is not handed a stale snapshot`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = newSession(scope, generatingConversation(Uuid.random()))

        // 模拟「加载挂起期间已有更新写入」：先往内存追加内容，再确认 update 看到的是新值
        val extra = UIMessage(
            role = MessageRole.USER,
            parts = listOf(app.androidrik.ai.ui.UIMessagePart.Text("newer turn")),
        ).toMessageNode()
        session.state.value = session.state.value.copy(
            messageNodes = session.state.value.messageNodes + extra
        )
        var seen: Conversation? = null

        session.updateMetadata(
            update = { c -> seen = c; c.copy(isPinned = true) },
            persist = { },
        )

        assertEquals("update 必须看到最新内存态", 2, seen?.messageNodes?.size)
    }
}
