package app.androidrik.rikkahub.service

import app.androidrik.mediagen.model.MediaGenerationTask
import app.androidrik.mediagen.model.MediaGenerationStatus
import app.androidrik.mediagen.provider.providers.MediaGenerationApiException
import app.androidrik.rikkahub.data.model.MediaCreationStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * 进程中断处置的窗口分析：设计稿「统一运行记录与恢复语义」的验收点——
 * 进程在远端响应、本地写入和结果下载之间退出时，各自落到正确的处置。
 *
 * 请求 ID 对账（classifyReconciliation）的口径也在这个文件：对账是只读查询，
 * 三种结果各自落到「转回可续查 / 维持待确认 / 下次启动再对账」，任何分支都不重新提交。
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
        assertEquals(MediaInterruptionOutcome.SUBMIT_UNKNOWN, outcome(null, MediaCreationStatus.DOWNLOADING))
    }

    @Test
    fun `already settled records never change their outcome`() {
        // 终态记录不该出现在恢复流程里；万一出现，按安全口径处理而不是复活它
        assertEquals(MediaInterruptionOutcome.MARK_FAILED, outcome(null, MediaCreationStatus.SUCCEEDED))
        assertEquals(MediaInterruptionOutcome.MARK_FAILED, outcome(null, MediaCreationStatus.FAILED))
        assertEquals(MediaInterruptionOutcome.MARK_FAILED, outcome(null, MediaCreationStatus.CANCELLED))
    }

    private fun apiError(statusCode: Int) = MediaGenerationApiException(
        provider = "siliconflow",
        statusCode = statusCode,
        code = "20012",
        message = "Task not found",
    )

    @Test
    fun `gateway errors and lost responses remain unknown but explicit rejection is safe`() {
        listOf(IOException("connection lost"), apiError(504), apiError(408)).forEach {
            assertEquals(MediaCreationStatus.SUBMIT_UNKNOWN, mediaFailureStatus(MediaCreationStatus.RUNNING, null, it))
        }
        assertEquals(MediaCreationStatus.FAILED, mediaFailureStatus(MediaCreationStatus.RUNNING, null, apiError(400)))
        assertEquals(MediaCreationStatus.FAILED, mediaFailureStatus(MediaCreationStatus.PREPARING, null, IOException()))
        assertEquals(MediaCreationStatus.FAILED, mediaFailureStatus(MediaCreationStatus.RUNNING, "remote-task", IOException()))
        assertEquals(MediaCreationStatus.SUBMIT_UNKNOWN, mediaFailureStatus(MediaCreationStatus.DOWNLOADING, null, IOException()))
    }

    @Test
    fun `unknown submissions remain eligible for read only reconciliation`() {
        assertEquals(MediaInterruptionOutcome.SUBMIT_UNKNOWN, outcome(null, MediaCreationStatus.SUBMIT_UNKNOWN))
    }

    private fun task(id: String = "req-1") =
        MediaGenerationTask(id = id, provider = "siliconflow", status = MediaGenerationStatus.RUNNING)

    @Test
    fun `reconciliation finding the task resumes the query`() {
        listOf(
            Result.success(task("req-9")),
            // 查到终态任务也一样：写回任务 ID 后走正常查询路径收尾（下载或落失败）
            Result.success(task("req-9").copy(status = MediaGenerationStatus.SUCCEEDED)),
        ).forEach { result ->
            assertEquals(
                MediaReconciliationDisposition.Resume("req-9"),
                classifyReconciliation(result),
            )
        }
    }

    @Test
    fun `reconciliation 404 confirms the request never landed`() {
        assertEquals(
            MediaReconciliationDisposition.ConfirmAbsent,
            classifyReconciliation(Result.failure(apiError(404))),
        )
    }

    @Test
    fun `transient reconciliation failures defer to the next startup`() {
        // 网络、限流、服务端异常都不升级成「确认没收到」：保持原状下次再对账
        listOf(
            Result.failure<MediaGenerationTask>(apiError(429)),
            Result.failure<MediaGenerationTask>(apiError(500)),
            Result.failure<MediaGenerationTask>(apiError(400)),
            Result.failure<MediaGenerationTask>(IOException("network dropped")),
        ).forEach { result ->
            assertEquals(MediaReconciliationDisposition.RetryLater, classifyReconciliation(result))
        }
    }
}
