package app.androidrik.rikkahub.data.ai

/**
 * workspace（rootfs）工具的**单轮总预算**护栏。
 *
 * **要解决的问题**：[ToolCallLoopGuard] 只拦「同工具同参数」的重复——模型每步换个参数
 * （`cat file1`、`cat file2`…、反复跑构建/生成大输出）不会被它命中，而全局 `maxSteps`
 * （256）要到很晚才兜底。单步输出有截断（32K chars），但**单轮累计**此前无上限：
 * 理论上 256 步 × 32K = 8M chars（≈2M token）——上下文被工具输出撑爆、请求成本失控。
 *
 * **口径**：只计 `workspace_*` 工具（read_file / write_file / edit_file / shell /
 * present_file，即 rootfs 语义），计**截断后实际进入上下文**的字符数（截断前的部分
 * 已写进 `tool_outputs` 文件、不进上下文，不是本项要管的钱），同时累计**调用步数**。
 * 两个上限（[DEFAULT_OUTPUT_BUDGET_CHARS] / [DEFAULT_MAX_STEPS]）任一命中即超限。
 *
 * **判定语义与 LoopGuard 一致——「执行后判定」而非「执行前拒绝」**：调用点在工具执行并
 * 截断**之后**计费；超限时本轮结果照常回填（协议要求每个 toolCallId 都有结果），
 * 然后提前、可见地结束这一轮。单批并行调用的过冲 ≤ 批大小 × 单步上限，可接受。
 *
 * **状态挂会话、跨审批续跑累积**（LoopGuard 的同款教训）：一次「轮」可跨多次
 * `generateText`（工具审批会让循环 break 等用户，批准后是新的调用续跑同一轮），
 * 若建在单次调用内每次批准即重置、永远累积不到上限。由
 * [app.androidrik.rikkahub.service.ConversationSession] 持有，新一轮用户消息
 * （或重新生成）时 [reset]。
 *
 * 本类**无副作用、无 Android 依赖**（诊断日志由调用点打），可直接上 JVM 单测。
 */
class WorkspaceTurnBudget(
    private val outputBudgetChars: Int = DEFAULT_OUTPUT_BUDGET_CHARS,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
) {
    private var usedChars: Long = 0
    private var steps: Int = 0

    /** 本轮已使用的输出字符数（诊断/提示用）。 */
    val usedOutputChars: Long get() = usedChars

    /** 本轮已发生的 workspace 工具调用步数（诊断/提示用）。 */
    val usedSteps: Int get() = steps

    /**
     * 记一次 workspace 工具调用并给出判定。
     *
     * @param outputChars 本次进入上下文的输出字符数（截断后）；失败的调用计 0 步由调用点
     *   直接跳过（无输出可计）
     */
    fun record(outputChars: Int): Verdict {
        usedChars += outputChars.toLong().coerceAtLeast(0)
        steps += 1
        return when {
            usedChars > outputBudgetChars -> Verdict.Exceeded(
                usedChars = usedChars,
                budgetChars = outputBudgetChars.toLong(),
                steps = steps,
                by = ExceedReason.OUTPUT,
            )
            steps > maxSteps -> Verdict.Exceeded(
                usedChars = usedChars,
                budgetChars = outputBudgetChars.toLong(),
                steps = steps,
                by = ExceedReason.STEPS,
            )
            else -> Verdict.Continue
        }
    }

    /**
     * 本轮预算用量占上限的比例（取**字符与步数两者较大的那个**；可能 >1）。
     *
     * 给「规则提醒」的 `budget_near_limit` 用：现状是**撞上限才优雅停止**，模型没有收尾的机会；
     * 有了这个比例就能在撞线之前提醒它收敛。上限口径仍留在本类里 —— 调用方只能拿到比例，
     * 改不了预算本身（与 `usageRatio` 只读、`record` 才计费的分工一致）。
     */
    fun usageRatio(): Double = maxOf(
        usedChars.toDouble() / outputBudgetChars.coerceAtLeast(1),
        steps.toDouble() / maxSteps.coerceAtLeast(1),
    )

    /** 清零（新一轮开始时调用）。 */
    fun reset() {
        usedChars = 0
        steps = 0
    }

    /** 预算判定结果。 */
    sealed interface Verdict {
        /** 未超限，继续。 */
        data object Continue : Verdict

        /**
         * 预算耗尽——本轮结果回填后应提前结束（优雅停止，同 LoopGuard）。
         *
         * 注意这是**粘性**判定：超限后本轮内后续 workspace 调用仍会执行并回填结果
         * （调用点只在超限的**那一次**设 notice），但不重复报。
         */
        data class Exceeded(
            val usedChars: Long,
            val budgetChars: Long,
            val steps: Int,
            val by: ExceedReason,
        ) : Verdict
    }

    /** 先撞到哪个上限（提示文案区分用）。 */
    enum class ExceedReason { OUTPUT, STEPS }

    companion object {
        /**
         * 单轮 workspace 工具输出总预算：256K chars（≈64K token）。
         * ≈ 8 个满额输出（单步上限 32K）或 ~64 个 4K 级输出——对合法 agent 工作
         * （批量读文件、跑构建）余量充足，对失控足够早停。
         */
        const val DEFAULT_OUTPUT_BUDGET_CHARS = 256 * 1024

        /**
         * 单轮 workspace 工具调用步数上限：64 次。全局 `maxSteps`（256）保留给
         * 非 workspace 工具的合法场景，不动。
         */
        const val DEFAULT_MAX_STEPS = 64

        /** 参与计费的工具名前缀（rootfs 语义的工作区工具）。 */
        const val TOOL_NAME_PREFIX = "workspace_"
    }
}
