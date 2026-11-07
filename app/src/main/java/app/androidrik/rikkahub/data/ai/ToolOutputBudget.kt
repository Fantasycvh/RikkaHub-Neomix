package app.androidrik.rikkahub.data.ai

import android.content.Context
import android.util.Log
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.utils.estimateTokenCount
import app.androidrik.rikkahub.utils.effectiveContextLength
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 日志沿用原 TAG「GenerationHandler」——诊断按此 grep，改值会断线。
private const val TAG = "GenerationHandler"
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 上下文闸 B：请求前紧急卸载 + 硬限（design-tool-context-gate §5，阈值比 Yuihub 的 0.90/0.97 收紧）。
// 占用 ≥85% 时把旧工具结果卸载到文件留 2K 预览；卸完仍 ≥90% 判定硬限。
// （闸 A 的限幅常量与纯函数核、硬限信号 ContextGateHardStop 在 ToolOutputContextGate.kt）
private const val CONTEXT_EMERGENCY_RATIO = 0.85f
private const val CONTEXT_HARD_RATIO = 0.90f
private const val EMERGENCY_TOOL_PREVIEW_CHARS = 2 * 1024
private const val CONTEXT_GUARD_KEEP_RECENT = 4

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断。
// 豁免只在基础档生效：分级收缩档（16K/8K）下窗口优先级高于豁免（design-tool-context-gate §3）。
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

/** 上下文闸 A 的调用侧包装（批次 10 从 GenerationHandler 拆出）：按消息估算 + 模型窗口取档。 */
internal fun toolOutputBudget(messages: List<UIMessage>, model: Model): Int =
    toolOutputBudgetFor(estimateTokenCount(messages), model.effectiveContextLength())

/**
 * 上下文闸 B：请求前紧急卸载（design-tool-context-gate §5，Yuihub 3.0.0 `enforceContextBudget` 的投影版）。
 *
 * 占用 ≥85% 时从最旧向新把工具结果全文卸载到 `tool_outputs/guard-<id>.txt`、
 * part 替换为「卸载标记 + resultId + 恢复指引 + 2K 预览」，保留最近
 * [CONTEXT_GUARD_KEEP_RECENT] 条消息不动（新近结果是模型正在用的）；
 * 每卸载一条重算估算，达标即停。卸完仍 ≥90% 抛 [ContextGateHardStop]。
 *
 * 幂等：guard 文件按 toolCallId 命名、内容不可变，已存在即跳过写入；
 * 已是投影（卸载标记 / 既有截断标记）的 part 不再处理。
 * 快速路径（≤85%）每请求只多一次本地估算。
 */
internal suspend fun enforceContextBudget(
    context: Context,
    messages: List<UIMessage>,
    model: Model,
): List<UIMessage> {
    val window = model.effectiveContextLength().coerceAtLeast(1)
    val softLimit = (window * CONTEXT_EMERGENCY_RATIO).toInt()
    val hardLimit = (window * CONTEXT_HARD_RATIO).toInt()
    var current = messages
    var estimated = estimateTokenCount(current)
    if (estimated <= softLimit) return messages

    val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
    var changed = false
    val keepFrom = (current.size - CONTEXT_GUARD_KEEP_RECENT).coerceAtLeast(0)
    for (index in 0 until keepFrom) {
        val message = current[index]
        var messageChanged = false
        val parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool || part.output.isEmpty()) return@map part
            val textParts = part.output.filterIsInstance<UIMessagePart.Text>()
            val fullText = textParts.joinToString("\n") { it.text }
            if (fullText.length <= EMERGENCY_TOOL_PREVIEW_CHARS ||
                fullText.contains("[Context guard: output offloaded]") ||
                fullText.contains("[Tool output truncated:")
            ) {
                return@map part
            }
            val safeId = part.toolCallId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val fileName = "guard-$safeId.txt"
            val outputFile = File(outputDir, fileName)
            if (!outputFile.exists() || outputFile.readText() != fullText) {
                withContext(Dispatchers.IO) {
                    outputFile.writeText(fullText)
                }
            }
            messageChanged = true
            val nonText = part.output.filter { it !is UIMessagePart.Text }
            part.copy(
                output = listOf(
                    UIMessagePart.Text(
                        buildString {
                            appendLine("[Context guard: output offloaded]")
                            appendLine("resultId: guard-$safeId")
                            appendLine("Full output saved to: /tool_outputs/$fileName")
                            appendLine(
                                "Read only the needed range with workspace_read_file " +
                                    "(or shell cat/grep if bound), instead of re-running the tool."
                            )
                            appendLine()
                            append(fullText.take(EMERGENCY_TOOL_PREVIEW_CHARS))
                        }
                    )
                ) + nonText
            )
        }
        if (messageChanged) {
            current = current.toMutableList().also { it[index] = message.copy(parts = parts) }
            changed = true
            estimated = estimateTokenCount(current)
            if (estimated <= softLimit) break
        }
    }
    if (estimated > hardLimit) {
        Log.w(TAG, "enforceContextBudget: hard limit after offloading ($estimated / $window)")
        throw ContextGateHardStop(estimated, window)
    }
    if (changed) {
        Log.i(TAG, "enforceContextBudget: offloaded old tool outputs, $estimated / $window tokens")
    }
    return if (changed) current else messages
}

