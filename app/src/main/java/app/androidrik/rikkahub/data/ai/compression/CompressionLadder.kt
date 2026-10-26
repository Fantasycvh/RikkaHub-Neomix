package app.androidrik.rikkahub.data.ai.compression

import kotlin.math.roundToInt

/**
 * 压缩失败阶梯（压缩档 C0–C4）与覆盖率闸——`docs/design/design-multi-pass-compression.md` §3.5。
 *
 * 形状借 NMoi-Agent P4.3（其 `docs/compression.md` 自注设计未实现——抄判据不抄「已实现」印象）。
 * 命名用 **C 档**避开本仓记忆分层 L0–L3 的冲突。
 *
 * 本文件落地的三档（C0/C1 待滚动摘要落地后接入同一编排）：
 * - **C3 分块并行**（现状流水线，调用方注入）→ 覆盖率闸；
 * - **C2 单步整体**（同一批内容一次调用）→ 覆盖率闸；原文超 [COMPRESS_SINGLE_STEP_MAX_CHARS]
 *   时调用方传 null 跳过（单步请求自身会超窗，重试无意义）；
 * - **C4 截断 + incomplete 声明**（[buildTruncatedFallback]，确定性、无 LLM、必然成功）。
 *
 * 四条原则（§3.5）：压缩不可重入；退档只许一层（C3→C2→C4，无多层分段）；C4 诚实声明
 * （正文带 `[compression incomplete: …]`，绝不静默降级）；同一次压缩冻结模型（编排方保证——
 * 各档 summarizer 由调用方用同一个 model 构造）。
 *
 * **覆盖率闸**（每档产出后过闸）：从原文提取硬事实（路径 / 版本号 / ≥3 位数字与百分比），
 * 摘要必须**逐字命中** ≥ [DEFAULT_COVERAGE_THRESHOLD]；事实 < [MIN_HARD_FACTS] 条豁免
 * （纯散文不误伤）。这是执行确认而非新要求——默认压缩提示词已要求逐字保留精确信息。
 * 阈值宁松勿严：误杀多付一次调用，漏杀丢事实，代价不对称。
 *
 * 纯函数 + lambda 注入编排 ⇒ 可 JVM 单测（无 Android 依赖）。
 */

/** 覆盖率闸默认阈值：硬事实逐字命中比例（§3.5「宁松勿严」） */
const val DEFAULT_COVERAGE_THRESHOLD: Double = 0.8

/** 硬事实少于此数时豁免（原文没有可核对的精确信息，闸不适用） */
const val MIN_HARD_FACTS: Int = 4

/** C2 单步整体的原文长度上限（字符）：超出则单步请求自身会超窗，跳过该档 */
const val COMPRESS_SINGLE_STEP_MAX_CHARS: Int = 60_000

/** C4 截断兜底的原文保留上限（字符）：≈ 2000 token 量级，与 [COMPRESS_TARGET_TOKENS] 对齐 */
const val COMPRESS_TRUNCATED_FALLBACK_CHARS: Int = 8_000

private val PATH_FACT = Regex("""[A-Za-z0-9_\-]+(?:\.[A-Za-z0-9_\-]+)*/[A-Za-z0-9_\-./]+""")
private val VERSION_FACT = Regex("""\d+(?:\.\d+)+(?:-\w+)*""")
private val PERCENT_FACT = Regex("""\d+(?:\.\d+)?%""")
private val BIG_NUMBER_FACT = Regex("""\d[\d,]{2,}(?:\.\d+)?""")

/**
 * 从原文提取硬事实（NMoi「硬事实命中」因子的提取侧）：文件路径、版本号串、百分比、
 * ≥3 位数字（`12`、`3.5` 这类碎片数字不算——逐字保留的意义集中在可定位的精确值）。
 * 去重后按长度降序取前 [limit] 条；被更长事实**包含**的短事实剔除（`1.0` 之于 `1.0.28`）。
 */
fun extractHardFacts(text: String, limit: Int = 64): List<String> {
    val found = buildList {
        addAll(PATH_FACT.findAll(text).map { it.value })
        addAll(VERSION_FACT.findAll(text).map { it.value })
        addAll(PERCENT_FACT.findAll(text).map { it.value })
        addAll(BIG_NUMBER_FACT.findAll(text).map { it.value })
    }
        .map { it.trim(',').removeSuffix(",").trim() }
        .filter { it.length >= 2 }
        .distinct()
        .sortedByDescending { it.length }
    val kept = mutableListOf<String>()
    for (fact in found) {
        if (kept.size >= limit) break
        // 已保留的更长事实里已含它 ⇒ 命中检查是冗余的
        if (kept.none { it.contains(fact) }) kept += fact
    }
    return kept
}

