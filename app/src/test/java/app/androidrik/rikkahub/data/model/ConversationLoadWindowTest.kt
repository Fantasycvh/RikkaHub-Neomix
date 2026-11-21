package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

@Suppress("DEPRECATION")

class ConversationLoadWindowTest {

    private fun summary(
        id: Uuid,
        parentId: Uuid?,
        index: Int,
        bytes: Long = 100,
    ) = NodeSummary(id = id, parentId = parentId, nodeIndex = index, messageBytes = bytes)

    /** 线性链 root→a→b→c→d，返回摘要与 ids。 */
    private fun linearChain(count: Int = 5, bytes: Long = 100): Pair<List<NodeSummary>, List<Uuid>> {
        val ids = List(count) { Uuid.random() }
        val summaries = ids.mapIndexed { index, id ->
            summary(id = id, parentId = ids.getOrNull(index - 1), index = index, bytes = bytes)
        }
        return summaries to ids
    }

    @Test
    fun `empty conversation yields empty window`() {
        val window = buildConversationLoadWindow(emptyList(), null)
        assertEquals(emptyList<Uuid>(), window.pathIds)
        assertEquals(emptyList<Uuid>(), window.loadedIds)
        assertFalse(window.hasOlder)
    }

    @Test
    fun `null leaf loads the newest tail within node budget`() {
        val (summaries, ids) = linearChain()
        val window = buildConversationLoadWindow(summaries, activeLeafNodeId = null, maxNodes = 3)
        assertEquals(ids, window.pathIds)
        assertEquals(ids.takeLast(3), window.loadedIds)
        assertEquals(2, window.omittedCount)
        assertTrue(window.hasOlder)
    }

    @Test
    fun `branch only loads the active leaf ancestry`() {
        val root = Uuid.random()
        val a1 = Uuid.random()
        val a2 = Uuid.random()
        val b1 = Uuid.random()
        val summaries = listOf(
            summary(root, null, 0),
            summary(a1, root, 1),
            summary(a2, a1, 2),
            summary(b1, root, 3),
        )
        val window = buildConversationLoadWindow(summaries, activeLeafNodeId = b1)
        assertEquals(listOf(root, b1), window.pathIds)
        assertEquals(listOf(root, b1), window.loadedIds)
    }

    @Test
    fun `compression boundary excludes the summarized prefix`() {
        val (summaries, ids) = linearChain()
        val window = buildConversationLoadWindow(
            summaries = summaries,
            activeLeafNodeId = ids.last(),
            boundaryNodeId = ids[2],
        )
        assertEquals(ids, window.pathIds)
        assertEquals(ids.takeLast(2), window.loadedIds)
        assertEquals(3, window.omittedCount)
    }

    @Test
    fun `byte budget stops before exceeding the limit`() {
        val (summaries, ids) = linearChain(bytes = 400)
        val window = buildConversationLoadWindow(
            summaries = summaries,
            activeLeafNodeId = ids.last(),
            maxBytes = 1_000,
        )
        assertEquals(ids.takeLast(2), window.loadedIds)
    }

    @Test
    fun `always keeps the last node even when it alone exceeds the budget`() {
        val (summaries, ids) = linearChain(count = 2, bytes = 2_000)
        val window = buildConversationLoadWindow(
            summaries = summaries,
            activeLeafNodeId = ids.last(),
            maxBytes = 100,
        )
        assertEquals(listOf(ids.last()), window.loadedIds)
    }

    @Test
    fun `broken parent chain falls back to creation order`() {
        val ids = List(3) { Uuid.random() }
        val summaries = listOf(
            summary(ids[0], null, 0),
            summary(ids[1], Uuid.random(), 1), // 父不在摘要里
            summary(ids[2], ids[1], 2),
        )
        val window = buildConversationLoadWindow(summaries, activeLeafNodeId = ids[2], maxNodes = 2)
        assertEquals(ids, window.pathIds)
        assertEquals(ids.takeLast(2), window.loadedIds)
    }

