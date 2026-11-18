package app.androidrik.rikkahub.data.media

import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationCapabilities
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.mediagen.provider.capabilities
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
    val supportsTextTemplate get() =
        mediaProvider !is MediaGenerationProviderSetting.SiliconFlow ||
            !model.modelId.contains("I2V", ignoreCase = true)

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
    for (provider in mediaGenerationProviders) {
        if (provider !is MediaGenerationProviderSetting.OpenAI &&
            provider !is MediaGenerationProviderSetting.SiliconFlow
        ) continue
        for (model in provider.models) {
            if (model.modelId.isBlank()) continue
            val capabilities = provider.capabilities(model.kind) ?: continue
            add(MediaModelOption(MediaGenerationTarget.Media(provider, model), model, provider.id,
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
    val provider = mediaGenerationProviders.firstOrNull { it.id == record.providerId } ?: return null
    val capabilities = provider.capabilities(record.kind) ?: return null
    val model = provider.models.firstOrNull {
        it.modelId == record.modelId && it.kind == record.kind &&
            (record.catalogModelId == null || it.id == record.catalogModelId)
    } ?: MediaGenerationModel(record.modelId, record.kind, id = record.catalogModelId ?: Uuid.random())
    return MediaModelOption(MediaGenerationTarget.Media(provider, model), model, provider.id, provider.name, capabilities)
}
