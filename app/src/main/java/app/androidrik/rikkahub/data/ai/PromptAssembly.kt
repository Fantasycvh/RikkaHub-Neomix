package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.withoutUiNotices
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.ai.prompts.partitionCheckpointMessages
import app.androidrik.ai.ui.limitContext
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.CardSlot
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.cardPartsDivergeFromSystemPrompt
import app.androidrik.rikkahub.data.model.effectiveCardSlotOrder
import app.androidrik.rikkahub.data.model.hasCardParts
import app.androidrik.rikkahub.data.model.renderCardSlotBlock

/**
 * 发送给 provider 的消息组装——**真实请求与「拼装预览」的唯一共同来源**。
 *
 * 背景（`docs/peer-apps-benchmark.md` §6.7 ③）：原先这段组装内联在
 * [GenerationHandler.generateInternal] 里，任何「预览」只能另写一份，而两份实现必然漂移
 * ——预览会显示一个与真实请求不同的东西，比没有预览更糟（社区讨论里 Tavo 的
 * 「实际拼装后提示词」正是这个价值点）。
 *
 * 因此这里把组装抽成纯函数：真实路径与预览**调用同一个函数**，结构上不可能不一致。
 *
 * 分工：
 * - [buildSystemPromptText]：system 消息的正文（systemPrompt + 记忆 + 工具 schema + 渲染提示）
 * - [buildSendWindowMessages]：压缩窗口 + 上下文截断后的对话消息
 * - [buildInternalMessages]：把两者合成最终列表（真实请求用的就是这个）
 */

/**
 * system 内部的一个**带来源标记的块**。
 *
 * `buildSystemPromptText` = 各块 content 以单个 `\n` 连接；块结构本身不改变发送内容，
 * 它存在的意义是**归因**——拼装预览（`PromptInspector`）据此把 system 大段细分为
 * 「人设/记忆/工具 schema/渲染提示/角色卡槽位」等来源段（#1542 复核 Tier B / Tier 4）。
 */
internal data class SystemBlock(
    val source: SystemBlockSource,
    /** 卡槽块的槽位（source == [SystemBlockSource.CARD_SLOT] 时非空），供预览显示具体槽名。 */
    val cardSlot: CardSlot? = null,
    val content: String,
    /**
     * 本块与**下一块**之间的分隔符（最后一块不使用）。
     *
     * 默认单换行（历史行为：`appendLine()` 分隔）；卡槽块之间是**双换行**
     * （`buildCardSystemPrompt` 的历史连接符——槽间是空行分隔）。逐字节契约的一部分。
     */
    val separatorAfter: String = "\n",
)

internal enum class SystemBlockSource {
    /** 助手人设（systemPrompt，或会话级覆盖） */
    PERSONA,

    /** 单个卡槽（仅在 PERSONA 未漂移的角色卡助手里拆出，见 [buildSystemBlocks]） */
    CARD_SLOT,

    MEMORY,

    /**
     * L1 锚点（进 system）。**无锚点时不产生该块** —— 见 [buildMemoryAnchorPrompt]。
     */
    ANCHOR,

    /** 单个工具的 systemPrompt */
    TOOL,

    DIAGRAM_HINT,

    MATH_HINT,
}

/**
 * 组装 system 消息的**块列表**（[buildSystemPromptText] 的真相源，拼装预览的归因来源）。
 *
 * ⚠️ 逐字节契约：`buildSystemBlocks(...).joinToString("\n") { it.content }` 必须与
 * 历史输出完全一致（`PromptAssemblyTest` 锁着）。分隔符是**单个 `\n`**——各块 content
 * 原样拼接，包括空内容的工具块（历史上会留下孤立换行，保持不变）。
 *
 * 角色卡的特别处理（#1542 复核 Tier B）：**未漂移**的角色卡助手
 * （`hasCardParts` 且 `systemPrompt == buildCardSystemPrompt(...)`）的 system 由
 * **卡槽逐槽渲染**成带来源的块——槽位开关/排序**即时生效**（无需点「重新生成提示词」），
 * 且预览能按槽归因。输出与原 systemPrompt **逐字节相同**（「未漂移」的定义即是如此），
 * 故这是零行为变更的身份升级。
 * - 漂移（用户手改过 systemPrompt）：手改内容**优先**，整段作为单个 PERSONA 块，
 *   漂移提示照旧引导「重建将替换为字段内容」。
 * - 会话级自定义提示词：优先于一切（历史行为），同样走单个 PERSONA 块。
 */
