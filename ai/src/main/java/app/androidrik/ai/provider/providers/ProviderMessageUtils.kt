package app.androidrik.ai.provider.providers

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart

/**
 * 消息 parts 按工具边界分组的结果
 * - Content: 普通内容（Text、Image、Reasoning 等）
 * - Tools: 连续的已执行工具
 */
internal sealed class PartGroup {
    data class Content(val parts: List<UIMessagePart>) : PartGroup()
    data class Tools(val tools: List<UIMessagePart.Tool>) : PartGroup()
}

/**
 * 将消息 parts 按工具边界分组
 *
 * 例如 [Text1, Tool1, Tool2, Text2, Tool3] 会分组为:
 * - Content([Text1])
 * - Tools([Tool1, Tool2])
 * - Content([Text2])
 * - Tools([Tool3])
 *
 * 这样可以确保 tool_use/functionCall 后面紧跟 tool_result/functionResponse
 */
internal fun groupPartsByToolBoundary(parts: List<UIMessagePart>): List<PartGroup> {
    val groups = mutableListOf<PartGroup>()
    val currentContent = mutableListOf<UIMessagePart>()
    val currentTools = mutableListOf<UIMessagePart.Tool>()

    fun flushContent() {
        if (currentContent.isNotEmpty()) {
            groups.add(PartGroup.Content(currentContent.toList()))
            currentContent.clear()
        }
    }

    fun flushTools() {
        if (currentTools.isNotEmpty()) {
            groups.add(PartGroup.Tools(currentTools.toList()))
            currentTools.clear()
        }
    }

    for (part in parts) {
        if (part is UIMessagePart.Tool && part.isExecuted) {
            flushContent()
            currentTools.add(part)
        } else {
            flushTools()
            currentContent.add(part)
        }
    }

    flushContent()
    flushTools()
    return groups
}

/**
 * 「位置在中部、但目标 API 不能承载 system 角色」的系统内容，降级为 user 时的**标记包裹**。
 *
 * 背景（③-3b，2026-10-06）：注入条目可以配置 `role = system` 且位置在对话中部
 * （TOP_OF_CHAT / BOTTOM_OF_CHAT / AT_DEPTH / 尾部）。OpenAI chat/completions 能原样承载中部
 * `system`，但 **Claude / Gemini 的 messages·contents 不接受 system 角色**（system 只在顶层，
 * 且放顶部会作废缓存前缀），**Responses API 的 input 也不接受**——这些路径只能降级为 user。
 * 裸降级会让模型把系统指令读成「用户说过的话」；包裹一层标记后，失去 role 也能自证是系统指令
 * （与 Claude Code 的 `<system-reminder>`、本仓 system 正文里的 `<recovery_note>` 同风格）。
 *
 * - 只包裹 Text part；图片等非文本 part 原样保留（不往里塞标记文本）。
 * - 幂等：已包裹过的不再包一层（防止同一条消息被多次降级处理）。
 */
internal fun UIMessage.asDowngradedSystemMessage(): UIMessage = copy(
    role = MessageRole.USER,
    parts = parts.map { part ->
        if (part is UIMessagePart.Text && !part.text.startsWith(SYSTEM_REMINDER_OPEN)) {
            part.copy(text = SYSTEM_REMINDER_OPEN + "\n" + part.text + "\n" + SYSTEM_REMINDER_CLOSE)
        } else {
            part
        }
    },
)

/** 降级包裹的起止标记（保持常量公开给测试与调用方，避免各处硬编码漂移）。 */
internal const val SYSTEM_REMINDER_OPEN = "<system-reminder>"
internal const val SYSTEM_REMINDER_CLOSE = "</system-reminder>"
