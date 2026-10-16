package app.androidrik.rikkahub.data.image

import app.androidrik.ai.provider.ImageGenerationParams
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.ui.ImageGenerationItem
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.db.entity.GenMediaEntity
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.repository.GenMediaRepository
import java.io.File

/**
 * **生图产物的唯一写路径**（生成 → 落盘 → 落 `GenMediaEntity`）。
 *
 * 为什么要有这个类：会话内的 `generate_image` 工具（见
 * `docs/design/design-agent-image-tool.md`）与生图页 `ImgGenVM` 都要做同一件事——
 * 调 provider、把 base64 落成文件、写一条媒体记录。这段逻辑原本整块长在 `ImgGenVM` 里；
 * 直接复制一份到工具里意味着**文件名清洗、相对路径约定、落库字段三处各有一份**，
 * 而这三处任一走偏都会产生"库里有记录、图裂"或"图在但画廊看不见"这类难查的问题
 * （上游 `85af5b91` / #1993 就是这么来的）。
 *
 * 共用的边界刻意划在**编排之前**：本类提供
 *
 * - [resolveModel]：解析"设置里配置的生图模型"（会话内工具据此决定装不装配）
 * - [saveImageToStorage]：单张落盘 + 落库（生图页的**流式预览**也走它）
 * - [generate]：不关心中间态的调用方（工具）用的整段编排
 *
 * 生图页保留自己的 `collectImageGeneration`——它要把 `partial_images` 的中间预览推给 UI，
 * 那份编排是页面独有的，不该为了共用而牺牲。
 */
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
internal fun Settings.resolveImageGenerationModel(): Model? {
    val model = findModelById(imageGenerationModelId) ?: return null
    // provider 也要能解析：只有模型、没有可用 provider 时同样属于"没配好"
    return model.takeIf { it.findProvider(providers) != null }
}

class ImageGenerationRunner(
    private val providerManager: ProviderManager,
    private val filesManager: FilesManager,
    private val genMediaRepository: GenMediaRepository,
) {

    /** 非流式整段生成（工具的入口）：只收终态图，中间预览丢弃。 */
    suspend fun generate(
        settings: Settings,
        prompt: String,
        numOfImages: Int,
        size: String,
    ): List<GeneratedImage> {
        val model = settings.resolveImageGenerationModel()
            ?: error("no usable image generation model configured")
        val provider = model.findProvider(settings.providers)
            ?: error("image generation provider not found")

        val params = ImageGenerationParams(
            model = model,
            prompt = prompt,
            numOfImages = numOfImages,
            size = size,
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        )

        val generated = mutableListOf<GeneratedImage>()
        var index = 0
        providerManager.getProviderByType(provider).generateImage(provider, params).collect { item ->
            // 中间态（OpenAI `partial_images` 的流式预览）对工具没有意义：卡片渲染的是终态
            if (item.partial) return@collect
            val file = saveImageToStorage(
                item = item,
                prompt = prompt,
                modelName = model.displayName,
                index = index,
            )
            generated += GeneratedImage(
                id = 0, // 落库后的自增 id 由画廊重新读取时带回（与生图页同款）
                prompt = prompt,
                filePath = file.absolutePath,
                timestamp = System.currentTimeMillis(),
                model = model.displayName,
            )
            index++
        }
        return generated
    }

    /**
     * 单张终态图落盘 + 落库，返回落盘后的文件。
     *
     * 库里的 `path` 存**相对路径**（`images/<文件名>`）：备份/迁移时目录整体搬走仍然有效，
     * 绝对路径会在换机后全部失效。绝对路径只在读取侧由 [toGeneratedImage] 拼回来。
     */
    suspend fun saveImageToStorage(
        item: ImageGenerationItem,
        prompt: String,
        modelName: String,
        index: Int,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ): File {
        val imagesDir = filesManager.getImagesDir()

        val timestamp = System.currentTimeMillis()
        // 清洗模型名：含 "/" 时（OpenRouter `vendor/model`）裸拼会让文件落进子目录，而记录只存
        // 文件名 ⇒ 图裂（上游 `85af5b91` / #1993）
        val filename = "${timestamp}_${modelName.toFileNamePart()}_$index.png"
        val imageFile = File(imagesDir, filename)

        val createdFile = filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)

        val entity = GenMediaEntity(
            path = "images/${imageFile.name}",
            modelId = modelName,
            prompt = prompt,
            createAt = timestamp,
            type = type,
            sourcePaths = sourcePaths,
        )
        genMediaRepository.insertMedia(entity)

        return createdFile
    }
}
