package app.androidrik.rikkahub.data.ai

import android.content.Context
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.files.FileFolders
import java.io.File

/**
 * 超窗撤回最大工具结果重发（NMoi 11-17 轮，与输入超窗反应式压缩互补）。
 *
 * 解决的问题：
 * 1. 自动压缩关闭（默认关）或调用方未提供 `contextOverflowRecover`（如无头子代理 / 重生成）时，
 *    确定超窗原本直接报错；
 * 2. 即使开启自动压缩，反应式压缩保护「最后一个真实 USER 轮起」的全部消息（含本轮刚执行的超大工具结果），
 *    保护区自身撑爆窗口时压缩返回「无可压缩」或压完仍超窗。
 *
 * 策略：仅在 [OverflowConfidence.DEFINITE] + 零增量时，挑出当前发送窗口里体积最大的未撤回工具结果，
 * 把全文无损落盘到 `tool_outputs/overflow-<safeId>.txt`（若原输出已是带文件指针的截断预览则沿用原路径、
 * 绝不用预览覆盖全文），在**请求面投影**里替换为极简句柄桩后同轮重发，DB/UI 原始历史完全不动。
 */
internal const val MAX_OVERFLOW_TOOL_EVICTIONS: Int = 3

/** 低于此字符数的工具结果不值得撤回（替换为句柄桩本身约 200+ 字符，撤回短结果无净收益） */
internal const val MIN_EVICTABLE_TOOL_OUTPUT_CHARS: Int = 512

internal const val OVERFLOW_EVICTED_MARKER: String =
    "[Overflow guard: tool result withdrawn to fit context window]"

private val EXISTING_SAVED_FILE_REGEX = Regex("""Full output saved to:\s*(/tool_outputs/[^\s]+)""")

internal data class OverflowEvictionCandidate(
    val toolCallId: String,
    val toolName: String,
    val textChars: Int,
    val fullText: String,
    /** 若输出本就已被闸 A/B 落盘过，沿用其路径、避免用截断预览覆盖磁盘上的完整全文 */
    val existingFilePath: String?,
)

internal data class OverflowEvictionRecord(
    val toolCallId: String,
    val toolName: String,
    val originalChars: Int,
    val stubChars: Int,
    val savedFilePath: String?,
)

/**
 * 在当前发送窗口里找体积最大、尚未被撤回、且长度达到 [minChars] 的已执行工具结果。
 * 返回 null 表示窗口里没有值得撤回的工具结果（例如纯长文本对话或全部结果都已是极简桩）。
 */
internal fun findLargestEvictableToolResult(
    messages: List<UIMessage>,
    alreadyEvicted: Set<String>,
    minChars: Int = MIN_EVICTABLE_TOOL_OUTPUT_CHARS,
): OverflowEvictionCandidate? {
    var best: OverflowEvictionCandidate? = null
    for (message in messages) {
        for (part in message.parts) {
            if (part !is UIMessagePart.Tool || part.output.isEmpty()) continue
            if (part.toolCallId in alreadyEvicted) continue
            val textParts = part.output.filterIsInstance<UIMessagePart.Text>()
            if (textParts.isEmpty()) continue
            val fullText = textParts.joinToString("\n") { it.text }
            if (fullText.contains(OVERFLOW_EVICTED_MARKER)) continue
            if (fullText.length < minChars) continue
            if (best == null || fullText.length > best.textChars) {
                val existingPath = EXISTING_SAVED_FILE_REGEX.find(fullText)?.groupValues?.getOrNull(1)
                best = OverflowEvictionCandidate(
                    toolCallId = part.toolCallId,
                    toolName = part.toolName,
                    textChars = fullText.length,
                    fullText = fullText,
                    existingFilePath = existingPath,
                )
            }
        }
    }
    return best
}

/**
 * 为候选工具结果生成请求面极简句柄桩（必要时先把全文落盘到 `/tool_outputs/overflow-<safeId>.txt`）。
 *
 * 落盘失败（磁盘满 / 单测无 filesDir）绝不抛错把撤回打断——如实省略路径行、只告知重新调用，
 * 不撒谎声称「已保存到文件」。
 */
internal fun evictToolResultForOverflow(
    context: Context?,
    candidate: OverflowEvictionCandidate,
    workspaceBound: Boolean,
    hasShellAccess: Boolean,
): Pair<String, OverflowEvictionRecord> {
    val savedPath: String? = candidate.existingFilePath ?: saveOverflowTextIfPossible(
        context = context,
        toolCallId = candidate.toolCallId,
        fullText = candidate.fullText,
    )
    val recovery = when {
        savedPath != null && workspaceBound ->
            "Use workspace_read_file(path=\"$savedPath\", offset=1, limit=200) to inspect specific ranges."
        savedPath != null && hasShellAccess ->
            "Use shell to inspect specific lines from `$savedPath` (e.g. `grep` or `sed`)."
        else ->
            "Re-run `${candidate.toolName}` with narrower arguments if specific details are still needed."
    }
    val stub = buildString {
        appendLine(OVERFLOW_EVICTED_MARKER)
        appendLine("tool: ${candidate.toolName} (${candidate.textChars} chars withdrawn)")
        if (savedPath != null) {
            appendLine("Full output saved to: $savedPath")
        }
        append(recovery)
    }
    val record = OverflowEvictionRecord(
        toolCallId = candidate.toolCallId,
        toolName = candidate.toolName,
        originalChars = candidate.textChars,
        stubChars = stub.length,
        savedFilePath = savedPath,
    )
    return stub to record
}

private fun saveOverflowTextIfPossible(
    context: Context?,
    toolCallId: String,
    fullText: String,
): String? {
    val filesDir = runCatching { context?.filesDir }.getOrNull() ?: return null
    val safeId = toolCallId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val fileName = "overflow-$safeId.txt"
    return runCatching {
        val outputDir = File(filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        val outputFile = File(outputDir, fileName)
        if (!outputFile.exists() || outputFile.readText() != fullText) {
            outputFile.writeText(fullText)
            if (outputFile.readText() != fullText) return null
        }
        "/tool_outputs/$fileName"
    }.getOrNull()
}

/**
 * 纯请求面投影：把已登记撤回的工具调用输出替换为极简句柄桩（保留非文本 part，不修改原始列表）。
 */
internal fun applyOverflowEvictions(
    messages: List<UIMessage>,
    evictions: Map<String, String>,
): List<UIMessage> {
    if (evictions.isEmpty()) return messages
    var anyChanged = false
    val projected = messages.map { message ->
        var msgChanged = false
        val newParts = message.parts.map { part ->
            if (part is UIMessagePart.Tool) {
                val stub = evictions[part.toolCallId]
                if (stub != null) {
                    msgChanged = true
                    val nonText = part.output.filter { it !is UIMessagePart.Text }
                    part.copy(output = listOf(UIMessagePart.Text(stub)) + nonText)
                } else {
                    part
                }
            } else {
                part
            }
        }
        if (msgChanged) {
            anyChanged = true
            message.copy(parts = newParts)
        } else {
            message
        }
    }
    return if (anyChanged) projected else messages
}
