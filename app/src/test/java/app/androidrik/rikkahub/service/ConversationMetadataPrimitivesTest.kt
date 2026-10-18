package app.androidrik.rikkahub.service

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.toMessageNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Web 路由 pin / title / injections 三处改用的元数据写入原语的**字段级契约**：
 * **只改目标字段，其它字段（尤其 `messageNodes`）逐字段不变**。
 *
 * 为什么需要这层测试：历史写法是「读 DB 整对象 → 改一个字段 → `saveConversation`」，
 * 对**正在生成**的会话会用 DB 里的旧快照整份覆盖内存态（在途正文消失）。改成单列写之后，
 * 「其余字段一个都不动」必须是可以被钉住的不变量 —— 而它由
 * [updateMetadataSafely]（= `ChatService.updateConversationMetadata` 的实现）保证，
 * 所以这里按生产代码的同一形态（`update` 只 copy 目标字段、`persist` 只写该列）断言：
 *
 * - 内存态：目标字段变了，`messageNodes` 与其它字段逐字段等于基线；
 * - 落库侧：`persist` 收到的是**同一个**会话（内存值），而不是某个 DB 快照。
 */
class ConversationMetadataPrimitivesTest {

    private fun newSession(scope: CoroutineScope, initial: Conversation) = ConversationSession(
        id = initial.id,
        initial = initial,
        scope = scope,
        onIdle = {},
    )

    /** 一个「正在生成」的会话：内存里有已流式收到的内容（DB 里还没有）。 */
    private fun generatingConversation(): Conversation {
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = Uuid.random())
        val node = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("streamed so far")),
        ).toMessageNode()
        return conversation.copy(messageNodes = conversation.messageNodes + node)
    }

    private fun markGenerating(session: ConversationSession, scope: CoroutineScope) {
        session.setJob(scope.launch { awaitCancellation() })
        assertTrue("前置：会话必须处于生成中", session.isGenerating)
    }

    @Test
    fun `pin primitive flips only isPinned`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val baseline = generatingConversation()
        val session = newSession(scope, baseline)
        markGenerating(session, scope)
        var persisted: Conversation? = null

        // 与 ChatService.togglePinStatus 同一形态：基数是**当前内存值**
        updateMetadataSafely(
            session = session,
            loadCurrent = { error("生成中改置顶不得读 DB 快照当基线") },
            update = { it.copy(isPinned = !it.isPinned) },
            persist = { persisted = it },
        )

        val expected = baseline.copy(isPinned = true)
        assertEquals("只改 is_pinned 一列（其余字段逐字段相同）", expected, session.state.value)
        assertEquals("落库侧拿到的必须是内存那个值", expected, persisted)
        assertEquals("在途消息一个字都不许变", baseline.messageNodes, persisted?.messageNodes)
    }

    @Test
    fun `title primitive changes only the title`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val baseline = generatingConversation()
        val session = newSession(scope, baseline)
        markGenerating(session, scope)
        var persisted: Conversation? = null

        // 与 ChatService.setConversationTitle 同一形态
        updateMetadataSafely(
            session = session,
            loadCurrent = { error("生成中改标题不得读 DB 快照当基线") },
            update = { it.copy(title = "手改的标题") },
            persist = { persisted = it },
        )

        assertEquals(baseline.copy(title = "手改的标题"), session.state.value)
        assertEquals(baseline.copy(title = "手改的标题"), persisted)
        assertEquals(baseline.messageNodes, persisted?.messageNodes)
    }

    @Test
    fun `injections primitive changes only the two id sets`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val baseline = generatingConversation().copy(
            modeInjectionIds = setOf(Uuid.random()),
            lorebookIds = setOf(Uuid.random()),
        )
        val session = newSession(scope, baseline)
        markGenerating(session, scope)
        var persisted: Conversation? = null
        val newModeIds = setOf(Uuid.random(), Uuid.random())
        val newLorebookIds = setOf(Uuid.random())

        // 与 ChatService.setConversationInjections 同一形态
        updateMetadataSafely(
            session = session,
            loadCurrent = { error("生成中改注入不得读 DB 快照当基线") },
            update = { it.copy(modeInjectionIds = newModeIds, lorebookIds = newLorebookIds) },
            persist = { persisted = it },
        )

        val expected = baseline.copy(modeInjectionIds = newModeIds, lorebookIds = newLorebookIds)
        assertEquals("只改两条注入列（其余字段逐字段相同）", expected, session.state.value)
        assertEquals(expected, persisted)
        assertEquals(baseline.messageNodes, persisted?.messageNodes)
        // 反向保证：两条绑定是**整份替换**（不是与旧值合并），否则用户取消勾选不会生效
        assertTrue(
            "旧绑定必须被替换掉",
            persisted?.modeInjectionIds!!.intersect(baseline.modeInjectionIds).isEmpty(),
        )
    }

    /** 没有活跃 session（Web 调用时该会话没被打开）才允许从 DB 读一次作为基线，仍只改目标字段。 */
    @Test
    fun `title primitive without a session keeps every other field from the db baseline`() = runBlocking {
        val dbBaseline = generatingConversation().copy(title = "旧标题")
        var persisted: Conversation? = null

        updateMetadataSafely(
            session = null,
            loadCurrent = { dbBaseline },
            update = { it.copy(title = "新标题") },
            persist = { persisted = it },
        )

        assertEquals(dbBaseline.copy(title = "新标题"), persisted)
    }
}
