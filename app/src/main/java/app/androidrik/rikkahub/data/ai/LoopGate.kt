package app.androidrik.rikkahub.data.ai

/**
 * 收尾闸门（loop-end gate，`docs/design/design-deliverable-gate.md` §三）——
 * 让「没真的交付就不许结束」从提示词自觉变成可判定的闸门。
 *
 * **哲学约束**：本仓刻意「只提醒不拦截」（[RuleEngine] 三条规则全走注入），闸门是
 * **独立的 opt-in 机制**，只允许挂在「有明确任务、结束由模型宣告、跑完没人盯着」的场景
 * （子代理运行 / 定时任务会话；workspace 工作轮另议）。普通聊天**永不**挂闸——
 * 聊天路径的调用方不构造 [LoopGateConfig]，即不挂闸。
 *
 * 语义（PurrCat 原味）：检查类动作的唯一产出是往历史里追加一条 `role=user` 的提示；
 * 未通过 → 打回本轮继续；上限 [LOOP_GATE_MAX_RETRIES] 次强制放行；通过 → 静默。
 *
 * **判定分两档**：
 * - 确定性：声明了 [deliverables] 就查文件（[checkDeliverables] 返回缺失清单）——
 *   文件系统是天然验收器，模型没有解释权；
 * - 自检：没声明交付物时降级为「结束前自检」提示，模型的下一步若仍自然收束即视为通过
 *   （自检只是把「结束」变成一个显式动作，不是验收——见设计稿 §七 诚实边界 1）。
 */
class LoopGateConfig(
    /**
     * 交付物契约：相对 workspace 根的路径列表。**空 = 只有自检**（无确定性档）。
     * 非法声明（绝对路径 / `..` 越界）在 [DeliverableChecker.normalizeRelative] 阶段剔除。
     */
    val deliverables: List<String> = emptyList(),
    /**
     * 确定性校验：对每个声明路径返回缺失清单（空 = 全部通过）。实现方负责 workspace
     * 解析与 IO——本类与 [GenerationHandler] 保持场景无关、不做任何磁盘访问。
     */
    val checkDeliverables: suspend (List<String>) -> List<String> = { emptyList() },
) {
    /** 本轮闸门打回次数（含自检注入；run 结束后由调用方读取落账本/注记）。 */
    var retries: Int = 0
        private set

    /** 是否达上限强制放行（true = 交付物有缺失，见 [missingAtForcePass]）。 */
    var forcePassed: Boolean = false
        private set

    /** 强制放行时的缺失清单（[forcePassed] 为 false 时恒空）。 */
    var missingAtForcePass: List<String> = emptyList()
        private set

    internal fun recordRetry() {
        retries++
    }

    internal fun recordForcePass(missing: List<String>) {
        forcePassed = true
        missingAtForcePass = missing
    }
}

/** 打回上限：达上限强制放行，宁可带瑕疵交付，不可无限烧钱（设计稿 §七.4）。 */
const val LOOP_GATE_MAX_RETRIES = 3

/** 闸门在自然出口的裁决（内部，单测覆盖）。 */
internal sealed interface LoopGateVerdict {
    /** 通过：照旧收束本轮。 */
    data object Pass : LoopGateVerdict

    /** 打回：向历史追加 [prompt]（role=user）后继续本轮。 */
    data class Retry(val prompt: String) : LoopGateVerdict
}

/**
 * 出口裁决（**纯编排**，IO 全在 [LoopGateConfig.checkDeliverables] 里，便于 JVM 单测）。
 *
 * - 确定性档：缺失为空 → 通过；有缺失且未达上限 → 打回（缺失清单措辞）；
 *   达上限 → 强制放行（记录缺失清单，照旧收束）。
 * - 自检档：第一次到出口 → 注入自检提示打回；此后再自然收束 → 通过
 *   （「不过」的信号是模型发起实质动作——那是工具循环的自然继续，不归闸门管）。
 */
internal suspend fun evaluateLoopGate(gate: LoopGateConfig): LoopGateVerdict {
    if (gate.deliverables.isNotEmpty()) {
        val missing = gate.checkDeliverables(gate.deliverables)
        if (missing.isEmpty()) return LoopGateVerdict.Pass
        if (gate.retries < LOOP_GATE_MAX_RETRIES) {
            gate.recordRetry()
            return LoopGateVerdict.Retry(deliverableMissingPrompt(missing))
        }
        gate.recordForcePass(missing)
        return LoopGateVerdict.Pass
    }
    // 自检档：只注入一次——注入后模型仍选择收束即放行，反复注入只会烧 token
    if (gate.retries == 0) {
        gate.recordRetry()
        return LoopGateVerdict.Retry(SELF_CHECK_PROMPT)
    }
    return LoopGateVerdict.Pass
}

/**
 * 确定性缺失的打回措辞（设计稿 §四.1）。不打回整轮重跑（进度全在上下文里），
 * 只要求「补齐或明确说明」；不暗示工具失败（与 `ToolResultBalancingTransformer` 同一纪律）。
 */
internal fun deliverableMissingPrompt(missing: List<String>): String =
    "任务未结束：未检测到声明的交付物「${missing.joinToString("、")}」。" +
        "请补齐后再结束；若确实无法产出，明确说明原因。"

/** 自检档的打回措辞（设计稿 §四.2）。 */
internal const val SELF_CHECK_PROMPT: String =
    "结束前自检：本轮任务是否已实际完成并产出可用结果？若已完成，直接给出结论性回复；若没有，继续执行。"

/** 子代理强制放行时追加到 summary 尾部的注记（账本/父模型都能看到）。 */
internal fun forcePassNote(missing: List<String>): String =
    "交付物缺失：${missing.joinToString("、")}（闸门重试达上限，已强制放行）"

/**
 * 交付物路径的**纯校验/规范化**（设计稿 §2.2）。
 *
 * 「非空」= 存在且 size > 0，由调用方的 resolver 解释（本对象不做 IO）：
 * resolver 返回 `null` = 不存在；`<= 0` = 空。目录型交付 v1 不支持——声明到具体文件
 * （设计稿 §七.2：非文件类交付请把任务改造成「结论写进某文件」）。
 */
object DeliverableChecker {
    /**
     * 规范化一条声明：返回相对路径（去首尾 `/`）；非法（空白 / 绝对路径 / `..` 越界 /
     * 反斜杠）返回 null——**声明非法不是执行期错误**，是配置错误，直接剔除（宁缺勿炸）。
     */
    fun normalizeRelative(raw: String): String? {
        val trimmed = raw.trim().replace('\\', '/')
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("/")) return null
        val parts = trimmed.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** 批量规范化：剔除非法声明，保序去重。 */
    fun normalizeAll(raw: List<String>): List<String> =
        raw.mapNotNull(::normalizeRelative).distinct()
}