/** 硬事实被摘要逐字命中的比例；无事实时返回 1.0（无可核对 ≠ 不合格） */
fun coverageRatio(facts: List<String>, summary: String): Double {
    if (facts.isEmpty()) return 1.0
    val covered = facts.count { summary.contains(it) }
    return covered.toDouble() / facts.size
}

/**
 * 覆盖率闸：原文硬事实 ≥ [MIN_HARD_FACTS] 条时，摘要必须逐字命中 ≥ [threshold]；
 * 事实不足（含空原文）⇒ 豁免通过。**空白摘要永远不通过**（事实被豁免时也如此）。
 */
fun coveragePassed(
    source: String,
    summary: String,
    threshold: Double = DEFAULT_COVERAGE_THRESHOLD,
    minFacts: Int = MIN_HARD_FACTS,
): Boolean {
    if (summary.isBlank()) return false
    val facts = extractHardFacts(source)
    if (facts.size < minFacts) return true
    return coverageRatio(facts, summary) >= threshold
}

/** 阶梯档位：C3 分块并行 / C2 单步整体 / C4 截断兜底（C0/C1 未来接入同枚举） */
enum class CompressionTier { CHUNKED, SINGLE, TRUNCATED }

data class CompressionLadderResult(val summary: String, val tier: CompressionTier)

/**
 * 跑 C3→C2→C4 阶梯：每档 LLM 产出先过覆盖率闸（异常 = 失败），不过就退一档；
 * C4 是确定性兜底，必然返回。**退档只许一层**——本函数天然满足（C3/C2 之间不循环重试）。
 *
 * 取消语义：`CancellationException` **重抛**而非当作档位失败——用户取消压缩时绝不能
 * 退到 C4 把截断文本当摘要落库（那比失败更糟）。
 *
 * @param sourceText 覆盖率核对的原文（与喂给 summarizer 的转文本一致）
 * @param chunkedSummarize C3：现状分块并行流水线（可能抛异常——视为该档失败）
 * @param singleSummarize C2：单步整体；null = 原文过长跳过该档
 * @param truncatedFallback C4 兜底文本（调用方用 [buildTruncatedFallback] 构造）
 */
suspend fun runCompressionLadder(
    sourceText: String,
    chunkedSummarize: suspend () -> String,
    singleSummarize: (suspend () -> String)?,
    truncatedFallback: String,
): CompressionLadderResult {
    val chunked = trySummarize(chunkedSummarize)
    if (!chunked.isNullOrBlank() && coveragePassed(sourceText, chunked)) {
        return CompressionLadderResult(chunked, CompressionTier.CHUNKED)
    }
    if (singleSummarize != null) {
        val single = trySummarize(singleSummarize)
        if (!single.isNullOrBlank() && coveragePassed(sourceText, single)) {
            return CompressionLadderResult(single, CompressionTier.SINGLE)
        }
    }
    return CompressionLadderResult(truncatedFallback, CompressionTier.TRUNCATED)
}

/** 档位执行的统一异常边界：取消必须传播，其余异常 = 该档失败（返回 null 退档） */
private suspend fun trySummarize(block: suspend () -> String): String? = try {
    block()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/** C4 声明行：检查点正文里明写「没压过」，绝不静默降级（§3.5 原则 3） */
internal const val TRUNCATED_FALLBACK_NOTICE =
    "[compression incomplete: automated summaries failed the coverage check; " +
        "the text above is a verbatim truncation of the original conversation, not a summary]"

/**
 * 构造 C4 兜底文本：原文头部 [maxChars] 字符 + 声明行。
 * 截断在最近换行处断开（不切碎最后一行），无换行时硬截。
 */
fun buildTruncatedFallback(sourceText: String, maxChars: Int = COMPRESS_TRUNCATED_FALLBACK_CHARS): String {
    val head = if (sourceText.length <= maxChars) {
        sourceText
    } else {
        val cut = sourceText.lastIndexOf('\n', maxChars).takeIf { it > 0 } ?: maxChars
        sourceText.substring(0, cut).trimEnd()
    }
    return head + "\n\n" + TRUNCATED_FALLBACK_NOTICE
}

/** 诊断用：闸的判定细节（给 Log 与未来预览卡） */
data class CoverageDecision(val facts: List<String>, val ratio: Double, val passed: Boolean, val exempt: Boolean)

fun coverageDecision(
    source: String,
    summary: String,
    threshold: Double = DEFAULT_COVERAGE_THRESHOLD,
    minFacts: Int = MIN_HARD_FACTS,
): CoverageDecision {
    val facts = extractHardFacts(source)
    val exempt = facts.size < minFacts
    val ratio = coverageRatio(facts, summary)
    return CoverageDecision(
        facts = facts,
        ratio = (ratio * 100).roundToInt() / 100.0,
        passed = if (exempt) summary.isNotBlank() else ratio >= threshold,
        exempt = exempt,
    )
}
