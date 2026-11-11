package app.androidrik.rikkahub.data.ai

/**
 * Phone Use 的**单轮步数硬上限**。
 *
 * 要解决的问题：`phone_use` 一轮内可能被连续调用几十次（每步换参数，LoopGuard 的
 * 「同签名重复」拦不住），每次都会读屏、带回元素列表并计入上下文与耗时；全局
 * `maxSteps`（256）到很晚才兜底。本类只给手机操作一条更早的止损线。
 *
 * **判定语义与 [WorkspaceTurnBudget] 一致——「执行后判定」而非「执行前拒绝」**：
 * 超限的那次调用结果照常回填（协议要求每个 toolCallId 都有结果），随后提前、可见地
 * 结束这一轮，让模型没有继续动手的机会。
 *
 * **状态挂会话、跨审批续跑累积**：一次「轮」可跨多次 `generateText`（审批会让循环
 * break 等用户），由 [app.androidrik.rikkahub.service.ConversationSession] 持有，
 * 新一轮用户消息 / 重新生成时 [reset]。
 *
 * 本类无副作用、无 Android 依赖，可直接上 JVM 单测。
 */
class PhoneUseTurnBudget(
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
) {
    private var steps: Int = 0

    /** 本轮已使用的 Phone Use 步数（诊断/提示用）。 */
    val usedSteps: Int get() = steps

    /** 记一次 `phone_use` 调用并给出判定（含失败调用：它们同样消耗时间与上下文）。 */
    fun record(): Verdict {
        steps += 1
        return if (steps > maxSteps) Verdict.Exceeded(steps = steps, limit = maxSteps) else Verdict.Continue
    }

    /** 清零（新一轮开始时调用）。 */
    fun reset() {
        steps = 0
    }

    sealed interface Verdict {
        data object Continue : Verdict

        /**
         * 步数耗尽——本次结果回填后应提前结束本轮。
         *
         * 粘性：超限后本轮内后续 `phone_use` 调用仍会执行并回填结果（调用点只在超限的
         * **那一次**设提示），不重复报。
         */
        data class Exceeded(val steps: Int, val limit: Int) : Verdict
    }

    companion object {
        /**
         * 单轮 Phone Use 步数上限：40 次。
         *
         * 每步含 0.8–1.5s 的界面稳定等待与一次元素列表回带；40 步足够覆盖「打开应用 →
         * 多屏浏览 → 输入 → 提交」的常见任务，又能在失控循环里较早止损。低于 workspace
         * 的 64：手机操作更慢、每步都有真实 UI 副作用。
         */
        const val DEFAULT_MAX_STEPS = 40
    }
}
