package app.androidrik.rikkahub.data.ai.transformers

import android.util.Log
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart

/**
 * 发送前护栏：工具调用（functionCall / tool_calls）与工具结果（functionResponse / tool message）
 * 的**数量配平**。
 *
 * 现场（Gemini 原生错误，经 OpenAI 兼容中转透传）：
 * `Please ensure that the number of function response parts is equal to the number of function
 * call parts of the function call turn.`
 * 成因是历史里存在「有 tool call、没有对应 tool result」的轮次（工具执行被打断 / 审批未决 /
 * 中途停止，`UIMessagePart.Tool.output` 为空），上游按轮次校验后整请求被拒。
 *
 * 本护栏**只做派生**：输入 `List<UIMessage>` → 输出修正后的 `List<UIMessage>`，
 * 不落库、不改 UI、不改导出——只在「构建本次请求的消息列表」那一刻生效。规则：
 * 1. assistant 消息里有 tool call 却缺结果（`output` 为空）⇒ **补一条合成结果**，
 *    而不是剔除该 call（剔除会让模型以为自己没调过，行为上比报错更糟）。
 *    合成结果的措辞见 [SYNTHESIZED_TOOL_RESULT_TEXT]：只表达「结果拿不到了」，
 *    **不**暗示「工具执行失败」——后者会诱发模型用同样参数重跑带副作用的调用。
 * 2. 有结果却没有对应 call 的孤儿结果（历史遗留的 `UIMessagePart.ToolResult`）⇒ **丢弃**，
 *    少数 provider 会因孤儿结果报同类错误。
 * 3. 配对键是 `toolCallId`；本仓当前形态把结果内嵌在 `UIMessagePart.Tool.output` 里，
 *    故「缺结果」= `output.isEmpty()`（见 `UIMessagePart.Tool.isExecuted`）。
 * 4. 顺序不合法（call 与 result 之间隔了别的消息）时**只做数量配对**，绝不重排历史顺序
 *    ——本函数除「补 output」「移除孤儿结果 part」外不改动任何消息/part 的位置。
 *
 * 未改动任何东西时返回**同一个列表实例**（下游 `assertSame` 级别的「原样通过」语义，
 * 也让调用方零成本判断是否发生修正）。
 */
object ToolResultBalancingTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.balancedForRequest()
}

internal const val TOOL_RESULT_BALANCE_TAG = "ToolResultBalancing"

/**
 * 补进缺失结果的合成正文。
 *
 * 措辞是**契约的一部分**：这里只允许说「结果拿不到了」，**不得**出现 `error` / `failed` /
 * `missing` 这类「工具执行失败」的暗示。
 *
 * 为什么（真机观察到的连锁反应）：模型看到 `{"error":"tool result missing"}` 会读成
 * 「这次调用失败了」⇒ 立刻用**同样的参数再发一次**带副作用的调用（write / shell / memory
 * create 被重复执行）。而这条合成结果是「app 在保存这次输出之前被打断」——重跑既不安全也不
 * 必要，正确动作是告诉用户、让用户决定。
 *
 * 机器可读标记保留在 `_meta.synthesized`（`true` = 系统补的，不是工具的真实输出）。
 */
internal const val SYNTHESIZED_TOOL_RESULT_TEXT =
    """{"result":"unavailable — the app was interrupted before saving this tool's output; """ +
        """do not re-run it automatically, ask the user first","_meta":{"synthesized":true}}"""

/** 一次配平的产物：修正后的列表 + 两个计数（供日志与单测断言）。 */
internal data class ToolResultBalancing(
    val messages: List<UIMessage>,
    val synthesized: Int,
    val dropped: Int,
) {
    val changed: Boolean get() = synthesized > 0 || dropped > 0
}

/**
 * 对**本次请求**的消息列表做配平，修正发生时 `Log.w` 一次（带补/丢的数量）。
 *
 * 无修正时直接返回 `this`（原实例），保证「原样通过」路径零分配、零日志。
 */
internal fun List<UIMessage>.balancedForRequest(logTag: String = TOOL_RESULT_BALANCE_TAG): List<UIMessage> {
    val balancing = balanceToolCallResults(this)
    if (!balancing.changed) return this
    Log.w(
        logTag,
        "tool result balancing: synthesized ${balancing.synthesized} missing tool result(s), " +
            "dropped ${balancing.dropped} orphan tool result(s)",
    )
    return balancing.messages
}

/**
 * 配平主逻辑（**纯函数**，便于 JVM 单测；日志与副作用留在 [balancedForRequest]）。
 */
@Suppress("DEPRECATION")
internal fun balanceToolCallResults(messages: List<UIMessage>): ToolResultBalancing {
    // 配对键集合：只收 assistant 消息里的调用——
    // 其它角色的 Tool part 各 provider 编码时一律忽略（见 toGooglePart / toContentBlock 的
    // else 分支、ChatCompletionsAPI.addNonAssistantMessage），不会变成 functionCall。
    // 一并收历史遗留的 ToolCall：它同样是「模型确实调用过」的记录，据此判定结果是不是孤儿。
    val callIds = buildSet {
        messages.forEach { message ->
            if (message.role != MessageRole.ASSISTANT) return@forEach
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Tool -> if (part.toolCallId.isNotBlank()) add(part.toolCallId)
                    is UIMessagePart.ToolCall -> if (part.toolCallId.isNotBlank()) add(part.toolCallId)
                    else -> Unit
                }
            }
        }
    }

    var synthesized = 0
    var dropped = 0
    val balanced = messages.map { message ->
        val isAssistant = message.role == MessageRole.ASSISTANT
        var messageChanged = false
        val parts = message.parts.mapNotNull { part ->
            when {
                // 规则 1：call 有、结果缺 ⇒ 补合成结果（保留 call 本身）
                isAssistant && part is UIMessagePart.Tool && part.output.isEmpty() -> {
                    synthesized++
                    messageChanged = true
                    part.copy(output = listOf(UIMessagePart.Text(SYNTHESIZED_TOOL_RESULT_TEXT)))
                }

                // 规则 2：孤儿结果（无对应 call）⇒ 丢弃
                part is UIMessagePart.ToolResult && part.toolCallId !in callIds -> {
                    dropped++
                    messageChanged = true
                    null
                }

                else -> part
            }
        }
        if (messageChanged) message.copy(parts = parts) else message
    }

    return if (synthesized == 0 && dropped == 0) {
        ToolResultBalancing(messages, 0, 0)
    } else {
        ToolResultBalancing(balanced, synthesized, dropped)
    }
}
