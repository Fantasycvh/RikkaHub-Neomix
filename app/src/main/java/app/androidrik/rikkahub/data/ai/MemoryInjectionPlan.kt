package app.androidrik.rikkahub.data.ai

import app.androidrik.common.tokenizer.TokenizerRegistry
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.utils.heuristicTokenCount
import kotlin.math.floor

/** 计费口径：使用创建 plan 时捕获的 tokenizer 实例（不是 provider 精确账单）。 */
internal const val MEMORY_ESTIMATION_TOKENIZER = "tokenizer"

/** 计费口径：tokenizer 未就绪，本 plan 锁定字符启发式。 */
internal const val MEMORY_ESTIMATION_HEURISTIC = "heuristic"

/**
 * L2 每条 JSON 的分隔/缩进保守开销（token）。
 *
 * 预算选取用的是**单条试渲染**的成本（见 [buildMemoryInjectionPlan]），数组里的逗号与换行
 * 每条约 1–2 token；加这个常量让选取偏保守——多留的余量由 L3 或最终精确复核兜住。
 */
private const val MEMORY_ENTRY_SEPARATOR_OVERHEAD_TOKENS = 2

/**
 * 一次请求的**不可变**记忆注入计划：L0 纪律、L1 锚点、L2 全文、L3 索引四层一次算清。
 *
 * 为什么要一次算清：旧实现里 system 的 L1 与尾部的 L2/L3 各自挑各自的（[buildMemoryAnchorPrompt]
 * 与 [buildMemoryDataPrompt]），既可能重复、又无法对「总计费」给出一个可验证的数。现在真实装配和
 * 预览都只读本对象（Lead 的 PromptAssembly/Inspector 接线）。
 *
 * - [disciplineText]/[anchorText] 进 system；[dataText] 进对话尾部；
 * - [anchors]/[fullText]/[indexed] 三层互不重叠，[omittedCount] 是「既不在锚点、也不在全文/
 *   索引里」的条数（锚点在 system 里可见，所以不算省略）；
 * - 三个 token 字段都是**对应渲染字符串**的实际计数，[totalEstimatedTokens] 是它们的和。
 *
 * 计费策略在 plan 创建时锁定一次（[estimationMode]），不会出现同一块文本前后两种口径。
 */
internal data class MemoryInjectionPlan(
    val budgetTokens: Int = 0,
    val disciplineText: String = "",
    val anchorText: String = "",
    val dataText: String = "",
    val anchors: List<AssistantMemory> = emptyList(),
    val fullText: List<AssistantMemory> = emptyList(),
    val indexed: List<AssistantMemory> = emptyList(),
    val omittedCount: Int = 0,
    val disciplineTokens: Int = 0,
    val anchorTokens: Int = 0,
    val dataTokens: Int = 0,
    val estimationMode: String = MEMORY_ESTIMATION_HEURISTIC,
) {
    /** 总计费 = 三段实际渲染文本的计数之和（**不是** content 估算之和）。 */
    val totalEstimatedTokens: Int get() = disciplineTokens + anchorTokens + dataTokens
}

/**
 * 构建一次请求的记忆注入计划（纯规划，无副作用）。
 *
 * 打包顺序与硬边界（Lead 已裁定）：
 * 1. **L0** 纪律层先计；预算非正、空库、或连 L0 都装不下 ⇒ 全部为空；
 * 2. **L1** 对最多 5 条锚点**逐条整块试渲染**，system 小计装得下才收；放不下的锚点不进 L2
 *    全文（否则等于绕过 L1 预算），但允许进 L3 索引；
 * 3. 预留尾部脚手架（前导 + 空数组 + 省略告知）后，剩余可变预算的 75% 给 **L2**：
 *    按 [rankedMemoryCandidates] 顺序整条选取，只收完整 JSON，**绝不截断正文**；
 * 4. **L3** 吃掉全部剩余额度（先扣清单标题），按 `sortKey` 升序整行选取；
 * 5. L1 已渲染的 id 从 L2/L3 排除；被冻结集合排除的锚点仍是普通 L2 候选。
 *
 * 最终以**实际渲染字符串**精确复核；若超预算，按「去全索引 → 去全正文 → 去尾块」最多
 * 3 级确定性 fallback 收敛。任何一级都不裁剪正文，因此 total 一定 <= max(0, budgetTokens)。
 *
 * @param frozenAnchorIds `null` = 不冻结；非 null = 只把集合内的 `isAnchor` 记忆算锚点
 *   （集合本身应由调用方用 [anchorIdsOf] 的原始值持久化，不能用本 plan 过滤后的结果）。
 */
