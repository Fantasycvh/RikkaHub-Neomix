package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MemoryKind
import java.time.Instant
import java.time.ZoneId
import app.androidrik.rikkahub.utils.estimateTokens
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.uuid.Uuid

/** 一次整理最多塞进模型的「新轮次」正文预算（token，估算口径同全仓）。 */
internal const val DEFAULT_TRANSCRIPT_TOKENS = 6_000

/**
 * 记忆库现状下发给整理 agent 的预算（token）。
 *
 * 比聊天注入的 8K 小：这里还要同时装下新轮次（6K）与校准表，而整理是**一次多步的后台调用**，
 * 上下文越短越快越省钱；看不清的库靠 `memory_tool action=read` 按需取（与渐进披露同一机制）。
 */
internal const val DEFAULT_LIBRARY_TOKENS = 4_000

/**
 * 手动「全量复核」（fullReview）一页的预算（token）。
 *
 * 比自动路径大 3 倍：用户显式点击「整理一下」是要**看完整个库**，所以给他更大的页；
 * 但仍**不是无上限**——真机库 49 条 ≈58,489 token，一次塞不下（也不该塞：一轮多步调用会把
 * 这段上下文重复发很多次）。**全量靠跨轮次分页实现**，见 [pageOfLibrary]。
 */
internal const val PAGE_LIBRARY_TOKENS = 12_000

/**
 * 手动「全量复核」的**上限**：一次请求最多带多少 token 的库。
 *
 * 用户定的一档（2026-10-16）。它**不是**「页预算」本身，而是给 [manualLibraryBudget] 封顶用。
 */
internal const val MAX_MANUAL_LIBRARY_TOKENS = 512_000

/** 手动复核时**允许占窗口的比例**：留 40% 给输出 / 思考 / 工具回执 */
internal const val MANUAL_LIBRARY_INPUT_RATIO = 0.6f

/** 给校准表留的余量（每条约 40 token，一页通常几十条） */
private const val CALIBRATION_RESERVE_TOKENS = 4_000

/** 库太小/窗口太小时的兜底，保证任何模型都还能复核一页 */
private const val MIN_LIBRARY_TOKENS = 4_000

/**
 * 手动「全量复核」一页的库预算 —— **按模型自己的上下文窗口放开**，不再固定 12K。
 *
 * ## 为什么改（用户 2026-10-16 提出）
 *
 * 真机库 ≈30–58K token，而固定 12K 的页 ⇒ 一次「整理一下」只能看 1/3 到 1/2，
 * 「全量」要分 2–3 轮。按窗口放开后，**通常是 1 轮走完全库**。
 *
 * ## 上限为什么必须**与模型窗口取小**（这是本函数唯一容易写错的地方）
 *
 * 用户给的是「最大 512K、按 60% 允许输入」。若直译成 `512K × 60% = 307K`，
 * 那么 **128K / 200K 窗口的模型会收到一个必然失败的请求**。
 * 所以是 `min(窗口, 512K) × 60%`：512K 表达的是「再大也不超过这个数」，
 * 真正的约束永远是**模型自己的窗口**。
 *
 * ## 还要扣掉同一次请求里的其它东西
 *
 * 这一页不是请求的全部：同一发里还有新轮次（[DEFAULT_TRANSCRIPT_TOKENS]）与校准表。
 * 60% 是**整个输入**的份额，所以先扣掉它们再给库，否则「60%」会被库独吞而超出窗口。
 */
internal fun manualLibraryBudget(contextLength: Int): Int {
    val input = (contextLength.coerceAtMost(MAX_MANUAL_LIBRARY_TOKENS) * MANUAL_LIBRARY_INPUT_RATIO).toInt()
    return (input - DEFAULT_TRANSCRIPT_TOKENS - CALIBRATION_RESERVE_TOKENS)
        .coerceAtLeast(MIN_LIBRARY_TOKENS)
        .coerceAtMost(MAX_MANUAL_LIBRARY_TOKENS)
}

/**
 * 库快照的**一页**（全量复核的推进单位）。
 *
 * 为什么引入「页」这个概念：整理侧的快照预算只有 4K（手动 12K），而真机库有 ≈58K token
 * ⇒ 一次看不完；更糟的是原来的选取位置**每轮都一样**，于是反复「整理一下」永远看同一批、
 * 剩下的永不见天日。分页 + 游标让「全量」变成**可推进、可断点续跑**的事。
 */
