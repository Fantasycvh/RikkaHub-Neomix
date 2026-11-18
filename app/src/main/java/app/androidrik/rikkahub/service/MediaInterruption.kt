package app.androidrik.rikkahub.service

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
