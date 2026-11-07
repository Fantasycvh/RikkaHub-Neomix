package app.androidrik.rikkahub.data.datastore

import androidx.datastore.core.DataStore
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.asr.ASRProviderSetting
import app.androidrik.common.settings.resolveSelected
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.utils.toMutableStateFlow
import app.androidrik.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first

// —— Settings 查询扩展（批次 4 拆出）——

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

/**
 * 解析压缩使用的模型：
 * 1. 显式指定且可解析 -> 用该模型（用户可专门配一个便宜模型做摘要）
 * 2. **未指定（null = 「跟随对话模型」）或指定已失效（模型被删）** -> 用 [followModel]（该会话的生成模型）
 * 3. 都没有 -> 全局当前对话模型
 */
fun Settings.resolveCompressionModel(followModel: Model? = null): Model? =
    compressModelId?.let { findModelById(it) }
        ?: followModel
        ?: getCurrentChatModel()

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

/**
 * 等到设置**真正从磁盘读出**（`init = true` 的 `Settings.dummy()` 退场）。
 *
 * 为什么必须有这个口：本仓库 `settingsFlow` 以 [Settings.dummy] 起手（`toMutableStateFlow`
 * 的初始值），冷启动 / 后台进程被 WorkManager 拉起时，`settingsFlow.value` 与
 * `settingsFlow.first()` 在最初几百毫秒里返回的都是**全默认配置**——`providers` 是内置空表、
 * `chatModelId` 是 `Uuid.random()`。生成侧 `findModelById` 于是一定返回 null，行为不是「用错模型」
 * 而是「整轮静默不动」（2026-10-30 定时任务真机复现：后台注入的 user 消息落了库，assistant
 * 一条没有、也没有任何错误）。上游同名 helper 即此语义，MediaCreationService 早已在用。
 *
 * 调用方应加超时兜底（`withTimeoutOrNull`），避免 DataStore 持续读失败时把调用方挂死；
 * 超时后拿到的仍是 dummy，下游至少能显式报错而不是无声。
 */
suspend fun SettingsStore.awaitLoaded(): Settings = settingsFlow.first { !it.init }

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    // 停用的提供商不参与选择（见 TTSProviderSetting.enabled）。
    // 与 ASR 同款取舍：**选中项被停用时不静默回退**到别的提供商——用户既然关了它，
    // 就不该悄悄改用另一个可能计费的提供商，而是返回 null 让 TTS 侧如实置「不可用」。
    // 注：「一个启用的都不剩」在读路径已被 ensureFallbackTtsEnabled 拦掉（系统 TTS 兜底），
    // 只有用户把系统 TTS 也删掉时才可能真的没有可用项。
    // 契约实现 = Enableable.resolveSelected（与 ASR 共享一处）。
    return resolveSelected(ttsProviders, selectedTTSProviderId)
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    // 停用的提供商不参与选择（见 ASRProviderSetting.enabled）。
    // **选中项被停用时不静默回退**到别的提供商——用户既然关了它，就不该悄悄改用另一个
    // 可能计费的提供商；返回 null 让消费路径如实提示「请配置语音识别」
    // （听写 asr_error_not_configured / 语音模式 chat_page_voice_configure_asr）。
    // 契约实现 = Enableable.resolveSelected（与 TTS 共享一处）。
    return resolveSelected(asrProviders, selectedASRProviderId)
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(proxy = provider.proxy, models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}