internal data class MemoryLibraryPage(
    /** 本页要下发的记录（**按 `sortKey` 升序**：一页一页往前走，不跳） */
    val records: List<AssistantMemory>,
    /** 本页走完后水位推进到哪；[isLast] 时为 0（下一轮从头开始） */
    val nextCursor: Long,
    val isLast: Boolean,
    /** 本页之前已经复核过多少条（给模型与界面一个「走到哪了」） */
    val reviewedBefore: Int,
    /** 库总条数 */
    val total: Int,
) {
    val from: Int get() = if (total == 0) 0 else reviewedBefore + 1
    val to: Int get() = reviewedBefore + records.size
}

/**
 * 从 [memories] 里取 [cursor] 之后的一页（**纯函数**，好单测）。
 *
 * 三条必须守住的规则：
 *
 * 1. **至少给一条**：`sortKey > cursor` 还有记录时，本页**绝不能为空**——否则水位不推进、
 *    下一轮还是同一页，变成死循环。真机上确实存在「单条就超过整页预算」的记录
 *    （`0d01f604` ≈5,151 token），所以首条**豁免预算**（与 `selectMemoriesForPrompt` 同款处理）。
 * 2. **按 `sortKey` 升序**推进，不按分数：全量复核要的是**不重不漏**，不是「这轮最相关的」。
 * 3. **[cursor] 已到末尾（或已失效）⇒ 从头开始**：库会变，旧的「已复核」不能当永久结论。
 */
internal fun pageOfLibrary(
    memories: List<AssistantMemory>,
    cursor: Long,
    budgetTokens: Int = PAGE_LIBRARY_TOKENS,
): MemoryLibraryPage {
    if (memories.isEmpty()) {
        return MemoryLibraryPage(emptyList(), 0L, isLast = true, reviewedBefore = 0, total = 0)
    }
    val ordered = memories.sortedBy { it.sortKey }
    val fromStart = cursor <= 0L || ordered.none { it.sortKey > cursor }
    val remaining = if (fromStart) ordered else ordered.filter { it.sortKey > cursor }
    val reviewedBefore = ordered.size - remaining.size

    val page = mutableListOf<AssistantMemory>()
    var used = 0
    for (memory in remaining) {
        val cost = estimateTokens(memory.content)
        // 首条豁免：否则一条超预算的长记忆会把水位永远卡住
        if (page.isNotEmpty() && used + cost > budgetTokens) break
        page += memory
        used += cost
    }
    val consumedAll = page.size == remaining.size
    return MemoryLibraryPage(
        records = page,
        nextCursor = if (consumedAll) 0L else page.last().sortKey.toLong(),
        isLast = consumedAll,
        reviewedBefore = reviewedBefore,
        total = ordered.size,
    )
}

/**
 * 把一页渲染成整理 agent 的库现状块。
 *
 * 与 [buildMemoryDataPrompt] 的区别是**这一页全部给全文**：那条路会再分「全文层 + 清单层」，
 * 而清单层只给 40 字摘要 ⇒ 模型看得到一个 id 却没有正文 ⇒ **盲改**（真机实证：同一条 edit
 * 被逐字节重复提出）。全量复核要么看到全文，要么就不该看到那条的 id。
 */
internal fun buildLibraryPagePrompt(page: MemoryLibraryPage): String = buildString {
    if (page.total == 0) {
        appendLine("**Library** is empty.")
        return@buildString
    }
    appendLine(
        "**Library** page ${page.from}–${page.to} of ${page.total} " +
            "(updated only through `memory_tool`; only these records are listed this round)"
    )
    if (!page.isLast) {
        appendLine(
            "The rest of the library is **not shown this round** and arrives in a later run — " +
                "do not guess about records you cannot see."
        )
    }
    val json = buildJsonArray {
        page.records.forEach { memory ->
            add(
                buildJsonObject {
                    put("id", memory.id)
                    put("revision", memory.revision)
                    put("content", memory.content)
                    put("kind", memory.kind.storage)
                    if (memory.kind == MemoryKind.TASK) {
                        put("task_status", memory.taskStatus.storage)
                        if (memory.dueAt > 0L) put("due", memory.consolidationDueDate())
                    }
                },
            )
        }
    }
    append(app.androidrik.rikkahub.utils.JsonInstantPretty.encodeToString(json))
}

/**
 * 整理 agent 的系统提示。
 *
 * 与 `memory_tool` 的描述**分工明确**：工具描述负责「什么能记、什么永不记」（那份文本已经在
 * 工具定义里，模型调用前必读），这里只负责「这次后台整理要干哪几件事、按什么优先级」。
 * 刻意不承诺工具没提供的动作（没有 list、没有「按主题重写整库」），也不引入人格/语气要求。
 */
