package app.androidrik.rikkahub.data.ai

import app.androidrik.common.tokenizer.TokenizerRegistry
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.MemoryKind
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import app.androidrik.rikkahub.utils.countTokensInParallel
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
 * 一次请求的**不可变**记忆注入计划：L0 纪律、L1 锚点、L2 全文、L3 索引及专用任务尾部通道。
 *
 * 为什么要一次算清：旧实现里 system 的 L1 与尾部的 L2/L3 各自挑各自的（[buildMemoryAnchorPrompt]
 * 与 [buildMemoryDataPrompt]），既可能重复、又无法对「总计费」给出一个可验证的数。现在真实装配和
 * 预览都只读本对象（Lead 的 PromptAssembly/Inspector 接线）。
 *
 * - [disciplineText]/[anchorText] 进 system；[dataText] 进对话尾部；
 * - [anchors]/[fullText]/[indexed]/[tasks] 互不重叠；[omittedCount] 仅统计未显示的话题/画像，
 *   [taskOmittedCount] 单独统计未在 L1/任务块显示的开放事务，关闭事务不计入任何省略告知；
 * - discipline/anchor/data token 是三段实际计数，总计为其和；[taskTokens] 是 [dataTokens]
 *   内的诊断子项，不能再次加到 [totalEstimatedTokens]。
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
    val tasks: List<AssistantMemory> = emptyList(),
    val taskText: String = "",
    val taskTokens: Int = 0,
    val taskOmittedCount: Int = 0,
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
 * 3. 未在 L1 渲染的开放事务先预留最多 800 token / 20 条（首条可超软配额，受最终硬预算约束）；
 * 4. 预留尾部脚手架后，剩余可变预算的 75% 给 **L2**，只收完整话题/画像 JSON；
 * 5. **L3** 吃掉剩余额度，按 `sortKey` 升序整行选取；所有 TASK 均排除于 L2/L3；
 * 6. L1 已渲染的 id 从 L2/L3 排除；被冻结集合排除的非事务锚点仍是普通候选。
 *
 * 最终以**实际渲染字符串**精确复核；若超预算，按「去全索引 → 去全正文 → 去任务正文
 * （保留各自省略告知）→ 去尾块」最多 4 级确定性 fallback 收敛。绝不裁剪正文，
 * total 一定 <= max(0, budgetTokens)。
 *
 * @param frozenAnchorIds `null` = 不冻结；非 null = 只把集合内的 `isAnchor` 记忆算锚点
 *   （集合本身应由调用方用 [anchorIdsOf] 的原始值持久化，不能用本 plan 过滤后的结果）。
 */
/**
 * 与 [buildMemoryInjectionPlan] L2 内部**完全同口径**的逐条全文成本，只是把试渲染与计数
 * 并行化。L2 选中与否是串行贪心，但"每条多贵"彼此独立，可以先行算热（实测该阶段 3.5s）。
 */
internal suspend fun precomputeMemoryEntryCosts(memories: List<AssistantMemory>): Map<String, Int> {
    if (memories.isEmpty()) return emptyMap()
    val emptyTail = renderMemoryDataPrompt(emptyList(), emptyList(), 1)
    val trials = memories.map { renderMemoryDataPrompt(listOf(it), emptyList(), 1) }
    val counts = countTokensInParallel(listOf(emptyTail) + trials)
    val emptyTailCount = counts.first()
    return memories.mapIndexed { index, memory ->
        memory.id to ((counts[index + 1] - emptyTailCount).coerceAtLeast(0) + MEMORY_ENTRY_SEPARATOR_OVERHEAD_TOKENS)
    }.toMap()
}

