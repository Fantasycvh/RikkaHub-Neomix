package app.androidrik.rikkahub.ui.pages.chat

import android.content.Context
import java.time.LocalDateTime
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.semanticRole
import app.androidrik.ai.util.encodeBase64
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.export.effectiveExportDisplayConfig
import app.androidrik.rikkahub.data.export.messageExportDisplayConfig
import app.androidrik.rikkahub.data.export.resolveExportModelLabel
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.components.richtext.generateMarkdownHtml
import app.androidrik.rikkahub.utils.JsonInstantPretty
import app.androidrik.rikkahub.utils.escapeHtml
import app.androidrik.rikkahub.utils.toLocalString

/**
 * 将 markdown 中的 TikZ（代码围栏 + $$ 内嵌图表）渲染为内嵌 <svg>（矢量），
 * 其余 markdown 交给 generateMarkdownHtml。用占位符绕过 flexmark 对原始 HTML 的转义。
 *
 * TikZ 走 native 引擎（与聊天渲染同链路：XDV→dvisvgm SVG），须在 IO 线程
 * 调用（内部阻塞等待隔离进程编译，~3-6s/图）。
 */
internal suspend fun renderMarkdownWithTikz(context: Context, content: String): String {
    // replace 回调非 suspend：先收集待编译 TikZ 源码，占位标记后统一走 native
    val pendingRaw = mutableListOf<String>()

    // 1. 处理 $$...$$ 块内嵌的 TikZ 图表（\begin{tikzpicture}/\begin{tikzcd} 等）
    val afterDollar = DOLLAR_TIKZ_REGEX.replace(content) { m ->
        val latex = m.groupValues[1]
        if (DIAGRAM_LATEX_PATTERNS.any { it.containsMatchIn(latex) }) {
            val placeholder = "%%TIKZ_PH_${pendingRaw.size}%%"
            pendingRaw.add(latex)
            placeholder
        } else {
            m.value // 普通数学公式，保留
        }
    }

    // 2. 处理 ```tikz 围栏
    val afterFence = TIKZ_FENCE_REGEX.replace(afterDollar) { m ->
        val placeholder = "%%TIKZ_PH_${pendingRaw.size}%%"
        pendingRaw.add(m.groupValues[1])
        placeholder
    }

    // 3. 统一 native 编译（XDV→dvisvgm SVG，与聊天渲染同链路；IO 线程阻塞 ~3-6s/图）
    //    先查内容寻址缓存（聊天里渲染过的图直接复用，免编译）
    val html = generateMarkdownHtml(afterFence)
    var result = html
    pendingRaw.forEachIndexed { i, code ->
        val trimmed = code.trim()
        val svg = compileTikzToSvg(context, code)
        if (svg != null) {
            result = result.replace("%%TIKZ_PH_${i}%%", svg)
        } else {
            // native 失败：占位符还原为原始围栏（导出可见源码，不残留占位文本）
            result = result.replace("%%TIKZ_PH_${i}%%", "```tikz\n$trimmed\n```")
        }
    }
    return result
}

