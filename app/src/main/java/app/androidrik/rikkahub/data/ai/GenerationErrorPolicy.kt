package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.effectiveApiKeys

/**
 * 判断生成错误是否值得自动重试：
 * - 网络层异常（超时/DNS/连接失败/TLS 等 IOException 家族）→ 重试
 * - 响应解析失败（中转站返回 HTML 错误页等非 JSON 内容）→ 重试
 * - HTTP 408/425/429/5xx → 重试；401/403（鉴权）、400（参数）、404（模型不存在）→ 不重试
 *
 * provider 层抛的是字符串异常（如 "Failed to get response: 429 ..."），状态码从 message 提取。
 * **内容风控拦截不重试**：同样内容重发同样被拦（见 [CONTENT_BLOCKED_MARKERS]）。
 */
internal fun isRetryableGenerationError(t: Throwable): Boolean {
    // 内容风控/违禁词：确定性失败，须用户改内容——优先于网络/状态码判定（重发同样被拦）
    if (t.matchesAnyMarker(CONTENT_BLOCKED_MARKERS)) return false
    if (t is java.io.IOException) return true
    if (t is kotlinx.serialization.SerializationException) return true
    // 额度耗尽是确定性失败：余额不会自恢复，等待无意义——快速失败走换 key / fallback
    if (t.matchesAnyMarker(QUOTA_EXHAUSTED_MARKERS)) return false
    // 限流：等窗口重置可解（无状态码的 SSE 流内限流错误在此兜底）
    if (t.matchesAnyMarker(RATE_LIMITED_MARKERS)) return true
    val code = findHttpStatusCode(t.message) ?: return false
    return code == 408 || code == 425 || code == 429 || code in 500..599
}

/**
 * 换 key 可解决的错误：401/403（鉴权/凭证失效）+ 429（账户级限流，多账户场景换 key 可避开），
 * 以及无状态码的 SSE 流内错误文本兜底（中转站 200 + error 事件）——额度耗尽/限流都是账户级问题。
 * 到此处的一定是零输出（流式半截 429 已被 #1812 分流拦截），换 key 无计费损失。
 *
 * **例外（内容风控拦截）**：网关按内容判定违禁并返回 403 时，问题在**请求内容**而非凭证——
 * 所有 key 都会撞同一道防火墙，换 key 必然全败且把额度/时间白耗在轮换上。故先行排除（见
 * [CONTENT_BLOCKED_MARKERS]）。这类错误也不重试（同样内容同样被拦）。
 */
internal fun isKeySwitchingError(t: Throwable): Boolean {
    if (t.matchesAnyMarker(CONTENT_BLOCKED_MARKERS)) return false
    val code = findHttpStatusCode(t.message)
    if (code == 401 || code == 403 || code == 429) return true
    return t.matchesAnyMarker(QUOTA_EXHAUSTED_MARKERS) || t.matchesAnyMarker(RATE_LIMITED_MARKERS)
}

/**
 * 内容风控/违禁词拦截特征：网关按**内容**拒绝，与凭证无关。
 *
 * 实测样例（中转站/网关 403）：「触发网站风控违禁词，无法调用模型：内容命中网关内容防火墙规则
 * [关键词：...]，已被拦截。请修改内容后重试。」——**换 key 无用**（同一防火墙）、**重试无用**
 * （同样内容同样被拦），只能由用户改内容。若不加此判据，会被 403 规则误判为凭证失效而轮换所有 key。
 *
 * 注意：网关多按**子串**匹配（如 `sb`、`sm`、`anal` 会命中 `val sb = StringBuilder()`、`analyze`
 * 等普通标识符），故用户侧报错常显「莫名其妙」；这是网关行为，客户端无法规避，只能如实归类。
 */
