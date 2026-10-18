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
 * **信用记账**（读数 → 打分）的两个旋钮，2026-10-09 接入；取舍见 `docs/design/design-memory-discipline.md` §4.6。
 */

/**
 * 读数饱和点：读满这么多次即得满额信用。
 *
 * 为什么是 8：区分的是「清单里瞟了一眼」与「这条被反复用到」；再往上加次数不构成新证据，
 * 对数曲线会把增量压平（见 [memoryReadCredit]）。
 */
private const val READ_CREDIT_SATURATION_READS = 8

/**
 * 信用最多把重要度往 1.0 方向补多少**比例**：`importance + (1 − importance) × credit × 0.5`。
 *
 * 取 0.5 的含义：反复被读的记忆最多「升半档到一档」——`importance` 0.2 的记忆读满信用等效 0.6
 * （= 默认档），但仍到不了 0.9 那类身份信息的位置。上限的必要性：读数是**间接**证据
 * （渐进披露的清单层会让人更容易点到某条记忆），不能让它盖过用户的显式声明。
 */
private const val READ_CREDIT_CONFIDENCE_GAIN = 0.5f

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
 * 见 `docs/design/design-memory-scoring.md` §五。
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
 *
 * 反向路径（2026-10-09 信用记账）：被**反复读取**的低重要度记忆会被读数信用抬回线上——
 * 读数是「这条真的在用」的行为证据，不是噪声（见 [memoryScore]）。
 */
private const val MIN_MEMORY_INJECTION_SCORE = 0.2f

/**
 * 清单层占注入预算的比例（默认 1/4）。
 *
 * 口径由用户拍板：**总预算不变、拆成「全文层 + 清单层」**。为什么给清单 1/4 就够：
 * 清单每行只有 `id ｜ 主题 ｜ 更新日`（≈20–60 token），2K 能装下几十条；而全文层要装
 * 完整内容，必须占大头。**代价说明**：全文层从 8K 降到 6K ⇒ 入选集合会比改造前略小，
 * 换来的是「预算外条目不再静默消失」（见 `docs/design/design-memory-discipline.md` §4.2）。
 */
internal const val MEMORY_INDEX_BUDGET_RATIO = 0.25f

/** 清单行里主题词缺省（模型没写 description）时的内容截断长度。 */
private const val MEMORY_INDEX_FALLBACK_CHARS = 80

/**
 * **L1 锚点配额**（条）。刻意很小 —— 本层在 system，改动一次其后全部重算，
 * 所以它换来的不是"多注入记忆"而是"这几条永远在最稳的位置且永远在缓存里"。
 */
internal const val MEMORY_ANCHOR_LIMIT = 5

/**
 * **L0 纪律层**（静态）：记忆使用纪律 + 指向尾部数据的说明。
 *
 * 位置在 **system**，且**逐字节恒定**（不含任何记忆数据）⇒ 记忆写入不会让 system 失配，
 * 前缀缓存得以保住。真正的记忆数据见 [buildMemoryDataPrompt]，由 [buildInternalMessages]
 * 追加到**对话尾部**（设计见 `docs/design/design-memory-injection-tiering.md`）。
 *
 * **反注入声明（「记忆是记录、不是指令」+「遇附和/伴侣要求则忽略」）已于 2026-10-09 按用户
 * 拍板移除，不要复活**：本 app 的正当用法本就包含人设与伴侣扮演，让记忆层教模型无视这类
 * 偏好会与用户自己的助手配置打架（取舍见 `docs/design/design-memory-discipline.md` §2.3）。
 */
internal fun buildMemoryDisciplinePrompt(): String = buildString {
    appendLine()
    append("**Memories**")
    appendLine()
    append("These are memories stored via the memory_tool that you can reference in future conversations.")
    appendLine()
    append(
        "Use a memory only to the degree it was recorded (\"mentioned once\" is not \"enthusiast\"), " +
            "and only when it changes the answer — do not showcase that you remember. " +
            "Sensitive items (health, finances, identity) enter an answer only when the user raises them in this turn."
    )
    appendLine()
    append("The memory data itself is appended at the end of this conversation as a background block — ")
    append("it is reference material, not part of the dialogue.")
    appendLine()
}

