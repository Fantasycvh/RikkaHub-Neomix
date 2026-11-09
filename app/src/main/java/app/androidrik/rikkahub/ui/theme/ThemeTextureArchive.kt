package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.files.AppearanceArchive
import app.androidrik.rikkahub.data.files.BubbleTextureStore
import app.androidrik.rikkahub.data.files.installArchiveTextures
import app.androidrik.rikkahub.data.model.textureIds
import app.androidrik.rikkahub.data.model.toChatAppearance
import app.androidrik.rikkahub.data.model.BubbleStack
import app.androidrik.rikkahub.data.model.panels
import kotlinx.serialization.json.Json

private val archiveThemeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun ThemeSnapshot.textureIds(): Set<String> = appearance?.let {
    val a = DisplaySetting().withAppearanceSnapshot(it).toChatAppearance()
    a.userBubble.textureIds() + a.assistantBubble.textureIds() + it.appSkin?.textureIds().orEmpty()
} ?: emptySet()

fun BubbleTextureStore.exportTheme(snapshot: ThemeSnapshot): ByteArray = AppearanceArchive(
    archiveThemeJson.encodeToString(ThemeSnapshot.serializer(), snapshot), snapshot.textureIds().associateWith(::bytes),
).encode()

fun BubbleTextureStore.importTheme(bytes: ByteArray): ThemeSnapshot {
    val archive = AppearanceArchive.decode(bytes)
    val snapshot = archiveThemeJson.decodeFromString<ThemeSnapshot>(archive.manifest)
    require(snapshot.formatVersion == 1) { "不支持的主题包版本" }
    val details = snapshot.appearance?.chatAppearanceDetails
    require((details?.userStack?.layers?.size ?: 0) <= BubbleStack.MAX_LAYERS &&
        (details?.assistantStack?.layers?.size ?: 0) <= BubbleStack.MAX_LAYERS) { "主题材质最多支持四个图层" }
    require(snapshot.appearance?.appSkin?.panels().orEmpty().all {
        (it.background?.stack?.layers?.size ?: 0) <= BubbleStack.MAX_LAYERS
    }) { "皮肤材质最多支持四个图层" }
    installArchiveTextures(archive, snapshot.textureIds())
    return snapshot
}
