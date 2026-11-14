package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.ui.components.message.tools.ToolUIContext
import app.androidrik.rikkahub.ui.components.message.tools.ToolUIRegistry
import androidx.compose.ui.res.stringResource

/**
 * 活动感知加载态（design-ui-overhaul-research.md §9.1/§9.2 方向 I）：生成中在列表尾部
 * 显示「当前阶段」一行——思考中 → 工具执行中 → 输出中，随事件流步进切换。
 *
 * 事件映射（逐字段核对 UIMessagePart 语义）：
 * - 显式处理状态（OCR/压缩/重试的 `processingStatus`）优先，它是数据层自己声明的状态；
 * - [UIMessagePart.Tool]：`isPending`（等审批）→ 等待确认；`!isExecuted`（结果未回填）
 *   → 运行中，文案取注册渲染器的 `title`（未注册工具回落「调用工具 xx」）；
 * - [UIMessagePart.ServerTool]：`status == IN_PROGRESS` → 内置工具执行中（复用现有文案）；
 * - [UIMessagePart.Reasoning]：`finishedAt == null` → 思考中；
 * - [UIMessagePart.Text]（非空白）：最后一个非空白部分是正文 → 输出中（首 token 前的
 *   空消息返回 null，只显示原有 Rabbit 动画，不伪造阶段）。
 *
 * 扫描方向为**倒序**：最后一个活动信号才是「当前阶段」——工具之间可能夹着已完成的部分
 * （已执行完的工具、已结束的思考），往前扫到第一个进行中的信号即停。
 *
 * 计时不在此行重复：工具步骤已有 [app.androidrik.rikkahub.ui.components.message.ToolStepDuration]
 * 同款计时、思考标题已有「思考了 x 秒」，聚合行再放一个只会信息打架。
 *
 * 性能：只在 loading 期间被组合（LoadingIndicator item 仅 loading 时存在）；单次开销为
 * 最后一条消息的 parts 倒序扫描 + 一次工具入参 JSON 解析（remember 缓存），流式 chunk
 * 级重组可承受。
 */
@Composable
internal fun rememberChatActivityStage(
    lastNode: MessageNode?,
    processingStatus: String?,
): String? {
    if (processingStatus != null) return processingStatus
    val message = lastNode?.messages?.getOrNull(lastNode.selectIndex) ?: return null
    if (message.role != MessageRole.ASSISTANT) return null

    for (part in message.parts.asReversed()) {
        when (part) {
            is UIMessagePart.Tool -> when {
                // 已执行完（结果已回填）：不是活动信号，继续往前找
                part.isExecuted -> continue
                // 等审批/问答：生成实际被闸门停住，明确告知「在等你」
                part.approvalState is ToolApprovalState.Pending ->
                    return stringResource(R.string.chat_stage_tool_awaiting)
                // 已下发未回填：执行中。文案走注册渲染器的标题（各工具友好名）
                else -> {
                    val renderer = ToolUIRegistry.resolve(part.toolName)
                    val context = remember(part.input, part.toolCallId) {
                        ToolUIContext(
                            tool = part,
                            arguments = part.inputAsJson(),
                            content = null,
                            loading = true,
                        )
                    }
                    return stringResource(R.string.chat_stage_tool_running, renderer.title(context))
                }
            }

            is UIMessagePart.ServerTool ->
                if (part.status == ServerToolStatus.IN_PROGRESS) {
                    return stringResource(R.string.chat_server_tool_running, part.toolName)
                } else {
                    continue
                }

            is UIMessagePart.Reasoning ->
                if (part.finishedAt == null) {
                    return stringResource(R.string.chat_stage_thinking)
                } else {
                    continue
                }

            // 最后一个非空白部分是正文：流式输出中
            is UIMessagePart.Text ->
                if (part.text.isBlank()) continue else return stringResource(R.string.chat_stage_generating)

            else -> continue
        }
    }
    return null
}