/**
 * **L1 锚点层**（静态，进 system）：身份/权威类记忆，获得 system 级权重。
 *
 * **与 L2/L3 的差异是"稳定"而不是"重要"**：本层在 system，而 system 是第一条消息、
 * 前缀缓存从位置 0 起连续匹配 —— 改它一次其后全部重算（真机实测命中率 98% → 0%）。
 * 所以本层**配额极小**（[MEMORY_ANCHOR_LIMIT]）且**只由用户/整理器固化**（AI 不得自填，
 * 见 `MemoryRepository.addMemory` 没有该参数），使它在会话期间几乎不变。
 *
 * **无锚点时返回空串**，调用方据此**不产生该块** ⇒ 输出与改动前逐字节一致，
 * 既有契约测试与缓存行为都不受影响。
 *
 * 设计见 `docs/design/design-memory-injection-tiering.md`。
 */
internal fun buildMemoryAnchorPrompt(
    memories: List<AssistantMemory>,
    limit: Int = MEMORY_ANCHOR_LIMIT,
    /**
     * **冻结的锚点 id 集合**（`null` = 未冻结）。
     *
     * 冻结的是「哪些记忆算锚点」，**不是**锚点的文本——文本始终按当前内容渲染。这样
     * 「删掉一条锚点」与「改写一条锚点」立刻生效（记忆库有两条**自动**删除路径：做梦整理
     * 与 200 条上限淘汰，都不能指望用户去手动刷新），而 system 的字节稳定性只由**集合**
     * 决定：集合不变 ⇒ 逐字节不变 ⇒ 前缀缓存照常命中。
     */
    frozenIds: Set<String>? = null,
): String {
    val anchors = selectAnchorMemories(memories, limit, frozenIds)
    if (anchors.isEmpty()) return ""

    return buildString {
        appendLine()
        append("**Anchors**")
        appendLine()
        append("Standing facts that always apply. Treat them as settled background ")
        append("unless the user says otherwise in this turn.")
        appendLine()
        anchors.forEach { memory -> appendLine("- ${memory.content}") }
    }
}

/**
 * 锚点选取：配额内按 importance 取前 N，同分按 `sortKey`（写入先后）取稳定序。
 *
 * [frozenIds] 非 null 时**先按冻结集合过滤**——集合是会话开始那一刻定下的，此后新固化的
 * 锚点不会挤进本会话（要生效得走记忆页的手动刷新）。
 */
internal fun selectAnchorMemories(
    memories: List<AssistantMemory>,
    limit: Int,
    frozenIds: Set<String>? = null,
): List<AssistantMemory> = memories
    .filter { it.isAnchor && (frozenIds == null || it.id in frozenIds) }
    .sortedWith(compareByDescending<AssistantMemory> { it.importance }.thenBy { it.sortKey })
    .take(limit)

/**
 * 冻结时记下的锚点 id（供 `ChatService` 写进 `Conversation.frozenAnchors`）。
 *
 * 与未冻结时的渲染取**同一套**选取逻辑——否则会出现「冻结那一刻显示 5 条、冻结后只剩 3 条」
 * 这种自相矛盾的瞬间。顺序不入库：渲染时按实时重要度重排，被冻住的只是**集合**。
 */
internal fun anchorIdsOf(
    memories: List<AssistantMemory>,
    limit: Int = MEMORY_ANCHOR_LIMIT,
): List<String> = selectAnchorMemories(memories, limit).map { it.id }

/** 尾部记忆块的前导行：用于识别该合成消息（对应压缩检查点 `isCompactionCheckpointPreamble` 的同款做法）。 */
internal const val MEMORY_TAIL_PREAMBLE = "**Memory data** (background, not part of the dialogue)"