private val CONTENT_BLOCKED_MARKERS = listOf(
    // 中文网关/中转站（"风控" 是内容风控惯用词）
    "风控", "违禁词", "内容防火墙", "已被拦截", "命中网关", "请修改内容后重试",
    // 国际厂商内容策略
    "content policy", "content_policy_violation", "content filter", "content moderation",
    "moderation_blocked", "safety system", "flagged by", "responsible ai",
)

/** 账户额度耗尽特征：等待无用（余额不自恢复），换 key（换账户）或 fallback 模型可解 */
private val QUOTA_EXHAUSTED_MARKERS = listOf(
    "quota is not enough",       // new-api 系中转站（实测复制样例）
    "insufficient quota",        // OpenAI 官方系
    "insufficient_quota",
    "exceeded your current quota",
    "quota exceeded",
    "余额不足", "额度不足",
)

/** 限流特征（TPM/RPM/速率）：等窗口重置或换 key（换账户）可解；锚定写法防 "tpm-mini" 之类误伤 */
private val RATE_LIMITED_MARKERS = listOf(
    "rate limit", "rate_limit", "ratelimit",
    "too many requests",
    "tpm/rpm", "exceeds tpm", "exceeds rpm", "tpm limit", "rpm limit",
    "请求过于频繁", "触发限流",
)

/**
 * 「输入上下文超窗」特征（design-context-boundaries-and-overflow-recovery §3.1，OpenHands
 * SDK `classifier.py` 的 `LONG_PROMPT_PATTERNS` 全量移植，小写子串匹配、不限状态码——
 * SDK 对 InternalServerError 也查文本，部分网关用 5xx 携带超窗文案）。
 *
 * 命中且**零增量**（请求被拒在生成之前，见 [GenerationHandler] 的恢复编排）时触发一次
 * 压缩恢复重发同一步。`finish_reason=length` 一族是**输出**截断（走续写），异常文本不在此列。
 *
 * 存档（SDK `MALFORMED_HISTORY_PATTERNS`，工具配对类 400）：`tool_use ids were found without
 * tool_result blocks` / `each tool_use must have a single result` / `must be followed by tool
 * messages` 等——**不进 v1 恢复**（设计稿 §3.1：按历史结构问题处理，走现行错误路径）；
 * 阶段一的守卫六 + `alignCutIndex` 已从源头堵这类请求形态，确有样本再扩类。
 */
/**
 * 输入上下文超窗的两档置信度（NMoi 11-17 轮超窗撤回判据）：
 * - [DEFINITE]：明确指出输入上下文/token 超过窗口上限（可安全触发反应式压缩或摘最大工具结果重发）；
 * - [POSSIBLE]：仅提示载荷/请求体过大（如裸 HTTP 413 / payload too large，可能来自图片/字段限制而非上下文窗口），
 *   不盲目摘除工具结果；
 * - [NONE]：非超窗错误。
 */
internal enum class OverflowConfidence {
    DEFINITE,
    POSSIBLE,
    NONE,
}

private val CONTEXT_OVERFLOW_MARKERS = listOf(
    "contextwindowexceedederror",                          // litellm 异常类名透传
    "prompt is too long",                                  // Anthropic 官方措辞
    "input length and `max_tokens` exceed context limit",  // Anthropic v1
    "please reduce the length of",                         // OpenAI 官方措辞（maximum context length ...）
    "maximum context length",                              // OpenAI / 兼容层常见措辞
    "exceeds the available context size",                  // Azure / 旧版措辞
    "context length exceeded",                             // OpenAI 兼容层常见
    "input exceeds the context window",                    // 通用措辞
    "context window exceeds limit",                        // Minimax
    "token limit exceeded",                                // 常见网关/兼容层
    "too many tokens",                                     // 常见 token 超限措辞
)

/** 可能是超窗但也可能是请求体/附件体积限制的模糊特征（仅归入 [OverflowConfidence.POSSIBLE]）。 */
private val POSSIBLE_OVERFLOW_MARKERS = listOf(
    "payload too large",
    "request entity too large",
    "request body too large",
    "content too large",
)