internal fun buildSystemBlocks(
    assistant: Assistant,
    settings: Settings,
    model: Model,
    messages: List<UIMessage>,
    memories: List<AssistantMemory>,
    tools: List<Tool>,
    conversationSystemPrompt: String?,
    /**
     * 会话级「不使用记忆」（隐身对话）：为真时**整个记忆块都不出现**。
     *
     * 为什么不是「传空列表」：那样仍会渲染出记忆块的外壳（标题 + 纪律 + 空数组 + 清单标题），
     * 等于告诉模型「这里有记忆系统，只是空的」——与「这次对话不碰记忆库」的语义不符。
     */
    memoryDisabled: Boolean = false,
    /**
     * **冻结的锚点 id 集合**（`null` = 未冻结）：非 null 时只渲染这些锚点。
     *
     * 冻结的是**集合**，文本仍按 [memories] 实时渲染 —— 于是「删掉一条锚点」立刻消失、
     * 「改写一条锚点」立刻生效，而新固化的锚点不会挤进本会话（要生效走记忆页手动刷新）。
     * 空集 = 冻结那一刻没有锚点 ⇒ 不产生该块（不变式：无锚点 ⇒ 无块）。
     * 见 `docs/design/design-memory-injection-tiering.md` §5。
     */
    frozenAnchorIds: Set<String>? = null,
    /** Reuse the exact selection/counting snapshot used for the request tail and preview. */
    memoryPlan: MemoryInjectionPlan? = null,
): List<SystemBlock> {
    val blocks = mutableListOf<SystemBlock>()

    // 人设区：会话级覆盖 > 漂移的原文 > 未漂移角色卡的逐槽渲染
    val usesConversationOverride =
        assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()
    val personaIsCardSlots = !usesConversationOverride &&
        assistant.hasCardParts &&
        !assistant.cardPartsDivergeFromSystemPrompt()
    when {
        usesConversationOverride -> blocks += SystemBlock(
            SystemBlockSource.PERSONA,
            content = conversationSystemPrompt!!,
        )

        personaIsCardSlots -> {
            assistant.effectiveCardSlotOrder
                .filter { it !in assistant.disabledCardSlots }
                .forEach { slot ->
                    renderCardSlotBlock(slot, assistant.name, assistant)?.let { content ->
                        blocks += SystemBlock(SystemBlockSource.CARD_SLOT, cardSlot = slot, content = content)
                    }
                }
            // 槽间分隔是双换行（buildCardSystemPrompt 的历史连接符）；最后一个卡槽块与
            // 后续块（记忆/渲染提示）之间仍是单换行（历史：system 整体 + appendLine）
            for (i in 0 until blocks.lastIndex) {
                val b = blocks[i]
                if (b.source == SystemBlockSource.CARD_SLOT &&
                    blocks[i + 1].source == SystemBlockSource.CARD_SLOT
                ) {
                    blocks[i] = b.copy(separatorAfter = "\n\n")
                }
            }
        }

        assistant.systemPrompt.isNotBlank() -> blocks += SystemBlock(
            SystemBlockSource.PERSONA,
            content = assistant.systemPrompt,
        )
    }

    // 记忆（会话级「不使用记忆」时整块不发）
    if (assistant.enableMemory && !memoryDisabled) {
        val plan = memoryPlan ?: buildMemoryInjectionPlan(
            memories, settings.memoryInjectBudgetTokens,
            query = memoryQueryOf(messages), frozenAnchorIds = frozenAnchorIds,
        )
        // The component strings already contain their newlines; do not add unbudgeted separators.
        if (plan.anchorText.isNotBlank()) {
            blocks += SystemBlock(SystemBlockSource.ANCHOR, content = plan.anchorText, separatorAfter = "")
        }
        if (plan.disciplineText.isNotBlank()) {
            blocks += SystemBlock(SystemBlockSource.MEMORY, content = plan.disciplineText, separatorAfter = "")
        }
    }
    // 工具prompt
    tools.forEach { tool ->
        blocks += SystemBlock(SystemBlockSource.TOOL, content = tool.systemPrompt(model, messages))
    }
    // TikZ 渲染提示（启用时引导模型以闭合 $$...$$ 块输出图表，触发 MathBlock 渲染）。
    // 注意：必须是原始字符串——普通字符串里 $$ 会被 Kotlin 转义成单个 $，模型将收到错误指令。
    // 强调「闭合」：只写开头 $$ 漏掉结尾 $$ 的块，客户端不会识别为图表（显示为源码）。
    if (settings.displaySetting.enableDiagramRendering) {
        blocks += SystemBlock(
            SystemBlockSource.DIAGRAM_HINT,
            content = """For TikZ diagrams, wrap the ENTIRE environment in a CLOSED $$...$$ block: open with $$ immediately before \begin{tikzpicture} (or \begin{tikzcd} or \begin{axis}), and close with $$ immediately after the matching \end{...}. Never omit the closing $$, and never put anything between the closing \end{...} and the closing $$. Example: $$\begin{tikzpicture}\draw (0,0) -- (1,1);\end{tikzpicture}$$. Full TikZ/pgf is supported (positioning, calc, pgfplots, tikz-cd, all standard libraries) with CJK text — do not simplify or avoid advanced syntax. Math macros inside node text are supported too: amsmath and amssymb are loaded (\dfrac, \tfrac, \binom, \text, \operatorname, \mathbb, \mathfrak, \checkmark and the rest), so use them directly instead of downgrading to plain \frac forms or avoiding notation. Diagrams render as inline vector graphics. Colors: use color names (blue, red, gray) optionally with opacity (blue!30); do NOT use #hex colors inside options like fill=#4A90D9 — the # is a TeX parameter token and breaks pgf option parsing (renders as source) PGF Math trigonometry: radians MUST use sin(deg(x)) or sin(x*pi/180) — sin(x rad) and sin(x r) are NOT supported and abort rendering.""",
        )
    }
    // 数学公式提示（启用 LaTeX 渲染时）：引导模型用 \(...\) / $...$ 输出公式并触发 jlatexmath。
    // 注意：定界符内不能有空格（$ x^2 $ 不会被识别为公式）；括号形式 \(x^2\) 更稳妥。
    if (settings.displaySetting.enableLatexRendering) {
        blocks += SystemBlock(
            SystemBlockSource.MATH_HINT,
            content = """For math formulas, wrap them in \( ... \) (preferred) or $...$ with NO whitespace immediately after the opening delimiter and before the closing delimiter, e.g. \(x^2 + y^2 = r^2\) or $\frac{a}{b}$ or $\sqrt{x}$. They render as inline math symbols.""",
        )
    }
    return blocks
}

