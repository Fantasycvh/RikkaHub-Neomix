package app.androidrik.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.CustomBody
import app.androidrik.ai.provider.CustomHeader
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.toMessageNode
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceTest {
    @Test
    fun `fork conversation inherits folder and workspace context`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "Source conversation",
            messageNodes = emptyList(),
            workspaceCwd = "/workspace/project",
            folderId = Uuid.random(),
        )

        val fork = createForkConversation(source, emptyList())

        assertNotEquals(source.id, fork.id)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.folderId, fork.folderId)
        // 分叉标题沿用源标题 + 序号（上游 458c16df 的语义）
        assertEquals("Source conversation(1)", fork.title)
        assertFalse(fork.isPinned)
    }

    // ---- 分叉标题（`forkConversationTitle`）----

    private fun titledConversation(title: String) = Conversation(
        assistantId = Uuid.random(),
        title = title,
        messageNodes = emptyList(),
    )

    @Test
    fun `fork title takes the first unoccupied index`() {
        val source = titledConversation("Alpha")
        assertEquals(
            "Alpha(3)",
            forkConversationTitle(source, setOf("Alpha(1)", "Alpha(2)")),
        )
    }

    @Test
    fun `fork title reuses an index freed by deletion`() {
        // 用户删掉 Alpha(2) 后再分叉：应复用空出的 2，而不是一路涨到 3
        val source = titledConversation("Alpha")
        assertEquals(
            "Alpha(2)",
            forkConversationTitle(source, setOf("Alpha(1)", "Alpha(3)")),
        )
    }

    @Test
    fun `fork title ignores unrelated titles`() {
        val source = titledConversation("Alpha")
        assertEquals(
            "Alpha(1)",
            forkConversationTitle(source, setOf("Beta(1)", "Gamma", "Alpha 的讨论")),
        )
    }

    @Test
    fun `fork keeps title blank when source has no title`() {
        // 有意与上游不同：上游在空标题时产出 "(1)"，既无信息量又因「非空」让自动起名
        // （generateTitle 仅在 title.isBlank() 时触发）永远不再修正它。保持空白才能被正常命名。
        assertEquals("", forkConversationTitle(titledConversation(""), emptySet()))
        assertEquals("", forkConversationTitle(titledConversation("   "), setOf("(1)")))
    }

    @Test
    fun `fork conversation title follows the same rule through the factory`() {
        val source = titledConversation("Alpha")
        val fork = createForkConversation(source, emptyList(), setOf("Alpha(1)"))
        assertEquals("Alpha(2)", fork.title)
    }

    // ---- 已有序号递增而非叠加（上游 95fed05e / #1961）----

    @Test
    fun `fork increments existing numeric suffix instead of stacking`() {
        assertEquals(
            "Alpha(2)",
            forkConversationTitle(titledConversation("Alpha(1)"), emptySet()),
        )
        // 已占用 2、3 时顺延到 4
        assertEquals(
            "Alpha(4)",
            forkConversationTitle(titledConversation("Alpha(1)"), setOf("Alpha(2)", "Alpha(3)")),
        )
        // 对 Alpha(2) 再分叉：从 3 起，不产出 Alpha(2)(1)
        assertEquals(
            "Alpha(3)",
            forkConversationTitle(titledConversation("Alpha(2)"), setOf("Alpha(1)", "Alpha(2)")),
        )
    }

    @Test
    fun `fork treats non-numeric suffix as part of the base title`() {
        assertEquals(
            "Alpha(abc)(1)",
            forkConversationTitle(titledConversation("Alpha(abc)"), emptySet()),
        )
    }

    @Test
    fun `fork ignores a suffix that is not at the end`() {
        // 中间的 (1) 属于基名的一部分，只有**尾部**数字后缀才算序号
        assertEquals(
            "Alpha(1) 的讨论(1)",
            forkConversationTitle(titledConversation("Alpha(1) 的讨论"), emptySet()),
        )
    }

    @Test
    fun `fork never stacks suffixes across three generations`() {
        // 模拟连续分叉：Alpha → Alpha(1) → 对它再分叉
        val first = createForkConversation(titledConversation("Alpha"), emptyList(), emptySet())
        assertEquals("Alpha(1)", first.title)

        val second = createForkConversation(first, emptyList(), setOf(first.title))
        assertEquals("Alpha(2)", second.title)
        assertFalse(second.title.contains("(1)(1)"))

        val third = createForkConversation(second, emptyList(), setOf(first.title, second.title))
        assertEquals("Alpha(3)", third.title)
    }

    @Test
    fun `blank source title still wins over suffix handling`() {
        // fork 的有意差异优先：源标题为空时保持空白（让自动起名能正常接管）
        assertEquals("", forkConversationTitle(titledConversation(""), emptySet()))
        assertEquals("", forkConversationTitle(titledConversation("   "), setOf("(1)")))
    }

    // ---- #1975「配置缺失」提示的防刷屏契约 ----

    private fun errorFor(conversationId: Uuid?, solution: ChatErrorSolution?) = ChatError(
        error = IllegalStateException("boom"),
        conversationId = conversationId,
        solution = solution,
    )

    @Test
    fun `config error is reported once per conversation`() {
        val conv = Uuid.random()
        val existing = listOf(errorFor(conv, ChatErrorSolution.CheckTitleModelSettings))

        // 同一会话同类提示已存在 ⇒ 不再重复报（标题与建议共用同一 solution，
        // 故一轮里标题先报、建议被去重，不会刷屏）
        assertFalse(shouldReportConfigError(existing, conv, ChatErrorSolution.CheckTitleModelSettings))
        // 用户点掉后（列表里没有了）⇒ 恢复提醒
        assertTrue(shouldReportConfigError(emptyList(), conv, ChatErrorSolution.CheckTitleModelSettings))
    }

    @Test
    fun `config error is not suppressed across conversations`() {
        val convA = Uuid.random()
        val convB = Uuid.random()
        val existing = listOf(errorFor(convA, ChatErrorSolution.CheckTitleModelSettings))

        // 另一个会话首次遇到同样问题 ⇒ 仍然提示（只抑制「同一会话重复」）
        assertTrue(shouldReportConfigError(existing, convB, ChatErrorSolution.CheckTitleModelSettings))
        // 没有会话归属的错误（如全局错误）不抑制任何会话
        val globalError = listOf(errorFor(null, ChatErrorSolution.CheckTitleModelSettings))
        assertTrue(shouldReportConfigError(globalError, convA, ChatErrorSolution.CheckTitleModelSettings))
    }

    @Test
    fun `config error ignores other error kinds in the same conversation`() {
        val conv = Uuid.random()
        // 同会话的「生成失败」错误（无 solution）不该让配置提示被吃掉
        val existing = listOf(errorFor(conv, null))
        assertTrue(shouldReportConfigError(existing, conv, ChatErrorSolution.CheckTitleModelSettings))
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `merge compressed conversation - no new messages keeps only compressed nodes`() {
        val snapshot = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(
                UIMessage.user("old-1").toMessageNode(),
                UIMessage.user("old-2").toMessageNode(),
            ),
        )
        val latest = snapshot.copy(title = "更新后的标题")
        val compressedNodes = listOf(UIMessage.user("summary").toMessageNode())

        val result = mergeCompressedConversation(snapshot, latest, compressedNodes)

        assertEquals(listOf("summary"), result.messageNodes.map { it.currentMessage.toText() })
        assertEquals(emptyList<String>(), result.chatSuggestions)
    }

    @Test
    fun `merge compressed conversation - keeps nodes appended during compression`() {
        val snapshot = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(
                UIMessage.user("old-1").toMessageNode(),
                UIMessage.user("old-2").toMessageNode(),
            ),
        )
        val latest = snapshot.copy(
            messageNodes = snapshot.messageNodes +
                UIMessage.user("new-1").toMessageNode() +
                UIMessage.user("new-2").toMessageNode(),
        )
        val compressedNodes = listOf(UIMessage.user("summary").toMessageNode())

        val result = mergeCompressedConversation(snapshot, latest, compressedNodes)

        assertEquals(
            listOf("summary", "new-1", "new-2"),
            result.messageNodes.map { it.currentMessage.toText() },
        )
    }

    @Test
    fun `merge compressed conversation - metadata comes from latest state not stale snapshot`() {
        val id = Uuid.random()
        val folderId = Uuid.random()
        val snapshot = Conversation.ofId(
            id = id,
            messages = listOf(UIMessage.user("old-1").toMessageNode()),
        ).copy(title = "旧标题")
        val latest = snapshot.copy(
            title = "新标题",
            folderId = folderId,
            isPinned = true,
        )
        val compressedNodes = listOf(UIMessage.user("summary").toMessageNode())

        val result = mergeCompressedConversation(snapshot, latest, compressedNodes)

        assertEquals("新标题", result.title)
        assertEquals(folderId, result.folderId)
        assertEquals(true, result.isPinned)
        assertEquals(emptyList<String>(), result.chatSuggestions)
    }

    @Test
    fun `merge compressed conversation - appends nothing when latest shrank`() {
        // 边界：latest 比快照短（drop 超长应安全返回空），不崩溃且结果只有压缩节点
        val snapshot = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(
                UIMessage.user("old-1").toMessageNode(),
                UIMessage.user("old-2").toMessageNode(),
            ),
        )
        val latest = snapshot.copy(
            messageNodes = listOf(UIMessage.user("old-1").toMessageNode()),
        )
        val compressedNodes = listOf(UIMessage.user("summary").toMessageNode())

        val result = mergeCompressedConversation(snapshot, latest, compressedNodes)

        assertEquals(listOf("summary"), result.messageNodes.map { it.currentMessage.toText() })
    }
}
