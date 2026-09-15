package app.androidrik.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.utils.JsonInstantPretty
import app.androidrik.rikkahub.utils.estimateTokens
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

/** 记忆注入默认预算（token）；可经设置覆盖（`Settings.memoryInjectBudgetTokens`）。 */
const val DEFAULT_MEMORY_BUDGET_TOKENS = 8_000

/** 时效衰减半衰期：30 天（对齐 Yuihub）。 */
private const val RECENCY_HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000

/**
 * 每条除正文外的固定开销估算（opaque id + JSON 键名与标点）。
 *
 * 计入它有两个作用：①预算更贴近真实占用（否则漏算 id 与脚手架）；②给「每条至少花
 * 这么多」兜底，天然限制条目数——否则空内容记忆（成本 0）会被无限塞入。
 */
private const val PER_ENTRY_OVERHEAD_TOKENS = 8

/**
 * 分数分档粒度：排序按**量化后**的分数分桶（[SCORE_BUCKET_SIZE] 一档），桶内按 `sortKey` 稳定排序。
 *
 * 为什么要量化：打分含时效衰减 → 分数随时间连续变化 → 若按原始分数排序，相对次序会
 * 不停漂移，**打掉供应商的前缀缓存**（记忆注入在 system prompt 里 = 前缀）。量化后只有
 * 跨桶才改变次序，把漂移频率从「每次请求」降到「天级」。
 * 见 `docs/design-memory-scoring.md` §五。
 *
 * **用「就近取整」而非「向下取整」**：模型倾向于给整数档（0.9/0.8/0.7），而
 * `importance × 1.0`（刚更新）恰好等于该整数→ 向下取整会让这些**最常见的值正好压在
 * 桶边界上**，只要衰减一点点就掉一档（实测 0.8 过一分钟就从桶 8 掉到桶 7）。
 * 就近取整把这些整数放在**桶心**，可稳定数天；边界则落在 0.75/0.85 这类较少出现的值上。
 */
private const val SCORE_BUCKET_SIZE = 0.1f

/**
 * 构建记忆注入提示词。
 *
 * 选谁进 = [selectMemoriesForPrompt]（打分校准 + token 预算截断）；
 * 输出顺序另按 `sortKey` 稳定排列（理由见该函数注释）。
 */
internal fun buildMemoryPrompt(
    memories: List<AssistantMemory>,
    budgetTokens: Int = DEFAULT_MEMORY_BUDGET_TOKENS,
    now: Long = System.currentTimeMillis(),
): String {
    val selected = selectMemoriesForPrompt(memories, budgetTokens, now)
    return buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val json = buildJsonArray {
            selected.forEach { memory ->
                add(buildJsonObject {
                    // 对模型暴露 opaque token（非自增编号）：避免编号泄漏记忆条数、也避免删除空洞难看
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }
}

/**
 * 选出本次注入的记忆：按 `importance × 时效衰减` 打分，在 token 预算内取「最该取」的。
 *
 * **输出顺序按 `sortKey`**（= 写入先后，与记忆页展示一致）而非分数序：分数随时间连续
 * 变化，按分数输出会让 system prompt 前缀**每次请求都漂移**、缓存全废；按 sortKey 输出则
 * 只要「入选集合」不变，前缀字节完全稳定。
 *
 * 与旧「取最新 N 条」的关键区别：旧法是纯时间序，会让「很久没改但很重要」的记忆
 * （身份、强偏好）被新写入的次要记忆挤掉；现在由 importance 保住它们，
 * 由衰减让次要且过期的记忆自然沉底。
 */
internal fun selectMemoriesForPrompt(
    memories: List<AssistantMemory>,
    budgetTokens: Int = DEFAULT_MEMORY_BUDGET_TOKENS,
    now: Long = System.currentTimeMillis(),
): List<AssistantMemory> {
    if (memories.isEmpty()) return emptyList()

    val ranked = memories.sortedWith(
        compareByDescending<AssistantMemory> { memoryScoreBucket(it, now) }
            .thenByDescending { it.sortKey }
    )

    val selected = mutableListOf<AssistantMemory>()
    var used = 0
    for (memory in ranked) {
        val cost = estimateTokens(memory.content) + PER_ENTRY_OVERHEAD_TOKENS
        // 超预算时**跳过本条并继续**（不是 break）：一条长记忆不该把后续预算全堵死，
        // 后面的小记忆仍应有机会入选。`selected.isNotEmpty()` 让首条即使超预算也入选，
        // 避免「预算极小 → 返回空集合」这种最坏结果。
        if (used + cost > budgetTokens && selected.isNotEmpty()) continue
        selected += memory
        used += cost
    }
    return selected.sortedByDescending { it.sortKey }
}

/**
 * 记忆打分：`importance × (0.55 + 0.45 × 时效衰减)`，值域 [0, 1]。
 *
 * 时效衰减半衰期 30 天；`updatedAt <= 0`（未知，如迁移回填前的旧数据）视为**不衰减**——
 * 宁可让它按 importance 参与排序，也不要因缺时间戳被当成「极旧」而静默沉底。
 *
 * 两个因子的分工：importance 让「重要但久未触碰」的记忆留在上下文（身份/强偏好）；
 * 衰减让「次要且过期」的记忆自然退出。
 */
internal fun memoryScore(memory: AssistantMemory, now: Long): Float {
    val recency = if (memory.updatedAt <= 0L) {
        1f
    } else {
        val age = (now - memory.updatedAt).coerceAtLeast(0L)
        exp(-ln(2.0) * age / RECENCY_HALF_LIFE_MS).toFloat()
    }
    return memory.importance.coerceIn(0f, 1f) * (0.55f + 0.45f * recency)
}

/** 量化到 [SCORE_BUCKET_SIZE] 一档的桶号（排序用，取整策略见该常量注释）。 */
internal fun memoryScoreBucket(memory: AssistantMemory, now: Long): Int =
    floor(memoryScore(memory, now) / SCORE_BUCKET_SIZE + 0.5f).toInt()
