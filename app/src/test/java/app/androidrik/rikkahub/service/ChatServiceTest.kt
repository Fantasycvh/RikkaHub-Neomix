package app.androidrik.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.CustomBody
import app.androidrik.ai.provider.CustomHeader
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.model.Assistant
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
        assertEquals("", fork.title)
        assertFalse(fork.isPinned)
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

    // 外挂搜索注册口径（上游 aac6e963 / #1666）：模型自带内置搜索时不再叠加外部搜索工具

    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }
}
