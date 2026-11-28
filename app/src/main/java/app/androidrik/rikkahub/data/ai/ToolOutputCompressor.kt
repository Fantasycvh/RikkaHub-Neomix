package app.androidrik.rikkahub.data.ai

import android.content.Context
import app.androidrik.ai.core.DEFERRED_TOOL_SEARCH_TOOL_NAME
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.files.FileFolders
import java.io.File
import kotlin.math.roundToInt

/**
 * 单一工具输出压缩器（design-tool-output-budget 阶段 3：B1/B2/B4/B5/B6/B7/B8/C1/C4/D2/D3）。
 *
 * 架构要点：
 * 1. **单一压缩器（B8/D2）**：成功路径与失败路径共用本组件，不另开旁路；
 * 2. **分级降级（B1）**：`VERBATIM → DEDUPED → MECHANICAL_HEAD_TAIL → MINIMAL_ENVELOPE`；
 *    不引入 LLM 摘要级（设计稿 §6 明确不做）；
 * 3. **去重 ≠ 丢内容（B2）**：连续重复行折叠单独计 `dedupedLines`，不计入 `contentLossRatio`；
 * 4. **信封优先 + 错误进信封（B4/B5/C4）**：不可裁剪头（状态 + 指针句柄 + 恢复指引 + 错误摘要）先留，
 *    剩余配额再给可变详情；
 * 5. **保头保尾 + 保护原子（B6/B7）**：60% 头 + 40% 尾，不丢结尾汇总/错误；从被裁中段抽取关键原子
 *    （路径 / URI / hash / exit code / 错误码 / 版本号 / ISO 日期），未出现在头尾的补一小段索引；
 * 6. **避开 senera 1 号坑**：压缩器写文件失败时绝不把成功的工具执行改写成 failure observation，
 *    保持 `executionSucceeded = true`，只降级 `outputAvailability = PARTIAL_OFFLOAD_FAILED`。
 */
internal object ToolOutputCompressor {

    private const val HEAD_TAIL_PREVIEW_CHARS = 4 * 1024
    private const val HEAD_SHARE = 0.60
    private const val MAX_PROTECTED_ATOMS = 12
    private const val MAX_PROTECTED_ATOMS_CHARS = 360

    /** 基础档豁免工具（仅在基础档生效；收缩档/零额度下窗口优先级高于豁免） */
    private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

    // B6 保护原子提取正则：退出码 / 路径 / URI / git hash / 错误码 / 版本号 / ISO 日期
    private val EXIT_CODE_ATOM = Regex("""\b(?:exit(?:ed)?(?:\s+with)?\s+code|exit_code|status)\s*[:=]?\s*-?\d+\b""", RegexOption.IGNORE_CASE)
    private val URI_ATOM = Regex("""https?://[^\s"'<>]+""")
    private val PATH_ATOM = Regex("""(?:[A-Za-z0-9_.\-]+/)+[A-Za-z0-9_.\-]+(?::\d+)?""")
    private val ERROR_CODE_ATOM = Regex("""\b(?:ERR_[A-Z0-9_]+|E[A-Z]{2,}[0-9]*|HTTP\s+[45]\d{2}|[A-Z][A-Za-z]+Exception)\b""")
    private val HASH_ATOM = Regex("""\b[0-9a-f]{7,40}\b""")
    private val VERSION_ATOM = Regex("""\bv?\d+\.\d+(?:\.\d+)+(?:-[A-Za-z0-9.]+)?\b""")
    private val ISO_DATE_ATOM = Regex("""\b\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}:\d{2}Z?)?\b""")

    enum class Method {
        /** 完整原样通过（C1：无任何截断标记/额外字节） */
        VERBATIM,
        /** 仅折叠连续重复行即满足预算（B2：contentLoss = 0） */
        DEDUPED,
        /** 机械保头保尾 + 保护原子 + 原文落盘（B6/B7） */
        MECHANICAL_HEAD_TAIL,
        /** 零额度 / 极紧预算下的最小可用信封（A3：状态 + 指针 + 一行指引） */
        MINIMAL_ENVELOPE,
    }

