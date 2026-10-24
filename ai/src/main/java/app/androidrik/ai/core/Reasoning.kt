package app.androidrik.ai.core

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReasoningLevel(
    val budgetTokens: Int,
    val effort: String
) {
    @SerialName("off")
    OFF(0, "none"),
    @SerialName("auto")
    AUTO(-1, "auto"),
    @SerialName("low")
    LOW(1_000, "low"),
    @SerialName("medium")
    MEDIUM(2_000, "medium"),
    @SerialName("high")
    HIGH(8_000, "high"),
    @SerialName("xhigh")
    XHIGH(16_000, "xhigh"),
    @SerialName("max")
    MAX(32_000, "max");

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        fun fromBudgetTokens(budgetTokens: Int?): ReasoningLevel {
            return entries.minByOrNull { kotlin.math.abs(it.budgetTokens - (budgetTokens ?: AUTO.budgetTokens)) } ?: AUTO
        }
    }
}

/**
 * 思考强度映射的**用户覆盖**（按模型，或按提供商兜底）。
 *
 * 背景：档位 → 各家 API 参数的映射是代码里按 host/系列写死的（effort 词表、budget 数值），
 * 中转站/私部署的方言经常对不上（如要求非标准 effort 文本、或 thinking_budget 落在特定区间）。
 * 两个自由度分别覆盖映射里对应的值；null = 该自由度跟随内置映射。
 *
 * 解析链：[modelReasoningOverride] → [providerReasoningOverride]（提供商兜底）→ 内置映射。
 * 只影响「选中了具体档位」（enabled 且非 AUTO）时的取值；OFF/AUTO 的开关语义不变。
 */
@Serializable
data class ReasoningOverride(
    /** 覆盖各方言里 effort 字符串（reasoning_effort / thinkingLevel / output_config.effort） */
    val effort: String? = null,
    /** 覆盖 budget 数值（thinking_budget / thinkingBudget） */
    val budgetTokens: Int? = null,
)

/** 解析生效的覆盖：模型级优先，提供商级兜底，都为空返回 null（= 全跟随内置映射） */
fun resolveReasoningOverride(
    model: Model,
    providerSetting: ProviderSetting?,
): ReasoningOverride? = model.reasoningOverride ?: providerSetting?.reasoningOverride

