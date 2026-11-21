package app.androidrik.rikkahub.ui.pages.setting.components

import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.data.media.legacyImageExecutable
import app.androidrik.rikkahub.data.media.toMediaImageModel
import app.androidrik.rikkahub.data.media.withChatProviderCredentials

internal fun ProviderSetting.ApiKey.toMediaGenerationProvider(): MediaGenerationProviderSetting {
    val draft = when (this) {
        is ProviderSetting.OpenAI -> MediaGenerationProviderSetting.OpenAI(
            name = name, customHeaders = customHeaders, models = emptyList(),
        )
        is ProviderSetting.Google -> MediaGenerationProviderSetting.Google(
            name = name, customHeaders = customHeaders, models = emptyList(),
        )
        else -> error("Unsupported chat provider for media import")
    }
    return checkNotNull(draft.withChatProviderCredentials(this)).withImportedImageModels(this)
}

internal fun MediaGenerationProviderSetting.withImportedImageModels(
    source: ProviderSetting.ApiKey,
): MediaGenerationProviderSetting {
    val existingIds = models.filter { it.kind == MediaKind.IMAGE }.map { it.modelId }.toSet()
    val imported = source.models.asSequence()
        .filter { it.enabled && it.modelId.isNotBlank() && it.modelId !in existingIds }
        // A provider override can send requests to a different endpoint or account.
        .filter { it.providerOverwrite == null && legacyImageExecutable(source, it) }
        .distinctBy { it.modelId }
        .map { it.toMediaImageModel() }
        .toList()
    return copyProvider(models = models + imported)
}