    enum class OutputAvailability {
        COMPLETE,
        PARTIAL_OFFLOADED,
        /** 避开 senera 1 号坑：工具执行本身成功，仅落盘备份失败，内存内降级返回预览而不改写执行结论 */
        PARTIAL_OFFLOAD_FAILED,
    }

    data class CompressionResult(
        val parts: List<UIMessagePart>,
        val method: Method,
        val complete: Boolean,
        val originalChars: Int,
        val outputChars: Int,
        /** B2 独立计数器：折叠掉的重复行数（不计入 contentLossRatio） */
        val dedupedLines: Int,
        /** B2 独立计数器：实质裁掉的字符数 */
        val omittedChars: Int,
        /** B1 内容损失比例（0.0..1.0；VERBATIM 与纯 DEDUPED 恒为 0.0） */
        val contentLossRatio: Double,
        /** C4/D3 可回读全文的句柄路径（`/tool_outputs/...`），null = 未落盘或无需落盘 */
        val savedFilePath: String?,
        val availability: OutputAvailability,
        val wireMeasure: WirePayloadMeasure,
    )

    /**
     * 压缩成功路径的工具输出（B8/D2 统一入口）。
     *
     * @param maxChars 两级限幅后的本次字符预算（0 = 触发 A3 最小信封降级）
     * @param globalCapChars 全局单次上限（用于 search_web 仅在基础档豁免的判定）
     */
    fun compressSuccess(
        context: Context?,
        tool: UIMessagePart.Tool,
        output: List<UIMessagePart>,
        maxChars: Int,
        globalCapChars: Int = maxChars,
        workspaceBound: Boolean,
        hasShellAccess: Boolean,
    ): CompressionResult {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        // 延迟工具检索结果携带有 DeferredToolSearchResultMetadata（多轮重放必需），绝不截断改写
        if (tool.toolName == DEFERRED_TOOL_SEARCH_TOOL_NAME) {
            return verbatimResult(tool, output, totalChars)
        }

        // search_web 豁免仅在基础档且非零额度生效（收缩档下窗口优先级高于豁免）
        if (tool.toolName in TOOLS_WITHOUT_OUTPUT_TRUNCATION &&
            maxChars >= MAX_TOOL_OUTPUT_CHARS &&
            globalCapChars >= MAX_TOOL_OUTPUT_CHARS
        ) {
            return verbatimResult(tool, output, totalChars)
        }

        // 快速路径：原样落进预算内 ⇒ 零修改、零 _meta 成本（C1 缺省 = 完整）
        if (maxChars > 0 && totalChars <= maxChars) {
            return verbatimResult(tool, output, totalChars)
        }

        val fullText = textParts.joinToString("\n") { it.text }

        // 级别 2（B2）：先尝试折叠连续重复行（去重不是内容丢失）；若折叠后能装下则无需落盘截断
        val deduped = foldConsecutiveDuplicateLines(fullText)
        if (maxChars > 0 && deduped.dedupedLines > 0 && deduped.text.length <= maxChars) {
            val outParts = listOf(UIMessagePart.Text(deduped.text)) + nonTextParts
            return CompressionResult(
                parts = outParts,
                method = Method.DEDUPED,
                complete = true,
                originalChars = totalChars,
                outputChars = deduped.text.length,
                dedupedLines = deduped.dedupedLines,
                omittedChars = 0,
                contentLossRatio = 0.0,
                savedFilePath = null,
                availability = OutputAvailability.COMPLETE,
                wireMeasure = measureWirePayload(tool.toolName, tool.toolCallId, outParts),
            )
        }

        // 需要落盘截断（级别 3 机械保头保尾 / 级别 4 零额度最小信封）：
        // 先无损落盘全文（避开 senera 1 号坑：磁盘失败不抛错污染工具执行结论！）
        val (savedPath, availability) = trySaveFullOutput(context, tool.toolCallId, fullText)
        val recovery = buildRecoveryHint(savedPath, workspaceBound, hasShellAccess)

        // A3 零额度或极紧预算（< 512 字符）：返回最小可用信封，不抛错
        if (maxChars < 512) {
            val envelope = buildString {
                appendLine("[Tool output truncated: $totalChars characters total (context budget exhausted; minimal envelope)]")
                if (savedPath != null) {
                    appendLine("Full output saved to: $savedPath")
                } else {
                    appendLine("Note: full output offload unavailable (execution succeeded; output_availability=partial)")
                }
                append(recovery)
            }
            val outParts = listOf(UIMessagePart.Text(envelope)) + nonTextParts
            val loss = if (totalChars > 0) 1.0 else 0.0
            return CompressionResult(
                parts = outParts,
                method = Method.MINIMAL_ENVELOPE,
                complete = false,
                originalChars = totalChars,
                outputChars = envelope.length,
                dedupedLines = deduped.dedupedLines,
                omittedChars = totalChars,
                contentLossRatio = loss,
                savedFilePath = savedPath,
                availability = availability,
                wireMeasure = measureWirePayload(tool.toolName, tool.toolCallId, outParts),
            )
        }

        // 级别 3（B4/B6/B7）：信封优先 + 机械保头保尾（60% 头 + 40% 尾）+ 被裁中段的保护原子
        val workingText = deduped.text
        val previewBudget = minOf(HEAD_TAIL_PREVIEW_CHARS, (maxChars - 512).coerceAtLeast(512))
        val sampled = sampleHeadTailWithProtectedAtoms(workingText, previewBudget)
        val omittedChars = (workingText.length - sampled.keptChars).coerceAtLeast(0)
        val lossRatio = if (workingText.isNotEmpty()) {
            (omittedChars.toDouble() / workingText.length).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val payloadText = buildString {
            appendLine("[Tool output truncated: $totalChars characters total]")
            if (savedPath != null) {
                appendLine("Full output saved to: $savedPath")
            } else {
                appendLine("Note: full output offload unavailable (execution succeeded; output_availability=partial)")
            }
            if (deduped.dedupedLines > 0 || omittedChars > 0) {
                val lossPct = (lossRatio * 100).roundToInt()
                appendLine("Summary: omitted=$omittedChars chars (~$lossPct% loss), deduped=${deduped.dedupedLines} duplicate line(s)")
            }
            append(recovery)
            appendLine()
            append(sampled.preview)
        }
        val outParts = listOf(UIMessagePart.Text(payloadText)) + nonTextParts
        return CompressionResult(
            parts = outParts,
            method = Method.MECHANICAL_HEAD_TAIL,
            complete = false,
            originalChars = totalChars,
            outputChars = payloadText.length,
            dedupedLines = deduped.dedupedLines,
            omittedChars = omittedChars,
            contentLossRatio = lossRatio,
            savedFilePath = savedPath,
            availability = availability,
            wireMeasure = measureWirePayload(tool.toolName, tool.toolCallId, outParts),
        )
    }

    /**
     * 压缩失败路径的错误输出（D2 与成功路径共用同一通道 + B5 错误消息进不可裁剪信封）。
     * 始终优先保全 `[ExceptionType] message` 错误头，仅对堆栈详情做去重和保头保尾限幅。
     */
    fun compressError(
        errorHeader: String,
        stackTrace: String?,
        maxChars: Int,
    ): String {
        val full = if (stackTrace.isNullOrBlank()) {
            errorHeader
        } else {
            "$errorHeader\n$stackTrace"
        }
        val effectiveLimit = maxChars.coerceAtLeast(256)
        if (full.length <= effectiveLimit) return full

        val notice = "\n[... error text truncated at $maxChars chars]"
        val available = (effectiveLimit - notice.length).coerceAtLeast(64)

        // B5：错误头属于不可裁剪信封，优先保留；若错误头自身已超预算，对头本身保头保尾
        if (errorHeader.length >= available) {
            val headLen = (available * 0.7).toInt().coerceAtLeast(32)
            val tailLen = (available - headLen - 5).coerceAtLeast(0)
            val trimmedHeader = if (tailLen > 16) {
                errorHeader.take(headLen) + " ... " + errorHeader.takeLast(tailLen)
            } else {
                errorHeader.take(available)
            }
            return trimmedHeader + notice
        }

        val traceBudget = (available - errorHeader.length - 1).coerceAtLeast(0)
        if (stackTrace.isNullOrBlank() || traceBudget < 64) {
            return errorHeader + notice
        }
        val dedupedTrace = foldConsecutiveDuplicateLines(stackTrace).text
        if (dedupedTrace.length <= traceBudget) {
            return "$errorHeader\n$dedupedTrace"
        }
        val sampledTrace = sampleHeadTailWithProtectedAtoms(dedupedTrace, traceBudget).preview
        return "$errorHeader\n$sampledTrace$notice"
    }

    // ---- 纯函数内核（公开 internal 供单测直接覆盖）----

    internal data class DedupResult(val text: String, val dedupedLines: Int)

    /** B2：折叠连续完全相同的行（常见于编译告警/重试日志），保留首行 + 重复计数。 */
    internal fun foldConsecutiveDuplicateLines(text: String): DedupResult {
        if (!text.contains('\n')) return DedupResult(text, 0)
        val lines = text.split('\n')
        if (lines.size < 3) return DedupResult(text, 0)

        val out = ArrayList<String>(lines.size)
        var deduped = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            var run = 1
            while (i + run < lines.size && lines[i + run] == line) {
                run++
            }
            out += line
            if (run >= 3) {
                out += "  ... (repeated ${run - 1} more times)"
                deduped += (run - 1)
            } else if (run == 2) {
                out += line
            }
            i += run
        }
        return if (deduped == 0) DedupResult(text, 0) else DedupResult(out.joinToString("\n"), deduped)
    }

