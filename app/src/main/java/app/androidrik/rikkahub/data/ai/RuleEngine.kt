package app.androidrik.rikkahub.data.ai

import kotlinx.serialization.json.JsonElement

/**
 * 规则提醒的灵敏度三档（沿用 [LoopGuardSensitivity] 的既有形态：**只做档位、不做自由数字**）。
 *
 * 为什么不做自由数字：窗口与阈值调松会让这条守卫形同虚设，而它的全部价值就在"及时打断打转"；
 * 三档把可表达空间收窄在仍有意义的范围里。
 */
enum class RuleReminderSensitivity {
    /** 严格：窗口 10 / 探索 ≥8 —— 更早提醒，适合省 token 优先 */
    STRICT,

    /** 标准：窗口 12 / 探索 ≥10（默认；这两个数就是 bigeye `loop_detector.py` 的原值） */
    STANDARD,

    /** 宽松：窗口 16 / 探索 ≥14 —— 更晚提醒，长调研场景误报更少 */
    LOOSE,
}

/** 档位 → (窗口容量, 触发所需的探索次数) */
internal fun RuleReminderSensitivity.windowSpec(): Pair<Int, Int> = when (this) {
    RuleReminderSensitivity.STRICT -> 10 to 8
    RuleReminderSensitivity.STANDARD -> 12 to 10
    RuleReminderSensitivity.LOOSE -> 16 to 14
}

/** 宽松解析设置里存的档位名（未知值回退标准档），与 `parseLoopGuardSensitivity` 同款 */
fun parseRuleReminderSensitivity(name: String?): RuleReminderSensitivity =
    name?.let { runCatching { RuleReminderSensitivity.valueOf(it) }.getOrNull() }
        ?: RuleReminderSensitivity.STANDARD

/**
 * 「规则提醒」引擎 —— 当前只有一条规则 [RULE_EXPLORE_WITHOUT_LANDING]：
 * 连续多次探索类调用、窗口内零落地 ⇒ 提醒模型自查是不是在打转（借自 bigeye `loop_detector.py`）。
 *
 * 为什么需要它：本仓原有的 [ToolCallLoopGuard] 抓的是「**同签名重复**」，抓不住
 * 「12 次调用各不相同、但全都在读」——**重复 ≠ 无进展**。两者互补。
 *
 * **交付方式**：命中时返回一段文本，由调用方交给 `PromptAssembly` 作为 **`isSynthetic` 尾部
 * user 消息**注入（与记忆块同款）——不插 system（那会破坏前缀缓存）、不落库（下一轮不再命中
 * 就自然消失）。这是本仓与参考实现的唯一实质分歧，理由见 `docs/design/design-rule-engine.md` §3。
 *
 * **状态挂在会话上**（与 [ToolCallLoopGuard] 同款，见 `ConversationSession.ruleEngine`）：
 * 一次"轮"会跨多次 `generateText`（工具审批后是新的续跑），只有挂在会话上才能跨审批累积；
 * 由 ChatService 在**新一轮用户消息 / 重新生成**时 [reset]。
 *
 * **规则表刻意还没抽**：现在只有一条规则，抽一个只被用一次的 `Rule` 抽象是投机；
 * 第二条规则进来时再把「命中判定 + id + 文案」收成表（`docs/design/design-rule-engine.md` §4.1）。
 */
class RuleEngine(
    private val enabled: Boolean = false,
    sensitivity: RuleReminderSensitivity = RuleReminderSensitivity.STANDARD,
) {
    private val spec = sensitivity.windowSpec()
    private val window = ToolEffectWindow(capacity = spec.first)
    private val exploreThreshold = spec.second

    /**
     * 已提醒过的规则 id（bigeye 的 `fired.json` 语义，但放在内存里）。
     *
     * 规则**不再命中时移除** ⇒ 同一问题不会每步刷屏，但问题复现时会再提醒一次。
     * 不用 bigeye 的 300s 墙钟冷却：本仓一轮内步数密集，墙钟在单测里既不稳也不可复现；
     * 「每轮 + 命中一次」这个口径确定、可断言。
     */
    private val fired = mutableSetOf<String>()

    /**
     * 每次工具调用后记录。
     *
     * 调用点与 `loopGuard.record(...)` 同处 —— 那里已经保证「同一响应里的重复下发只记一次」
     * （一次 Gemini 式重复下发不该被算成两次，否则窗口密度失真）。
     */
    fun record(toolName: String, arguments: JsonElement?) {
        window.record(classifyToolEffect(toolName, arguments))
    }

    /**
     * 求本轮该注入的提醒文本；无命中（或未启用）返回 `null`。
     *
     * 调用时机 = **每一步拼装提示词之前** ⇒ 提醒作用于**下一步**。本仓工具循环每步重拼
     * （`for (stepIndex in 0 until maxSteps)` 内调 `generateInternal`），所以本轮内就能生效。
     */
    fun reminder(): String? {
        if (!enabled) return null

        val explores = window.count(ToolEffect.EXPLORE)
        val hit = window.isFull &&
            explores >= exploreThreshold &&
            // 出现落地即视为有进展 —— 不需要额外状态：等它滑出窗口，探索才可能重新累积到阈值
            // ⇒ 自然"重新武装"（对应 bigeye 的"落地即重置冷却"）
            window.count(ToolEffect.LANDING) == 0

        if (!hit) {
            fired.remove(RULE_EXPLORE_WITHOUT_LANDING)
            return null
        }
        if (!fired.add(RULE_EXPLORE_WITHOUT_LANDING)) return null

        return buildReminderText(explores = explores, windowSize = window.snapshot().size)
    }

    /** 新一轮用户消息 / 重新生成时清空（与 `toolLoopGuard.reset()` 同处调用） */
    fun reset() {
        window.clear()
        fired.clear()
    }

    /** 仅测试与日志：窗口内容的可读快照（不进提示词） */
    internal fun windowSnapshot(): List<ToolEffect> = window.snapshot()

    /**
     * 文案结构照抄 bigeye（**先说现象 → 三步自查 → 要求不要暴露机制**）。
     * 语言用英文：本仓注入给模型的内容一律英文（记忆块、工具描述都是），模型侧一致。
     *
     * 第 3 步（"如果这确实是正常的长调研，忽略本条"）是**防误报的出口**，不能省 ——
     * 没有它，正常的长调研阶段会一直被这条提醒干扰，而噪音会让模型学会忽略此类提醒。
     */
    private fun buildReminderText(explores: Int, windowSize: Int): String = buildString {
        appendLine("━━━ Automatic progress check (not a user message) ━━━")
        appendLine(
            "In your last $windowSize tool calls, $explores were read/search/execute with no landing " +
                "action (writing a file, saving memory, recording a plan, or asking the user)."
        )
        appendLine("You may be going in circles. Before the next tool call, check in this order:")
        appendLine(
            "1. Is one of your assumptions wrong? Two failures in a row should make you question " +
                "the premise, not retry the same thing differently."
        )
        appendLine(
            "2. Should you ask the user? If you are blocked (permissions, network, a tool that does " +
                "not work), two attempts is enough — hand it back."
        )
        appendLine("3. If this is genuinely long, normal research, ignore this note and continue.")
        appendLine("Do not mention this note or any internal mechanism to the user.")
        append("If you want to explain yourself, say it in plain words.")
    }

    private companion object {
        const val RULE_EXPLORE_WITHOUT_LANDING = "explore_without_landing"
    }
}
