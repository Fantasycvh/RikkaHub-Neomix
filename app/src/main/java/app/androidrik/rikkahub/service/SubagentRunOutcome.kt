package app.androidrik.rikkahub.service

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.GenerationNotice
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity

/**
 * 当前活跃 run 的进度快照（对话内工具卡的数据源）。
 *
 * [label] 是 profile/预设名，[steps] 是**模型步数**（助手消息条数——一步里可以并行下发多个
 * 工具调用，计成一次更贴近“跑到第几步”的直觉），[lastTool] 是最近一次工具调用的名字。
 */
data class RunProgress(
    val runId: String,
    val label: String,
    /** 任务目标：并行时卡片靠 (label, goal) 认领自己那一条（两者都在卡片入参里）。 */
    val goal: String,
    val status: String,
    val steps: Int,
    val lastTool: String?,
    val startedAt: Long,
)

/** [snapshotProgress] 的结果（纯数据，便于单测）。 */
internal data class RunProgressSnapshot(val steps: Int, val lastTool: String?)

/**
 * 从 run 的实时消息列表里取进度（**纯函数**）。
 *
 * 刻意从 `currentMessages` 派生而不是自己记账：轨迹是**多步共享的唯一事实来源**，
 * 另记一份计数一定会跟它漂移（例：审批续跑、工具结果回填都会重算 messages）。
 */
internal fun snapshotProgress(messages: List<UIMessage>): RunProgressSnapshot {
    val steps = messages.count { it.role == MessageRole.ASSISTANT }
    val lastTool = messages.asReversed().firstNotNullOfOrNull { message ->
        message.parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()?.toolName
    }
    return RunProgressSnapshot(steps = steps, lastTool = lastTool)
}

/** run 的完成判定（**纯函数**，便于 JVM 单测——§9.2.1 ③：空响应不得记成成功）。 */
internal sealed interface RunOutcome {
    /** [summary] 是**完整**最终文本；列表行的截断由落库处负责（`RESULT_SUMMARY_MAX_CHARS`，见 SubagentRunner）。 */
    data class Completed(val summary: String) : RunOutcome
    data class Failed(val errorKind: String, val detail: String?) : RunOutcome
}

internal fun evaluateCompletion(
    messages: List<UIMessage>,
    stopNotice: GenerationNotice?,
): RunOutcome {
    // 护栏停止优先：run 的失败细分直接来自 notice 类型
    when (stopNotice) {
        is GenerationNotice.ToolLoopStopped ->
            return RunOutcome.Failed(
                AgentRunEntity.ERROR_LOOP_DETECTED,
                "tool=${stopNotice.toolName} ×${stopNotice.repeats}",
            )
        is GenerationNotice.WorkspaceBudgetExhausted ->
            return RunOutcome.Failed(
                AgentRunEntity.ERROR_BUDGET_EXHAUSTED,
                "${stopNotice.usedChars}/${stopNotice.budgetChars} chars, ${stopNotice.steps} steps",
            )
        is GenerationNotice.PhoneUseBudgetExhausted ->
            // 无头 run 不装配 phone_use（来源门控），分支仅为 when 穷尽；仍按预算类失败记录
            return RunOutcome.Failed(
                AgentRunEntity.ERROR_BUDGET_EXHAUSTED,
                "${stopNotice.steps}/${stopNotice.limit} steps",
            )
        is GenerationNotice.TooManyDenials ->
            return RunOutcome.Failed(AgentRunEntity.ERROR_PERMISSION_DENIED, "${stopNotice.denials} denials")
        is GenerationNotice.ThinkingDegenerationStopped ->
            // 思考退化（零工具调用的空转/自重复）：无头场景同样是**静默失败**——继续跑只会烧
            // token 到空响应，故记成 model_gave_up（与「模型空转结束」判定同族）
            return RunOutcome.Failed(
                AgentRunEntity.ERROR_MODEL_GAVE_UP,
                "thinking degeneration: stalled=${stopNotice.stalledCalls} restated=${stopNotice.restated} " +
                    "cut=${stopNotice.cutRule ?: "none"}/${stopNotice.repeated}",
            )
        is GenerationNotice.ContextBudgetExhausted ->
            return RunOutcome.Failed(
                AgentRunEntity.ERROR_CONTEXT_BUDGET,
                "${stopNotice.usedTokens}/${stopNotice.windowTokens} tokens",
            )
        null -> Unit
    }

    val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val text = lastAssistant?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        ?.trim()
        .orEmpty()

    // §9.2.1 ③：空响应不得记成 COMPLETED——「看起来跑完了」与「真的完成了」由这里区分
    if (text.isEmpty()) {
        return RunOutcome.Failed(AgentRunEntity.ERROR_MODEL_GAVE_UP, null)
    }
    // 刻意**不截断**：阻塞路径要把全文回给父模型（§十），只有列表摘要列需要短文本，
    // 截断放在落库那一步（`finishRun` 的调用点）。
    return RunOutcome.Completed(summary = text)
}
