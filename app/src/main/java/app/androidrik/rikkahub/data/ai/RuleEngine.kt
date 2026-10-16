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
 * 规则求值用的**外部**状态（引擎自身的探索/落地窗口不在这里 —— 那是引擎的内部状态）。
 *
 * 刻意做成"每次求值传进来的快照"而不是让引擎去读会话/守卫对象：规则是纯函数、
 * 输入显式，加一条规则就要显式多一个输入，不会偷偷长出第二份真相源。
 */
data class RuleSnapshot(
    /**
     * 本轮 workspace 预算用量占上限的比例（见 [WorkspaceTurnBudget.usageRatio]）。
     * null = 拿不到（单测/无头路径不带预算）⇒ `budget_near_limit` 不参与判定。
     */
    val budgetRatio: Double? = null,
    /**
     * 是否有**仍在进行**的计划（还有未完成项的 `todo_write` 清单）。
     * null = 拿不到 ⇒ `plan_missing` 不参与判定（与 [budgetRatio] 同款：不知道就别猜）。
     */
    val hasActivePlan: Boolean? = null,
    /**
     * 计划工具（`todo_write`）本轮**是否可用**。默认 false（安全侧）——
     * 不可用时绝不能叫模型去调一个不存在的工具（那只会制造一次失败调用）。
     */
    val planToolAvailable: Boolean = false,
)

