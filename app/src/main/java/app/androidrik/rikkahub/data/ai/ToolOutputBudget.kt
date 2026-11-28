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

/** 上下文闸 A 的调用侧包装（批次 10 从 GenerationHandler 拆出）：按消息估算 + 统一有效窗口取档（A5）。 */
internal fun toolOutputBudget(
    messages: List<UIMessage>,
    model: Model,
    contextLengthOverride: Int? = null,
    providerContextLengthOverride: Int? = null,
): Int = toolOutputBudgetFor(
    estimateTokenCount(messages),
    model.effectiveContextLength(contextLengthOverride, providerContextLengthOverride),
)

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
    contextLengthOverride: Int? = null,
    providerContextLengthOverride: Int? = null,
): List<UIMessage> {
    val window = model.effectiveContextLength(contextLengthOverride, providerContextLengthOverride).coerceAtLeast(1)
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
    globalCapChars: Int = maxChars,
): List<UIMessagePart> {
    val result = ToolOutputCompressor.compressSuccess(
        context = context,
        tool = tool,
        output = output,
        maxChars = maxChars,
        globalCapChars = globalCapChars,
        workspaceBound = workspaceBound,
        hasShellAccess = hasShellAccess,
    )
    if (result.method != ToolOutputCompressor.Method.VERBATIM) {
        Log.i(
            TAG,
            "tool.budget tool=${tool.toolName} callId=${tool.toolCallId} method=${result.method} " +
                "chars=${result.originalChars}->${result.outputChars} deduped=${result.dedupedLines} " +
                "loss=${"%.2f".format(result.contentLossRatio)} wireTokens=${result.wireMeasure.wireTokens} " +
                "wireBytes=${result.wireMeasure.wireBytes} avail=${result.availability}",
        )
    }
    return result.parts
}
