package app.androidrik.rikkahub.data.image

import app.androidrik.ai.ui.ImageGenerationItem
import app.androidrik.mediagen.model.MediaGenerationInput
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.db.entity.GenMediaEntity
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.media.MediaGenerationExecutor
import app.androidrik.rikkahub.data.media.MediaModelOption
import app.androidrik.rikkahub.data.media.defaultImageModel
import app.androidrik.rikkahub.data.repository.GenMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.uuid.Uuid

/**
 * 设置里配置且**可用**的生图模型；`null` = 没配好（没配 / provider 解析不出来）。
 *
 * 抽成**顶层扩展函数**而不是 runner 的成员：装配门控是纯判定，抽出来就能直接单测，
 * 不必为了测它去构造一个带 ProviderManager/FilesManager/数据库的 runner。
 *
 * 会话内工具用它的返回值做装配门控——没配就**不注册工具**，而不是注册一个"一调用就报错"
 * 的工具（`ChatToolFactory` 里 `subagentDispatcher` 同款定式：宁可没有工具，也不能出现一个
 * 一调用就崩的工具）。顺带的效果是：没配生图模型的用户一个 token 都不付。
 */
internal fun Settings.resolveImageGenerationModel(): MediaModelOption? = defaultImageModel()

internal sealed interface ImageGenerationUpdate {
    data class Preview(
        val item: ImageGenerationItem,
        val modelName: String,
    ) : ImageGenerationUpdate

    data class Saved(val image: GeneratedImage) : ImageGenerationUpdate
}

/** Writes final images to the gallery used by chat tools and the standalone image page. */
class ImageGenerationRunner(
    private val executor: MediaGenerationExecutor,
    private val filesManager: FilesManager,
    private val genMediaRepository: GenMediaRepository,
) {

    internal fun generate(
        settings: Settings,
        request: MediaGenerationRequest,
    ): Flow<ImageGenerationUpdate> = generate(
        settings.resolveImageGenerationModel() ?: error("No model selected"),
        request,
    )

    internal fun generate(option: MediaModelOption, request: MediaGenerationRequest): Flow<ImageGenerationUpdate> = flow {
        val prompt = requireNotNull(request.prompt)
        val editInputs = request.inputs.filterIsInstance<MediaGenerationInput.Image>()
        val type = if (editInputs.isEmpty()) GenMediaEntity.TYPE_IMAGE_GENERATION else GenMediaEntity.TYPE_IMAGE_EDIT
        val sourcePaths = editInputs.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.url }
        executor.images(option.target, request).collect { item ->
            if (item.partial) {
                emit(ImageGenerationUpdate.Preview(item, option.name))
                return@collect
            }
            val image = saveImageToStorage(
                item = item,
                prompt = prompt,
                modelName = option.name,
                type = type,
                sourcePaths = sourcePaths,
            )
            emit(ImageGenerationUpdate.Saved(image))
        }
    }

    /**
     * 单张终态图落盘 + 落库，返回图片信息。
     *
     * 库里的 `path` 存**相对路径**（`images/<文件名>`）：备份/迁移时目录整体搬走仍然有效，
     * 绝对路径会在换机后全部失效。绝对路径只在读取侧由 [toGeneratedImage] 拼回来。
     */
    private suspend fun saveImageToStorage(
        item: ImageGenerationItem,
        prompt: String,
        modelName: String,
        type: String,
        sourcePaths: String?,
    ): GeneratedImage = withContext(Dispatchers.IO) {
        val imagesDir = filesManager.getImagesDir()

        val timestamp = System.currentTimeMillis()
        val filename = "${timestamp}_${Uuid.random()}.png"
        val imageFile = File(imagesDir, filename)

        val entity = GenMediaEntity(
            path = "images/${imageFile.name}",
            modelId = modelName,
            prompt = prompt,
            createAt = timestamp,
            type = type,
            sourcePaths = sourcePaths,
        )
        // Finish the file and its row together if the caller cancels mid-write.
        withContext(NonCancellable) {
            try {
                filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)
                genMediaRepository.insertMedia(entity)
            } catch (e: Exception) {
                imageFile.delete()
                throw e
            }
        }
        entity.toGeneratedImage(filesManager)
    }
}
