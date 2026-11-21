package app.androidrik.rikkahub.service

import app.androidrik.mediagen.model.MediaGenerationTask
import app.androidrik.mediagen.provider.providers.MediaGenerationApiException
import app.androidrik.rikkahub.data.model.MediaCreationStatus

/**
 * 进程重启后，一条进行中的媒体记录（[MediaCreationService.resumePending] 没能立即接管）该怎么处置。
 */
internal enum class MediaInterruptionOutcome {
    /** 已有服务端任务 ID，注册任务接着查询。 */
    RESUME_QUERY,

    /**
     * 请求可能已到达服务端但本地没有可续查的凭据，标记为提交结果待确认，
     * 由用户决定是否重新提交（可能重复计费）。
     */
    SUBMIT_UNKNOWN,

    /** 还没有提交给接口，直接标记为中断失败，重新提交是安全的。 */
    MARK_FAILED,
}

/**
 * 一次提交的窗口分析：`create()` 在发请求**之前**把记录置为 RUNNING（提交前状态已持久化），
 * 服务端返回后立刻用 NonCancellable 写任务 ID。因此：
 *
 * - 死时带任务 ID → 本地写入已完成，远端结果可续查（结果下载被中断也一样，续查后重新下载）；
 * - 死时 RUNNING 且无任务 ID → 请求已发出、任务 ID 还没落库（或同步流式接口正在接收结果），
 *   服务端可能已经计费但无法对账 → 待确认；
 * - 其余状态（PREPARING 等提交前）→ 请求还没发出，中断是安全的。
 */
internal fun resolveMediaInterruption(taskId: String?, status: MediaCreationStatus): MediaInterruptionOutcome = when {
    taskId != null -> MediaInterruptionOutcome.RESUME_QUERY
    status == MediaCreationStatus.RUNNING -> MediaInterruptionOutcome.SUBMIT_UNKNOWN
    else -> MediaInterruptionOutcome.MARK_FAILED
}

/**
 * 启动期按请求 ID 对账（`SUBMIT_UNKNOWN` 记录）的处置。对账是只读查询：任何分支都不会重新提交。
 */
internal sealed interface MediaReconciliationDisposition {
    /** 服务端有这个任务：写回任务 ID 转回可续查状态，注册任务接着跑。 */
    data class Resume(val taskId: String) : MediaReconciliationDisposition

    /**
     * 服务端确认没有这个任务：请求没送达或没被受理，重新提交是安全的。维持待确认状态
     * 交由用户处置，不自动重新提交。
     */
    data object ConfirmAbsent : MediaReconciliationDisposition

    /** 暂时查不了（网络、限流、接口异常）：保持原状，下次启动再对账，不升级成失败。 */
    data object RetryLater : MediaReconciliationDisposition
}

/**
 * 对账查询结果的分类。404（接口明确拒绝且任务不存在）是唯一「确认没收到」的口径；
 * 其余失败一律按「暂时查不了」处理，避免把网络抖动误判成安全重试。
 */
internal fun classifyReconciliation(result: Result<MediaGenerationTask>): MediaReconciliationDisposition =
    result.fold(
        onSuccess = { task -> MediaReconciliationDisposition.Resume(task.id) },
        onFailure = { error ->
            if (error is MediaGenerationApiException && error.statusCode == 404) {
                MediaReconciliationDisposition.ConfirmAbsent
            } else {
                MediaReconciliationDisposition.RetryLater
            }
        },
    )
