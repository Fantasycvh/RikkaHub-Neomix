package app.androidrik.rikkahub.data.files

import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.data.model.BubbleStack
import app.androidrik.rikkahub.data.model.textureIds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BubbleMaterialDocument(val format: String = "neomix.bubble", val version: Int = 1, val bubble: ChatBubble)

private val materialJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun BubbleTextureStore.exportMaterial(bubble: ChatBubble): ByteArray {
    val normalized = bubble.normalized()
    val manifest = materialJson.encodeToString(BubbleMaterialDocument.serializer(), BubbleMaterialDocument(bubble = normalized))
    return AppearanceArchive(manifest, normalized.textureIds().associateWith(::bytes)).encode()
}

fun BubbleTextureStore.importMaterial(bytes: ByteArray): ChatBubble {
    val archive = AppearanceArchive.decode(bytes)
    val document = materialJson.decodeFromString<BubbleMaterialDocument>(archive.manifest)
    require(document.format == "neomix.bubble" && document.version == 1) { "不支持的材质包格式" }
    require((document.bubble.stack?.layers?.size ?: 0) <= BubbleStack.MAX_LAYERS) { "材质包最多支持四个图层" }
    val bubble = document.bubble.normalized()
    installArchiveTextures(archive, bubble.textureIds())
    return bubble
}

fun BubbleTextureStore.installArchiveTextures(archive: AppearanceArchive, required: Set<String>) {
    require(archive.textures.keys == required) { "材质包纹理缺失或包含未引用资源" }
    archive.textures.forEach { (id, bytes) -> validate(id, bytes) }
    archive.textures.forEach { (id, bytes) -> install(id, bytes) }
}
