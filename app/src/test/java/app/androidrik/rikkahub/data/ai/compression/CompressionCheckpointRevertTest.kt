package app.androidrik.rikkahub.data.ai.compression

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * 压缩分界线可撤回（「删线即还原」，NMoi D324–D327）：
 *
 * 1. 模型层语义：从 `compressionSummaries` 移除检查点后，`activeCompression()` 自动失效或回退到
 *    更早的有效检查点，`windowNodes()` 与 `requestWindowMessages()` 立即恢复完整原文历史；
 * 2. 接线守卫：DAO / Store / Repo / ChatService / ChatVM / CompactionCheckpointRow /
 *    CompressionSummarySheet / ChatList / ChatPage 全链路贯通。
 */
class CompressionCheckpointRevertTest {

    private fun read(relative: String): String {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
        val file = root.resolve(relative)
        assertTrue("源文件不存在：${file.path}", file.isFile)
        return file.readText()
    }

    private fun chainConversation(count: Int): Conversation {
        val convId = Uuid.random()
        var parentId: Uuid? = null
        val nodes = (0 until count).map { i ->
            val node = MessageNode(
                parentId = parentId,
                messages = listOf(
                    UIMessage(
                        role = if (i % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("msg-$i")),
                    )
                ),
            )
            parentId = node.id
            node
        }
        return Conversation(
            id = convId,
            assistantId = Uuid.random(),
            messageNodes = nodes,
            activeLeafNodeId = nodes.last().id,
        )
    }

    @Test
    fun `removing the active checkpoint restores the full message window`() {
        val base = chainConversation(6)
        val boundaryIndex = 3
        val checkpoint = CompressionSummary(
            id = 42L,
            conversationId = base.id,
            content = "summary of first 4 messages",
            messageCount = 4,
            boundaryNodeId = base.messageNodes[boundaryIndex].id.toString(),
            areaFingerprint = base.areaFingerprintFor(boundaryIndex),
        )
        val compressed = base.copy(compressionSummaries = listOf(checkpoint))
        assertEquals(checkpoint, compressed.activeCompression())
        // 压缩态窗口仅含边界后的 2 条消息（+1 条合成检查点消息）
        assertEquals(2, compressed.windowNodes().size)
        assertEquals(3, compressed.requestWindowMessages().size)

        // 删线即还原：移除检查点行后，原始 6 条消息全部回到窗口
        val reverted = compressed.copy(
            compressionSummaries = compressed.compressionSummaries.filterNot { it.id == checkpoint.id }
        )
        assertNull(reverted.activeCompression())
        assertEquals(6, reverted.windowNodes().size)
        assertEquals(6, reverted.requestWindowMessages().size)
        assertEquals(
            (0..5).map { "msg-$it" },
            reverted.requestWindowMessages().map { it.toText() },
        )
    }

    @Test
    fun `removing the latest checkpoint falls back to an earlier valid checkpoint if present`() {
        val base = chainConversation(6)
        val older = CompressionSummary(
            id = 10L,
            conversationId = base.id,
            content = "older summary",
            messageCount = 2,
            boundaryNodeId = base.messageNodes[1].id.toString(),
            areaFingerprint = base.areaFingerprintFor(1),
        )
        val newer = CompressionSummary(
            id = 20L,
            conversationId = base.id,
            content = "newer summary",
            messageCount = 4,
            boundaryNodeId = base.messageNodes[3].id.toString(),
            areaFingerprint = base.areaFingerprintFor(3),
        )
        val stacked = base.copy(compressionSummaries = listOf(older, newer))
        assertEquals(20L, stacked.activeCompression()?.id)
        assertEquals(2, stacked.windowNodes().size)

        val reverted = stacked.copy(
            compressionSummaries = stacked.compressionSummaries.filterNot { it.id == 20L }
        )
        assertEquals(10L, reverted.activeCompression()?.id)
        assertEquals(4, reverted.windowNodes().size)
    }

    @Test
    fun `delete checkpoint flow is wired end-to-end across DAO, service, VM, and UI`() {
        assertTrue(
            "DAO 必须提供 deleteById",
            read("app/androidrik/rikkahub/data/db/dao/CompressionCheckpointDAO.kt").contains("suspend fun deleteById("),
        )
        val service = read("app/androidrik/rikkahub/service/ChatService.kt")
        assertTrue("ChatService 必须提供 deleteCompressionCheckpoint", service.contains("suspend fun deleteCompressionCheckpoint("))
        assertTrue("ChatService 撤回检查点必须走会话压缩互斥锁", service.contains("deleteCompressionCheckpoint("))
        val vm = read("app/androidrik/rikkahub/ui/pages/chat/ChatVM.kt")
        assertTrue("ChatVM 必须暴露 deleteCompressionSummary", vm.contains("fun deleteCompressionSummary("))
        val ui = read("app/androidrik/rikkahub/ui/components/message/CompactionCheckpoint.kt")
        assertTrue("CompactionCheckpointRow 必须暴露 onDeleteClick", ui.contains("onDeleteClick:"))
        assertTrue("CompressionSummarySheet 必须暴露 onDelete", ui.contains("onDelete:"))
        assertTrue("必须有二次确认弹窗", ui.contains("fun CompactionUndoConfirmDialog("))
        val chatPage = read("app/androidrik/rikkahub/ui/pages/chat/ChatPage.kt")
        assertTrue("ChatPage 必须接线 onDeleteCompressionSummary", chatPage.contains("onDeleteCompressionSummary ="))
    }
}
