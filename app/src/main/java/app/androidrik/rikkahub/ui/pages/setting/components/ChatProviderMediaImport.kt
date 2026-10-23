package app.androidrik.rikkahub.ui.pages.setting.components

import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting

/** 独立快照，不共享 ID 或聊天模型；媒体适配器目前只支持单个 API Key。 */
internal fun ProviderSetting.OpenAI.toMediaGenerationProvider(): MediaGenerationProviderSetting.OpenAI =
    MediaGenerationProviderSetting.OpenAI(
        name = name,
        baseUrl = baseUrl,
        apiKey = effectiveApiKeys.split(Regex("[\\s,]+")).firstOrNull { it.isNotBlank() }.orEmpty(),
        models = emptyList(),
    )