internal fun buildMemoryInjectionPlan(
    memories: List<AssistantMemory>,
    budgetTokens: Int = DEFAULT_MEMORY_BUDGET_TOKENS,
    now: Long = System.currentTimeMillis(),
    query: String? = null,
    frozenAnchorIds: Set<String>? = null,
    /** 仅诊断：记忆计划内部阶段耗时。 */
    timing: PromptInspectionTiming? = null,
    /** 预计算好的逐条全文成本（并行算热用）；缺省时按需逐条试渲染。 */
    precomputedEntryCosts: Map<String, Int>? = null,
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

    timing?.mark("mem_setup")
    val ordinaryMemories = memories.filter { it.kind != MemoryKind.TASK }
    val openTasks = memories.filter { it.kind == MemoryKind.TASK && it.taskStatus == MemoryTaskStatus.OPEN }

    // 空库 / 非正预算 ⇒ 不产生任何记忆块（连 L0 也不塞，保持「空就是全空」）。
    if (memories.isEmpty() || budgetTokens <= 0) {
        timing?.mark("mem_final")
    return MemoryInjectionPlan(
            budgetTokens = budgetTokens, omittedCount = ordinaryMemories.size,
            taskOmittedCount = openTasks.size, estimationMode = estimationMode,
        )
    }

    timing?.mark("mem_discipline")
    val disciplineText = buildMemoryDisciplinePrompt()
    val disciplineTokens = count(disciplineText)
    if (disciplineTokens > budgetTokens) {
        // 预算连最小纪律层都装不下 ⇒ 全空（调用方据此不产生任何记忆段）。
        return MemoryInjectionPlan(
            budgetTokens = budgetTokens, omittedCount = ordinaryMemories.size,
            taskOmittedCount = openTasks.size, estimationMode = estimationMode,
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

    timing?.mark("mem_anchors")
    // ---- Open tasks: reserve before L2/L3; first whole record may exceed the soft cap. ----
    // Historical task anchors excluded by freezing, quota or L1 budget still need the task channel.
    val taskCandidates = openTasks.filter { it.id !in anchorIds }
        .sortedWith(compareBy<AssistantMemory> { if (it.dueAt > 0L) it.dueAt else Long.MAX_VALUE }
            .thenBy { it.sortKey })
    val tasks = mutableListOf<AssistantMemory>()
    val taskCap = minOf(MEMORY_TASK_BUDGET_TOKENS, (budgetTokens - disciplineTokens - anchorTokens).coerceAtLeast(0))
    taskCandidates.forEach { candidate ->
        if (tasks.size >= MEMORY_TASK_LIMIT) return@forEach
        val trial = tasks + candidate
        val trialText = renderMemoryTaskPrompt(trial, taskCandidates.size - trial.size, now)
        if (tasks.isEmpty() || count(trialText) <= taskCap) tasks += candidate
    }
    var taskOmittedCount = taskCandidates.size - tasks.size
    var taskText = renderMemoryTaskPrompt(tasks, taskOmittedCount, now)
    var taskTokens = count(taskText)
    val ordinaryAnchorCount = anchors.count { it.kind != MemoryKind.TASK }

    timing?.mark("mem_tasks")
    // All-topic scaffold stays byte-for-byte identical. Task text is charged separately.
    val tailScaffoldTokens = count(
        renderMemoryDataPrompt(emptyList(), emptyList(), ordinaryMemories.size, taskText)
    ) - taskTokens
    val remaining = budgetTokens - disciplineTokens - anchorTokens - taskTokens - tailScaffoldTokens

    timing?.mark("mem_scaffold")
    // ---- L2：全文层，占剩余可变预算 75% ----
    val l2Cap = if (remaining > 0) {
        floor(remaining * (1f - MEMORY_INDEX_BUDGET_RATIO)).toInt()
    } else {
        0
    }
    val emptyTailTokens = count(renderMemoryDataPrompt(emptyList(), emptyList(), 1))
    val entryCosts = HashMap<String, Int>()
    precomputedEntryCosts?.let { entryCosts.putAll(it) }
    fun fullEntryCost(memory: AssistantMemory): Int = entryCosts.getOrPut(memory.id) {
        // 单条试渲染 − 空数组：把每条的 id/revision/content 转义与缩进都算进去（保守）。
        (count(renderMemoryDataPrompt(listOf(memory), emptyList(), 1)) - emptyTailTokens)
            .coerceAtLeast(0) + MEMORY_ENTRY_SEPARATOR_OVERHEAD_TOKENS
    }

    val l2Ranked = if (l2Cap > 0) rankedMemoryCandidates(memories, now, query) else emptyList()
    timing?.mark("mem_l2_rank")
    val fullText = mutableListOf<AssistantMemory>()
    if (l2Cap > 0) {
        var used = 0
        l2Ranked.forEach { memory ->
            if (memory.id in anchorIds || memory.id in overflowAnchorIds) return@forEach
            val cost = fullEntryCost(memory)
            if (used + cost <= l2Cap) {
                fullText += memory
                used += cost
            }
        }
    }
    timing?.mark("mem_l2")
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
        ordinaryMemories
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

    timing?.mark("mem_l3")
    // Exact whole-tail billing is authoritative, including for the first-task exemption.
    var omittedCount = omittedOf(ordinaryMemories.size, ordinaryAnchorCount, fullText.size, indexed.size)
    var dataText = renderMemoryDataPrompt(fullText, indexed, omittedCount, taskText)
    var dataTokens = count(dataText)
    fun overBudget() = disciplineTokens + anchorTokens + dataTokens > budgetTokens
    fun rerender() {
        omittedCount = omittedOf(ordinaryMemories.size, ordinaryAnchorCount, fullText.size, indexed.size)
        dataText = renderMemoryDataPrompt(fullText, indexed, omittedCount, taskText)
        dataTokens = count(dataText)
    }
    if (overBudget()) {
        indexed.clear() // 1. index yields before full memories or tasks
        rerender()
    }
    if (overBudget()) {
        fullText.clear() // 2. keep the ordinary all-omitted notice
        rerender()
    }
    if (overBudget()) {
        tasks.clear() // 3. tasks yield last, retaining their own omission notice
        taskOmittedCount = taskCandidates.size
        taskText = renderMemoryTaskPrompt(emptyList(), taskOmittedCount, now)
        taskTokens = count(taskText)
        rerender()
    }
    if (overBudget()) {
        // 4. even notices do not fit; L0+L1 already obey the hard cap.
        dataText = ""
        dataTokens = 0
        taskText = ""
        taskTokens = 0
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
        tasks = tasks.toList(),
        taskText = taskText,
        taskTokens = taskTokens,
        taskOmittedCount = taskOmittedCount,
        disciplineTokens = disciplineTokens,
        anchorTokens = anchorTokens,
        dataTokens = dataTokens,
        estimationMode = estimationMode,
    )
}

/** 省略条数 = 总条数 − 锚点 − 全文 − 索引；锚点在 system 可见，故不计入省略。 */
private fun omittedOf(total: Int, anchors: Int, fullText: Int, indexed: Int): Int =
    (total - anchors - fullText - indexed).coerceAtLeast(0)
