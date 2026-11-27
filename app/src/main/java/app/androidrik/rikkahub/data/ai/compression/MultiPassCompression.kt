package app.androidrik.rikkahub.data.ai.compression

import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.ai.ThinkingDegenerationGuard
import kotlinx.coroutines.CancellationException

/**
 * 多步压缩二期：滚动摘要（rolling map-reduce）+ 锚点抽取/剥离 + 输出侧退化自检 + 精炼遍（refine pass）。
 *
 * 设计来源：`docs/design/in-progress/design-multi-pass-compression.md` §3–§4（revision 2 & 3）。
 * 全纯函数 + suspend lambda 注入，无 Android 依赖，可完整 JVM 单测。
 */

// ---- §4.3 固定 schema 锚点 ----

internal const val ANCHORS_OPEN_TAG = "<anchors>"
internal const val ANCHORS_CLOSE_TAG = "</anchors>"

/** 锚点清单最大条数（给精炼遍输入定死上界） */
const val MAX_ANCHOR_LINES: Int = 20

/** 允许的六种锚点类型（§4.3 / Q6 收敛决定） */
val ALLOWED_ANCHOR_TYPES: Set<String> = setOf("fact", "decision", "task", "value", "file", "name")

data class CompressionAnchor(
    val type: String,
    val content: String,
) {
    fun formatLine(): String = "- [$type] $content"
}