internal suspend fun exportToHtml(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>,
    settings: Settings,
): ExportedFile {
    val filename = "chat-export-${LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.html"

    val sb = StringBuilder()
    sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n")
    sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    sb.append("<style>\n")
    sb.append(
        """
        body { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; background: #f5f6f8; color: #1a1a1a; line-height: 1.6; }
        h1 { font-size: 1.5em; margin-bottom: 4px; }
        .meta { color: #888; font-size: 0.85em; margin-bottom: 24px; }
        .message { border-radius: 10px; padding: 12px 16px; margin: 12px 0; }
        .message.user { background: #e8f0fe; border-left: 4px solid #4285f4; }
        .message.assistant { background: #ffffff; border-left: 4px solid #b0b0b0; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
        .role { font-weight: 700; margin-bottom: 6px; font-size: 0.9em; }
        .model-label { font-weight: 400; color: #888; margin-left: 8px; font-size: 0.85em; }
        .message.user .role { color: #1a5dc7; }
        .message.assistant .role { color: #555; }
        .tool { background: #fafafa; border: 1px solid #e5e5e5; border-radius: 6px; padding: 8px 12px; margin: 8px 0; }
        .tool-name { font-weight: 600; font-family: ui-monospace, monospace; font-size: 0.9em; }
        details { margin: 8px 0; background: #fff8e1; border-radius: 6px; padding: 8px 12px; }
        summary { cursor: pointer; font-weight: 600; color: #b26a00; }
        img { max-width: 100%; border-radius: 6px; }
        pre { background: #f0f0f0; padding: 10px; border-radius: 6px; overflow-x: auto; }
        code { background: #f0f0f0; padding: 2px 4px; border-radius: 4px; font-family: ui-monospace, monospace; font-size: 0.9em; }
        pre code { background: transparent; padding: 0; }
        """.trimIndent()
    )
    sb.append("\n</style>\n</head>\n<body>\n")

    sb.append("<h1>").append(conversation.title.escapeHtml()).append("</h1>\n")
    sb.append("<p class=\"meta\">Exported on ").append(LocalDateTime.now().toLocalString().escapeHtml()).append("</p>\n")

    // 会话所属助手名（自定义文案的 {assistant} 占位符）：整篇导出只解析一次
    val assistantName = settings.assistants
        .find { it.id == conversation.assistantId }
        ?.name
        ?.takeIf { it.isNotBlank() }
    // 会话级覆盖优先，否则跟随全局（三个导出器共用同一决策点）；
    // 每个消息再叠一层「消息级覆盖」（三级：消息 > 会话 > 全局）
    val baseDisplayConfig = effectiveExportDisplayConfig(
        conversationOverride = conversation.exportDisplayConfig,
        global = settings.exportDisplayConfig,
    )
    messages.forEach { message ->
        val isUser = message.semanticRole == MessageRole.USER
        val roleClass = if (message.semanticRole == MessageRole.SYSTEM) "system" else if (isUser) "user" else "assistant"
        val roleLabel = if (message.semanticRole == MessageRole.SYSTEM) "System" else if (isUser) "User" else "Assistant"
        sb.append("<div class=\"message ").append(roleClass).append("\">\n")
        // 提供商 / 模型标签：与 PNG / Markdown 同一套三级优先级；用户可自定义文案，必须转义
        val label = resolveExportModelLabel(
            message = message,
            providers = settings.providers,
            config = messageExportDisplayConfig(
                base = baseDisplayConfig,
                messageOverride = conversation.exportDisplayOverrides[message.id.toString()],
            ),
            assistantName = assistantName,
        ).label
        sb.append("<div class=\"role\">").append(roleLabel)
        if (label.isNotBlank()) {
            sb.append("<span class=\"model-label\">").append(label.escapeHtml()).append("</span>")
        }
        sb.append("</div>\n")
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    sb.append(renderMarkdownWithTikz(context, part.text))
                    sb.append('\n')
                }

                is UIMessagePart.Image -> {
                    part.encodeBase64().getOrNull()?.let { enc ->
                        sb.append("<img src=\"").append(enc.base64).append("\" alt=\"image\" />\n")
                    }
                }

                is UIMessagePart.Reasoning -> {
                    sb.append("<details>\n<summary>").append(context.getString(R.string.export_reasoning_summary).escapeHtml()).append("</summary>\n")
                    sb.append(generateMarkdownHtml(part.reasoning))
                    sb.append("\n</details>\n")
                }

                is UIMessagePart.Tool -> {
                    sb.append("<div class=\"tool\">\n")
                    sb.append("<div class=\"tool-name\">").append(part.toolName.escapeHtml()).append("</div>\n")
                    if (part.toolCallId.isNotBlank()) {
                        sb.append("<div style=\"color:#888;font-size:0.8em;\">Call ID: ").append(part.toolCallId.escapeHtml()).append("</div>\n")
                    }
                    sb.append("<div><strong>Input</strong></div>\n")
                    sb.append("<pre>").append(JsonInstantPretty.encodeToString(part.inputAsJson()).escapeHtml()).append("</pre>\n")
                    if (part.output.isNotEmpty()) {
                        sb.append("<div><strong>Output</strong></div>\n")
                        part.output.forEach { outputPart ->
                            when (outputPart) {
                                is UIMessagePart.Text -> {
                                    sb.append("<pre>").append(outputPart.text.escapeHtml()).append("</pre>\n")
                                }

                                is UIMessagePart.Reasoning -> {
                                    sb.append(generateMarkdownHtml(outputPart.reasoning)).append('\n')
                                }

                                is UIMessagePart.Image -> {
                                    outputPart.encodeBase64().getOrNull()?.let { enc ->
                                        sb.append("<img src=\"").append(enc.base64).append("\" alt=\"tool image\" />\n")
                                    }
                                }

                                is UIMessagePart.Document -> {
                                    sb.append("<a href=\"").append(outputPart.url.escapeHtml()).append("\">Document: ").append(outputPart.fileName.escapeHtml()).append("</a>\n")
                                }

                                is UIMessagePart.Video -> {
                                    sb.append("<a href=\"").append(outputPart.url.escapeHtml()).append("\">Video</a>\n")
                                }

                                is UIMessagePart.Audio -> {
                                    sb.append("<a href=\"").append(outputPart.url.escapeHtml()).append("\">Audio</a>\n")
                                }

                                else -> {}
                            }
                        }
                    }
                    sb.append("</div>\n")
                }

                else -> {}
            }
        }
        sb.append("</div>\n")
    }
    sb.append("</body>\n</html>")

    // 同 Markdown：不吞异常、不自行分享
    return writeExportBytes(
        displayName = filename,
        bytes = sb.toString().toByteArray(Charsets.UTF_8),
        mimeType = "text/html",
    )
}
