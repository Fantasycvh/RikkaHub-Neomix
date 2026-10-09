package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.rikkahub.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆工具审批判定（生产函数 [memoryToolNeedsApproval]，按 action 分档）。
 *
 * 规则：`create` 由 [Assistant.memoryCreateApproval] 决定（**默认 false** = 直接记录）；
 * `edit`/`delete` 由 [Assistant.memoryModifyApproval] 决定（默认 true = 需确认）；
 * 缺失/未知 action 按 edit/delete 同口径（宁可多拦，不可被当成 create 蒙混）。
 */
class MemoryToolApprovalTest {

    private fun assistant(
        modifyApproval: Boolean = true,
        createApproval: Boolean = false,
    ) = Assistant(memoryModifyApproval = modifyApproval, memoryCreateApproval = createApproval)

    // ---- create（新开关，默认关） ----

    @Test
    fun `create is allowed by default`() {
        // 默认值：新增记忆直接记录，不打断（保持既有行为）
        assertFalse(memoryToolNeedsApproval(assistant(), "create"))
    }

    @Test
    fun `create requires approval when create approval enabled`() {
        assertTrue(memoryToolNeedsApproval(assistant(createApproval = true), "create"))
    }

    @Test
    fun `create approval is independent of modify approval`() {
        // 只开修改审批时新增仍放行；只开创建审批时新增被拦
        assertFalse(memoryToolNeedsApproval(assistant(modifyApproval = true, createApproval = false), "create"))
        assertTrue(memoryToolNeedsApproval(assistant(modifyApproval = false, createApproval = true), "create"))
    }

    // ---- read（V2 渐进披露：只读，恒不拦） ----

    @Test
    fun `read never requires approval, even with every switch on`() {
        // 只读没有可被否决的副作用（与 workspace_task_status 免审批同款）；
        // 若落进 else 兜底，渐进披露的每次按需读取都会变成一次审批轰炸
        assertFalse(memoryToolNeedsApproval(assistant(modifyApproval = true, createApproval = true), "read"))
        assertFalse(memoryToolNeedsApproval(assistant(modifyApproval = false, createApproval = false), "read"))
    }

    // ---- edit / delete（既有开关，默认开） ----

    @Test
    fun `edit and delete require approval by default`() {
        assertTrue(memoryToolNeedsApproval(assistant(), "edit"))
        assertTrue(memoryToolNeedsApproval(assistant(), "delete"))
    }

    @Test
    fun `edit and delete are auto when modify approval disabled`() {
        val a = assistant(modifyApproval = false)
        assertFalse(memoryToolNeedsApproval(a, "edit"))
        assertFalse(memoryToolNeedsApproval(a, "delete"))
    }

    @Test
    fun `create approval does not affect edit or delete`() {
        val a = assistant(modifyApproval = false, createApproval = true)
        assertFalse(memoryToolNeedsApproval(a, "edit"))
        assertFalse(memoryToolNeedsApproval(a, "delete"))
    }

    // ---- 兜底 ----

    @Test
    fun `missing or unknown action falls back to requiring approval`() {
        // 不得被当作 create 放行（未知 action 不该绕过审批）
        val on = assistant(modifyApproval = true)
        assertTrue(memoryToolNeedsApproval(on, ""))
        assertTrue(memoryToolNeedsApproval(on, "unknown"))
        // 与 edit/delete 同口径：修改审批关时未知 action 也放行（由执行体自身报未知 action 错）
        val off = assistant(modifyApproval = false)
        assertFalse(memoryToolNeedsApproval(off, "unknown"))
    }
}
