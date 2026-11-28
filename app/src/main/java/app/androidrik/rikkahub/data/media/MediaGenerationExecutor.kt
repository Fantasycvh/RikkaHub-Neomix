package app.androidrik.rikkahub.data.media

import app.androidrik.ai.ui.ImageGenerationItem
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaGenerationStatus
import app.androidrik.mediagen.model.MediaGenerationTask
import app.androidrik.mediagen.provider.MediaGenerationManager
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.data.ai.UsageRecorder
import app.androidrik.common.http.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.io.encoding.Base64

/**
 * 统一媒体目标（M3b-1 目录切换后只剩 mediagen 形态：legacy 生图模型在目录层映射成 Media，
 * 见 [legacyMediaOption]；旧 `Image` 分支与 ai 适配器执行路径已随本批退役）。
 */
internal sealed interface MediaGenerationTarget {
    data class Media(
        val provider: MediaGenerationProviderSetting,
        val model: MediaGenerationModel,
    ) : MediaGenerationTarget
}

/**
 * 统一提交/续查入口（M3b-1 后 Media-only）：旧 `ImageStream` 分支随 `MediaGenerationTarget.Image`
 * 一并退役，legacy 生图模型经 [legacyMediaOption] 映射后同样走 mediagen 任务链路。
 */
class MediaGenerationExecutor(
    private val mediaGenerationManager: MediaGenerationManager,
    private val downloadClient: OkHttpClient = OkHttpClient(),
    private val usageRecorder: UsageRecorder? = null,
) {
    internal fun images(target: MediaGenerationTarget.Media, request: MediaGenerationRequest): Flow<ImageGenerationItem> =
        flow {
            val task = submit(target, request)
            check(task.status == MediaGenerationStatus.SUCCEEDED) {
                task.error?.message ?: "Image generation did not complete"
            }
            // 聊天工具分支的用量入账（手动创作/定时模板在 MediaCreationService 侧记）
            usageRecorder?.recordMediaTask(target.provider, target.model, task, mediaSessionId = null)
            for (output in task.outputs) {
                val data = output.data ?: downloadClient.newCall(
                    Request.Builder().url(requireNotNull(output.url)).build(),
                ).await().use { response ->
                    check(response.isSuccessful) { "Image download failed (HTTP ${response.code})" }
                    withContext(Dispatchers.IO) { response.body.bytes() }
                }
                emit(ImageGenerationItem(Base64.encode(data), output.mimeType))
            }
        }

    internal suspend fun submit(
        target: MediaGenerationTarget.Media,
        request: MediaGenerationRequest,
    ): MediaGenerationTask {
        val task = mediaGenerationManager.create(target.provider, target.model, request).getOrElse {
            currentCoroutineContext().ensureActive()
            throw it
        }
        return task
    }

    internal suspend fun query(
        target: MediaGenerationTarget.Media,
        taskId: String,
    ): Result<MediaGenerationTask> = mediaGenerationManager.query(target.provider, target.model, taskId)

    internal fun supportsQuery(target: MediaGenerationTarget.Media): Boolean =
        mediaGenerationManager.supportsQuery(target.provider, target.model)

    /** 该供应商的这个模型是否支持按对账键对账（启动期据此决定 SUBMIT_UNKNOWN 记录能否自动找回）。 */
    internal fun supportsReconciliation(
        provider: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
    ): Boolean = mediaGenerationManager.supportsReconciliation(provider, model)

    /** 按提交时下发的对账键查询服务端任务（只读，任何结果都不会重新提交）。 */
    internal suspend fun queryByRequestId(
        provider: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        requestId: String,
    ): Result<MediaGenerationTask> = mediaGenerationManager.queryByRequestId(provider, model, requestId)
}
