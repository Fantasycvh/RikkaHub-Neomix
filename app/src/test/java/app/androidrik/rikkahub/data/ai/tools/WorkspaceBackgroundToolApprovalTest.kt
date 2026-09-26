package app.androidrik.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作空间工具审批判定（生产函数 [resolveWorkspaceToolApproval]：工作空间级 `tool_approvals`
 * 覆盖 > [WorkspaceToolDefaultApprovals] 默认值 > 未知工具 false）。
 *
 * 这里钉住后台任务三件套的分档，因为它们的默认值差异是**有意的**：
 * - `workspace_run_background` / `workspace_kill_task` 默认**需审批**（占用设备资源、破坏性终止，
 *   且模型看不到输出，人需要先知道它在跑什么）；
 * - `workspace_task_status` 默认**免审批**（只读；否则长任务轮询会变成审批轰炸）。
 */
class WorkspaceBackgroundToolApprovalTest {

    @Test
    fun `background run and kill require approval by default`() {
        assertTrue(resolveWorkspaceToolApproval("workspace_run_background", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_kill_task", emptyMap()))
    }

    @Test
    fun `task status is approval-free by default`() {
        assertFalse(resolveWorkspaceToolApproval("workspace_task_status", emptyMap()))
    }

    @Test
    fun `shell family defaults are unchanged`() {
        assertTrue(resolveWorkspaceToolApproval("workspace_shell", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_read_file", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_write_file", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_edit_file", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_present_file", emptyMap()))
    }

    @Test
    fun `workspace overrides win over defaults in both directions`() {
        // 用户在工作空间设置里关掉后台运行审批 ⇒ 不再询问（默认值只是默认）
        assertFalse(
            resolveWorkspaceToolApproval(
                "workspace_run_background",
                mapOf("workspace_run_background" to false),
            )
        )
        // 反过来：给只读的状态查询开启审批也要生效
        assertTrue(
            resolveWorkspaceToolApproval(
                "workspace_task_status",
                mapOf("workspace_task_status" to true),
            )
        )
    }

    @Test
    fun `unknown tools default to no approval`() {
        assertFalse(resolveWorkspaceToolApproval("workspace_unknown_tool", emptyMap()))
        assertEquals(
            WorkspaceToolDefaultApprovals.size,
            WorkspaceToolDefaultApprovals.keys.distinct().size,
        )
    }

    @Test
    fun `every default approval entry is a declared workspace tool`() {
        // 防止改名后留下「永远不会被查询」的孤儿默认值（默认值失效是静默故障）
        val declared = setOf(
            "workspace_read_file",
            "workspace_write_file",
            "workspace_edit_file",
            "workspace_present_file",
            "workspace_shell",
            "workspace_run_background",
            "workspace_task_status",
            "workspace_kill_task",
        )
        assertEquals(declared, WorkspaceToolDefaultApprovals.keys)
    }
}