    /** B6：从被裁掉的中段文本中提取不可合并的关键原子（退出码/错误码/路径/URI/版本号/hash/日期）。 */
    internal fun extractProtectedAtoms(text: String, limit: Int = MAX_PROTECTED_ATOMS): List<String> {
        val found = LinkedHashSet<String>()
        fun collect(regex: Regex) {
            for (match in regex.findAll(text)) {
                if (found.size >= limit * 2) break
                val v = match.value.trim()
                if (v.length >= 3) found += v
            }
        }
        // 优先级：退出码与错误码最先保护（防止把 exit 1 与 exit 0 混成一样），其次路径/URI/版本/日期/hash
        collect(EXIT_CODE_ATOM)
        collect(ERROR_CODE_ATOM)
        collect(PATH_ATOM)
        collect(URI_ATOM)
        collect(VERSION_ATOM)
        collect(ISO_DATE_ATOM)
        collect(HASH_ATOM)
        return found.take(limit)
    }

    internal data class HeadTailSample(val preview: String, val keptChars: Int, val protectedAtoms: List<String>)

    /**
     * B7 + B6：保头（60%）+ 保尾（40%），在换行处断开；
     * 若被裁掉的中段含头尾未出现的关键原子（如 `exit code 1` / 文件路径），在中段省略标记内附带紧凑索引。
     */
    internal fun sampleHeadTailWithProtectedAtoms(text: String, budgetChars: Int): HeadTailSample {
        if (text.length <= budgetChars) return HeadTailSample(text, text.length, emptyList())
        val safeBudget = budgetChars.coerceAtLeast(128)
        val headTarget = (safeBudget * HEAD_SHARE).toInt().coerceAtLeast(64)
        val tailTarget = (safeBudget - headTarget).coerceAtLeast(48)

        val headCut = text.lastIndexOf('\n', headTarget).takeIf { it > headTarget / 2 } ?: headTarget
        val tailStartRaw = (text.length - tailTarget).coerceAtLeast(headCut)
        val tailStart = text.indexOf('\n', tailStartRaw).takeIf { it in tailStartRaw until (text.length - tailTarget / 2) }
            ?.let { it + 1 }
            ?: tailStartRaw

        val head = text.substring(0, headCut).trimEnd()
        val tail = text.substring(tailStart).trimStart()
        val middle = if (tailStart > headCut) text.substring(headCut, tailStart) else ""
        val omittedChars = middle.length

        // 只补充「头尾里都没有」的保护原子，避免重复占字
        val uniqueAtoms = if (middle.isNotEmpty()) {
            extractProtectedAtoms(middle).filterNot { atom ->
                head.contains(atom) || tail.contains(atom)
            }
        } else {
            emptyList()
        }

        val atomsBlock = if (uniqueAtoms.isNotEmpty()) {
            val joined = buildString {
                for (atom in uniqueAtoms) {
                    val piece = if (isEmpty()) atom else ", $atom"
                    if (length + piece.length > MAX_PROTECTED_ATOMS_CHARS) break
                    append(piece)
                }
            }
            if (joined.isNotEmpty()) "\n[Protected atoms from omitted middle: $joined]" else ""
        } else {
            ""
        }

        val preview = buildString {
            append(head)
            append("\n\n... [omitted $omittedChars chars from middle] ...")
            append(atomsBlock)
            append("\n\n")
            append(tail)
        }
        return HeadTailSample(
            preview = preview,
            keptChars = head.length + tail.length,
            protectedAtoms = uniqueAtoms,
        )
    }

