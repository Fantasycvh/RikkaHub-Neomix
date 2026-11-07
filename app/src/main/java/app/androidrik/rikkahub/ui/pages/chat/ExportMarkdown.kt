package app.androidrik.rikkahub.ui.pages.chat

import android.content.Context
import java.time.LocalDateTime
import androidx.compose.ui.text.buildAnnotatedString
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.semanticRole
import app.androidrik.ai.util.encodeBase64
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.export.effectiveExportDisplayConfig
import app.androidrik.rikkahub.data.export.messageExportDisplayConfig
import app.androidrik.rikkahub.data.export.resolveExportModelLabel
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.utils.JsonInstantPretty
import app.androidrik.rikkahub.utils.toLocalString

internal suspend fun exportToMarkdown(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>,
    settings: Settings,
): ExportedFile {
    val filename = "chat-export-${LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.md"
    // 会话级覆盖优先，否则跟随全局（三个导出器共用同一决策点）；
    // 每个消息再叠一层「消息级覆盖」（三级：消息 > 会话 > 全局）
    val baseDisplayConfig = effectiveExportDisplayConfig(
        conversationOverride = conversation.exportDisplayConfig,
        global = settings.exportDisplayConfig,
    )

    val sb = buildAnnotatedString {
        append("# ${conversation.title}\n\n")
        append("*Exported on ${LocalDateTime.now().toLocalString()}*\n\n")

        // 会话所属助手名（自定义文案的 {assistant} 占位符）：整篇导出只解析一次
        val assistantName = settings.assistants
            .find { it.id == conversation.assistantId }
            ?.name
            ?.takeIf { it.isNotBlank() }
        messages.forEach { message ->
            val role = when (message.semanticRole) { MessageRole.USER -> "**User**"; MessageRole.SYSTEM -> "**System**"; else -> "**Assistant**" }
            // 提供商 / 模型标签：与 PNG / HTML 同一套三级优先级（消息 > 会话 > 全局）。
            // 空串 = 不显示（含「不显示」档、非 assistant）
            val label = resolveExportModelLabel(
                message = message,
                providers = settings.providers,
                config = messageExportDisplayConfig(
                    base = baseDisplayConfig,
                    messageOverride = conversation.exportDisplayOverrides[message.id.toString()],
                ),
                assistantName = assistantName,
            ).label
            if (label.isNotBlank()) {
                append("$role ($label):\n\n")
            } else {
                append("$role:\n\n")
            }
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        append(part.text)
                        appendLine()
                    }

                    is UIMessagePart.Image -> {
                        append("![Image](${part.encodeBase64().getOrNull()?.base64})")
                        appendLine()
                    }

                    is UIMessagePart.Reasoning -> {
                        part.reasoning.lines()
                            .filter { it.isNotBlank() }
                            .map { "> $it" }
                            .forEach {
                                append(it)
                            }
                        appendLine()
                        appendLine()
                    }

                    is UIMessagePart.Tool -> {
                        append("**Tool**: `${part.toolName}`")
                        appendLine()
                        if (part.toolCallId.isNotBlank()) {
                            append("- Call ID: `${part.toolCallId}`")
                            appendLine()
                        }

                        append("Input:")
                        appendLine()
                        append("```json")
                        appendLine()
                        append(JsonInstantPretty.encodeToString(part.inputAsJson()))
                        appendLine()
                        append("```")
                        appendLine()

                        if (part.output.isNotEmpty()) {
                            append("Output:")
                            appendLine()
                            part.output.forEach { outputPart ->
                                when (outputPart) {
                                    is UIMessagePart.Text -> {
                                        append("```text")
                                        appendLine()
                                        append(outputPart.text)
                                        appendLine()
                                        append("```")
                                        appendLine()
                                    }

                                    is UIMessagePart.Reasoning -> {
                                        outputPart.reasoning.lines()
                                            .filter { it.isNotBlank() }
                                            .forEach {
                                                append("> $it")
                                                appendLine()
                                            }
                                    }

                                    is UIMessagePart.Image -> {
                                        append("![Tool Image](${outputPart.encodeBase64().getOrNull()?.base64})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Document -> {
                                        append("[Document: ${outputPart.fileName}](${outputPart.url})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Video -> {
                                        append("[Video](${outputPart.url})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Audio -> {
                                        append("[Audio](${outputPart.url})")
                                        appendLine()
                                    }

                                    else -> {}
                                }
                            }
                        }
                        appendLine()
                    }

                    else -> {}
                }
            }
            appendLine()
            append("---")
            appendLine()
        }
    }

    // **不再吞异常**（原实现 catch + printStackTrace 导致卡片误报「已导出」，设计稿 §1.2 ①）；
    // 也**不再自己分享**——统一由调用方按用途分流（分享面板 / 另存对话框）
    return writeExportBytes(
        displayName = filename,
        bytes = sb.toString().toByteArray(Charsets.UTF_8),
        mimeType = "text/markdown",
    )
}
