package app.androidrik.rikkahub.ui.pages.taskcenter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.data.task.TaskCancelResult
import app.androidrik.rikkahub.data.task.TaskCard
import app.androidrik.rikkahub.data.task.TaskCenter
import app.androidrik.rikkahub.data.task.TaskKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * 任务中心（P0）：只读聚合 + 取消/批准转发（设计 `design-agent-task-runtime.md` §5.5）。
 *
 * 不在这里做准入判断（并发上限由各执行器裁决），也不缓存任务状态——`TaskCenter.observeTasks()`
 * 本身就是三源 combine，重复 stateIn 只会制造第二份真相。
 */
class TaskCenterVM(
    private val taskCenter: TaskCenter,
) : ViewModel() {

    val tasks = taskCenter.observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<TaskCard>())

    /** 取消并回传结果；UI 据此如实提示（不在跑 ≠ 已取消）。 */
    suspend fun cancel(key: TaskKey): TaskCancelResult = taskCenter.cancel(key)

    /** 批准/拒绝待批工具（与通知栏按钮同一条队列）；返回 false = 无人在等。 */
    fun resolveApproval(toolCallId: String, approved: Boolean): Boolean =
        taskCenter.resolveApproval(toolCallId, approved)

    /** 便捷入口：按 runId 打开详情页前，先确认它仍有待批工具（详情页自带审批弹窗）。 */
    fun hasPendingApproval(runId: String): Boolean =
        tasks.value.any { it.key is TaskKey.Agent && it.key.runId == runId && it.pendingApprovalCount > 0 }
}
