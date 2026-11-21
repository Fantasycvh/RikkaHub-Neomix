package app.androidrik.rikkahub.data.media

import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationStatus
import app.androidrik.mediagen.model.MediaGenerationTask
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.data.ai.UsageRecorder
import kotlin.uuid.Uuid

/**
 * 媒体任务的用量入账（三分支汇聚点）：SUCCEEDED 终态按计费单位取数——图像按张
 * （usage 缺省时按任务产出数），视频按秒（SiliconFlow timings），token 有则带上。
 * 调用方：[MediaCreationService.generate]（手动创作 + 定时模板）与
 * [MediaGenerationExecutor.images]（聊天工具），对应 UsageSource.MEDIA。
 */
internal fun UsageRecorder.recordMediaTask(
    provider: MediaGenerationProviderSetting,
    model: MediaGenerationModel,
    task: MediaGenerationTask,
    mediaSessionId: Uuid?,
) {
    if (task.status != MediaGenerationStatus.SUCCEEDED) return
    val imageCount: Int
    val videoSeconds: Double?
    when (model.kind) {
        MediaKind.IMAGE -> {
            imageCount = task.usage?.inputImageCount ?: task.outputs.size
            videoSeconds = null
        }

        MediaKind.VIDEO -> {
            imageCount = 0
            videoSeconds = task.usage?.totalSeconds
        }
    }
    recordMediaGeneration(
        providerId = provider.id,
        providerName = provider.name,
        modelId = model.id,
        modelName = model.displayName.ifBlank { model.modelId },
        kind = model.kind,
        imageCount = imageCount,
        videoSeconds = videoSeconds,
        totalTokens = task.usage?.totalTokens,
        mediaSessionId = mediaSessionId,
    )
}
