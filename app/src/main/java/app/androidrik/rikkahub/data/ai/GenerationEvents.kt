package app.androidrik.rikkahub.data.ai

import kotlinx.serialization.Serializable
import app.androidrik.ai.ui.UIMessage

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk

    /**
     * 需要让用户看到、但**不属于对话内容**的提示（如工具调用失控护栏停止本轮）。
     *
     * 为什么不写进消息：提示是给用户看的，写进消息会被持久化、并在下一轮作为上下文发给模型，
     * 变成「自己教自己」的噪音。走独立 chunk，由上层转成用户可见的提示。
     */
    data class Notice(
        val notice: GenerationNotice,
    ) : GenerationChunk
}

/** [GenerationChunk.Notice] 的载荷。 */
@Serializable
sealed interface GenerationNotice {
    /** 同一工具同参数反复调用，已提前结束本轮（见 [ToolCallLoopGuard]）。 */
    data class ToolLoopStopped(
        val toolName: String,
        val repeats: Int,
    ) : GenerationNotice

    /**
     * workspace（rootfs）工具的单轮总预算耗尽，已提前结束本轮（见 [WorkspaceTurnBudget]）。
     * 堵的是 LoopGuard 防不住的形态：每步换参数的 workspace 调用，累计输出/步数无上限。
     */
    data class WorkspaceBudgetExhausted(
        val usedChars: Long,
        val budgetChars: Long,
        val steps: Int,
        val by: WorkspaceTurnBudget.ExceedReason,
    ) : GenerationNotice

    /**
     * 无头 run 的重复拒绝达阈值（见 [DenialGuard]）——模型被策略拒绝后换着方式反复撞，
     * 继续跑只会到空响应；提前结束，run 以 `failed(permission_denied)` 收尾。
     */
    data class TooManyDenials(
        val denials: Int,
    ) : GenerationNotice

    /**
     * 上下文预算耗尽（设计 §十二）：轨迹把窗口吃到"剩下的不够产出一轮回答"，
     * 优雅停止本轮（已产生的工具结果照常回填）。
     *
     * 与 [WorkspaceBudgetExhausted] 的区别：那个计「工具输出堆了多少」，
     * 这个计「窗口还剩多少」——前者是成本/体量，后者是硬墙（撞上去 provider 直接报错）。
     */
    data class ContextBudgetExhausted(
        val usedTokens: Int,
        val windowTokens: Int,
    ) : GenerationNotice

    /**
     * **思考退化**（零工具调用的失控形态）——连续纯思考/复述上一步，或单次调用内逐字重复，
     * 已提前结束本轮（见 [ThinkingDegenerationGuard]）。
     *
     * @param cutRule 调用内断流命中的规则；null = 只是调用边界上的计数达阈值
     * @param repeated 断流时的重复次数（相同 delta 次数）或周期长度
     */
    data class ThinkingDegenerationStopped(
        val stalledCalls: Int,
        val restated: Boolean,
        val cutRule: ThinkingDegenerationGuard.Rule?,
        val repeated: Int,
    ) : GenerationNotice
}