internal fun maybeTruncateToolOutput(
    context: Context,
    tool: UIMessagePart.Tool,
    output: List<UIMessagePart>,
    maxChars: Int,
    workspaceBound: Boolean,
    hasShellAccess: Boolean,
): List<UIMessagePart> {
    // 截断**恒生效**（原 `|| !hasShellAccess` 短路已废除，design-tool-context-gate §3）：
    // 无恢复通道时宁可给「缩窄重跑」提示，也不能让 30K+ 输出无界进历史
    if (tool.toolName in TOOLS_WITHOUT_OUTPUT_TRUNCATION && maxChars >= MAX_TOOL_OUTPUT_CHARS) return output

    val toolCallId = tool.toolCallId
    val textParts = output.filterIsInstance<UIMessagePart.Text>()
    val nonTextParts = output.filter { it !is UIMessagePart.Text }
    val totalChars = textParts.sumOf { it.text.length }

    if (totalChars <= maxChars) return output

    Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars, budget $maxChars)")

    val fullText = textParts.joinToString("\n") { it.text }
    val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

    // [FIX] toolCallId 由模型提供（外部可控），直接拼文件名可含 "/" 或 "../" →
    // 逃逸 tool_outputs 目录写入 app 私有目录其他位置（移植自 rikkahub-turbo a9449cc）。
    val safeFileName = toolCallId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val fileName = "$safeFileName.txt"
    val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
    val outputFile = File(outputDir, fileName)
    outputFile.writeText(fullText)
    // 投影无损回环校验（华灯投影原则）：外部全文是模型恢复上下文的**唯一**来源，
    // 写坏（磁盘满/静默截断）必须当场报错——绝不能让「预览说存了、文件却是残的」。
    require(outputFile.readText() == fullText) {
        "Tool output projection write failed (lossless check): $fileName"
    }

    // 恢复指引按会话真实能力三分支（§4）：有 workspace 用分页读（比整段 cat 省）；
    // 只有 shell 才指引 cat/grep；两者皆无时诚实告知重跑，不给假指引
    val recovery = when {
        workspaceBound ->
            "Use workspace_read_file(path=\"/tool_outputs/$fileName\", offset=1, limit=200) " +
                "to read only the needed range instead of re-running the tool."
        hasShellAccess ->
            "Use shell to read: `cat /tool_outputs/$fileName`\n" +
                "Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`"
        else ->
            "The output is too large to include in context and this session has no file-reading " +
                "tool. Re-run the tool with narrower arguments to get a smaller result."
    }

    return listOf(
        UIMessagePart.Text(
            buildString {
                appendLine("[Tool output truncated: $totalChars characters total]")
                appendLine("Full output saved to: /tool_outputs/$fileName")
                append(recovery)
                appendLine()
                append(preview)
            }
        )
    ) + nonTextParts
}
