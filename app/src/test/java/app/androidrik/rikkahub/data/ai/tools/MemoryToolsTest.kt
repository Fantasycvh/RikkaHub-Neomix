package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `buildMemoryTools` 的**接线**测试：验证 `needsApproval` 回调能从 `input` JSON 里正确取出
 * `action` 并交给判定函数。
 *
 * 审批**判定规则本身**（create 默认放行 / 可选拦截、edit-delete 默认拦截、未知 action 兜底）
 * 见 [MemoryToolApprovalTest]，那里测的是生产函数 [memoryToolNeedsApproval]——
 * 本类只负责"JSON → action → 判定"这条链路，不再复刻判定逻辑。
 */
class MemoryToolsTest {

    private val json = Json

    /** 记录回调实际收到的 action，用于验证提取路径。 */
    private val seenActions = mutableListOf<String>()

    private fun memoryTool(
        modifyApproval: Boolean = true,
        createApproval: Boolean = false,
    ) = buildMemoryTools(
        json = json,
        onCreation = { AssistantMemory("00000001", it) },
        onUpdate = { id, content -> AssistantMemory(id, content) },
        onDelete = { },
        needsApproval = { action ->
            seenActions += action
            memoryToolNeedsApproval(
                Assistant(
                    memoryModifyApproval = modifyApproval,
                    memoryCreateApproval = createApproval,
                ),
                action,
            )
        },
    ).first { it.name == "memory_tool" }

    private fun input(action: String) = buildJsonObject { put("action", action) }

    @Test
    fun `action is extracted from input json`() {
        val tool = memoryTool()
        tool.needsApproval(input("edit"))
        tool.needsApproval(input("delete"))
        tool.needsApproval(input("create"))
        assertEquals(listOf("edit", "delete", "create"), seenActions)
    }

    @Test
    fun `missing action yields empty string not a crash`() {
        val tool = memoryTool()
        // 缺 action 不应抛（回调收到空串，由判定函数按兜底处理）
        tool.needsApproval(buildJsonObject { })
        assertEquals(listOf(""), seenActions)
    }

    @Test
    fun `default config gates edit and delete but allows create`() {
        val tool = memoryTool()
        assertTrue(tool.needsApproval(input("edit")))
        assertTrue(tool.needsApproval(input("delete")))
        assertFalse(tool.needsApproval(input("create")))
    }

    @Test
    fun `create is gated end-to-end when create approval enabled`() {
        val tool = memoryTool(createApproval = true)
        assertTrue(tool.needsApproval(input("create")))
    }

    @Test
    fun `tool name and description integrity`() {
        val tool = memoryTool()
        assertEquals("memory_tool", tool.name)
        assertTrue(tool.description.isNotBlank())
    }
}