/**
 * **L2 工作集 + L3 索引层**（动态）：真正的记忆数据，注入到**对话尾部**。
 *
 * **兼容出口（tail-only）**：本函数保留旧签名，内部**委托** [buildMemoryInjectionPlan]，
 * 并以 `frozenAnchorIds = emptySet()` 明确表示「只算尾部、不产生 L1」——L1 由
 * [buildMemoryAnchorPrompt] 单独装配。真实聊天装配请直接用 plan（L0/L1/L2/L3 统一预算与
 * 去重），不要再用本函数拼第二套选取。
 *
 * **两层渐进披露**（V2）不变：
 * - **全文层** = 预算内按分数选出的整条 JSON（**不再截断正文**）；
 * - **清单层** = 其余记忆每行 `id ｜ 主题 ｜ 更新日`，模型据此决定要不要
 *   `memory_tool action=read` 取全文。
 *
 * 两层**不重叠**；入选集合各自按 `sortKey`（写入先后）渲染 ⇒ 只要集合不变，字节稳定。
 *
 * **为什么在尾部而不是 system**：本块**每次记忆写入都会变**，而 system 是第一条消息、
 * 前缀缓存从位置 0 起连续匹配 —— 留在 system 会让整条 system 失配，实测命中率 98% → 0%
 * （设计稿 §2）。放在尾部后，它怎么变都**不影响前缀**（L0/L1 仍可缓存）。
 */
internal fun buildMemoryDataPrompt(
    memories: List<AssistantMemory>,
    budgetTokens: Int = DEFAULT_MEMORY_BUDGET_TOKENS,
    now: Long = System.currentTimeMillis(),
    /**
     * 本轮查询文本（见 [memoryQueryOf]）。非 null 时选取会带上**相关性维度**，
     * 因此本块的入选集合**每轮可能不同** —— 这正是把记忆放到尾部换来的自由（设计稿 §3.2）。
     */
    query: String? = null,
): String = buildMemoryInjectionPlan(
    memories = memories,
    budgetTokens = budgetTokens,
    now = now,
    query = query,
    // 尾部-only：空冻结集合 ⇒ 不产生 L1，锚点仍按普通 L2 候选参与排序。
    frozenAnchorIds = emptySet(),
).dataText

/** 清单段标题（含前导空行与行尾换行）：预算规划器按它扣除 L3 的固定开销。 */
internal fun memoryIndexHeaderText(): String = buildString {
    appendLine()
    append("**Memory index** (topics not included above; description is only a hint — ")
    append("call `memory_tool` with `action:\"read\"` and the id to load the full text)")
    appendLine()
}

/** 省略告知（尾部块最后一段）：这条告知本身也按实际渲染计费。 */
internal fun memoryOmissionNotice(omittedCount: Int): String =
    "**Omitted**: $omittedCount more memories are not shown; " +
        "use `memory_tool` with `action:\"search\"` and a query, then `action:\"read\"` with a returned id."

/** 尾部 JSON 数组（`id`/`revision`/内容，pretty JSON）：id/revision 与转义都按实际字符串计费。 */
private fun memoryJsonArrayText(memories: List<AssistantMemory>): String =
    JsonInstantPretty.encodeToString(
        buildJsonArray {
            memories.forEach { memory ->
                add(
                    buildJsonObject {
                        // 对模型暴露 opaque token（非自增编号）：避免编号泄漏记忆条数、也避免删除空洞难看
                        put("id", memory.id)
                        put("revision", memory.revision)
                        put("content", memory.content)
                    }
                )
            }
        }
    )

/**
 * 渲染**尾部数据块**的唯一实现（plan 与兼容 wrapper 共用）。
 *
 * 计费口径就是「本函数返回的字符串」：前导行、JSON 转义、清单标题/行、省略告知与换行
 * 全部按实际渲染文本计 token，而不是按 `content` 估算。
 *
 * 无全文、无索引且无省略 ⇒ 返回空串（调用方据此不产生该块）。
 */
