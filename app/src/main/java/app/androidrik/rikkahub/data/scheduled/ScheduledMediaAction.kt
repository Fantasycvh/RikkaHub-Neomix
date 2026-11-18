package app.androidrik.rikkahub.data.scheduled

import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationParameter
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.media.MediaModelOption
import app.androidrik.rikkahub.data.media.MediaModelRef
import app.androidrik.rikkahub.data.model.MediaCreationParams
import app.androidrik.rikkahub.data.model.supportedBy
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.Serializable

/** Authorization created by the task editor. Agent grants permit one text-to-image call per run. */
@Serializable
data class ScheduledMediaAction(
    val model: MediaModelRef,
    val params: MediaCreationParams,
    val configurationFingerprint: String,
    val templatePrompt: String? = null,
) {
    val isTemplate: Boolean get() = templatePrompt != null

    internal fun permits(option: MediaModelOption): Boolean {
        if (option.ref != model || params != params.supportedBy(option.capabilities)) return false
        if (!option.permitsBoundedCount || configurationFingerprint != option.authorizationFingerprint()) return false
        if (isTemplate && (templatePrompt.isNullOrBlank() || !option.supportsTextTemplate)) return false
        if (!isTemplate && model.kind != MediaKind.IMAGE) return false
        if (params.count != null && params.count !in 1..4) return false
        val required = option.capabilities.requiredParameters
        return (MediaGenerationParameter.COUNT !in required || params.count != null) &&
            (MediaGenerationParameter.RESOLUTION !in required || !params.resolution.isNullOrBlank()) &&
            (MediaGenerationParameter.ASPECT_RATIO !in required || !params.aspectRatio.isNullOrBlank()) &&
            (MediaGenerationParameter.DURATION !in required || params.durationSeconds != null)
    }
}

internal fun ScheduledTaskEntity.mediaGrant(): ScheduledMediaAction? =
    mediaAction?.takeIf { createdBy == ScheduledTaskEntity.CREATED_BY_USER }
        ?.let { runCatching { JsonInstant.decodeFromString<ScheduledMediaAction>(it) }.getOrNull() }

internal val ScheduledTaskEntity.isMediaTemplate: Boolean
    get() = mediaAction != null && conversationId.isBlank() && assistantId == null
