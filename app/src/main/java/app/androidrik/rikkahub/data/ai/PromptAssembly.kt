package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.ai.prompts.partitionCheckpointMessages
import app.androidrik.ai.ui.limitContext
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.Conversation

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
 * 组装 system 消息正文。
 *
 * 顺序即前缀缓存的稳定性来源：**任何一段内容变化都会作废整段前缀缓存**（system 在位置 0）。
 * 调用方改动此处顺序前请先读 `docs/peer-apps-benchmark.md` §6.7 ④。
 */
internal fun buildSystemPromptText(
    assistant: Assistant,
    settings: Settings,
    model: Model,
    messages: List<UIMessage>,
    memories: List<AssistantMemory>,
    tools: List<Tool>,
    conversationSystemPrompt: String?,
): String = buildString {
    val effectiveSystemPrompt =
        if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
            conversationSystemPrompt
        } else {
            assistant.systemPrompt
        }
    if (effectiveSystemPrompt.isNotBlank()) {
        append(effectiveSystemPrompt)
    }

    // 记忆
    if (assistant.enableMemory) {
        appendLine()
        // 预算来自设置（可调）：注入「哪些」由打分校准决定、「装多少」由它决定
        append(
            buildMemoryPrompt(
                memories = memories,
                budgetTokens = settings.memoryInjectBudgetTokens,
            )
        )
    }
    // 工具prompt
    tools.forEach { tool ->
        appendLine()
        append(tool.systemPrompt(model, messages))
    }
    // TikZ 渲染提示（启用时引导模型以闭合 $$...$$ 块输出图表，触发 MathBlock 渲染）。
    // 注意：必须是原始字符串——普通字符串里 $$ 会被 Kotlin 转义成单个 $，模型将收到错误指令。
    // 强调「闭合」：只写开头 $$ 漏掉结尾 $$ 的块，客户端不会识别为图表（显示为源码）。
    if (settings.displaySetting.enableDiagramRendering) {
        appendLine()
        append(
            """For TikZ diagrams, wrap the ENTIRE environment in a CLOSED $$...$$ block: open with $$ immediately before \begin{tikzpicture} (or \begin{tikzcd} or \begin{axis}), and close with $$ immediately after the matching \end{...}. Never omit the closing $$, and never put anything between the closing \end{...} and the closing $$. Example: $$\begin{tikzpicture}\draw (0,0) -- (1,1);\end{tikzpicture}$$. Full TikZ/pgf is supported (positioning, calc, pgfplots, tikz-cd, all standard libraries) with CJK text — do not simplify or avoid advanced syntax. Diagrams render as inline vector graphics. Colors: use color names (blue, red, gray) optionally with opacity (blue!30); do NOT use #hex colors inside options like fill=#4A90D9 — the # is a TeX parameter token and breaks pgf option parsing (renders as source) PGF Math trigonometry: radians MUST use sin(deg(x)) or sin(x*pi/180) — sin(x rad) and sin(x r) are NOT supported and abort rendering."""
        )
    }
    // 数学公式提示（启用 LaTeX 渲染时）：引导模型用 \(...\) / $...$ 输出公式并触发 jlatexmath。
    // 注意：定界符内不能有空格（$ x^2 $ 不会被识别为公式）；括号形式 \(x^2\) 更稳妥。
    if (settings.displaySetting.enableLatexRendering) {
        appendLine()
        append(
            """For math formulas, wrap them in \( ... \) (preferred) or $...$ with NO whitespace immediately after the opening delimiter and before the closing delimiter, e.g. \(x^2 + y^2 = r^2\) or $\frac{a}{b}$ or $\sqrt{x}$. They render as inline math symbols."""
        )
    }
}

/**
 * 对话消息部分（不含 system）：压缩窗口 + 上下文截断。
 *
 * 每步对 live [messages] 重算（而非传入冻结列表）——否则本回合内工具调用的结果
 * 进不了上下文，模型会重复调用同一工具。检查点固定在窗口最前
 * 且不参与 [limitContext] 截断（limitContext 保留尾部会截掉开头的检查点）。
 *
 * 注意：回写基线仍是**全量** messages（由调用方持有），这里产出的合成消息不会被持久化。
 * 见 `docs/design-auto-compaction.md` §4.2。
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
internal fun buildInternalMessages(
    assistant: Assistant,
    settings: Settings,
    model: Model,
    messages: List<UIMessage>,
    memories: List<AssistantMemory>,
    tools: List<Tool>,
    conversationSystemPrompt: String?,
    compressionWindow: Conversation.CompressionWindow?,
): List<UIMessage> {
    val system = buildSystemPromptText(
        assistant = assistant,
        settings = settings,
        model = model,
        messages = messages,
        memories = memories,
        tools = tools,
        conversationSystemPrompt = conversationSystemPrompt,
    )
    return buildList {
        if (system.isNotBlank()) {
            add(UIMessage.system(prompt = system).copy(isSynthetic = true))
        }
        addAll(
            buildSendWindowMessages(
                messages = messages,
                compressionWindow = compressionWindow,
                contextMessageLimit = assistant.contextMessageLimit,
            )
        )
    }
}
