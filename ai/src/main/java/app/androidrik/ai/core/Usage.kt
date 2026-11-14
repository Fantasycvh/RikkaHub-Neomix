package app.androidrik.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
    // DeepSeek 内置 web tools 的**计费次数**（usage.server_tool_use，实测 2026-11-14：
    // {tool_search, tool_fetch, tool_image_search}；¥5/千次各自）。
    // 旧消息 JSON 无这些字段 ⇒ 默认 0 零迁移。
    val toolSearch: Int = 0,
    val toolFetch: Int = 0,
    val toolImageSearch: Int = 0,
)

fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    val cachedTokens = if (other.cachedTokens > 0) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens,
        // 工具计数与 token 同口径：流式快照取最大值（服务端按轮累加计数）
        toolSearch = maxOf(other.toolSearch, this?.toolSearch ?: 0),
        toolFetch = maxOf(other.toolFetch, this?.toolFetch ?: 0),
        toolImageSearch = maxOf(other.toolImageSearch, this?.toolImageSearch ?: 0),
    )
}

/**
 * 多步工具轮的全轮口径累计（this = 前面各步的累计，other = 本步最终 usage）。
 *
 * **completionTokens 累加**：每步各自新生成的 tokens，全轮口径必须求和，否则终值
 * tok/s 拿"最后一步的 tokens ÷ 全轮时长"会严重失真。
 * **promptTokens / cachedTokens 取最新一步**：它反映最近一次请求的真实上下文体量，
 * 是占用显示与自动压缩触发依赖的"usage 锚点"——按步求和会翻倍虚高。
 * **工具计数累加**：每次搜索/抓取都是独立计费（¥5/千次），全轮必须求和。
 */
fun TokenUsage.accumulateRound(other: TokenUsage): TokenUsage {
    val promptTokens = other.promptTokens.takeIf { it > 0 } ?: promptTokens
    val completionTokens = completionTokens + other.completionTokens
    val cachedTokens = other.cachedTokens.takeIf { it > 0 } ?: cachedTokens
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = promptTokens + completionTokens,
        cachedTokens = cachedTokens,
        toolSearch = toolSearch + other.toolSearch,
        toolFetch = toolFetch + other.toolFetch,
        toolImageSearch = toolImageSearch + other.toolImageSearch,
    )
}
