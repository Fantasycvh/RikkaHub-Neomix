package app.androidrik.rikkahub.service

import app.androidrik.rikkahub.data.model.MediaCreationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 进程中断处置的窗口分析：设计稿「统一运行记录与恢复语义」的验收点——
 * 进程在远端响应、本地写入和结果下载之间退出时，各自落到正确的处置。
 */
class MediaInterruptionTest {
    private fun outcome(taskId: String?, status: MediaCreationStatus) =
        resolveMediaInterruption(taskId, status)

    @Test
    fun `task id written before death resumes the query`() {
        assertEquals(MediaInterruptionOutcome.RESUME_QUERY, outcome("req-1", MediaCreationStatus.QUEUED))
        assertEquals(MediaInterruptionOutcome.RESUME_QUERY, outcome("req-1", MediaCreationStatus.RUNNING))
        assertEquals(MediaInterruptionOutcome.RESUME_QUERY, outcome("req-1", MediaCreationStatus.DOWNLOADING))
    }

    @Test
    fun `death in the submission window waits for confirmation`() {
        // create() 先置 RUNNING 再发请求，任务 ID 在响应后用 NonCancellable 补写：
        // RUNNING 且无任务 ID = 请求已发出但凭据没落库（或同步流正在接收结果）
        assertEquals(MediaInterruptionOutcome.SUBMIT_UNKNOWN, outcome(null, MediaCreationStatus.RUNNING))
    }

    @Test
    fun `death before submission fails safely`() {
        assertEquals(MediaInterruptionOutcome.MARK_FAILED, outcome(null, MediaCreationStatus.PREPARING))
        assertEquals(MediaInterruptionOutcome.MARK_FAILED, outcome(null, MediaCreationStatus.QUEUED))
        assertEquals(MediaInterruptionOutcome.MARK_FAILED, outcome(null, MediaCreationStatus.DOWNLOADING))
    }

    @Test
    fun `already settled records never change their outcome`() {
        // 终态记录不该出现在恢复流程里；万一出现，按安全口径处理而不是复活它
        assertEquals(MediaInterruptionOutcome.MARK_FAILED, outcome(null, MediaCreationStatus.SUCCEEDED))
        assertEquals(MediaInterruptionOutcome.MARK_FAILED, outcome(null, MediaCreationStatus.FAILED))
        assertEquals(MediaInterruptionOutcome.MARK_FAILED, outcome(null, MediaCreationStatus.CANCELLED))
    }
}
