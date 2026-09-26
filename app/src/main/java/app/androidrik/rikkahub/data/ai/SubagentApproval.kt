package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.ToolApprovalState

/**
 * 子代理的**人的审批**（设计 §十一）：无头 run 撞上 `needsApproval` 的工具时，
 * 不再一律策略拒绝，而是**暂停等人**——由通知按钮或运行详情页填决定，然后继续跑。
 *
 * 本文件只放**纯逻辑**（文本契约 + 状态回填），等待/通知/序列化都在
 * [SubagentApprovalQueue] 与 `SubagentRunner` 里；这样判定部分可以 JVM 单测。
 */

/**
 * 用户**手点拒绝**时的拒绝文本。
 *
 * 与 [HEADLESS_DENIAL_REASON]（信封外工具）分开措辞：那条说"本 run 不允许这个工具"，
 * 这条要说"**你刚刚拒了它**"——否则模型会以为是策略问题而换个工具继续撞，
 * 而用户拒它往往是因为「这次别动这个文件」，换个工具反而更糟。
 */
const val USER_DENIED_REASON =
    "Denied by the user in this run. Do not retry this tool and do not work around it; " +
        "proceed without it or finish and report what you could not do."

/**
 * **超时**（无人应答）时的拒绝文本。
 *
 * 与用户手拒区别对待的理由：超时多半只是"人不在/没看见"，措辞要说明这一点，
 * 让模型知道**这不是针对这个工具的禁令**（否则它会把"没人应答"当成永久限制而放弃任务）。
 */
const val APPROVAL_TIMEOUT_REASON =
    "Approval was not answered in time, so this run proceeded without it. " +
        "Do not retry this tool; continue with what you can do without it."

/**
 * 取出**正在等批**的工具（纯函数）。
 *
 * 扫**全部**消息而不是只看最后一条：审批发生在哪一条消息上取决于 provider 怎么下发
 * （Assistant 消息里内联；某些转换器会把工具挪到别处），只认最后一条会漏掉、
 * 于是 run 会停在 Pending 上直到整体超时（真机上表现为"卡住"而不是"等人"）。
 */
internal fun pendingApprovalTools(messages: List<UIMessage>): List<UIMessagePart.Tool> =
    messages.flatMap { message -> message.parts.filterIsInstance<UIMessagePart.Tool>() }
        .filter { it.isPending }

/**
 * 把决定回填进消息（纯函数）：匹配到的工具 part 换成 [ToolApprovalState.Approved] /
 * [ToolApprovalState.Denied]，其余原样。
 *
 * 回填后的消息**原样再喂回 `generateText`** —— 这正是聊天路径的续跑手法
 * （`canResumeExecution` = 已答复的工具可以执行），所以这里不改任何其他字段、
 * 不追加消息、不动顺序：**入参即协议**。
 */
internal fun applyApprovalDecisions(
    messages: List<UIMessage>,
    decisions: Map<String, ToolApprovalState>,
): List<UIMessage> {
    if (decisions.isEmpty()) return messages
    return messages.map { message ->
        val touched = message.parts.any { part ->
            part is UIMessagePart.Tool && decisions.containsKey(part.toolCallId)
        }
        if (!touched) {
            message
        } else {
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool) {
                        decisions[part.toolCallId]?.let { part.copy(approvalState = it) } ?: part
                    } else {
                        part
                    }
                }
            )
        }
    }
}

/**
 * 待批工具在通知/卡片里的**一行描述**（纯函数，便于单测口径统一）：
 * `工具名 + 入参摘要`。入参可能很长（shell 脚本、整份文件内容），故截断。
 */
internal fun approvalRequestSummary(tool: UIMessagePart.Tool, maxChars: Int = 160): String {
    val input = tool.input.replace(Regex("\\s+"), " ").trim()
    val compact = if (input.length > maxChars) input.take(maxChars) + "…" else input
    return if (compact.isEmpty()) tool.toolName else "${tool.toolName} $compact"
}
