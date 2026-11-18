package app.androidrik.rikkahub.ui.pages.setting.components

import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting

/**
 * 从聊天供应商导入媒体供应商。凭据走**活引用**（[MediaGenerationProviderSetting.OpenAI.credentialRef]）：
 * 之后聊天侧改 key / 端点媒体侧自动跟随；本地仍留一份导入时的快照，解除引用时回退为显式配置。
 */
internal fun ProviderSetting.OpenAI.toMediaGenerationProvider(): MediaGenerationProviderSetting.OpenAI =
    MediaGenerationProviderSetting.OpenAI(
        name = name,
        baseUrl = baseUrl,
        apiKey = effectiveApiKeys.split(Regex("[\\s,]+")).firstOrNull { it.isNotBlank() }.orEmpty(),
        credentialRef = id,
        models = emptyList(),
    )
