package app.androidrik.rikkahub.data.scheduled

import app.androidrik.mediagen.model.ImageRole
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationParameter
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.media.MediaModelOption
import app.androidrik.rikkahub.data.media.MediaModelRef
import app.androidrik.rikkahub.data.model.MediaCreationAsset
import app.androidrik.rikkahub.data.model.MediaCreationAssetType
import app.androidrik.rikkahub.data.model.MediaCreationParams
import app.androidrik.rikkahub.data.model.supportedBy
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.Serializable

/**
 * 定时模板的生成操作。TEXT 是纯提示词直跑；IMAGE_EDIT 带参考图走图片编辑；
 * FIRST_FRAME 是图生视频（一张首帧图）。
 */
@Serializable
enum class ScheduledMediaOperation {
    TEXT,
    IMAGE_EDIT,
    FIRST_FRAME,
}

/**
 * 模板里锁定的素材。保存时已复制进应用私有目录，[path] 相对于 filesDir，
 * 后台运行按这个路径稳定读取，不受相册里原文件被删的影响。
 */
@Serializable
data class ScheduledMediaAsset(
    val path: String,
    val type: MediaCreationAssetType = MediaCreationAssetType.IMAGE,
    val role: ImageRole = ImageRole.REFERENCE,
) {
    internal fun toMediaCreationAsset() = MediaCreationAsset(path, type, role)
}

/** Authorization created by the task editor. Agent grants permit one text-to-image call per run. */
@Serializable
data class ScheduledMediaAction(
    val model: MediaModelRef,
    val params: MediaCreationParams,
    val configurationFingerprint: String,
    val templatePrompt: String? = null,
    val operation: ScheduledMediaOperation = ScheduledMediaOperation.TEXT,
    val templateAssets: List<ScheduledMediaAsset> = emptyList(),
    /** 保存时的素材上传目的地指纹；模板带素材时运行前会核对当前配置，不一致视为授权失效。 */
    val uploadFingerprint: String? = null,
) {
    val isTemplate: Boolean get() = templatePrompt != null

    internal fun permits(option: MediaModelOption): Boolean {
        if (option.ref != model || params != params.supportedBy(option.capabilities)) return false
        if (!option.permitsBoundedCount || configurationFingerprint != option.authorizationFingerprint()) return false
        if (isTemplate && (templatePrompt.isNullOrBlank() || !permitsOperation(option))) return false
        if (!isTemplate && model.kind != MediaKind.IMAGE) return false
        if (params.count != null && params.count !in 1..4) return false
        val required = option.capabilities.requiredParameters
        return (MediaGenerationParameter.COUNT !in required || params.count != null) &&
            (MediaGenerationParameter.RESOLUTION !in required || !params.resolution.isNullOrBlank()) &&
            (MediaGenerationParameter.ASPECT_RATIO !in required || !params.aspectRatio.isNullOrBlank()) &&
            (MediaGenerationParameter.DURATION !in required || params.durationSeconds != null)
    }

    /**
     * 模板的操作与素材必须被模型能力完整接受：素材不能有被丢弃的（能力外的角色或类型），
     * 素材型操作必须有素材，FIRST_FRAME 必须提供首帧。TEXT 维持纯文本门控。
     */
    private fun permitsOperation(option: MediaModelOption): Boolean {
        val capabilities = option.capabilities
        return when (operation) {
            ScheduledMediaOperation.TEXT -> templateAssets.isEmpty() && option.supportsTextTemplate

            ScheduledMediaOperation.IMAGE_EDIT -> model.kind == MediaKind.IMAGE && acceptsAssets(option, requiredRole = null)

            ScheduledMediaOperation.FIRST_FRAME ->
                model.kind == MediaKind.VIDEO && acceptsAssets(option, requiredRole = ImageRole.FIRST_FRAME)
        }
    }

    private fun acceptsAssets(option: MediaModelOption, requiredRole: ImageRole?): Boolean {
        if (templateAssets.isEmpty()) return false
        // 能力会把不接受的角色退回参考图、把多余的槽位挤掉：只有一张不少地通过才算被完整接受
        val accepted = templateAssets.map(ScheduledMediaAsset::toMediaCreationAsset).supportedBy(option.capabilities)
        if (accepted.size != templateAssets.size) return false
        return requiredRole == null || accepted.any { it.role == requiredRole }
    }
}

internal fun ScheduledTaskEntity.mediaGrant(): ScheduledMediaAction? =
    mediaAction?.takeIf { createdBy == ScheduledTaskEntity.CREATED_BY_USER }
        ?.let { runCatching { JsonInstant.decodeFromString<ScheduledMediaAction>(it) }.getOrNull() }

internal val ScheduledTaskEntity.isMediaTemplate: Boolean
    get() = mediaAction != null && conversationId.isBlank() && assistantId == null
