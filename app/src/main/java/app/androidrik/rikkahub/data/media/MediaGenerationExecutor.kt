package app.androidrik.rikkahub.data.media

import app.androidrik.ai.provider.ImageEditParams
import app.androidrik.ai.provider.ImageGenerationParams
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.ImageGenSize
import app.androidrik.ai.ui.ImageGenerationItem
import app.androidrik.mediagen.model.MediaGenerationInput
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaGenerationTask
import app.androidrik.mediagen.provider.MediaGenerationManager
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

internal sealed interface MediaGenerationTarget {
    data class Image(
        val provider: ProviderSetting,
        val model: Model,
    ) : MediaGenerationTarget

    data class Media(
        val provider: MediaGenerationProviderSetting,
        val model: MediaGenerationModel,
    ) : MediaGenerationTarget
}

internal sealed interface MediaGenerationResult {
    data class ImageStream(val images: Flow<ImageGenerationItem>) : MediaGenerationResult
    data class Task(val task: MediaGenerationTask) : MediaGenerationResult
}

/**
 * Shared provider submission for image tools and creation pages. Callers own the
 * lifetime and persistence of the stream or provider task they receive.
 */
class MediaGenerationExecutor(
    private val imageProvider: (ProviderSetting) -> Provider<ProviderSetting>,
    private val mediaGenerationManager: MediaGenerationManager,
) {
    internal suspend fun submit(
        target: MediaGenerationTarget,
        request: MediaGenerationRequest,
    ): MediaGenerationResult = when (target) {
        is MediaGenerationTarget.Image -> MediaGenerationResult.ImageStream(generateImage(target, request))
        is MediaGenerationTarget.Media -> {
            val task = mediaGenerationManager.create(target.provider, target.model, request).getOrElse {
                currentCoroutineContext().ensureActive()
                throw it
            }
            MediaGenerationResult.Task(task)
        }
    }

    internal suspend fun query(
        target: MediaGenerationTarget.Media,
        taskId: String,
    ): Result<MediaGenerationTask> = mediaGenerationManager.query(target.provider, target.model, taskId)

    private suspend fun generateImage(
        target: MediaGenerationTarget.Image,
        request: MediaGenerationRequest,
    ): Flow<ImageGenerationItem> {
        val model = target.model
        val provider = imageProvider(target.provider)
        val prompt = requireNotNull(request.prompt)
        val count = request.count ?: 1
        val size = request.resolution ?: ImageGenSize.AUTO.value

        if (request.inputs.isEmpty()) {
            return provider.generateImage(
                target.provider,
                ImageGenerationParams(
                    model = model,
                    prompt = prompt,
                    numOfImages = count,
                    size = size,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
        }

        val images = request.inputs.map { input ->
            require(input is MediaGenerationInput.Image) { "Image editing requires image inputs" }
            input.url
        }
        return provider.editImage(
            target.provider,
            ImageEditParams(
                model = model,
                prompt = prompt,
                images = images,
                numOfImages = count,
                size = size,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
            ),
        )
    }
}
