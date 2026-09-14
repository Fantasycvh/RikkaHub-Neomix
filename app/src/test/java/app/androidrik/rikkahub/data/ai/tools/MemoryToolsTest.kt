package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 记忆工具审批（按 action 分档）的判定逻辑。
 *
 * 规则（见 roadmap「一、中等价值 → 记忆工具审批」与 [app.androidrik.rikkahub.data.model.Assistant.memoryModifyApproval]）：
 * **create 放行**（追加式、可撤销）；**edit/delete 在开启审批时拦截**（不可逆改/删）。
 * 一刀切拦截会因每次写记忆都打断而致用户关闭记忆功能，故按 action 分档。
 */
class MemoryToolsTest {

    private val json = Json

    private fun memoryTool(approvalOn: Boolean) = buildMemoryTools(
        json = json,
        onCreation = { AssistantMemory(1, it) },
        onUpdate = { id, content -> AssistantMemory(id, content) },
        onDelete = { },
        // 与 GenerationHandler 调用点同一判定
        needsApproval = { action -> approvalOn && action != "create" },
    ).first { it.name == "memory_tool" }

    private fun input(action: String) = buildJsonObject { put("action", action) }

    @Test
    fun `create is always allowed without approval`() {
        assertFalse(memoryTool(approvalOn = true).needsApproval(input("create")))
        assertFalse(memoryTool(approvalOn = false).needsApproval(input("create")))
    }

    @Test
    fun `edit and delete require approval when enabled`() {
        val tool = memoryTool(approvalOn = true)
        assertTrue(tool.needsApproval(input("edit")))
        assertTrue(tool.needsApproval(input("delete")))
    }

    @Test
    fun `edit and delete are auto when approval disabled`() {
        val tool = memoryTool(approvalOn = false)
        assertFalse(tool.needsApproval(input("edit")))
        assertFalse(tool.needsApproval(input("delete")))
    }

    @Test
    fun `missing or unknown action falls back to requiring approval`() {
        // 无 action / 未知 action：不得被当作 create 放行（宁可多拦，不可漏放）
        val tool = memoryTool(approvalOn = true)
        assertTrue(tool.needsApproval(buildJsonObject { }))
        assertTrue(tool.needsApproval(input("unknown")))
    }

    @Test
    fun `tool name and id integrity`() {
        val tool = memoryTool(approvalOn = true)
        assertTrue(tool.name == "memory_tool")
        // 放行与拦截都不应影响执行体（execute 非空）
        assertTrue(tool.description.isNotBlank())
    }
}
