package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * **UI 专用提示标记**：部分文本 part 只是给用户看的提示条（生成被打断 / 输出被截断），
 * 带 `uiNotice=true` 元数据。
 *
 * **为什么要有这个标记**：这些提示是**过程事实**（"这次没生成完"），对用户有价值、对模型是
 * 噪声——回传进去会让模型以为用户说过那句话，甚至模仿着往下写。故发请求前统一过滤
 * （本仓在 `PromptAssembly.buildInternalMessages` 这**唯一装配点**过滤，四个 provider 都从这里取
 * 消息，不必逐个改 provider 的 builder）。
 *
 * 附带好处：落库的仍是完整消息（含提示），所以**导出/备份/上传都保留提示**——用户回看时
 * 知道当时发生了什么（这与"提示不污染模型上下文"是两件事，不要混淆）。
 */
const val UI_NOTICE_METADATA_KEY = "uiNotice"

/** 生成被打断的提示标记（与 [UI_NOTICE_METADATA_KEY] 同时存在，便于将来区分提示种类）。 */
const val INTERRUPTED_NOTICE_METADATA_KEY = "interruptedNotice"

private fun JsonObject?.flag(key: String): Boolean =
    (this?.get(key) as? JsonPrimitive)?.content == "true"

/** 该 part 是否为纯 UI 提示（发请求时应过滤）。 */
val UIMessagePart.isUiNotice: Boolean
    get() = metadata.flag(UI_NOTICE_METADATA_KEY)

/** 该 part 是否为「生成被打断」提示。 */
val UIMessagePart.isInterruptedNotice: Boolean
    get() = metadata.flag(INTERRUPTED_NOTICE_METADATA_KEY)

/**
 * 生成被打断时，给末尾助手消息追加**可见提示**（幂等）。
 *
 * 现状：用户停止（或新消息打断）时我们会把已生成的部分落库（见 `ChatService.persistStoppedGeneration`），
 * 但**消息上没有任何标记**——回看时那段戛然而止的回复看起来就像「卡住了」，用户无从判断是
 * 模型抽风、网络断了、还是自己点了停止。
 *
 * 刻意不追加的情形：
 * - 末尾不是助手消息（用户刚发了新消息，打断的是上一条之外的轮次）；
 * - **没有任何可用内容**（零输出被打断：此时提示等于把「空回复」伪装成「有回复」，
 *   与既有 `GenerationPartialFailureException` 的判定口径也不一致）；
 * - 已有打断提示（幂等，重复 stop 不叠加）。
 *
 * @return (新消息列表, 是否发生了追加)；未追加时返回原列表与 false，调用方可据此跳过落库。
 */
fun List<UIMessage>.appendInterruptionNotice(noticeText: String): Pair<List<UIMessage>, Boolean> {
    val last = lastOrNull() ?: return this to false
    if (last.role != MessageRole.ASSISTANT) return this to false

    val hasUsableContent = last.parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
            else -> false
        }
    }
    if (!hasUsableContent) return this to false
    if (last.parts.any { it.isInterruptedNotice }) return this to false

    val notice = UIMessagePart.Text(
        text = noticeText,
        metadata = buildJsonObject {
            put(UI_NOTICE_METADATA_KEY, JsonPrimitive(true))
            put(INTERRUPTED_NOTICE_METADATA_KEY, JsonPrimitive(true))
        },
    )
    return (dropLast(1) + last.copy(parts = last.parts + notice)) to true
}

/**
 * 去掉消息里的 UI 提示 part（发请求前用）。
 *
 * 只在**过滤后会留下内容**时才返回裁剪结果；否则原样返回——避免把一条只剩提示的消息变成
 * 空消息（协议上 assistant 消息既无内容又无工具调用是非法/无意义的）。调用方与本函数共同
 * 保证「提示不改变发出去的对话结构」。
 */
fun UIMessage.withoutUiNotices(): UIMessage {
    if (parts.none { it.isUiNotice }) return this
    val kept = parts.filterNot { it.isUiNotice }
    if (kept.isEmpty()) return this
    return copy(parts = kept)
}