/**
 * 块 → system 文本：按各块的 [SystemBlock.separatorAfter] 拼接（逐字节契约见 [buildSystemBlocks]）。
 *
 * 提成独立函数：`buildSystemPromptText` 与测试/归因侧共用同一连接实现，
 * 避免测试里手写 join 分隔符与实现漂移（实测踩到：卡槽间是双换行，手写单换行必红）。
 */
internal fun joinSystemBlocks(blocks: List<SystemBlock>): String {
    if (blocks.isEmpty()) return ""
    return buildString {
        blocks.forEachIndexed { i, block ->
            append(block.content)
            if (i < blocks.lastIndex) append(block.separatorAfter)
        }
    }
}

/**
 * 组装 system 消息正文。
 *
 * 顺序即前缀缓存的稳定性来源：**任何一段内容变化都会作废整段前缀缓存**（system 在位置 0）。
 * 调用方改动此处顺序前请先读 `docs/peer-apps-benchmark.md` §6.7 ④。
 *
 * 实现为 [buildSystemBlocks] 经 [joinSystemBlocks] 的连接（逐字节契约见其 kdoc）。
 */
internal fun buildSystemPromptText(
    assistant: Assistant,
    settings: Settings,
    model: Model,
    messages: List<UIMessage>,
    memories: List<AssistantMemory>,
    tools: List<Tool>,
    conversationSystemPrompt: String?,
    memoryDisabled: Boolean = false,
    /** 冻结的锚点 id 集合：非 null 时只渲染这些锚点（见 [buildSystemBlocks] 同名参数）。 */
    frozenAnchorIds: Set<String>? = null,
    memoryPlan: MemoryInjectionPlan? = null,
): String = joinSystemBlocks(
    buildSystemBlocks(
        assistant = assistant,
        settings = settings,
        model = model,
        messages = messages,
        memories = memories,
        tools = tools,
        conversationSystemPrompt = conversationSystemPrompt,
        memoryDisabled = memoryDisabled,
        frozenAnchorIds = frozenAnchorIds,
        memoryPlan = memoryPlan,
    )
)

