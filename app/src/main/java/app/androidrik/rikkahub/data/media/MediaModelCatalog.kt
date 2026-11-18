package app.androidrik.rikkahub.data.media

import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationCapabilities
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.mediagen.provider.capabilities
import app.androidrik.mediagen.provider.modelCapabilities
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.model.MediaCreationRecord
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

@Serializable
data class MediaModelRef(val id: Uuid, val providerId: Uuid, val modelId: String, val kind: MediaKind)

internal data class MediaModelOption(
    val target: MediaGenerationTarget,
    val model: MediaGenerationModel,
    val providerId: Uuid,
    val providerName: String,
    val capabilities: MediaGenerationCapabilities,
    val visible: Boolean = true,
) {
    val ref get() = MediaModelRef(model.id, providerId, model.modelId, model.kind)
    val name get() = model.displayName.ifBlank { model.modelId }
    val mediaProvider get() = (target as? MediaGenerationTarget.Media)?.provider

    /**
     * 纯文本模板直跑是否可用：模型没有必填的图片输入（SiliconFlow I2V 必填首帧，
     * 见 [modelCapabilities] 的模型级约束），其余都行。
     */
    val supportsTextTemplate get() = when (target) {
        is MediaGenerationTarget.Image -> true
        is MediaGenerationTarget.Media -> capabilities.requiredImageInputs.isEmpty()
    }

    val permitsBoundedCount: Boolean get() = when (target) {
        is MediaGenerationTarget.Image -> target.model.customBodies.none {
            it.key in setOf("model", "n", "prompt", "size", "input_references")
        }
        is MediaGenerationTarget.Media -> true
    }

    fun authorizationFingerprint(): String {
        val configuration = when (target) {
            is MediaGenerationTarget.Image -> {
                val provider = target.provider as ProviderSetting.OpenAI
                listOf(
                    "image",
                    provider.baseUrl,
                    JsonInstant.encodeToString(provider.customHeaders),
                    JsonInstant.encodeToString(target.model.customHeaders),
                    JsonInstant.encodeToString(target.model.customBodies),
                )
            }
            is MediaGenerationTarget.Media -> listOf(target.provider.javaClass.name, target.provider.baseUrl)
        }
        val bytes = JsonInstant.encodeToString(configuration).encodeToByteArray()
        return Base64.encode(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

internal fun Settings.mediaModelCatalog(): List<MediaModelOption> = buildList {
    for (stored in mediaGenerationProviders) {
        if (stored !is MediaGenerationProviderSetting.OpenAI &&
            stored !is MediaGenerationProviderSetting.SiliconFlow
        ) continue
        // 凭据引用的生效配置：引用被删或引用对象不是凭据型供应商时，这套配置的模型整体失效
        val provider = effectiveMediaProvider(stored) ?: continue
        for (model in provider.models) {
            if (model.modelId.isBlank()) continue
            val capabilities = provider.modelCapabilities(model) ?: continue
            add(MediaModelOption(MediaGenerationTarget.Media(provider, model), model, stored.id,
                provider.name.ifBlank { provider.javaClass.simpleName }, capabilities))
        }
    }
    for (provider in providers) {
        for (model in provider.models) {
            if (model.type != ModelType.IMAGE && model.id != imageGenerationModelId) continue
            if (model.modelId.isBlank()) continue
            val effective = model.findProvider(providers) as? ProviderSetting.OpenAI ?: continue
            val presentation = MediaGenerationModel(model.modelId, MediaKind.IMAGE, model.displayName, model.id)
            add(MediaModelOption(MediaGenerationTarget.Image(effective, model), presentation, provider.id,
                provider.name.ifBlank { "OpenAI" }, legacyImageCapabilities,
                visible = provider.enabled && model.enabled && model.type == ModelType.IMAGE))
        }
    }
}

/**
 * 媒体供应商的生效配置：引用了旧 AI 供应商凭据（[MediaGenerationProviderSetting.OpenAI.credentialRef]）
 * 时，baseUrl/apiKey 取自被引用者，两处配置共用一份凭据。被引用者被删、或引用对象不是凭据型的
 * OpenAI 兼容供应商时返回 null——这套配置的模型从目录消失，运行期按既有「模型不可用」路径报错。
 *
 * 供应商自身 id 不变（[MediaModelRef] 与定时授权里的引用不受影响）；`authorizationFingerprint`
 * 只看解析后的 baseUrl，凭据值变化不影响指纹，端点变化才会要求重新批准。
 */
internal fun Settings.effectiveMediaProvider(stored: MediaGenerationProviderSetting): MediaGenerationProviderSetting? {
    if (stored !is MediaGenerationProviderSetting.OpenAI) return stored
    val refId = stored.credentialRef ?: return stored
    val source = providers.firstOrNull { it.id == refId } as? ProviderSetting.OpenAI ?: return null
    return stored.copyProvider(
        baseUrl = source.baseUrl,
        apiKey = source.effectiveApiKeys.split(Regex("[\\s,]+")).firstOrNull { it.isNotBlank() }.orEmpty(),
    )
}

private val legacyImageCapabilities =
    requireNotNull(MediaGenerationProviderSetting.OpenAI().capabilities(MediaKind.IMAGE))

internal fun Settings.resolveMediaModel(ref: MediaModelRef): MediaModelOption? =
    mediaModelCatalog().singleOrNull { it.ref == ref }

internal fun Settings.defaultImageModel(): MediaModelOption? =
    mediaModelCatalog().singleOrNull { it.model.id == imageGenerationModelId && it.model.kind == MediaKind.IMAGE }

internal fun Settings.resolveMediaRecord(record: MediaCreationRecord): MediaModelOption? {
    record.catalogModelId?.let { id ->
        resolveMediaModel(MediaModelRef(id, record.providerId, record.modelId, record.kind))?.let { return it }
    }
    val stored = mediaGenerationProviders.firstOrNull { it.id == record.providerId } ?: return null
    val provider = effectiveMediaProvider(stored) ?: return null
    val model = provider.models.firstOrNull {
        it.modelId == record.modelId && it.kind == record.kind &&
            (record.catalogModelId == null || it.id == record.catalogModelId)
    } ?: MediaGenerationModel(record.modelId, record.kind, id = record.catalogModelId ?: Uuid.random())
    val capabilities = provider.modelCapabilities(model) ?: return null
    return MediaModelOption(MediaGenerationTarget.Media(provider, model), model, stored.id, provider.name, capabilities)
}
