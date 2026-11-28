package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.ui.UIMessage

sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>,
        /** Only incremental content snapshots may be delayed; lifecycle/tool boundaries stay immediate. */
        val coalescible: Boolean = false,
        /** True 时 [messages] 仅包含本轮发生变更的增量消息（如流式末条助手消息），按 id 就地合并。 */
        val isDelta: Boolean = false,
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
     * Phone Use 单轮步数耗尽，已提前结束本轮（见 [PhoneUseTurnBudget]）。
     * 堵的是「每步换参数的手机操作」把一轮拖到几十次读屏/手势。
     */
    data class PhoneUseBudgetExhausted(
        val steps: Int,
        val limit: Int,
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

    /**
     * **输入超窗撤回最大工具结果并同轮重发**（NMoi 11-17 轮，与反应式压缩互补）：
     *
     * **非终止提示**（[isTerminalStop] = false）：本轮并未停止，只是通知上层 UI/日志
     * 「刚刚因为确定超窗把某条超大工具结果在请求面撤回成句柄桩并已重发同一步」，原始输出仍保留在
     * 历史与 `/tool_outputs/` 中。
     */
    data class ToolOutputEvictedOnOverflow(
        val toolName: String,
        val toolCallId: String,
        val originalChars: Int,
        val shrunkChars: Int,
        val attempt: Int,
    ) : GenerationNotice
}

/** 是否为「提前终止本轮」类护栏通知（区分于 [GenerationNotice.ToolOutputEvictedOnOverflow] 等信息性通知）。 */
internal val GenerationNotice.isTerminalStop: Boolean
    get() = this !is GenerationNotice.ToolOutputEvictedOnOverflow