internal val MEMORY_CONSOLIDATION_SYSTEM_PROMPT = """
    You are the memory librarian of a chat app. You run in the background, after the user stopped
    chatting, to keep their long-term memory library tidy.

    You will be given two things:
    1. THE LIBRARY: the records that exist today, plus a calibration table with each record's
       importance and source-attributed reads. `legacy+chat` retains old unknown-source credit;
       never treat unknown-source reads as known chat use, or consolidation self-reads as user demand.
    2. NEW TURNS: turns from the user's recent conversations that have not been reviewed yet.

    Do these in order, using `memory_tool` for every change:
    - Record what is missing. Durable facts the user stated themselves may be added with
      `action="create"`. The tool description defines what may be recorded and what never may:
      follow it exactly, including its `[stated]` test and its never-record list.
    - Merge duplicates only within the same kind. Rewrite the one you keep with `action="edit"`,
      then `action="delete"` the other. Keep the user's own wording and the union of useful detail.
      Never merge across kinds or reclassify records merely to bypass this restriction.
    - Fix what is wrong. If a record contradicts what the user said in the new turns, rewrite it.
    - Calibrate importance with the calibration table. A record the user keeps reading is worth more
      than it was rated (raise it, at most 0.9). A record nobody ever read, and that the new turns do
      not touch, may be lowered (never below 0.2). Only when the evidence is clear.
    - Close tasks only when the user explicitly says in NEW TURNS that they finished or cancelled
      that task: `action="complete"` means done; `action="drop"` means cancelled (dropped).
      Both require `id` and the actually observed `expected_revision`; read first if needed.
      A past due date, age, silence or an assistant's claim is never evidence of completion/cancellation.
    - Retire: delete a record only when its content is fully covered by another same-kind record you
      keep, or when the user explicitly asked to forget it. Never delete merely for being old or
      unimportant; done/dropped tasks may be retired under this same rule, not just because they closed.

    Kind-specific rules:
    - profile: stable identity or long-term preferences. Correct contradictions using the user's latest
      statement, merge synonyms within profile, never retire because of age. A profile is only a
      candidate in tail context, not automatically a system anchor: you cannot set anchors or solidify
      it; suggest user solidification for system-level standing facts when useful.
    - topic: knowledge, projects and decisions. Merge duplicates and correct superseded facts as usual.
    - task: the user's own commitment with a completion criterion. Never auto-complete, drop or retire
      an open task because it is old or overdue. Do not turn it into topic/profile to evade protection.
    - An existing record's kind is fixed: do not change it. If a finished task yields a lasting
      conclusion explicitly stated in NEW TURNS, create a separate topic for that conclusion; do not
      replace the task's kind. Uncertain classification stays as it is. (A separate user switch may
      lift this rule; see the extra section when present.)

    Rules:
    - The new turns are the only source of new facts. Never invent, infer, or record your own
      conclusions; never record what the tool description forbids.
    - Prefer fewer, better records over more records. Do not rewrite a record just to reword it.
    - The library belongs to the user: when there is nothing worth doing, stop immediately and reply
      with one short sentence. Do not narrate your reasoning.
""".trimIndent()

/**
 * **只提案模式**额外告诉模型的一句话（审批开着时的做梦，见设计稿 §7.2）。
 *
 * 为什么不改工具描述：那份描述是**稳定前缀**（缓存友好），为一个后台场景给它加分支不划算；
 * 而模型看到回执里的 `applied:false` 也需要一句前情，否则会以为自己写失败了而反复重试。
 */
internal val MEMORY_CONSOLIDATION_PROPOSAL_NOTE = """
    Note: this library requires the user's approval for changes (memory write approval is on), so your
    `create` / `edit` / `delete` / `complete` / `drop` calls are recorded as **suggestions**: nothing is
    applied to the library, and the tool replies with `applied:false` and an `operation_id`.
    For edit/delete/complete/drop copy the target's actually visible revision into `expected_revision`;
    read first if needed. To propose a merge, first successfully propose the same-kind replacement
    create/edit, then put its returned operation_id in the delete's non-empty `depends_on` array.
    Only create/edit replacement operation_ids may authorize that deletion after review; complete/drop
    are status changes, never replacement dependencies. Reads work normally.
""".trimIndent()

/**
 * **用户显式开启「整理时可调整类型」**时才追加的一段。
 *
 * 这是对主提示「已有记录类型固定」的**窄例外**，必须写明它覆盖哪一条、不覆盖哪些：
 * 关闭态不再有任何改类许可（仓库层也会拒绝），所以这里是唯一允许改类的入口。
 * 单步跨类合并仍然禁止；只有「先独立改类、后续步骤再合并」的两步路径被允许，且两侧都要有证据。
 */
