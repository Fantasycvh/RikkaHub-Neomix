package app.androidrik.rikkahub.service

import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * run 的**进度注册表**（从 [SubagentRunner] 拆出）：活跃 Job 登记（取消用）+ 进度列表，
 * 两条派发入口与执行体共用。
 *
 * 进度列表是对话内工具卡的数据源——**活跃 run 的进度**（"跑到第几步"）。
 * 并行上限 2 之后不能再假设"活跃的就是我这一个"，所以给的是列表，卡片按
 * **预设名 + 任务目标**匹配自己那一条（两者都在卡片的入参里，无需额外接线）；
 * 匹配不到且只有一条时退回那一条，多于一条且匹配不到时只显示"并行运行中"的聚合态。
 */
internal class RunProgressTracker {
    /** runId → 它所在的协程 Job（取消用；两条入口都登记，这样才能"只取消这一个 run"）。 */
    private val activeJobs = ConcurrentHashMap<Uuid, Job>()

    private val _progress = MutableStateFlow<List<RunProgress>>(emptyList())

    val progress: StateFlow<List<RunProgress>> = _progress.asStateFlow()

    /** 登记 run 的 Job（派发时）。 */
    fun register(runId: Uuid, job: Job) {
        activeJobs[runId] = job
    }

    /** 注销 Job（run 结束/取消时，与并发闸 release 同点）。 */
    fun unregister(runId: Uuid) {
        activeJobs.remove(runId)
    }

    /** 只取消 Job（不注销；注销在完成回调里做）。 */
    fun cancelJob(runId: Uuid) {
        activeJobs[runId]?.cancel()
    }

    /** 该 run 进度条目的起始时间（已存在则沿用，避免每步重置计时）。 */
    fun startedAt(runId: String): Long =
        _progress.value.firstOrNull { it.runId == runId }?.startedAt ?: System.currentTimeMillis()

    /** 进度列表的写入助手：按 runId 覆盖（并行下不能整体赋值）。 */
    fun upsert(progress: RunProgress) {
        _progress.value = _progress.value.filterNot { it.runId == progress.runId } + progress
    }

    /** 进度只服务"跑的时候看一眼"，结束即清（订阅者据此回到结果态）。 */
    fun clear(runId: String) {
        _progress.value = _progress.value.filterNot { it.runId == runId }
    }

    /** 按 runId 原位更新单条进度（如状态切到 awaiting_approval）。 */
    fun update(runId: String, transform: (RunProgress) -> RunProgress) {
        _progress.value = _progress.value.map { entry ->
            if (entry.runId == runId) transform(entry) else entry
        }
    }
}