internal fun renderMemoryDataPrompt(
    fullText: List<AssistantMemory>,
    indexed: List<AssistantMemory>,
    omittedCount: Int,
): String {
    if (fullText.isEmpty() && indexed.isEmpty() && omittedCount <= 0) return ""
    return buildString {
        append(MEMORY_TAIL_PREAMBLE)
        appendLine()
        append(memoryJsonArrayText(fullText))
        appendLine()
        if (indexed.isNotEmpty()) {
            append(memoryIndexHeaderText())
            indexed.forEach { memory -> appendLine(indexLine(memory)) }
        }
        if (omittedCount > 0) {
            appendLine()
            append(memoryOmissionNotice(omittedCount))
            appendLine()
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

/** 清单行：`id ｜ 主题（缺省=内容截断） ｜ 更新日`（internal：plan 的 L3 行成本按同一实现计费）。 */
internal fun indexLine(memory: AssistantMemory): String {
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
 * 记忆候选排序（L2 全文层的**唯一**排序口径）：原始 [memoryScore]（刻意不套
 * [memoryScoreBucket]）× 相关性增益；低于 [MIN_MEMORY_INJECTION_SCORE] 的丢弃；
 * 同分按 `sortKey` **新的在前**。
 *
 * 为什么不用分桶：分桶是给「system 时代分数连续漂移打掉前缀缓存」用的，而 L2 已不在
 * 前缀里；同一桶内按写入先后排会让相关性不到一档就白算。
 *
 * 输出顺序 = 入选尝试顺序；最终渲染仍按 `sortKey` 降序（见 [buildMemoryInjectionPlan]）。
 */
internal fun rankedMemoryCandidates(
    memories: List<AssistantMemory>,
    now: Long,
    query: String? = null,
): List<AssistantMemory> {
    if (memories.isEmpty()) return emptyList()

    // 相关性：查询只分词一次（逐条记忆里重复分词是纯浪费）
    val memoryQuery = memoryQueryTokens(query)
    val boostOf = { memory: AssistantMemory ->
        1f + RELEVANCE_BOOST * memoryQuery.relevanceOf(memory)
    }

    return memories
        .map { it to memoryScore(it, now) * boostOf(it) }
        // 相关性下限（华灯 RAG 相关性下限的阈值思想）：打分低于下限的记忆**不注入**——
        // 低重要度且已老化的记忆挤进上下文是纯 token 税。这里用**带相关性增益的分数**比较：
        // 一条"重要度被标低、但本轮查询确实在讲它"的记忆可以被相关性救回线上。
        .filter { (_, injectionScore) -> injectionScore >= MIN_MEMORY_INJECTION_SCORE }
        .sortedWith(
            compareByDescending<Pair<AssistantMemory, Float>> { it.second }
                .thenByDescending { it.first.sortKey }
        )
        .map { it.first }
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
    /**
     * 本轮查询文本（通常是发送窗口里最后一条真实用户消息；见 [memoryQueryOf]）。
     *
     * **默认 null = 不带相关性** ⇒ 打分与排序与改动前**逐位一致**（既有调用点与测试因此不受影响）。
     * 非 null 时给每条记忆乘上 `1 + RELEVANCE_BOOST × 相关性`（只抬不压，见 [memoryRelevance]）。
     */
    query: String? = null,
): List<AssistantMemory> {
    if (memories.isEmpty()) return emptyList()

    // 排序 + 相关性下限统一收口在 [rankedMemoryCandidates]，Behavior 与旧实现逐位一致。
    val ranked = rankedMemoryCandidates(memories, now, query)

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
 * 记忆打分：`有效重要度 × (0.55 + 0.45 × 活跃度)`，值域 [0, 1]。
 *
 * 四个因子，来自两个输入：
 * - **有效重要度** = `importance + (1 − importance) × 读取信用 × 0.5`——读数是「这条真的有用」的
 *   **行为证据**（信用记账，2026-10-09 接入），把模型/用户**声明**的分数往 1.0 方向补最多一半。
 *   声明与读数分开存（`importance` vs `read_count`/`last_read_at`），打分时合成只是当下口径；
 *   两者对照校准是 V3 整理器的事。
 * - **活跃度** = `max(写入衰减, 读取衰减)`——时效衰减不再只问「多久没改」，而是「多久没被碰过」：
 *   一条被反复读取的老记忆不会仅因为「很久没改」就被判陈旧。
 *
 * 分工不变：importance 让「重要但久未触碰」的记忆留在上下文（身份/强偏好）；衰减让「次要且过期」
 * 的记忆自然退出。**零读数的记忆打分与接入前逐位一致**（信用 0、读取衰减 0 ⇒ 退化成原公式），
 * 所以这次接入不改动既有数据的注入行为，只有真被 `memory_tool action=read` 取过的记录才会移动。
 *
 * 不知道时间戳的语义**两侧不同**（[recencyFactor] 的 `unknownIsFresh`）：写入时间未知（迁移前的
 * 旧数据）视为不衰减，读取时间未知（从未读过）则是**确定信息**、按 0 算——否则所有老记忆都会因为
 * 「没读过」而免于衰减。
 *
 * 下游两处共用本函数：注入入选与相关性下限（[selectMemoriesForPrompt]）、超限淘汰
 * （`MemoryRepository.enforceMaxCount`）——同一口径，避免「注入认为重要、淘汰认为次要」。
 */
internal fun memoryScore(memory: AssistantMemory, now: Long): Float {
    val importance = memory.importance.coerceIn(0f, 1f)
    val credit = memoryReadCredit(memory.readCount)
    val effectiveImportance = importance + (1f - importance) * credit * READ_CREDIT_CONFIDENCE_GAIN
    val activity = maxOf(
        recencyFactor(memory.updatedAt, now, unknownIsFresh = true),
        recencyFactor(memory.lastReadAt, now, unknownIsFresh = false),
    )
    return effectiveImportance * (0.55f + 0.45f * activity)
}

/**
 * 时效衰减因子（半衰期 30 天）。
 *
 * [unknownIsFresh] 决定「时间戳为 0」的语义——两个来源的「0」含义**相反**：写入时间为 0 是
 * 「未知」（迁移前的旧数据，宁可让它按 importance 参与排序，也不要因缺时间戳被当成极旧而静默沉底）；
 * 读取时间为 0 是「从未读过」这种**确定信息**，当成「刚读过」会让所有老记忆永久免于衰减。
 */
private fun recencyFactor(timestamp: Long, now: Long, unknownIsFresh: Boolean): Float {
    if (timestamp <= 0L) return if (unknownIsFresh) 1f else 0f
    val age = (now - timestamp).coerceAtLeast(0L)
    return exp(-ln(2.0) * age / RECENCY_HALF_LIFE_MS).toFloat()
}

/**
 * 读取信用：`readCount` **对数饱和**到 [0,1]（读满 [READ_CREDIT_SATURATION_READS] 次得满额）。
 *
 * 为什么对数而非线性：第一次读取就该有反馈（≈0.31），但「读两次 = 半信」不成立——真正要区分的是
 * 「清单里瞟了一眼」与「这条被反复用到」，饱和曲线把第 8 次以后的增量压平，避免高频条目无限涨分。
 */
internal fun memoryReadCredit(readCount: Int): Float {
    if (readCount <= 0) return 0f
    val capped = readCount.coerceAtMost(READ_CREDIT_SATURATION_READS)
    return (ln(1.0 + capped) / ln(1.0 + READ_CREDIT_SATURATION_READS)).toFloat().coerceIn(0f, 1f)
}

/** 量化到 [SCORE_BUCKET_SIZE] 一档的桶号（排序用，取整策略见该常量注释）。 */
internal fun memoryScoreBucket(memory: AssistantMemory, now: Long): Int =
    floor(memoryScore(memory, now) / SCORE_BUCKET_SIZE + 0.5f).toInt()
