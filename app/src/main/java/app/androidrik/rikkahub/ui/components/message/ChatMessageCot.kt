package app.androidrik.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.ui.components.message.tools.ToolUIRegistry
import app.androidrik.rikkahub.utils.JsonInstant

internal const val CHART_DISPLAY_TOOL_NAME = "chart_display"

/**
 * 思考步骤类型，用于分组 Reasoning、客户端 Tool、服务端 ServerTool 与过程解说文本
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep

    /** 服务端内置工具（googleSearch / urlContext 等），服务端执行，不走审批流 */
    data class ServerToolStep(
        val tool: UIMessagePart.ServerTool,
    ) : ThinkingStep

    /**
     * 工具调用之间的过程解说文本（“现在我来…”“让我看看…”）。
     *
     * 归一进思考链作为可折叠行，而不是渲染成永远可见的正文气泡：
     * 否则会在聊天页堆成一串“碎碎念”，还会把思考链切成多个小块导致折叠失效。
     */
    data class NarrationStep(
        val text: UIMessagePart.Text,
        val index: Int,
    ) : ThinkingStep
}

/**
 * 步骤的稳定标识：给 Compose `key(...)` 用。
 *
 * 折叠/展开会重排渲染槽位，没有稳定 key 时步骤级状态（展开态、加载态）会按位置错配。
 */
fun ThinkingStep.stableKey(): String = when (this) {
    is ThinkingStep.ReasoningStep -> "reasoning_${reasoning.createdAt}"
    is ThinkingStep.ToolStep -> "tool_${tool.toolCallId.ifBlank { tool.toolName }}"
    is ThinkingStep.ServerToolStep -> "server_${tool.toolCallId.ifBlank { tool.toolName }}"
    is ThinkingStep.NarrationStep -> "narration_$index"
}

/** 该步骤是否在等待用户操作（审批 / 问答）；这种步骤不能被折叠隐藏 */
fun ThinkingStep.isPendingStep(): Boolean = when (this) {
    is ThinkingStep.ToolStep -> tool.isPending
    is ThinkingStep.ServerToolStep, is ThinkingStep.NarrationStep, is ThinkingStep.ReasoningStep -> false
}

/**
 * 折叠头的常驻错误标记（决策 7：错误常驻红色标记，但不强制展开）。
 *
 * 本仓没有统一的工具失败字段：本地工具以 `{"error": ...}` 报错（chart_display / generate_image …），
 * 另有 `{"success": false}` 形态；被用户拒绝的调用同样算错误。
 */
fun ThinkingStep.hasError(): Boolean = when (this) {
    is ThinkingStep.ToolStep -> tool.approvalState is ToolApprovalState.Denied || tool.isFailedExecution()
    is ThinkingStep.ServerToolStep, is ThinkingStep.NarrationStep, is ThinkingStep.ReasoningStep -> false
}

/**
 * 折叠头摘要（决策 6）：步数 / 工具数 / 说明数，提示里面还有东西可看。
 */
data class ThinkingChainSummary(
    val stepCount: Int,
    val toolCount: Int,
    val narrationCount: Int,
)

