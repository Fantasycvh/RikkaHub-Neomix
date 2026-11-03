package app.androidrik.ai.core

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

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
    /** Named, independently selectable mappings; legacy scalar overrides remain unchanged. */
    val presets: List<ReasoningPreset> = emptyList(),
)

@Serializable
data class ReasoningPreset(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val level: ReasoningLevel = ReasoningLevel.HIGH,
    val effort: String? = null,
    val budgetTokens: Int? = null,
)

/** Model mappings take precedence over provider mappings with the same persistent ID. */
fun reasoningPresets(model: Model, provider: ProviderSetting?): List<ReasoningPreset> =
    (model.reasoningOverride?.presets.orEmpty() + provider?.reasoningOverride?.presets.orEmpty())
        .distinctBy { it.id }

/** Resolve per request, so edits/deletions and model switches cannot leave stale parameter values. */
fun resolveReasoningPreset(model: Model, provider: ProviderSetting?, id: String?): ReasoningPreset? =
    id?.let { selected -> reasoningPresets(model, provider).find { it.id == selected } }

fun Model.withReasoningPreset(preset: ReasoningPreset?): Model = if (preset == null) this else copy(
    reasoningOverride = ReasoningOverride(effort = preset.effort, budgetTokens = preset.budgetTokens),
)

/** 解析生效的覆盖：模型级优先，提供商级兜底，都为空返回 null（= 全跟随内置映射） */
fun resolveReasoningOverride(
    model: Model,
    providerSetting: ProviderSetting?,
): ReasoningOverride? = model.reasoningOverride.scalarOverride() ?: providerSetting?.reasoningOverride.scalarOverride()

/** A list of presets alone must not mask the provider's legacy fallback. */
fun ReasoningOverride?.scalarOverride(): ReasoningOverride? = this?.takeUnless {
    it.presets.isNotEmpty() && it.effort == null && it.budgetTokens == null
}