internal val MEMORY_CONSOLIDATION_RECLASSIFY_NOTE = """
    Type changes are enabled for this library (the user opted in). This section overrides the base rules
    that an existing record's kind is fixed and that records are never merged across kinds; every other
    rule above still applies.
    - You may change an existing record's kind with `action="edit"` and `kind`, but only when NEW TURNS
      contain the user's own statement that makes the current kind clearly wrong. An assistant's wording,
      your inference, age, a due date or silence is never such evidence.
    - Choose the kind that will stay correct: profile = stable identity or long-term response preference;
      topic = knowledge, projects and decisions; task = a commitment the user owns with a completion
      criterion. If still uncertain, keep the current kind.
    - Do not change kind to get under the 200-record limit, to move a task out of or into protection, or
      to make a merge pass. Cross-kind merging in one step stays forbidden: reclassify in its own edit
      first, then merge only when later steps independently support it.
    - Keep an unfinished open task as a task. The library rejects converting an open task to another kind,
      so use `complete` when the user says it is done and `drop` when they say it is cancelled.
    - A closed task normally stays a task: for a lasting conclusion prefer creating a separate topic over
      replacing the task's kind. Reclassify it only when the record itself was never a commitment and the
      user's statement shows that.
    - task -> profile/topic clears the task status and due date; take that consequence as part of the same
      decision, not as a side effect. Anchored records cannot become tasks (tasks cannot be anchors); do
      not attempt that.
    - This is a narrow exception for correcting kinds, not permission to rewrite records freely.
""".trimIndent()

/**
 * 一次整理的系统提示：主纪律 + 可选的两段补充。
 *
 * 纯函数是为了让「开关有没有真的进到提示里」能在 JVM 上钉死；顺序固定为
 * 主提示 → 改类例外 → 只提案说明，后者引用前面的动作名，倒过来会读起来像两套规则。
 */
internal fun memoryConsolidationSystemPrompt(
    proposeOnly: Boolean,
    allowKindChange: Boolean,
    basePrompt: String = MEMORY_CONSOLIDATION_SYSTEM_PROMPT,
): String = buildString {
    append(basePrompt.ifBlank { MEMORY_CONSOLIDATION_SYSTEM_PROMPT })
    if (allowKindChange) {
        append("\n\n").append(MEMORY_CONSOLIDATION_RECLASSIFY_NOTE)
    }
    if (proposeOnly) {
        append("\n\n").append(MEMORY_CONSOLIDATION_PROPOSAL_NOTE)
    }
}

/** 一次整理实际带进模型的新轮次，以及它们把各会话的进度推进到哪。 */
internal data class ConsolidationTranscript(
    val text: String,
    /**
     * 真正带进去的会话与它们的**新水位**（最后一条被带进去的消息时间）。
     *
     * 只有列在这里的会话才允许推进进度：没带进去的（预算不够）必须留在待整理里，
     * 否则它们的轮次会被永久跳过。
     */
    val consumed: List<ConsumedTurns>,
    /** 还有多少轮没装下（诊断用：日志里能看出"这次只吃了一半"） */
    val remainingTurns: Int,
)

internal data class ConsumedTurns(val conversationId: Uuid, val watermark: Long)

/**
 * 把待整理会话的**新轮次**拼成一段文本（按时间顺序）。
 *
 * 三条口径：
 * - **只取基线之后的消息**（一个节点算一轮；节点里有基线之后的消息就把整轮带上）；
 * - **预算不够就停**：装不下的轮次/会话不推进水位（[ConsumedTurns]），留给下一次；
 *   例外是「这一轮还什么都没装下」（`used == 0`）——此时即使单轮超预算也带上它，
 *   否则一个超大轮次会在预算被别的会话占满时永远轮不到；
 * - **非文本内容折叠成占位符**（`[image]` / `[file: x.pdf]`）：整理要的是用户说了什么，
 *   把图片/文档正文塞进来既贵又没用（文档解析已在写入侧做过）。
 */
