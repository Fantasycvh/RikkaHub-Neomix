package app.androidrik.mediagen.provider

import app.androidrik.mediagen.model.MediaGenerationModel

/**
 * 能从厂商接口拉取模型列表的适配器，供设置页「获取模型列表」使用。
 *
 * 单独成接口而不是塞进 [MediaGenerationProvider]：只有部分厂商的接口提供模型列表
 * （OpenAI 兼容的 `/models`、硅基流动按 `type` 过滤的 `/models`），其余厂商要么没有该接口，
 * 要么列表里混着聊天模型无法区分，保持手动填写更可靠。
 */
interface MediaModelLister<S : MediaGenerationProviderSetting> {
    /**
     * 拉取该厂商下**本应用能用于媒体生成**的模型。
     *
     * 只做拉取与粗筛，是否加入配置由用户在设置页勾选决定——所以厂商返回的模型名再怪
     * （中转站改名的 `flux-dev` 之类）也不会自动写进配置。
     */
    suspend fun listModels(setting: S): Result<List<MediaGenerationModel>>
}
