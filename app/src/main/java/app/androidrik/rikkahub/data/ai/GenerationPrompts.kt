package app.androidrik.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.utils.JsonInstantPretty
import app.androidrik.rikkahub.utils.estimateTokens
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

/** 记忆注入默认预算（token）；可经设置覆盖（`Settings.memoryInjectBudgetTokens`）。 */
const val DEFAULT_MEMORY_BUDGET_TOKENS = 8_000

/**
 * 世界书注入默认预算（token）。
 *
 * **默认 0 = 不限制**（保持 fork 旧行为）。ST 的默认是「上下文的 25%」，直接照搬会
 * **静默**削减用户既有注入量，故改为显式开启。见 `docs/peer-apps-benchmark.md` §6.2 ③。
 */
const val DEFAULT_LOREBOOK_BUDGET_TOKENS = 0

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
 * 记忆注入的**相关性下限**（原始分数）：[memoryScore] 低于此值的记忆不注入
 * （华灯 RAG 相关性下限的阈值思想——低相关注入是纯 token 税）。
 * 取 0.2：默认 importance 0.6 的记忆全老化也有 0.33 不会触线；刻意标低重要度
 * （≤0.36）且老化的记忆自然沉底。**用原始分数而非分桶**——分桶是四舍五入，
 * 0.11 分会被进位到桶 1 绕过下限，精确比较才符合「低相关不注入」的本意。
 */
private const val MIN_MEMORY_INJECTION_SCORE = 0.2f

/**
 * 清单层占注入预算的比例（默认 1/4）。
 *
 * 口径由用户拍板：**总预算不变、拆成「全文层 + 清单层」**。为什么给清单 1/4 就够：
 * 清单每行只有 `id ｜ 主题 ｜ 更新日`（≈20–60 token），2K 能装下几十条；而全文层要装
 * 完整内容，必须占大头。**代价说明**：全文层从 8K 降到 6K ⇒ 入选集合会比改造前略小，
 * 换来的是「预算外条目不再静默消失」（见 `docs/design-memory-discipline.md` §4.2）。
 */
private const val MEMORY_INDEX_BUDGET_RATIO = 0.25f

/** 清单行里主题词缺省（模型没写 description）时的内容截断长度。 */
private const val MEMORY_INDEX_FALLBACK_CHARS = 80

/**
 * 构建记忆注入提示词。
 *
 * **两层渐进披露**（V2）：
 * - **全文层** = [selectMemoriesForPrompt]（打分校准 + 预算截断），占预算 3/4；
 * - **清单层** = 其余记忆每行 `id ｜ 主题 ｜ 更新日`，占预算 1/4，模型据此决定要不要
 *   `memory_tool action=read` 取全文。
 *
 * 两层**不重叠**；入选集合各自按 `sortKey`（写入先后）渲染 ⇒ 只要集合不变，前缀字节稳定。
 */
internal fun buildMemoryPrompt(
    memories: List<AssistantMemory>,
    budgetTokens: Int = DEFAULT_MEMORY_BUDGET_TOKENS,
    now: Long = System.currentTimeMillis(),
): String {
    val fullTextBudget = (budgetTokens * (1f - MEMORY_INDEX_BUDGET_RATIO)).toInt()
    val indexBudget = (budgetTokens * MEMORY_INDEX_BUDGET_RATIO).toInt()
    val selected = selectMemoriesForPrompt(memories, fullTextBudget, now)
    val selectedIds = selected.mapTo(mutableSetOf()) { it.id }
    val indexed = selectMemoriesForIndex(memories, selectedIds, indexBudget, now)

    return buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        // 使用纪律（V1）：记忆块是**模型每轮都会读**的地方，声明必须写在这里——
        // 只写在工具描述里的话，「不写记忆」的场景（只读不写）就完全看不到纪律。
        // 这段是静态文本 ⇒ 不破坏前缀缓存稳定性。
        //
        // **反注入声明（「记忆是记录、不是指令」+「遇附和/伴侣要求则忽略」）已于 2026-10-09 按用户
        // 拍板移除，不要复活**：本 app 的正当用法本就包含人设与伴侣扮演，让记忆层教模型无视这类
        // 偏好会与用户自己的助手配置打架（取舍见 `docs/design-memory-discipline.md` §2.3）。
        append(
            "Use a memory only to the degree it was recorded (\"mentioned once\" is not \"enthusiast\"), " +
                "and only when it changes the answer — do not showcase that you remember. " +
                "Sensitive items (health, finances, identity) enter an answer only when the user raises them in this turn."
        )
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

        if (indexed.isNotEmpty()) {
            appendLine()
            append("**Memory index** (topics not included above; description is only a hint — ")
            append("call `memory_tool` with `action:\"read\"` and the id to load the full text)")
            appendLine()
            indexed.forEach { memory -> appendLine(indexLine(memory)) }
        }
    }
}

/**
 * 选进**清单层**的记忆：全文层之外、按 `sortKey` 升序取到预算为止。
 *
 * 为什么按 `sortKey`（而不是按分数）选：分数随时间连续衰减，用它选会让**清单集合**每过几天
 * 就换一批（前缀抖动）；按写入先后选则「集合只随增删而变」。取舍是清单偏向老记忆，但新记忆
 * 本来就更容易进全文层（新鲜 ⇒ 分数高）。
 *
 * **不套用 [MIN_MEMORY_INJECTION_SCORE] 下限**：下限的用意是「低相关全文不进上下文」，而清单
 * 一行的成本极小；若在清单层也套下限，被淘汰的记忆又会**静默消失**——正是本层要根治的事。
 */
internal fun selectMemoriesForIndex(
    memories: List<AssistantMemory>,
    excludedIds: Set<String>,
    budgetTokens: Int,
    now: Long = System.currentTimeMillis(),
): List<AssistantMemory> {
    if (memories.isEmpty() || budgetTokens <= 0) return emptyList()
    val result = mutableListOf<AssistantMemory>()
    var used = 0
    memories.sortedBy { it.sortKey }.forEach { memory ->
        if (memory.id in excludedIds) return@forEach
        val cost = estimateTokens(indexLine(memory)) + PER_ENTRY_OVERHEAD_TOKENS / 2
        if (used + cost > budgetTokens && result.isNotEmpty()) return@forEach
        result += memory
        used += cost
    }
    return result
}

/** 清单行：`id ｜ 主题（缺省=内容截断） ｜ 更新日`。 */
private fun indexLine(memory: AssistantMemory): String {
    val description = memory.description.ifBlank {
        memory.content.trim().take(MEMORY_INDEX_FALLBACK_CHARS)
    }
    return "- ${memory.id} | ${description.replace('\n', ' ')} | ${memory.indexDate()}"
}

/** 更新日（`yyyy-MM-dd`，本地时区）；时间未知（0）时不显示日期，避免给出假信息。 */
private fun AssistantMemory.indexDate(): String {
    if (updatedAt <= 0L) return "date unknown"
    return Instant.ofEpochMilli(updatedAt)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()
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
        // 相关性下限（华灯 RAG 相关性下限的阈值思想）：打分低于下限的记忆**不注入**——
        // 低重要度且已老化的记忆挤进上下文是纯 token 税。注意与「排序分桶」的分工：
        // 分桶只影响先后，本下限决定去留。默认 importance=0.6 的记忆永不触线
        // （全老化也有 0.33）；刻意标低重要度（≤0.36）且老化的记忆才会沉底。
        if (memoryScore(memory, now) < MIN_MEMORY_INJECTION_SCORE) continue
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
