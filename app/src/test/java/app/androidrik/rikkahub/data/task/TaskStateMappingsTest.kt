package app.androidrik.rikkahub.data.task

import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import app.androidrik.workspace.WorkspaceTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一任务态映射（设计 `design-agent-task-runtime.md` §4.3）。
 *
 * 钉住四类：①三套状态逐项映射；②未知/脏值**不得**变成 COMPLETED（保守 FAILED）；
 * ③`EXITED` 必须看 exitCode；④终态判定（任务中心据此显示取消按钮）。
 */
class TaskStateMappingsTest {

    @Test
    fun `agent run statuses map to unified states`() {
        val cases = mapOf(
            AgentRunEntity.STATUS_PENDING to TaskState.PENDING,
            AgentRunEntity.STATUS_RUNNING to TaskState.RUNNING,
            AgentRunEntity.STATUS_AWAITING_APPROVAL to TaskState.WAITING_APPROVAL,
            AgentRunEntity.STATUS_COMPLETED to TaskState.COMPLETED,
            AgentRunEntity.STATUS_FAILED to TaskState.FAILED,
            AgentRunEntity.STATUS_CANCELLED to TaskState.CANCELLED,
            AgentRunEntity.STATUS_INTERRUPTED to TaskState.INTERRUPTED,
        )
        for ((raw, expected) in cases) {
            assertEquals("agent_run.status=$raw", expected, TaskStateMappings.fromAgentRun(raw))
        }
    }

    @Test
    fun `agent run timeout is restored from error prefix`() {
        assertEquals(
            TaskState.TIMED_OUT,
            TaskStateMappings.fromAgentRun(
                AgentRunEntity.STATUS_FAILED,
                error = AgentRunEntity.ERROR_TIMEOUT + ": run timeout (1800000ms)",
            ),
        )
        // 前缀必须完整匹配：其他 error 里含 timeout 字样不能误判成超时。
        assertEquals(
            TaskState.FAILED,
            TaskStateMappings.fromAgentRun(
                AgentRunEntity.STATUS_FAILED,
                error = AgentRunEntity.ERROR_EXCEPTION + ": connection timeout",
            ),
        )
    }

    @Test
    fun `unknown agent status is failed not completed`() {
        assertEquals(TaskState.FAILED, TaskStateMappings.fromAgentRun("something-new"))
        // 旧版本/回滚写入的脏值同样保守处理。
        assertEquals(TaskState.FAILED, TaskStateMappings.fromAgentRun(""))
    }

    @Test
    fun `workspace status uses exit code only for EXITED`() {
        assertEquals(
            TaskState.RUNNING,
            TaskStateMappings.fromWorkspace(WorkspaceTaskStatus.RUNNING),
        )
        assertEquals(
            TaskState.COMPLETED,
            TaskStateMappings.fromWorkspace(WorkspaceTaskStatus.EXITED, exitCode = 0),
        )
        assertEquals(
            TaskState.FAILED,
            TaskStateMappings.fromWorkspace(WorkspaceTaskStatus.EXITED, exitCode = 1),
        )
        // 已退出但没拿到退出码：不能猜成功。
        assertEquals(
            TaskState.FAILED,
            TaskStateMappings.fromWorkspace(WorkspaceTaskStatus.EXITED, exitCode = null),
        )
        assertEquals(
            TaskState.CANCELLED,
            TaskStateMappings.fromWorkspace(WorkspaceTaskStatus.KILLED),
        )
        assertEquals(
            TaskState.TIMED_OUT,
            TaskStateMappings.fromWorkspace(WorkspaceTaskStatus.TIMED_OUT),
        )
        assertEquals(
            TaskState.FAILED,
            TaskStateMappings.fromWorkspace(WorkspaceTaskStatus.FAILED),
        )
    }

    @Test
    fun `scheduled run statuses map and unfinished returns null`() {
        assertNull(TaskStateMappings.fromScheduledRun(null))
        assertNull(TaskStateMappings.fromScheduledRun("  "))

        assertEquals(TaskState.COMPLETED, TaskStateMappings.fromScheduledRun(TaskRunStatus.OK))
        assertEquals(TaskState.FAILED, TaskStateMappings.fromScheduledRun(TaskRunStatus.FAILED))
        assertEquals(TaskState.TIMED_OUT, TaskStateMappings.fromScheduledRun(TaskRunStatus.TIMEOUT))
        assertEquals(
            TaskState.CANCELLED,
            TaskStateMappings.fromScheduledRun(TaskRunStatus.SKIPPED_MISSED),
        )
        assertEquals(
            TaskState.FAILED,
            TaskStateMappings.fromScheduledRun(TaskRunStatus.TARGET_GONE),
        )
        assertEquals(
            TaskState.FAILED,
            TaskStateMappings.fromScheduledRun(TaskRunStatus.ASSISTANT_GONE),
        )
        assertEquals(TaskState.CANCELLED, TaskStateMappings.fromScheduledRun("CANCELLED"))
        assertEquals(TaskState.INTERRUPTED, TaskStateMappings.fromScheduledRun("INTERRUPTED"))
        assertEquals(TaskState.FAILED, TaskStateMappings.fromScheduledRun("WHATEVER"))
    }

    @Test
    fun `terminal states exclude pending running and waiting`() {
        assertFalse(TaskState.PENDING.isTerminal)
        assertFalse(TaskState.RUNNING.isTerminal)
        assertFalse(TaskState.WAITING_APPROVAL.isTerminal)
        assertTrue(TaskState.COMPLETED.isTerminal)
        assertTrue(TaskState.FAILED.isTerminal)
        assertTrue(TaskState.CANCELLED.isTerminal)
        assertTrue(TaskState.TIMED_OUT.isTerminal)
        assertTrue(TaskState.INTERRUPTED.isTerminal)
    }
}
