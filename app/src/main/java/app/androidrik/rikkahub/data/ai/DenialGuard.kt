package app.androidrik.rikkahub.data.ai

/**
 * 无头 run 的**重复拒绝护栏**（护栏家族第三员；设计见
 * `docs/design-subagent-context.md` §9.2.1）。
 *
 * **要解决的问题**：能力信封拒绝后，模型可能**换参数/换工具继续撞**，连续多次后放弃填
 * 内容、以空响应结束（API 无回传自然终止）。这个循环 [ToolCallLoopGuard] 拦不住——
 * 它只认「同签名」重复，而连续拒绝的每次签名都不同；[WorkspaceTurnBudget] 只计
 * `workspace_*` 工具；全局 `maxSteps` 兜底太晚，且结束的 run 会被记成「正常完成」。
 * 在无头场景里这就是一次**静默失败**（completed、result 为空、没人知道为什么）。
 *
 * **口径**：按 **run** 计拒绝事件数（**不按签名**——盲区正在于此），阈值
 * [DEFAULT_THRESHOLD] 命中 → 判定停止。判定语义与护栏家族一致：**优雅停止**——
 * 已产生的结果照常回填，run 以 `failed(permission_denied)` 结束而非报错崩溃。
 *
 * 与 [ToolCallLoopGuard] 的关系：互补而非重叠。前者抓「同调用反复」，本类抓
 * 「被拒后反复换着方式撞」——两者的签名空间不同，计数的都是失控的证据。
 *
 * 本类**无副作用、无 Android 依赖**（诊断日志由调用点打），可直接上 JVM 单测。
 */
class DenialGuard(
    private val threshold: Int = DEFAULT_THRESHOLD,
) {
    private var denials: Int = 0

    /** 本 run 已发生的拒绝次数（诊断/提示用）。 */
    val denialCount: Int get() = denials

    /**
     * 记一次**策略拒绝**并给出判定。
     *
     * 调用点在拒绝结果回填给模型**之前**；超限时调用点设一次停止提示
     * （粘性——后续拒绝不再重复报）。
     */
    fun record(): Verdict {
        denials += 1
        return if (denials >= threshold) {
            Verdict.Exceeded(denials)
        } else {
            Verdict.Continue
        }
    }

    /** 清零（新一轮开始时调用；run 场景下 run 即一轮，一般不重置）。 */
    fun reset() {
        denials = 0
    }

    /** 判定结果。 */
    sealed interface Verdict {
        /** 未达阈值，继续。 */
        data object Continue : Verdict

        /**
         * 拒绝次数达阈值——本轮结果回填后应提前结束（优雅停止），
         * run 以 `failed(permission_denied)` 收尾。
         */
        data class Exceeded(val denials: Int) : Verdict
    }

    companion object {
        /**
         * 阈值 3：第 1 次拒绝可能是信封配置与任务需求的正常错位（模型换个思路就好），
         * 第 2 次是可疑，第 3 次基本可断定模型在原地打转——继续跑只会烧 token 到空响应。
         */
        const val DEFAULT_THRESHOLD = 3
    }
}