    @Test
    fun `cycle falls back instead of looping forever`() {
        val a = Uuid.random()
        val b = Uuid.random()
        val summaries = listOf(
            summary(a, b, 0),
            summary(b, a, 1),
        )
        val window = buildConversationLoadWindow(summaries, activeLeafNodeId = b, maxNodes = 1)
        assertEquals(2, window.pathIds.size)
        assertEquals(listOf(b), window.loadedIds)
    }
}

// ==================== 守卫六：删除集/保留集间的遗留工具配对 ====================

private fun toolNode(vararg parts: UIMessagePart) = MessageNode(
    messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()))
)

private fun legacyCall(id: String) = UIMessagePart.ToolCall(toolCallId = id, toolName = "t", arguments = "{}")

private fun legacyResult(id: String) = UIMessagePart.ToolResult(
    toolCallId = id, toolName = "t", content = JsonPrimitive("ok"), arguments = JsonPrimitive("{}"),
)

private fun pendingTool(id: String) = UIMessagePart.Tool(
    toolCallId = id, toolName = "t", input = "{}", output = emptyList(),
)

private fun executedTool(id: String) = UIMessagePart.Tool(
    toolCallId = id, toolName = "t", input = "{}", output = listOf(UIMessagePart.Text("result")),
)

class PruneLegacyPairGuardTest {

    @Test
    fun `kept legacy result whose call is deleted is a conflict`() {
        val callNode = toolNode(legacyCall("r1"))
        val keptResultNode = toolNode(legacyResult("r1"))
        val deletedIds = setOf(callNode.id)
        assertTrue(listOf(callNode, keptResultNode).hasLegacyToolPairAcrossPruneBoundary(deletedIds))
    }

    @Test
    fun `kept pending call whose result is deleted is a conflict`() {
        val resultNode = toolNode(legacyResult("r1"))
        val keptCallNode = toolNode(pendingTool("r1"))
        val deletedIds = setOf(resultNode.id)
        assertTrue(listOf(resultNode, keptCallNode).hasLegacyToolPairAcrossPruneBoundary(deletedIds))
    }

    @Test
    fun `pair fully inside the deletion set or the kept set is fine`() {
        val callNode = toolNode(legacyCall("r1"))
        val resultNode = toolNode(legacyResult("r1"))
        val keptNode = toolNode(pendingTool("r2"))
        // 两端同删：配对整体进摘要区域
        assertFalse(
            listOf(callNode, resultNode, keptNode)
                .hasLegacyToolPairAcrossPruneBoundary(setOf(callNode.id, resultNode.id))
        )
        // 两端同留
        assertFalse(
            listOf(callNode, resultNode, keptNode).hasLegacyToolPairAcrossPruneBoundary(setOf(keptNode.id))
        )
    }

    @Test
    fun `self contained executed tool adds no cross node reference`() {
        val deletedNode = toolNode(executedTool("r1"))
        val keptNode = toolNode(executedTool("r2"))
        assertFalse(
            listOf(deletedNode, keptNode).hasLegacyToolPairAcrossPruneBoundary(setOf(deletedNode.id))
        )
    }

    @Test
    fun `non selected candidates of kept nodes are also scanned`() {
        // 保留节点的非选中候选持有 result，其 call 在删除节点 ⇒ 切分支后仍会踩空
        val callNode = toolNode(legacyCall("r1"))
        val keptNode = MessageNode(
            messages = listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("clean"))),
                UIMessage(role = MessageRole.USER, parts = listOf(legacyResult("r1"))),
            ),
        )
        assertTrue(listOf(callNode, keptNode).hasLegacyToolPairAcrossPruneBoundary(setOf(callNode.id)))
    }

    @Test
    fun `empty deletion set or empty nodes never conflict`() {
        val node = toolNode(legacyCall("r1"))
        assertFalse(listOf(node).hasLegacyToolPairAcrossPruneBoundary(emptySet()))
        assertFalse(emptyList<MessageNode>().hasLegacyToolPairAcrossPruneBoundary(setOf(Uuid.random())))
    }
}