private val ANCHOR_BLOCK_REGEX = Regex(
    """<anchors>\s*(.*?)\s*</anchors>""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)
private val ANCHOR_LINE_REGEX = Regex("""^\s*[-*]\s*\[([A-Za-z]+)]\s+(.+)$""")

/**
 * 附在滚动摘要每块提示词之后的锚点抽取指令（§4.3）：
 * 锚点是摘要的**附录**而非正文，入档前通过 [stripAnchorsSection] 精确剥离，仅供给精炼遍核对。
 */
internal val ANCHOR_EXTRACTION_INSTRUCTION: String = """
    After the checkpoint sections above, append a fenced anchor checklist of the most critical concrete items from this conversation so a verification pass can check nothing was dropped:
    <anchors>
    - [fact] concise concrete fact
    - [decision] key decision made
    - [task] pending or active task item
    - [value] exact numeric value, threshold, version, or identifier
    - [file] exact file path or symbol
    - [name] person, project, or service name
    </anchors>
    Rules for <anchors>: use ONLY the six types {fact, decision, task, value, file, name}; emit at most $MAX_ANCHOR_LINES lines; do not duplicate items.
""".trimIndent()

/** 从模型输出中解析合法的锚点条目（仅接受六种类型、去重、最多 [limit] 条）。 */
fun extractAnchors(text: String, limit: Int = MAX_ANCHOR_LINES): List<CompressionAnchor> {
    val block = ANCHOR_BLOCK_REGEX.find(text)?.groupValues?.getOrNull(1) ?: return emptyList()
    val seen = LinkedHashSet<String>()
    val out = mutableListOf<CompressionAnchor>()
    for (line in block.lineSequence()) {
        if (out.size >= limit) break
        val match = ANCHOR_LINE_REGEX.matchEntire(line) ?: continue
        val type = match.groupValues[1].lowercase()
        if (type !in ALLOWED_ANCHOR_TYPES) continue
        val content = match.groupValues[2].trim()
        if (content.isEmpty()) continue
        val key = "$type|${content.lowercase()}"
        if (seen.add(key)) {
            out += CompressionAnchor(type = type, content = content)
        }
    }
    return out
}

/** 精确剥离 `<anchors>...</anchors>` 围栏块（入档前与滚动 carry 传递前必须调用）。 */
fun stripAnchorsSection(text: String): String {
    if (!text.contains(ANCHORS_OPEN_TAG, ignoreCase = true)) return text.trim()
    val stripped = ANCHOR_BLOCK_REGEX.replace(text, "")
    // 兜底：若模型未闭合 </anchors>，切掉 <anchors> 及其后的不完整附录
    val unclosed = stripped.indexOf(ANCHORS_OPEN_TAG, ignoreCase = true)
    return (if (unclosed >= 0) stripped.substring(0, unclosed) else stripped).trim()
}

/** 合并两批锚点（保留顺序、去重、封顶 [limit] 条）。 */
fun mergeAnchors(
    existing: List<CompressionAnchor>,
    incoming: List<CompressionAnchor>,
    limit: Int = MAX_ANCHOR_LINES,
): List<CompressionAnchor> {
    val seen = LinkedHashSet<String>()
    val out = mutableListOf<CompressionAnchor>()
    for (anchor in existing + incoming) {
        if (out.size >= limit) break
        val key = "${anchor.type.lowercase()}|${anchor.content.lowercase()}"
        if (seen.add(key)) out += anchor
    }
    return out
}

// ---- §4.2 滚动输出侧退化自检（独立标定，过渡保守阈值 0.95） ----

/** 退化自检过渡期保守阈值（§4.2 / Q3 决定：误杀丢块代价远高于漏杀，先记录分布再收紧） */
const val COMPRESS_DEGENERATION_CONTAINMENT_THRESHOLD: Double = 0.95

private const val DEGENERATION_MIN_CHARS = 320
private const val DEGENERATION_CYCLE_TAIL_CHARS = 256

/**
 * 测算单块摘要 `Si` 的**内部字面重复度**（0.0..1.0，§4.2）：
 * 复用 [ThinkingDegenerationGuard] 判据库（一库三消费者 §6.4）：
 * 1. 尾部周期循环检测（`minimalPeriod <= 64 && distinctChars >= 3` ⇒ 1.0）；
 * 2. 尾部（后 40%）对前部（前 60%）的 8-gram 包含度（适配短摘要正文，不受 4000 字符窗口假设限制）。
 */
fun summaryInternalContainment(summary: String): Double {
    val clean = stripAnchorsSection(summary)
    if (clean.length < DEGENERATION_MIN_CHARS) return 0.0

    // 判据 1：尾部陷入短周期逐字循环
    val cycleTail = clean.takeLast(DEGENERATION_CYCLE_TAIL_CHARS)
    val period = ThinkingDegenerationGuard.minimalPeriod(cycleTail)
    if (period <= ThinkingDegenerationGuard.DEFAULT_MAX_CYCLE_PERIOD_CHARS &&
        period * 3 <= cycleTail.length &&
        ThinkingDegenerationGuard.distinctChars(cycleTail, period) >= 3
    ) {
        return 1.0
    }

    // 判据 2：后半段对前半段的 shingle 包含度（回声复读接近 1.0，正常浓缩散文极低）
    val split = (clean.length * 6) / 10
    val prefix = clean.substring(0, split)
    val suffix = clean.substring(split)
    val splitScore = ThinkingDegenerationGuard.containment(prefix, suffix)

    // 判据 3：最后 256 字对前文的包含度（捕捉摘要尾部开始复读前文某段的情况）
    val tailStart = (clean.length - DEGENERATION_CYCLE_TAIL_CHARS).coerceAtLeast(1)
    val tailScore = ThinkingDegenerationGuard.containment(
        clean.substring(0, tailStart),
        clean.substring(tailStart),
    )
    return maxOf(splitScore, tailScore)
}

/** `Si` 是否命中输出侧退化自检（≥ [threshold]，默认 0.95）。 */
fun isDegenerateSummary(
    summary: String,
    threshold: Double = COMPRESS_DEGENERATION_CONTAINMENT_THRESHOLD,
): Boolean = summaryInternalContainment(summary) >= threshold

// ---- §3.1 滚动摘要编排（rolling map-reduce）----

internal const val SKIPPED_DEGENERATE_MARKER = "[skipped: degenerate]"

data class RollingCompressionResult(
    /** 最终合并摘要正文（已剥离 `<anchors>`） */
    val summaryBody: String,
    /** 跨块累积的锚点清单（≤20 条，供精炼遍校验） */
    val anchors: List<CompressionAnchor>,
    /** 因命中退化自检而跳过的块数 */
    val skippedChunks: Int,
    /** 每块的内部重复度观测值（P0 标定日志用） */
    val containmentScores: List<Double>,
)

/**
 * 组装滚动摘要某块的 `{additional_context}`（§3.1 契约，便于单测）：
 * - 用户 `additionalPrompt` 每块保留注入；
 * - 块 0：若有 `priorCheckpoint` 则以 `PRIOR CHECKPOINT` 注入一次；
 * - 块 1..N-1：将上一块产出的 `carry` 以 `PRIOR SUMMARY` 注入，要求合并为单一摘要而非拼接。
 */
fun buildRollingAdditionalContext(
    chunkIndex: Int,
    additionalPrompt: String,
    carry: String,
): String = buildString {
    if (additionalPrompt.isNotBlank()) {
        appendLine("Additional instructions from user: $additionalPrompt")
        appendLine()
    }
    if (carry.isNotBlank()) {
        if (chunkIndex == 0) {
            append("PRIOR CHECKPOINT (merge this with the new conversation into one consolidated checkpoint):\n")
        } else {
            append("PRIOR SUMMARY (merge this with the new conversation chunk into ONE single consolidated checkpoint; do NOT concatenate separate sections):\n")
        }
        append(carry)
    }
}.trim()

/**
 * 串行滚动摘要（design-multi-pass-compression §3.1 / §3.3 / §4.2）：
 * - `carry` 初始为 `priorSummary`（仅首块作为 PRIOR CHECKPOINT 注入一次，消除旧版每块重复注入）；
 * - 后续每块携带上一块产出的合并摘要 `Si` 作为 `carry`，最终产出 = `Sn`；
 * - 某块命中退化自检（`summaryInternalContainment >= threshold`）时丢弃该块产出、保持上一 `carry` 继续，
 *   并在最终正文尾附 `[skipped: degenerate]` 诚实声明；
 * - fail-fast：任意块抛错直接向上传播（由外层阶梯决定退档或重试，不产生半检查点）。
 */
suspend fun runRollingCompression(
    chunks: List<List<UIMessage>>,
    priorSummary: String,
    summarizeChunk: suspend (chunkIndex: Int, chunk: List<UIMessage>, carry: String) -> String,
    onProgress: (CompressionProgress) -> Unit = {},
    degenerationThreshold: Double = COMPRESS_DEGENERATION_CONTAINMENT_THRESHOLD,
): RollingCompressionResult {
    require(chunks.isNotEmpty()) { "chunks must not be empty" }
    var carry = stripAnchorsSection(priorSummary)
    var anchors = emptyList<CompressionAnchor>()
    var skippedChunks = 0
    val containmentScores = mutableListOf<Double>()

    for ((index, chunk) in chunks.withIndex()) {
        val raw = summarizeChunk(index, chunk, carry)
        val cleanBody = stripAnchorsSection(raw)
        val score = summaryInternalContainment(cleanBody)
        containmentScores += score

        if (cleanBody.isBlank() || score >= degenerationThreshold) {
            skippedChunks++
        } else {
            anchors = mergeAnchors(anchors, extractAnchors(raw))
            carry = cleanBody
        }

        onProgress(
            CompressionProgress(
                stage = CompressionProgressStage.CHUNK,
                current = index + 1,
                total = chunks.size,
            )
        )
    }

    if (carry.isBlank()) {
        throw IllegalStateException("Rolling compression produced no valid summary (skipped=$skippedChunks/${chunks.size})")
    }

    val finalBody = if (skippedChunks > 0) {
        "$carry\n\n$SKIPPED_DEGENERATE_MARKER ($skippedChunks/${chunks.size} chunk(s) omitted due to degeneration)"
    } else {
        carry
    }

    return RollingCompressionResult(
        summaryBody = finalBody,
        anchors = anchors,
        skippedChunks = skippedChunks,
        containmentScores = containmentScores,
    )
}

// ---- §4.1–§4.2 锚点精炼遍（三因子校验 + 三道护栏）----

/** 三因子权重（revision 3：硬事实 0.45 / 锚点 0.35 / 模型核对 0.20） */
const val REFINE_WEIGHT_HARD_FACTS: Double = 0.45
const val REFINE_WEIGHT_ANCHORS: Double = 0.35
const val REFINE_WEIGHT_MODEL: Double = 0.20

/** 精炼输出相对原文长度的最低健全性比例（< 50% 判为弱模型截坏摘要，直接丢弃保留 Sn） */
const val REFINE_MIN_LENGTH_RATIO: Double = 0.50

data class CodeSideCoverageScores(
    val hardFactRatio: Double,
    val anchorRatio: Double,
    val missingHardFacts: List<String>,
    val missingAnchors: List<CompressionAnchor>,
) {
    /** 合并三因子置信度（[modelPassed] 代表精炼遍是否接受/通过） */
    fun combinedConfidence(modelPassed: Boolean): Double =
        hardFactRatio * REFINE_WEIGHT_HARD_FACTS +
            anchorRatio * REFINE_WEIGHT_ANCHORS +
            (if (modelPassed) 1.0 else 0.0) * REFINE_WEIGHT_MODEL
}

/**
 * 计算单条锚点是否被摘要正文覆盖（代码侧确定性判定）：
 * 提取锚点中长度 ≥ 3 的显著词元（路径/数字/版本号/英文标识符/中文词段），命中 ≥ 50% 即判覆盖。
 */
fun isAnchorCovered(anchor: CompressionAnchor, summaryBody: String): Boolean {
    if (summaryBody.contains(anchor.content, ignoreCase = true)) return true
    val tokens = extractAnchorTokens(anchor.content)
    if (tokens.isEmpty()) return true
    val lowerSummary = summaryBody.lowercase()
    val hits = tokens.count { lowerSummary.contains(it) }
    return hits * 2 >= tokens.size
}

private val ANCHOR_TOKEN_REGEX = Regex("""[A-Za-z0-9_./:-]{3,}|[\u4e00-\u9fff]{2,}""")

private fun extractAnchorTokens(content: String): List<String> =
    ANCHOR_TOKEN_REGEX.findAll(content)
        .map { it.value.lowercase() }
        .distinct()
        .toList()

/** 锚点清单覆盖率（无锚点时返回 1.0）。 */
fun anchorCoverageRatio(anchors: List<CompressionAnchor>, summaryBody: String): Double {
    if (anchors.isEmpty()) return 1.0
    val covered = anchors.count { isAnchorCovered(it, summaryBody) }
    return covered.toDouble() / anchors.size
}

/** 运行精炼遍前的代码侧两层核对（硬事实 + 锚点）。 */
fun computeCodeSideScores(
    sourceText: String,
    summaryBody: String,
    anchors: List<CompressionAnchor>,
): CodeSideCoverageScores {
    val facts = extractHardFacts(sourceText)
    val missingFacts = facts.filterNot { summaryBody.contains(it) }
    val factRatio = if (facts.isEmpty()) 1.0 else (facts.size - missingFacts.size).toDouble() / facts.size
    val missingAnchors = anchors.filterNot { isAnchorCovered(it, summaryBody) }
    val anchorRatio = if (anchors.isEmpty()) 1.0 else (anchors.size - missingAnchors.size).toDouble() / anchors.size
    return CodeSideCoverageScores(
        hardFactRatio = factRatio,
        anchorRatio = anchorRatio,
        missingHardFacts = missingFacts,
        missingAnchors = missingAnchors,
    )
}

/** 构造精炼遍提示词（含第一人称规则 D147 + 回声护栏 + 不可得锚点标 `(lost)` 规则）。 */
fun buildRefinePrompt(
    summaryBody: String,
    anchors: List<CompressionAnchor>,
    missingHardFacts: List<String>,
    targetTokens: Int,
    locale: String,
): String = buildString {
    appendLine("You are verifying and refining a conversation checkpoint summary.")
    appendLine("Check the summary below against the anchor checklist and missing exact facts:")
    appendLine("1. Verify every anchor is covered; remove contradictions or duplicated paragraphs.")
    appendLine("2. Write in first person from the assistant's perspective (\"I said\", \"I decided\", \"the user asked me to...\"), NOT a third-person report.")
    appendLine("3. Preserve exact file paths, commands, error strings, identifiers, versions, and numeric values.")
    appendLine("4. If an anchor or fact is missing and can be restored from context inside the summary, weave it in; if it is genuinely unrecoverable from the summary, note it concisely with \"(lost)\" — do NOT fabricate details.")
    appendLine("5. ECHO GUARDRAIL: If you find NO defects, contradictions, duplicates, or missing items, return the summary VERBATIM without rewriting.")
    appendLine("6. Output ONLY the refined checkpoint summary in $locale (target ~$targetTokens tokens). Do NOT output <anchors> tags or commentary.")
    appendLine()
    if (anchors.isNotEmpty()) {
        appendLine("<anchors>")
        anchors.take(MAX_ANCHOR_LINES).forEach { appendLine(it.formatLine()) }
        appendLine("</anchors>")
        appendLine()
    }
    if (missingHardFacts.isNotEmpty()) {
        appendLine("<missing-hard-facts>")
        missingHardFacts.take(20).forEach { appendLine("- $it") }
        appendLine("</missing-hard-facts>")
        appendLine()
    }
    appendLine("<summary>")
    appendLine(summaryBody)
    append("</summary>")
}

data class RefinePassOutcome(
    val summary: String,
    val refined: Boolean,
    val reason: String,
    val scores: CodeSideCoverageScores,
)

/**
 * 执行精炼遍（§4.1 三道护栏 + 覆盖率不退化约束）：
 * 1. **回声指令**：内置于 [buildRefinePrompt]；
 * 2. **长度健全性检查**：输出空白或 `< 50%` 原摘要长度 ⇒ 丢弃精炼结果、保留 `Sn`；
 * 3. **硬事实不退化**：精炼后硬事实命中率低于 `Sn` ⇒ 丢弃精炼结果、保留 `Sn`（阈值只拉低不拉高）；
 * 4. **fail-open**：除 `CancellationException` 外，模型调用异常 ⇒ 保留 `Sn` 继续落库（退回 C1）。
 */
suspend fun runRefinePass(
    sourceText: String,
    rollingResult: RollingCompressionResult,
    targetTokens: Int,
    locale: String,
    refineCall: suspend (prompt: String) -> String,
): RefinePassOutcome {
    val original = rollingResult.summaryBody
    val codeScores = computeCodeSideScores(sourceText, original, rollingResult.anchors)
    val prompt = buildRefinePrompt(
        summaryBody = original,
        anchors = rollingResult.anchors,
        missingHardFacts = codeScores.missingHardFacts,
        targetTokens = targetTokens,
        locale = locale,
    )
    val rawRefined = try {
        refineCall(prompt)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        return RefinePassOutcome(
            summary = original,
            refined = false,
            reason = "fail-open: ${e.message ?: e.javaClass.simpleName}",
            scores = codeScores,
        )
    }

    val cleaned = stripAnchorsSection(rawRefined)
    if (cleaned.isBlank()) {
        return RefinePassOutcome(original, refined = false, reason = "rejected: blank output", scores = codeScores)
    }
    val minChars = (original.length * REFINE_MIN_LENGTH_RATIO).toInt()
    if (cleaned.length < minChars) {
        return RefinePassOutcome(
            summary = original,
            refined = false,
            reason = "rejected: too short (${cleaned.length} < $minChars)",
            scores = codeScores,
        )
    }
    if (isDegenerateSummary(cleaned)) {
        return RefinePassOutcome(
            summary = original,
            refined = false,
            reason = "rejected: degenerate refined output",
            scores = codeScores,
        )
    }
    // 覆盖率不退化保护：若原文有硬事实且精炼版丢掉了原本命中的事实，保留原版 Sn
    val facts = extractHardFacts(sourceText)
    if (facts.isNotEmpty() && coverageRatio(facts, cleaned) < codeScores.hardFactRatio) {
        return RefinePassOutcome(
            summary = original,
            refined = false,
            reason = "rejected: hard-fact coverage regressed",
            scores = codeScores,
        )
    }
    val afterScores = computeCodeSideScores(sourceText, cleaned, rollingResult.anchors)
    return RefinePassOutcome(
        summary = cleaned,
        refined = true,
        reason = "accepted",
        scores = afterScores,
    )
}