/**
 * 对话消息部分（不含 system）：压缩窗口 + 上下文截断。
 *
 * 每步对 live [messages] 重算（而非传入冻结列表）——否则本回合内工具调用的结果
 * 进不了上下文，模型会重复调用同一工具。检查点固定在窗口最前
 * 且不参与 [limitContext] 截断（limitContext 保留尾部会截掉开头的检查点）。
 *
 * 注意：回写基线仍是**全量** messages（由调用方持有），这里产出的合成消息不会被持久化。
 * 见 `docs/design/design-auto-compaction.md` §4.2。
 */
internal fun buildSendWindowMessages(
    messages: List<UIMessage>,
    compressionWindow: Conversation.CompressionWindow?,
    contextMessageLimit: Int,
): List<UIMessage> {
    val (checkpointMessages, restMessages) =
        (compressionWindow?.applyTo(messages) ?: messages).partitionCheckpointMessages()
    return buildList {
        addAll(checkpointMessages)
        addAll(restMessages.limitContext(contextMessageLimit))
    }
}

/**
 * 完整内部消息列表：system（若非空）+ 对话窗口。
 *
 * **真实请求路径**（[GenerationHandler.generateInternal]）与**拼装预览**
 * （`PromptInspector`）都必须走这里，不要各自组装。
 */
internal data class AssembledRequest(val messages: List<UIMessage>, val memoryPlan: MemoryInjectionPlan)

