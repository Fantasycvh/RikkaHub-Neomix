package app.androidrik.rikkahub.data.ai.openai

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.OpenAIAuthType
import app.androidrik.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * ChatGPT 订阅（Codex 后端）对话的**提供商锁定**。
 *
 * Codex 后端返回的推理是 `encrypted_content` 加密块，只有该后端能解读；其它 provider 既解不开、
 * 也无法回传（送过去会 400/静默丢弃）。因此一旦某条对话里已经落了订阅模型生成的消息，后续换模型
 * **只能在同一提供商内**挑，不能切到别的 provider（切过去等于把加密推理扔给外人）。
 *
 * 判定依据是**对话历史**而不是"当前选中的模型"：模型选择是助手级的（`assistant.chatModelId`），
 * 若按当前模型判定，用户把助手模型改成订阅模型后，该助手所有历史对话都会立刻被锁住、再也切不回去。
 * 只有真正产生过订阅模型回复的对话才需要锁。
 *
 * [providerId] 是锁定到的提供商 id；[providerName] 只用于提示文案。
 */
data class SubscriptionProviderLock(
    val providerId: Uuid,
    val providerName: String,
) {
    /** [model] 是否属于被锁定的提供商（同 provider 换模型是允许的）。 */
    fun allows(model: Model, providers: List<ProviderSetting>): Boolean {
        val provider = providers.firstOrNull { it.id == providerId } ?: return false
        return provider.models.any { it.id == model.id }
    }

    /** 模型选择器的候选提供商：只保留被锁定的那一个。 */
    fun filterProviders(providers: List<ProviderSetting>): List<ProviderSetting> =
        providers.filter { it.id == providerId }
}

/**
 * 发送前检查：未锁定（null）恒可发；锁定后只有该提供商的模型能继续这条对话。
 *
 * 抽成独立函数是为了让「发送拦截」这条规则能被 JVM 单测覆盖（Composable 里的
 * 内联判断测不到），也避免 UI 层漏写某个发送入口。
 */
fun SubscriptionProviderLock?.allowsSendingWith(
    model: Model?,
    providers: List<ProviderSetting>,
): Boolean {
    if (this == null) return true
    if (model == null) return true
    return allows(model, providers)
}

/**
 * 解析当前对话的订阅锁定。
 *
 * 触发条件：对话历史里有消息由某个**已启用**的订阅提供商生成。订阅提供商包括：
 * - 迁移前的旧形态 `ProviderSetting.OpenAI(authType = CHATGPT_SUBSCRIPTION)`：P5 删除旧链路之前
 *   仍可能有存量数据/回退安装，保留识别；
 * - 迁移后的账号类 `ProviderSetting.ChatGPT`：需要已登录（[ProviderSetting.Account.credential]
 *   非 null）——未登录的提供商发不出请求，锁在它上面只会让这条对话彻底没法继续。
 *
 * 没有订阅模型生成过的对话返回 null（普通对话不受影响，空对话也不会一选订阅模型就被锁住）。
 *
 * @param historyModelIds 对话历史各条消息的 `modelId`（[app.androidrik.ai.ui.UIMessage.modelId]，
 *   用户消息为 null，调用方直接过滤掉即可）。
 */
fun resolveSubscriptionProviderLock(
    providers: List<ProviderSetting>,
    historyModelIds: Collection<Uuid>,
): SubscriptionProviderLock? {
    if (historyModelIds.isEmpty()) return null

    val subscriptionProviders = providers.filter { it.isSubscriptionProvider() }
    if (subscriptionProviders.isEmpty()) return null

    val generatedBySubscription = historyModelIds.toSet()
    val locked = subscriptionProviders.firstOrNull { provider ->
        provider.models.any { it.id in generatedBySubscription }
    } ?: return null

    return SubscriptionProviderLock(
        providerId = locked.id,
        providerName = locked.name,
    )
}

/**
 * 该 provider 是否会产出「只有自家后端能解读的加密推理」（需要锁定对话）。
 *
 * 目前只有 ChatGPT 订阅：旧 OpenAI 订阅态与迁移后的
 * [ProviderSetting.ChatGPT] 账号类。Grok 等其它账号类不在此列（是否也锁定是独立设计决策，
 * 见 P4 报告未决问题）。
 */
private fun ProviderSetting.isSubscriptionProvider(): Boolean = when (this) {
    is ProviderSetting.OpenAI -> enabled && authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION
    is ProviderSetting.ChatGPT -> enabled && credential != null && models.isNotEmpty()
    else -> false
}
