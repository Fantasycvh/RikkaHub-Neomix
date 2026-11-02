package app.androidrik.rikkahub.data.task

import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import app.androidrik.workspace.WorkspaceTaskSnapshot
import app.androidrik.workspace.WorkspaceTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务中心读模型的**纯映射**单测（P0）。
 *
 * 这三条映射是 UI 的唯一事实来源：状态、能否取消、待批数。放在这里而不是 Compose 测试，
 * 是因为它们不依赖 Android，且最容易被后续重构「顺手」改坏（例如把 INTERRUPTED 并入 FAILED、
 * 或对已结束的 run 仍给取消按钮）。
 */
class TaskCenterTest {

    private fun agentRun(
        id: String = "run-1",
        profileName: String = "研究员",
        goal: String = "调研 X",
        status: String = AgentRunEntity.STATUS_RUNNING,
        error: String? = null,
        createdAt: Long = 100L,
        startedAt: Long? = 120L,
        finishedAt: Long? = null,
    ) = AgentRunEntity(
        id = id,
        entryPoint = AgentRunEntity.ENTRY_POINT_SUBAGENT,
        status = status,
        profileAssistantId = "assistant-1",
        profileName = profileName,
        modelId = "model-1",
        modelName = "Model",
        goal = goal,
        toolEnvelope = "[]",
        transcript = "[]",
        resultSummary = null,
        error = error,
        createdAt = createdAt,
        startedAt = startedAt,
        finishedAt = finishedAt,
    )

    private fun shellTask(
        id: String = "abcd1234",
        command: String = "make -j4\nsecond line",
        cwd: String = "/workspace",
        status: WorkspaceTaskStatus = WorkspaceTaskStatus.RUNNING,
        exitCode: Int? = null,
        startedAt: Long = 200L,
        finishedAt: Long? = null,
    ) = WorkspaceTaskSnapshot(
        id = id,
        root = "demo",
        command = command,
        cwd = cwd,
        status = status,
        exitCode = exitCode,
        stdout = "",
        stderr = "",
        stdoutTotalChars = 0,
        stderrTotalChars = 0,
        outputTruncated = false,
        startedAtMillis = startedAt,
        finishedAtMillis = finishedAt,
    )

    private fun scheduledTask(
        id: String = "task-1",
        title: String = "每日总结",
        lastStatus: String? = null,
        lastRunAt: Long? = null,
    ) = ScheduledTaskEntity(
        id = id,
        conversationId = "conv-1",
        title = title,
        prompt = "总结今天",
        scheduleType = "DAILY",
        schedulePayload = "{}",
        nextRunAt = 999L,
        lastRunAt = lastRunAt,
        lastStatus = lastStatus,
        missPolicy = "RUN_LATE",
        idleOnly = false,
        enabled = true,
        createdAt = 1L,
    )

    @Test
    fun `agent card derives state, cancel affordance and approval count`() {
        val running = agentTaskCard(agentRun(), pendingApprovals = 2)
        assertEquals(TaskState.RUNNING, running.state)
        assertTrue(running.canCancel)
        assertEquals(2, running.pendingApprovalCount)
        assertEquals(TaskKey.Agent("run-1"), running.key)
        assertEquals("agent:run-1", running.key.wire)
        assertEquals(120L, running.startedAt)

        val awaiting = agentTaskCard(
            agentRun(status = AgentRunEntity.STATUS_AWAITING_APPROVAL),
            pendingApprovals = 1,
        )
        assertEquals(TaskState.WAITING_APPROVAL, awaiting.state)
        assertTrue("等批也要能给取消（用户可能不想等了）", awaiting.canCancel)
    }

    @Test
    fun `terminal agent card is not cancellable and timeout is not folded into failed`() {
        val timedOut = agentTaskCard(
            agentRun(
                status = AgentRunEntity.STATUS_FAILED,
                // 历史数据可能是旧前缀 `exception: run timeout`，P0.5 起新数据是 `timeout:`
                error = "${AgentRunEntity.ERROR_TIMEOUT}: run timeout (1800000ms)",
                finishedAt = 500L,
            ),
            pendingApprovals = 0,
        )
        assertEquals(TaskState.TIMED_OUT, timedOut.state)
        assertFalse(timedOut.canCancel)
        assertEquals(500L, timedOut.finishedAt)

        val interrupted = agentTaskCard(
            agentRun(status = AgentRunEntity.STATUS_INTERRUPTED, finishedAt = 600L),
            pendingApprovals = 0,
        )
        assertEquals(TaskState.INTERRUPTED, interrupted.state)
        assertFalse(interrupted.canCancel)
    }

    @Test
    fun `shell card maps exit code and only running task is cancellable`() {
        val running = shellTaskCard(shellTask())
        assertEquals(TaskState.RUNNING, running.state)
        assertTrue(running.canCancel)
        assertEquals("make -j4", running.title)
        assertEquals("shell:demo:abcd1234", running.key.wire)

        val ok = shellTaskCard(
            shellTask(status = WorkspaceTaskStatus.EXITED, exitCode = 0, finishedAt = 300L),
        )
        assertEquals(TaskState.COMPLETED, ok.state)
        assertFalse(ok.canCancel)

        val failed = shellTaskCard(
            shellTask(status = WorkspaceTaskStatus.EXITED, exitCode = 3, finishedAt = 300L),
        )
        assertEquals("退出码非 0 不得显示成功", TaskState.FAILED, failed.state)

        val killed = shellTaskCard(shellTask(status = WorkspaceTaskStatus.KILLED, finishedAt = 300L))
        assertEquals(TaskState.CANCELLED, killed.state)
        assertFalse(killed.canCancel)
    }

    @Test
    fun `scheduled card prefers active run over last status`() {
        val active = scheduledTaskCard(scheduledTask(lastStatus = TaskRunStatus.OK), "run-9")
        assertEquals(TaskState.RUNNING, active.state)
        assertTrue(active.canCancel)
        assertEquals(TaskKey.Scheduled("task-1", "run-9"), active.key)
        assertEquals("sched:task-1:run-9", active.key.wire)
        assertNull("运行中不显示旧状态说明", active.subtitle)

        val idleOk = scheduledTaskCard(scheduledTask(lastStatus = TaskRunStatus.OK), null)
        assertEquals(TaskState.COMPLETED, idleOk.state)
        assertFalse(idleOk.canCancel)

        val neverRun = scheduledTaskCard(scheduledTask(lastStatus = null), null)
        assertEquals(TaskState.PENDING, neverRun.state)

        val cancelled = scheduledTaskCard(
            scheduledTask(lastStatus = TaskRunStatus.CANCELLED),
            null,
        )
        assertEquals(TaskState.CANCELLED, cancelled.state)
    }
}
