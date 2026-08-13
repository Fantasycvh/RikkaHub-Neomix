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
import org.junit.Test

class ChatServiceTest {
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