    private fun verbatimResult(
        tool: UIMessagePart.Tool,
        output: List<UIMessagePart>,
        totalChars: Int,
    ): CompressionResult = CompressionResult(
        parts = output,
        method = Method.VERBATIM,
        complete = true,
        originalChars = totalChars,
        outputChars = totalChars,
        dedupedLines = 0,
        omittedChars = 0,
        contentLossRatio = 0.0,
        savedFilePath = null,
        availability = OutputAvailability.COMPLETE,
        wireMeasure = measureWirePayload(tool.toolName, tool.toolCallId, output),
    )

    private fun trySaveFullOutput(
        context: Context?,
        toolCallId: String,
        fullText: String,
    ): Pair<String?, OutputAvailability> {
        val filesDir = runCatching { context?.filesDir }.getOrNull()
            ?: return null to OutputAvailability.PARTIAL_OFFLOAD_FAILED
        // [FIX] toolCallId 外部可控，清洗非法路径字符防目录逃逸
        val safeFileName = toolCallId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val fileName = "$safeFileName.txt"
        return runCatching {
            val outputDir = File(filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
            val outputFile = File(outputDir, fileName)
            outputFile.writeText(fullText)
            // 无损回环校验：写坏（磁盘满/静默截断）降级为 PARTIAL_OFFLOAD_FAILED，不抛错污染工具结论
            if (outputFile.readText() == fullText) {
                "/tool_outputs/$fileName" to OutputAvailability.PARTIAL_OFFLOADED
            } else {
                null to OutputAvailability.PARTIAL_OFFLOAD_FAILED
            }
        }.getOrElse {
            null to OutputAvailability.PARTIAL_OFFLOAD_FAILED
        }
    }

    private fun buildRecoveryHint(
        savedPath: String?,
        workspaceBound: Boolean,
        hasShellAccess: Boolean,
    ): String = when {
        savedPath != null && workspaceBound ->
            "Use workspace_read_file(path=\"$savedPath\", offset=1, limit=200) " +
                "to read only the needed range instead of re-running the tool."
        savedPath != null && hasShellAccess ->
            "Use shell to read: `cat $savedPath`\n" +
                "Use shell to search: `grep \"pattern\" $savedPath`"
        else ->
            "The output is too large to include in context and this session has no file-reading " +
                "tool. Re-run the tool with narrower arguments to get a smaller result."
    }
}