/** 将异常归类为「确定超窗 / 也许超窗 / 非超窗」三态（仅确定档触发反应式恢复与工具结果撤回）。 */
internal fun classifyInputContextOverflow(t: Throwable): OverflowConfidence {
    if (t.matchesAnyMarker(CONTEXT_OVERFLOW_MARKERS)) return OverflowConfidence.DEFINITE
    if (t.matchesAnyMarker(POSSIBLE_OVERFLOW_MARKERS) || findHttpStatusCode(t.message) == 413) {
        return OverflowConfidence.POSSIBLE
    }
    return OverflowConfidence.NONE
}

/** provider 报「确定输入上下文超窗」类错误（判据见 [CONTEXT_OVERFLOW_MARKERS]）。 */
internal fun isInputContextOverflowError(t: Throwable): Boolean =
    classifyInputContextOverflow(t) == OverflowConfidence.DEFINITE

private fun Throwable.matchesAnyMarker(markers: List<String>): Boolean {
    val message = this.message?.lowercase() ?: return false
    return markers.any { it in message }
}

/** 该提供商是否配置了多个可用 key（enabled 过滤后） */
internal fun hasMultipleEnabledKeys(providerSetting: ProviderSetting): Boolean {
    // 账号类没有 API Key 轮询
    val keys = (providerSetting as? ProviderSetting.ApiKey)?.apiKeys ?: return false
    return keys.count { it.enabled && it.key.isNotBlank() } > 1
}

/**
 * 换 key 重试的轮转：去掉首个生效 key（OrderKeyRoulette 固定取首个 = 刚失败的那个），
 * 剩余 key 写回瞬态副本（apiKeys + 旧 apiKey 字段同时覆盖，兼容旧字符串多 key）。
 * 副本仅用于本次重试循环内传给 provider，不落盘。只剩 ≤1 个 key 时返回 null（无可切换）。
 */
internal fun rotateProviderKeys(setting: ProviderSetting): ProviderSetting? {
    val keys = splitEffectiveKeys(setting.effectiveApiKeys)
    if (keys.size <= 1) return null
    val remaining = keys.drop(1)
    val entries = remaining.map { ApiKeyEntry(it) }
    val legacy = remaining.joinToString("\n")
    val apiKeySetting = setting as? ProviderSetting.ApiKey ?: return null
    return when (apiKeySetting) {
        is ProviderSetting.OpenAI -> apiKeySetting.copy(apiKeys = entries, apiKey = legacy)
        is ProviderSetting.Google -> apiKeySetting.copy(apiKeys = entries, apiKey = legacy)
        is ProviderSetting.Claude -> apiKeySetting.copy(apiKeys = entries, apiKey = legacy)
    }
}

/** 拆分 effectiveApiKeys 字符串（与 KeyRoulette.splitKey 同规则：空白/逗号分隔 + 去重） */
internal fun splitEffectiveKeys(keys: String): List<String> =
    keys.split("[\\s,]+".toRegex()).map { it.trim() }.filter { it.isNotBlank() }.distinct()

/** 从错误 message 提取 HTTP 状态码：优先 "code/status: NNN" 形态，回退到首个独立的 3 位数字。 */
private fun findHttpStatusCode(message: String?): Int? {
    if (message == null) return null
    val prefixed = Regex("""(?:code|status|HTTP)[\s:=]*(\d{3})""", RegexOption.IGNORE_CASE)
        .find(message)?.groupValues?.get(1)?.toIntOrNull()
    if (prefixed != null) return prefixed
    // 回退：形如 ": 429"、">502"、"#401" 的独立三位数（覆盖中转站 HTML 错误页正文与搜索服务 "#${code}" 形态）
    return Regex("""[\s:>(#](\d{3})\b""").find(message)?.groupValues?.get(1)?.toIntOrNull()
}