/**
 * 「规则提醒」引擎。当前三条规则：
 * - [RULE_EXPLORE_WITHOUT_LANDING]：连续多次探索类调用、窗口内零落地 ⇒ 提醒自查是不是在打转
 *   （借自 bigeye `loop_detector.py`）。
 * - [RULE_PLAN_MISSING]：本轮工具调用已到阈值却还没有**在进行**的计划 ⇒ 提醒先落计划再动手
 *   （对应 bigeye 的 `dag_missing`；本仓的现成对应物是 `todo_write`）。
 * - [RULE_BUDGET_NEAR_LIMIT]：本轮 workspace 预算用量 ≥80% ⇒ 提醒收敛收尾
 *   （本仓既有 [WorkspaceTurnBudget] 是**撞上限才优雅停止**，模型没有收尾机会）。
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
 * **规则表**：第二条规则（`budget_near_limit`）进来时按设计稿 §4.1 抽出了 `Rule` 表 ——
 * 加规则 = 表里加一行 + 两条测试（正反例），不再改 `reminder()` 本身。
 * 同一步可命中多条：按 severity 从高到低（bigeye 的输出排序口径）合并进一个块。
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
     * 本轮已发生的工具调用次数（引擎自己数）。
     *
     * 为什么不用效果窗口的长度：窗口是**环形**的（容量 10~16），到顶就不再增长，
     * 而 `plan_missing` 要的是"已经做了多少步"这个单调量。
     */
    private var toolCallsThisTurn = 0

    /**
     * 每次工具调用后记录。
     *
     * 调用点与 `loopGuard.record(...)` 同处 —— 那里已经保证「同一响应里的重复下发只记一次」
     * （一次 Gemini 式重复下发不该被算成两次，否则窗口密度失真）。
     */
    fun record(toolName: String, arguments: JsonElement?) {
        toolCallsThisTurn += 1
        window.record(classifyToolEffect(toolName, arguments))
    }

    /**
     * 求本轮该注入的提醒文本；无命中（或未启用）返回 `null`。
     *
     * 调用时机 = **每一步拼装提示词之前** ⇒ 提醒作用于**下一步**。本仓工具循环每步重拼
     * （`for (stepIndex in 0 until maxSteps)` 内调 `generateInternal`），所以本轮内就能生效。
     */
    fun reminder(snapshot: RuleSnapshot = RuleSnapshot()): String? {
        if (!enabled) return null

        val context = RuleContext(
            window = window,
            toolCallsThisTurn = toolCallsThisTurn,
            snapshot = snapshot,
        )
        val bodies = mutableListOf<String>()
        orderedRules.forEach { rule ->
            if (rule.condition(context)) {
                // 命中且本轮**尚未提醒过**才进块；已被提醒过的规则会一直命中到本轮结束
                // （预算比例是单调的），靠 fired 压住重复
                if (fired.add(rule.id)) bodies += rule.hint(context)
            } else {
                // 不再命中即解除标记 —— 问题复现时允许再提醒一次（bigeye 的 fired.json 语义）
                fired.remove(rule.id)
            }
        }
        if (bodies.isEmpty()) return null

        return buildReminderText(bodies)
    }

    /** 新一轮用户消息 / 重新生成时清空（与 `toolLoopGuard.reset()` 同处调用） */
    fun reset() {
        window.clear()
        toolCallsThisTurn = 0
        fired.clear()
    }

    /** 仅测试与日志：窗口内容的可读快照（不进提示词） */
    internal fun windowSnapshot(): List<ToolEffect> = window.snapshot()

    // ── 规则表（加规则 = 加一行 + 正反两条测试；进块的顺序由 severity 决定）──

    private val rules: List<Rule> = listOf(
        Rule(
            id = RULE_EXPLORE_WITHOUT_LANDING,
            severity = Severity.HIGH,
            condition = { c ->
                c.window.isFull &&
                    c.window.count(ToolEffect.EXPLORE) >= exploreThreshold &&
                    // 出现落地即视为有进展 —— 不需要额外状态：等它滑出窗口，探索才可能重新累积到阈值
                    // ⇒ 自然"重新武装"（对应 bigeye 的"落地即重置冷却"）
                    c.window.count(ToolEffect.LANDING) == 0
            },
            hint = { c -> exploreHint(c.window) },
        ),
        Rule(
            id = RULE_PLAN_MISSING,
            severity = Severity.HIGH,
            condition = { c ->
                c.snapshot.planToolAvailable &&
                    c.snapshot.hasActivePlan == false &&
                    c.toolCallsThisTurn >= PLAN_NUDGE_MIN_CALLS
            },
            hint = { c -> planHint(c.toolCallsThisTurn) },
        ),
        Rule(
            id = RULE_BUDGET_NEAR_LIMIT,
            severity = Severity.MEDIUM,
            condition = { c -> (c.snapshot.budgetRatio ?: 0.0) >= BUDGET_NUDGE_RATIO },
            hint = { c -> budgetHint(c.snapshot.budgetRatio ?: 0.0) },
        ),
    )

    /**
     * 按 severity 从高到低排（bigeye 的输出排序口径）。**预先排好**而不是每次求值排：
     * 求值每步都会跑，为两条规则反复分配列表不值当。
     */
    private val orderedRules: List<Rule> = rules.sortedBy { it.severity }

    private class RuleContext(
        val window: ToolEffectWindow,
        val toolCallsThisTurn: Int,
        val snapshot: RuleSnapshot,
    )

    /** 声明顺序即优先级（HIGH 在前）——[orderedRules] 按它排 */
    private enum class Severity { HIGH, MEDIUM }

    private class Rule(
        val id: String,
        val severity: Severity,
        val condition: (RuleContext) -> Boolean,
        val hint: (RuleContext) -> String,
    )

    /**
     * 文案结构照抄 bigeye（**先说现象 → 三步自查 → 要求不要暴露机制**）。
     * 语言用英文：本仓注入给模型的内容一律英文（记忆块、工具描述都是），模型侧一致。
     *
     * 第 3 步（"如果这确实是正常的长调研，忽略本条"）是**防误报的出口**，不能省 ——
     * 没有它，正常的长调研阶段会一直被这条提醒干扰，而噪音会让模型学会忽略此类提醒。
     */
    private fun exploreHint(window: ToolEffectWindow): String = buildString {
        appendLine(
            "In your last ${window.snapshot().size} tool calls, ${window.count(ToolEffect.EXPLORE)} " +
                "were read/search/execute with no landing action (writing a file, saving memory, " +
                "recording a plan, or asking the user)."
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
        append("If you want to explain yourself to the user, say it in plain words.")
    }

    /**
     * 计划提醒（对应 bigeye 的 `dag_missing`）。本仓 `todo_write` 的工具描述里自己写着
     * "**3+ distinct steps** 的任务要先调它" —— 这条提醒就是把那条自己定的规矩在执行中兜住。
     *
     * 报出**实际步数**：模型据此判断"这确实是个多步任务"还是"再一步就完了"。
     * 出口同样在最后（单步任务就忽略），避免把简单请求也赶上清单。
     */
    private fun planHint(toolCalls: Int): String = buildString {
        appendLine("You have made $toolCalls tool calls in this turn without recording a plan.")
        appendLine(
            "If this task has 3 or more distinct steps, call todo_write first with the full checklist " +
                "(short imperative items, exactly one in_progress) and keep it updated as you go — " +
                "the checklist keeps you from drifting and shows the user progress."
        )
        appendLine("If this is genuinely a single-step task, ignore this note and continue.")
        appendLine("Do not mention this note or any internal mechanism to the user.")
        append("Do not mention the checklist bookkeeping in your final answer either; the UI already shows it.")
    }

    /**
     * 预算提醒。与上一条的分工：上一条管"**没有产出**"，这一条管"**产出快把预算吃满了**"。
     *
     * 报出实际比例而不是只说"快满了"：模型据此决定是收尾还是硬撑，比一个模糊的警告有用。
     * 出口同样写在最后一句（缺关键信息就问人），避免"为了收尾而交半成品"。
     */
    private fun budgetHint(ratio: Double): String = buildString {
        appendLine(
            "You have used about ${(ratio * 100).toInt()}% of this turn's workspace budget " +
                "(output characters and steps)."
        )
        appendLine("Start wrapping up now: prefer finishing with what you already have over opening new lines of investigation.")
        appendLine("If something essential is still missing, say what it is and ask — that is better than silently continuing to dig.")
        append("Do not mention this note or any internal mechanism to the user.")
    }

    /** 多条命中合成一个块：共用一次表头，避免每步注入好几段"不是用户说的话"。 */
    private fun buildReminderText(bodies: List<String>): String = buildString {
        appendLine("━━━ Automatic reminders (not a user message) ━━━")
        bodies.forEachIndexed { index, body ->
            if (index > 0) appendLine()
            append(body)
        }
    }

    private companion object {
        const val RULE_EXPLORE_WITHOUT_LANDING = "explore_without_landing"
        const val RULE_PLAN_MISSING = "plan_missing"
        const val RULE_BUDGET_NEAR_LIMIT = "budget_near_limit"

        /**
         * 计划提醒的步数阈值：5 次工具调用。取 5 而不是工具描述里的"3+ 步"是因为
         * **工具调用数 ≠ 任务步数**（一步可能要好几次调用：读、改、验），
         * 留一点余量免得把简单请求也赶上清单 —— 误报的代价是模型学会忽略这类提醒。
         */
        const val PLAN_NUDGE_MIN_CALLS = 5

        /** 预算提醒阈值：用量到 80% 就提醒（撞上限是 100%，那时只能优雅停止、没有收尾余地） */
        const val BUDGET_NUDGE_RATIO = 0.8
    }
}
