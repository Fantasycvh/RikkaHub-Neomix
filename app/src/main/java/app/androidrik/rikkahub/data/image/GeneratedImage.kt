package app.androidrik.rikkahub.data.image

import app.androidrik.rikkahub.data.db.entity.GenMediaEntity
import app.androidrik.rikkahub.data.files.FilesManager
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 一张已生成的图片（**数据层类型**）。
 *
 * 原本定义在 `ui/pages/imggen/ImgGenVM.kt` 里，但它并不是 UI 概念——`data/sync/BackupItem.kt`
 * 早就在注释里依赖它的形态、`ImageGenerationRunner`（会话内工具与生图页共用）也要返回它。
 * 放在 UI 包里的直接后果是**数据层反向依赖 UI 包**，所以随本次抽取上移到 `data/image/`。
 */
@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String
)

/**
 * `GenMediaEntity`（库里存**相对路径**）→ [GeneratedImage]（带绝对路径，供 Coil / 分享使用）。
 *
 * 旧版本把含 `/` 的模型名（OpenRouter 的 `vendor/model`）直接拼进文件名 ⇒ 文件实际落在
 * 子目录 `images/<时间戳>_<vendor>/<name>`，而记录只存了最后一段。此处按旧形态回退查找，
 * 让这些老图重新可见（对齐上游 `85af5b91`；写入侧已改为先清洗，见
 * [ImageGenerationRunner.saveImageToStorage]）。
 */
internal fun GenMediaEntity.toGeneratedImage(filesManager: FilesManager): GeneratedImage {
    val imagesDir = filesManager.getImagesDir()
    val name = this.path.removePrefix("images/")
    val file = File(imagesDir, name)
    val fullPath = if (file.exists() || '/' !in modelId) {
        file.absolutePath
    } else {
        File(imagesDir, "${createAt}_${modelId.substringBeforeLast('/')}/$name").absolutePath
    }
    return GeneratedImage(
        id = this.id,
        prompt = this.prompt,
        filePath = fullPath,
        timestamp = this.createAt,
        model = this.modelId
    )
}

/**
 * 文件名安全化：模型名可能含 `/`（OpenRouter 的 `vendor/model`）等**路径分隔与保留字符**，
 * 直接拼进文件名会让产物落进子目录（而记录只存文件名 ⇒ 图裂）。
 * 对齐上游 `85af5b91` 的字符集。
 */
internal fun String.toFileNamePart(): String = replace(Regex("""[\\/:*?"<>|]"""), "_")