internal fun buildMemoryInjectionPlan(
    memories: List<AssistantMemory>,
    budgetTokens: Int = DEFAULT_MEMORY_BUDGET_TOKENS,
    now: Long = System.currentTimeMillis(),
    query: String? = null,
    frozenAnchorIds: Set<String>? = null,
): MemoryInjectionPlan {
    // 计费策略**每个 plan 只取一次**：中途 tokenizer 预热完成/卸载都不会让同一块文本出现
    // 两种口径的计数（否则字段与真实渲染对不上）。
    val tokenizer = TokenizerRegistry.current
    val estimationMode = if (tokenizer != null) MEMORY_ESTIMATION_TOKENIZER else MEMORY_ESTIMATION_HEURISTIC
    val count: (String) -> Int = if (tokenizer != null) {
        { text ->
            val cached = TokenizerRegistry.count(text)
            // Keep the captured counter even if the registry is released/reinstalled during planning.
            if (cached != null && TokenizerRegistry.current === tokenizer) cached else tokenizer.count(text)
        }
    } else {
        { text -> heuristicTokenCount(text) }
    }

    // 空库 / 非正预算 ⇒ 不产生任何记忆块（连 L0 也不塞，保持「空就是全空」）。
    if (memories.isEmpty() || budgetTokens <= 0) {
        return MemoryInjectionPlan(
            budgetTokens = budgetTokens, omittedCount = memories.size, estimationMode = estimationMode,
        )
    }

    val disciplineText = buildMemoryDisciplinePrompt()
    val disciplineTokens = count(disciplineText)
    if (disciplineTokens > budgetTokens) {
        // 预算连最小纪律层都装不下 ⇒ 全空（调用方据此不产生任何记忆段）。
        return MemoryInjectionPlan(
            budgetTokens = budgetTokens, omittedCount = memories.size, estimationMode = estimationMode,
        )
    }

    // ---- L1：锚点逐条整块试渲染（最多 5 次）----
    val anchorCandidates = selectAnchorMemories(memories, MEMORY_ANCHOR_LIMIT, frozenAnchorIds)
    val anchors = mutableListOf<AssistantMemory>()
    val overflowAnchorIds = HashSet<String>()
    var anchorText = ""
    var anchorTokens = 0
    anchorCandidates.forEach { candidate ->
        val trial = anchors + candidate
        val trialText = buildMemoryAnchorPrompt(trial, limit = trial.size)
        val trialTokens = count(trialText)
        if (disciplineTokens + trialTokens <= budgetTokens) {
            anchors += candidate
            anchorText = trialText
            anchorTokens = trialTokens
        } else {
            // 放不进 system 的锚点：不进 L2 全文，但可进 L3 索引（溢出锚点 index-only）。
            overflowAnchorIds += candidate.id
        }
    }
    val anchorIds = anchors.mapTo(HashSet()) { it.id }

    // ---- 尾部脚手架：前导行 + 空 JSON 数组 + 省略告知 ----
    // 按「全部省略」预留（位数最多），最终精确复核兜底。
    val tailScaffoldTokens = count(
        renderMemoryDataPrompt(emptyList(), emptyList(), omittedCount = memories.size)
    )
    val remaining = budgetTokens - disciplineTokens - anchorTokens - tailScaffoldTokens

    // ---- L2：全文层，占剩余可变预算 75% ----
    val l2Cap = if (remaining > 0) {
        floor(remaining * (1f - MEMORY_INDEX_BUDGET_RATIO)).toInt()
    } else {
        0
    }
    val emptyTailTokens = count(renderMemoryDataPrompt(emptyList(), emptyList(), 1))
    val entryCosts = HashMap<String, Int>()
    fun fullEntryCost(memory: AssistantMemory): Int = entryCosts.getOrPut(memory.id) {
        // 单条试渲染 − 空数组：把每条的 id/revision/content 转义与缩进都算进去（保守）。
        (count(renderMemoryDataPrompt(listOf(memory), emptyList(), 1)) - emptyTailTokens)
            .coerceAtLeast(0) + MEMORY_ENTRY_SEPARATOR_OVERHEAD_TOKENS
    }

    val fullText = mutableListOf<AssistantMemory>()
    if (l2Cap > 0) {
        var used = 0
        rankedMemoryCandidates(memories, now, query).forEach { memory ->
            if (memory.id in anchorIds || memory.id in overflowAnchorIds) return@forEach
            val cost = fullEntryCost(memory)
            if (used + cost <= l2Cap) {
                fullText += memory
                used += cost
            }
        }
    }
    // 最终 L2 顺序与旧实现一致：sortKey 降序（写入新的在前），集合不变则字节稳定。
    fullText.sortByDescending { it.sortKey }
    // 选取阶段的保守成本之和，用于估算 L3 还能吃多少。
    val fullTextProvisionalTokens = fullText.sumOf { fullEntryCost(it) }

    // ---- L3：索引层，吃光剩余额度（先扣清单标题的固定开销）----
    val indexHeaderTokens = count(memoryIndexHeaderText())
    val fullIds = fullText.mapTo(HashSet()) { it.id }
    val indexed = mutableListOf<AssistantMemory>()
    val l3Cap = remaining - fullTextProvisionalTokens - indexHeaderTokens
    if (l3Cap > 0) {
        val lineCosts = HashMap<String, Int>()
        fun indexLineCost(memory: AssistantMemory): Int = lineCosts.getOrPut(memory.id) {
            count(indexLine(memory) + "\n")
        }
        var used = 0
        // 原 sortKey 升序；不套用全文层的相关性下限（否则又会「静默消失」）。
        memories
            .filter { it.id !in anchorIds && it.id !in fullIds }
            .sortedBy { it.sortKey }
            .forEach { memory ->
                val cost = indexLineCost(memory)
                if (used + cost <= l3Cap) {
                    indexed += memory
                    used += cost
                }
            }
    }

    // ---- 精确复核 + 最多 3 级确定性 fallback（绝不裁剪正文）----
    var omittedCount = omittedOf(memories.size, anchors.size, fullText.size, indexed.size)
    var dataText = renderMemoryDataPrompt(fullText, indexed, omittedCount)
    var dataTokens = count(dataText)

    if (disciplineTokens + anchorTokens + dataTokens > budgetTokens) {
        // fallback 1：去掉全部索引，重新渲染（省略数随之更新）。
        val omittedWithoutIndex = omittedOf(memories.size, anchors.size, fullText.size, 0)
        val textWithoutIndex = renderMemoryDataPrompt(fullText, emptyList(), omittedWithoutIndex)
        val tokensWithoutIndex = count(textWithoutIndex)
        if (disciplineTokens + anchorTokens + tokensWithoutIndex <= budgetTokens) {
            indexed.clear()
            omittedCount = omittedWithoutIndex
            dataText = textWithoutIndex
            dataTokens = tokensWithoutIndex
        } else {
            // fallback 2：去掉全部正文，只留 all-omitted 告知。
            val allOmitted = omittedOf(memories.size, anchors.size, 0, 0)
            val allOmittedText = renderMemoryDataPrompt(emptyList(), emptyList(), allOmitted)
            val allOmittedTokens = count(allOmittedText)
            if (disciplineTokens + anchorTokens + allOmittedTokens <= budgetTokens) {
                fullText.clear()
                indexed.clear()
                omittedCount = allOmitted
                dataText = allOmittedText
                dataTokens = allOmittedTokens
            } else {
                // fallback 3：尾部整块丢弃；L0+L1 已保证装得下。
                fullText.clear()
                indexed.clear()
                omittedCount = allOmitted
                dataText = ""
                dataTokens = 0
            }
        }
    }

    return MemoryInjectionPlan(
        budgetTokens = budgetTokens,
        disciplineText = disciplineText,
        anchorText = anchorText,
        dataText = dataText,
        anchors = anchors.toList(),
        fullText = fullText.toList(),
        indexed = indexed.toList(),
        omittedCount = omittedCount,
        disciplineTokens = disciplineTokens,
        anchorTokens = anchorTokens,
        dataTokens = dataTokens,
        estimationMode = estimationMode,
    )
}

/** 省略条数 = 总条数 − 锚点 − 全文 − 索引；锚点在 system 可见，故不计入省略。 */
private fun omittedOf(total: Int, anchors: Int, fullText: Int, indexed: Int): Int =
    (total - anchors - fullText - indexed).coerceAtLeast(0)