fun List<ThinkingStep>.thinkingChainSummary(): ThinkingChainSummary = ThinkingChainSummary(
    stepCount = size,
    toolCount = count { it is ThinkingStep.ToolStep || it is ThinkingStep.ServerToolStep },
    narrationCount = count { it is ThinkingStep.NarrationStep },
)

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(
        val steps: List<ThinkingStep>,
        /**
         * 整轮折叠（决策 1/2/8）：该链能否整块收起，只留末尾的最终回答。
         *
         * 为 true 需要同时满足：整轮折叠已开启、该链之后还有面向用户的回答文本、
         * 链里没有待审批步骤、链里没有“交付物”步骤（图表 / 生成的图片不该被折起来）。
         */
        val canCollapse: Boolean = false,
        /** 决策 8：链里存在待审批步骤 ⇒ 强制展开（审批入口不能消失） */
        val forceExpand: Boolean = false,
    ) : MessagePartBlock

    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock

    /** 成功执行的 chart_display 工具调用, 在正文中以图表卡片展示 */
    data class ChartBlock(val tool: UIMessagePart.Tool, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock。
 *
 * 规则（对照 `docs/design/design-whole-turn-folding.md` §4）：
 * 1. `lastToolIndex` 之前的 `Text` 是过程解说，和 Reasoning/Tool/ServerTool 一起进思考链；
 * 2. `lastToolIndex` 之后的 `Text` 才是面向用户的最终回答，单独成正文块；
 * 3. 空白 `Text` 直接跳过（部分模型如 Qwen 会在工具之间输出空白文本，把它当分隔符
 *    会把一条思考链撕成多块，折叠头消失）；
 * 4. 成功执行的 `chart_display` 原地替换为 `ChartBlock`，其余工具按渲染器的
 *    [app.androidrik.rikkahub.ui.components.message.tools.ToolUIRenderer.cutsChain] 判断是否切断链；
 * 5. 非 assistant 消息（用户消息含工具调用的导入器路径）一律走 `ContentBlock`，绝不折叠。
 *
 * [wholeTurnFolding] 关闭时保持旧行为：所有 `Text` 都作为正文（不把解说并进思考链）。
 * 唯一的例外是待审批步骤：无论开关如何，含待审批步骤的链都 `forceExpand = true`
 * ——审批入口被“只显示最后若干步”藏起来会让整回合静默卡住（设计稿 §5.1）。
 */
fun List<UIMessagePart>.groupMessageParts(
    role: MessageRole = MessageRole.ASSISTANT,
    wholeTurnFolding: Boolean = false,
): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    // 角色守卫（§5.2）：只有 assistant 的回复才有“过程 / 回答”之分。用户消息里掺进来的
    // 工具调用（导入器路径）不能把用户自己的话折进折叠块。
    if (role != MessageRole.ASSISTANT) {
        this.fastForEachIndexed { index, part ->
            if (part is UIMessagePart.Text && part.text.isBlank()) return@fastForEachIndexed
            result.add(MessagePartBlock.ContentBlock(part, index))
        }
        return result
    }

    // 最后一次工具调用之后出现的文本才是面向用户的最终回答；
    // 位于工具调用之前的文本是过程解说，归入思考链一起折叠
    val lastToolIndex = indexOfLast { it is UIMessagePart.Tool || it is UIMessagePart.ServerTool }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                when {
                    part.isSuccessfulChartDisplay() -> {
                        flushThinkingSteps()
                        result.add(MessagePartBlock.ChartBlock(part, index))
                    }
                    // 交付物类工具（generate_image 等）切断链：独占一块 ⇒ 永远不会被
                    // “只显示最后若干步”或整轮折叠藏起来，本次交付物始终可见
                    part.cutsThinkingChain() -> {
                        flushThinkingSteps()
                        result.add(MessagePartBlock.ThinkingBlock(listOf(ThinkingStep.ToolStep(part))))
                    }

                    else -> currentThinkingSteps.add(ThinkingStep.ToolStep(part))
                }
            }

            is UIMessagePart.ServerTool -> {
                currentThinkingSteps.add(ThinkingStep.ServerToolStep(part))
            }

            is UIMessagePart.Text -> {
                // 空白文本既不渲染也不参与分组，否则会把一条思考链撕成两块
                if (part.text.isBlank()) return@fastForEachIndexed
                if (wholeTurnFolding && index < lastToolIndex) {
                    currentThinkingSteps.add(ThinkingStep.NarrationStep(part, index))
                } else {
                    flushThinkingSteps()
                    result.add(MessagePartBlock.ContentBlock(part, index))
                }
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result.withFoldDecisions(wholeTurnFolding)
}

/**
 * 折叠相关的展示决策（决策 2/7/8），在分组完成后统一算好，渲染层只管照做。
 *
 * [wholeTurnFolding] 只影响「能否收起」：`forceExpand` 是无条件的——待审批步骤被
 * `takeLast(2)` 藏起来会让整回合静默卡住，这与开关无关（设计稿 §5.1）。
 */
private fun List<MessagePartBlock>.withFoldDecisions(
    wholeTurnFolding: Boolean,
): List<MessagePartBlock> = mapIndexed { index, block ->
    if (block !is MessagePartBlock.ThinkingBlock) return@mapIndexed block
    val hasPendingStep = block.steps.any { it.isPendingStep() }
    // 交付物（图表 / 生成的图片）不参与折叠
    val hasDeliverable = block.steps.any { step ->
        step is ThinkingStep.ToolStep && step.tool.cutsThinkingChain()
    }
    // 决策 2：模型以工具调用结尾（后面没有正文）时折叠只会把全部内容藏起来 ⇒ 不折
    val hasFinalAnswer = drop(index + 1).any { next ->
        next is MessagePartBlock.ContentBlock && next.part is UIMessagePart.Text
    }
    block.copy(
        // 决策 8：有待审批步骤时整链不折（审批入口不能消失）
        canCollapse = wholeTurnFolding && hasFinalAnswer && !hasPendingStep && !hasDeliverable,
        // 决策 8：强制展开与开关无关，审批入口任何时候都不能被藏起来
        forceExpand = hasPendingStep,
    )
}

/**
 * 成功执行、且渲染器声明切断思考链的工具调用（§5.3）。
 *
 * 生成中 / 失败时不切断，仍作为普通步骤留在链里（设计稿 §4 规则 7）。
 */
private fun UIMessagePart.Tool.cutsThinkingChain(): Boolean =
    isExecuted && !isFailedExecution() && ToolUIRegistry.resolve(toolName).cutsChain()

private fun UIMessagePart.Tool.isFailedExecution(): Boolean {
    if (!isExecuted) return false
    val result = outputJsonObject() ?: return false
    val error = (result["error"] as? JsonPrimitive)?.contentOrNull
    if (!error.isNullOrBlank()) return true
    return (result["success"] as? JsonPrimitive)?.booleanOrNull == false
}

private fun UIMessagePart.Tool.outputJsonObject(): JsonObject? {
    val outputText = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    return runCatching { JsonInstant.parseToJsonElement(outputText) }.getOrNull() as? JsonObject
}

private fun UIMessagePart.Tool.isSuccessfulChartDisplay(): Boolean {
    if (toolName != CHART_DISPLAY_TOOL_NAME || !isExecuted) return false
    val result = outputJsonObject() ?: return false
    return (result["success"] as? JsonPrimitive)?.booleanOrNull == true
}