internal fun buildInternalMessagesWithPlan(
    assistant: Assistant,
    settings: Settings,
    model: Model,
    messages: List<UIMessage>,
    memories: List<AssistantMemory>,
    tools: List<Tool>,
    conversationSystemPrompt: String?,
    compressionWindow: Conversation.CompressionWindow?,
    /** 空回复补救提示（一次性）：追加到系统提示尾部，不进入对话历史、拼装预览不受影响 */
    recoveryHint: String? = null,
    /**
     * 规则提醒块（一次性）：命中时追加到**对话尾部**的合成 USER 消息，不进入对话历史。
     * 与记忆块同款做法与同款理由（见下方注释）；null/空白 = **不产生任何消息**
     * （空块会让每轮白付一段 token，且破坏"L0 逐字节恒定"）。
     */
    ruleReminder: String? = null,
    /** 会话级「不使用记忆」（隐身对话）：为真时不注入记忆块，见 [buildSystemBlocks] */
    memoryDisabled: Boolean = false,
    /** 冻结的锚点 id 集合：来自 `Conversation.frozenAnchorIds`（null = 未冻结）。 */
    frozenAnchorIds: Set<String>? = null,
): AssembledRequest {
    val windowMessages = buildSendWindowMessages(
        messages = messages,
        compressionWindow = compressionWindow,
        contextMessageLimit = assistant.contextMessageLimit,
    ).map { it.withoutUiNotices() }
    // Capture query, time, tokenizer strategy, rendered texts and selection once for this request.
    val memoryPlan = buildMemoryInjectionPlan(
        memories = if (assistant.enableMemory && !memoryDisabled) memories else emptyList(),
        budgetTokens = settings.memoryInjectBudgetTokens,
        query = memoryQueryOf(windowMessages),
        frozenAnchorIds = frozenAnchorIds,
    )
    val baseSystem = buildSystemPromptText(
        assistant = assistant,
        settings = settings,
        model = model,
        messages = messages,
        memories = memories,
        tools = tools,
        conversationSystemPrompt = conversationSystemPrompt,
        memoryDisabled = memoryDisabled,
        frozenAnchorIds = frozenAnchorIds,
        memoryPlan = memoryPlan,
    )
    // 恢复提示追加在系统提示末尾：不写入对话历史（仅本次请求存在，随后消失）
    val system = if (recoveryHint.isNullOrBlank()) {
        baseSystem
    } else {
        val separator = if (baseSystem.isBlank()) "" else "\n\n"
        baseSystem + separator + "<recovery_note>\n" + recoveryHint + "\n</recovery_note>"
    }
    val assembledMessages = buildList {
        if (system.isNotBlank()) {
            add(UIMessage.system(prompt = system).copy(isSynthetic = true))
        }
        addAll(windowMessages)
        // L2+L3：记忆数据追加到**对话尾部**（合成 USER 消息，不落库）。
        // 照压缩检查点同款做法（`partitionCheckpointMessages` 判 `isSynthetic && role == USER`）。
        // **为什么在尾部**：本块每次记忆写入都会变；若留在 system，则整条 system 失配 ⇒
        // 前缀从位置 0 起全断（真机实测命中率 98% → 0%，设计稿 §2）。放尾部则不影响前缀。
        //
        // **为什么尾部就能用相关性**（设计稿 §3.2）：尾部怎么变都不动它前面的前缀，
        // 于是选取可以依赖本轮查询、每轮重算。查询取窗口里最后一条真实用户消息。
        if (memoryPlan.dataText.isNotBlank()) {
            add(UIMessage.user(prompt = memoryPlan.dataText).copy(isSynthetic = true))
        }
        // 规则提醒（探索/落地守卫）：与记忆块同一个尾部位置、同一种"每轮算、不落库、不再命中即消失"
        // 的合成消息。**必须判空白**——空块会让每轮白付一段 token。
        ruleReminder?.takeIf { it.isNotBlank() }?.let {
            add(UIMessage.user(prompt = it).copy(isSynthetic = true))
        }
    }
    return AssembledRequest(assembledMessages, memoryPlan)
}

/** Compatibility entry point; the shared assembly owns selection and accounting. */
internal fun buildInternalMessages(
    assistant: Assistant,
    settings: Settings,
    model: Model,
    messages: List<UIMessage>,
    memories: List<AssistantMemory>,
    tools: List<Tool>,
    conversationSystemPrompt: String?,
    compressionWindow: Conversation.CompressionWindow?,
    recoveryHint: String? = null,
    ruleReminder: String? = null,
    memoryDisabled: Boolean = false,
    frozenAnchorIds: Set<String>? = null,
): List<UIMessage> = buildInternalMessagesWithPlan(
    assistant = assistant,
    settings = settings,
    model = model,
    messages = messages,
    memories = memories,
    tools = tools,
    conversationSystemPrompt = conversationSystemPrompt,
    compressionWindow = compressionWindow,
    recoveryHint = recoveryHint,
    ruleReminder = ruleReminder,
    memoryDisabled = memoryDisabled,
    frozenAnchorIds = frozenAnchorIds,
).messages