internal fun buildConsolidationTranscript(
    conversations: List<Conversation>,
    baselines: Map<Uuid, Long>,
    budgetTokens: Int = DEFAULT_TRANSCRIPT_TOKENS,
): ConsolidationTranscript {
    val builder = StringBuilder()
    val consumed = mutableListOf<ConsumedTurns>()
    var used = 0
    var remaining = 0
    var exhausted = false

    conversations.forEach { conversation ->
        val baseline = baselines[conversation.id] ?: 0L
        // 一轮 = 基线之后的那些消息（节点里只要有新消息，整轮就算这一轮的）
        val turns = conversation.messageNodes
            .map { node -> node.messages.filter { it.createdAt.millis() > baseline } }
            .filter { it.isNotEmpty() }
        if (turns.isEmpty()) return@forEach

        val blocks = turns.map { messages -> messages.mapNotNull { it.transcriptLine() }.joinToString("\n") }
        if (exhausted) {
            remaining += blocks.count { it.isNotEmpty() }
            return@forEach
        }

        var included = 0
        var seen = 0
        var watermark = baseline
        for ((index, block) in blocks.withIndex()) {
            val turnWatermark = turns[index].maxOf { it.createdAt.millis() }
            // 没有可整理正文的轮次（只有思考/工具结果）：不动文本，但水位要跨过去，
            // 否则它会永远算「待整理」，每两分钟被扫一次却什么也做不了
            if (block.isEmpty()) {
                watermark = maxOf(watermark, turnWatermark)
                seen++
                continue
            }
            val cost = estimateTokens(block)
            // 预算不够就停。**唯一例外是"这一轮还什么都没装下"**（`used == 0`）：否则单轮就超预算的
            // 会话会在预算被别的会话占满时永远轮不到（饥饿）；而预算已用时该让位给下一次
            // ——排在前面的会话水位推进后就不再是候选，列表会自然排空
            if (used + cost > budgetTokens && used > 0) {
                exhausted = true
                break
            }
            if (included == 0) {
                builder.append("--- conversation: ${conversation.title.ifBlank { conversation.id.toString() }} ---\n")
            }
            builder.append(block).append('\n')
            used += cost
            included++
            seen++
            watermark = maxOf(watermark, turnWatermark)
        }
        remaining += blocks.count { it.isNotEmpty() } - included
        if (seen > 0) consumed += ConsumedTurns(conversation.id, watermark)
    }

    return ConsolidationTranscript(text = builder.toString().trim(), consumed = consumed, remainingTurns = remaining)
}

/** `[2026-01-01 10:05] user: 正文`；非文本内容折叠成占位符，工具调用/思考/导入压缩检查点不进整理输入。 */
private fun UIMessage.transcriptLine(): String? {
    if (role != MessageRole.USER && role != MessageRole.ASSISTANT) return null
    // 导入的原版检查点是摘要而非对话正文：进整理输入会让记忆从「二手摘要」再蒸馏一次。
    if (isContextCheckpoint) return null
    val body = parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Image -> "[image]"
            is UIMessagePart.Video -> "[video]"
            is UIMessagePart.Audio -> "[audio]"
            is UIMessagePart.Document -> "[file: ${part.fileName}]"
            else -> null
        }
    }.joinToString("\n").trim()
    if (body.isEmpty()) return null
    return "[${createdAt.stamp()}] ${role.name.lowercase()}: $body"
}

private fun LocalDateTime.stamp(): String = toString().replace('T', ' ').take(16)

private fun LocalDateTime.millis(): Long = toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

private fun AssistantMemory.consolidationDueDate(): String =
    Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()

/**
 * **校准表**：每条记忆一行 `id ｜ kind（事务附 status/due）｜ 重要度 ｜ 来源读取 ｜ 主题`。
 *
 * 为什么要单独一张表，而不是把这些塞进注入块：注入块的读者是**聊天模型**，读次数对它没有意义
 * （V2 刻意用 `@Transient` 挡掉了）；整理 agent 是**唯一**该看到「声明的分数 vs 实际被用」对照的
 * 角色——这正是它「事后复核」职责的输入。表的成本很小（每条 ≈20 token，54 条 ≈1K）。
 */
internal fun buildMemoryCalibrationTable(memories: List<AssistantMemory>): String {
    if (memories.isEmpty()) return ""
    return memories
        .sortedBy { it.sortKey }
        .joinToString("\n") { memory ->
            val credit = if (memory.readCount <= 0) "never read" else "read ${memory.readCount}x"
            val reads = "legacy+chat $credit; chat ${memory.chatReadCount}x; " +
                "unknown ${memory.legacyReadCount}x; consolidation ${memory.consolidationReadCount}x"
            val topic = memory.description.ifBlank { memory.content.trim().take(40) }
                .replace('\r', ' ').replace('\n', ' ').replace("|", "\\|")
            val kind = buildString {
                append("kind ${memory.kind.storage}")
                if (memory.kind == MemoryKind.TASK) {
                    append("; status ${memory.taskStatus.storage}")
                    append("; due ${if (memory.dueAt > 0L) memory.consolidationDueDate() else "none"}")
                }
            }
            "- ${memory.id} | $kind | importance ${"%.2f".format(memory.importance)} | $reads | $topic"
        }
}
