package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.extractContextForMatching
import app.androidrik.rikkahub.data.model.isTriggered
import app.androidrik.rikkahub.data.model.limitByTokenBudget
import app.androidrik.rikkahub.utils.estimateTokens
import kotlin.uuid.Uuid

/**
 * 提示词注入转换器
 *
 * 根据 Assistant 关联的 ModeInjection 和 Lorebook 进行提示词注入
 */
object PromptInjectionTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return transformMessages(
            messages = messages,
            assistant = ctx.assistant,
            modeInjections = ctx.settings.modeInjections,
            lorebooks = ctx.settings.lorebooks,
            conversationModeInjectionIds = ctx.conversationModeInjectionIds,
            conversationLorebookIds = ctx.conversationLorebookIds,
            lorebookBudgetTokens = ctx.settings.lorebookInjectBudgetTokens,
            // 预设级绑定 → 该预设内全部条目的 id。
            // **助手级、不随会话覆盖**：预设表达的是「这个助手基于哪套方案」，
            // 属助手配置，与会话级的逐条勾选是两件事（会话侧也没有预设 id 字段）。
            presetEntryIds = ctx.settings.promptPresets
                .filter { it.enabled && ctx.assistant.promptPresetIds.contains(it.id) }
                .flatMap { it.entries }
                .map { it.id }
                .toSet(),
        )
    }
}

/**
 * 核心注入逻辑（可测试的纯函数）
 */
internal fun transformMessages(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    /** 世界书注入的 token 预算；0 = 不限制（默认，保持旧行为） */
    lorebookBudgetTokens: Int = 0,
    /** 已绑定预设所含条目的 id（由调用方按「启用的预设 ∩ 助手绑定」算出） */
    presetEntryIds: Set<Uuid> = emptySet(),
): List<UIMessage> {
    // 收集所有需要注入的内容
    val injections = collectInjections(
        messages = messages,
        assistant = assistant,
        modeInjections = modeInjections,
        lorebooks = lorebooks,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
        lorebookBudgetTokens = lorebookBudgetTokens,
        presetEntryIds = presetEntryIds,
    )

    if (injections.isEmpty()) {
        return messages
    }

    // 按位置和优先级分组
    val byPosition = injections
        .sortedByDescending { it.priority }
        .groupBy { it.position }

    // 应用注入
    return applyInjections(messages, byPosition)
}

/**
 * 收集需要注入的内容
 */
internal fun collectInjections(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    lorebookBudgetTokens: Int = 0,
    presetEntryIds: Set<Uuid> = emptySet(),
): List<PromptInjection> {
    val injections = mutableListOf<PromptInjection>()
    val effectiveModeInjectionIds = if (assistant.allowConversationPromptInjection) {
        conversationModeInjectionIds
    } else {
        assistant.modeInjectionIds
    }
    val effectiveLorebookIds = if (assistant.allowConversationPromptInjection) {
        conversationLorebookIds
    } else {
        assistant.lorebookIds
    }

    // 1. 获取关联的 ModeInjection
    //    生效条件 = 条目自身启用 且（被逐条绑定 **或** 属于已绑定的预设）。
    //    两者是**并集**：预设提供「一次绑一组」，条目级绑定保留单独勾选能力。
    //    `distinctBy { id }` 必需：同一 id 同时走两条路径（预设内 + 被单独勾选）
    //    否则会注入两次——UI 上就会出现重复段落。
    modeInjections
        .filter { it.enabled && (effectiveModeInjectionIds.contains(it.id) || presetEntryIds.contains(it.id)) }
        .distinctBy { it.id }
        .forEach { injections.add(it) }

    // 2. 获取关联的 Lorebook 中被触发的 RegexInjection
    val enabledLorebooks = lorebooks.filter {
        it.enabled && effectiveLorebookIds.contains(it.id)
    }
    if (enabledLorebooks.isNotEmpty()) {
        // 提取上下文用于匹配（只取非 SYSTEM 消息）
        val nonSystemMessages = messages.filter { it.role != MessageRole.SYSTEM }
        // 助手人设文本：仅当条目开了 matchAssistantPrompt 时才纳入（对齐 ST 的 match* 开关）。
        // 用 `systemPrompt` 而非最终发出的 system 消息——后者含记忆/工具 schema/渲染提示，
        // 那些是**请求期合成**的易变内容，纳入扫描会让条目随机命中自己或工具描述。
        val assistantPromptText = assistant.systemPrompt

        // 先收集全部命中条目，**再统一按预算裁剪**（而非逐本条裁剪）：
        // 预算语义是「本次注入总量上限」，需要看到全部候选才能正确取舍。
        val triggered = mutableListOf<PromptInjection.RegexInjection>()
        enabledLorebooks.forEach { lorebook ->
            lorebook.entries
                .filter { entry ->
                    val context = extractContextForMatching(
                        messages = nonSystemMessages,
                        scanDepth = entry.scanDepth,
                        extraTexts = if (entry.matchAssistantPrompt) listOf(assistantPromptText) else emptyList(),
                    )
                    entry.isTriggered(context)
                }
                .forEach { triggered.add(it) }
        }
        // 预算 <= 0 表示不限制（默认）——limitByTokenBudget 会原样返回
        injections.addAll(
            triggered.limitByTokenBudget(
                budgetTokens = lorebookBudgetTokens,
                estimate = { estimateTokens(it) },
            )
        )
    }

    return injections
}

/**
 * 应用注入到消息列表
 */
internal fun applyInjections(
    messages: List<UIMessage>,
    byPosition: Map<InjectionPosition, List<PromptInjection>>
): List<UIMessage> {
    val result = messages.toMutableList()

    // 找到系统消息的索引（通常是第一条）
    val systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }

    // 处理 BEFORE_SYSTEM_PROMPT 和 AFTER_SYSTEM_PROMPT
    if (systemIndex >= 0) {
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""

        if (beforeContent.isNotEmpty() || afterContent.isNotEmpty()) {
            val systemMessage = result[systemIndex]
            val originalText = systemMessage.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }

            val newText = buildString {
                if (beforeContent.isNotEmpty()) {
                    append(beforeContent)
                    appendLine()
                }
                append(originalText)
                if (afterContent.isNotEmpty()) {
                    appendLine()
                    append(afterContent)
                }
            }

            result[systemIndex] = systemMessage.copy(
                parts = listOf(UIMessagePart.Text(newText)),
                isSynthetic = true,
            )
        }
    } else {
        // 没有系统消息时，创建一个新的系统消息
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""

        val combinedContent = buildString {
            if (beforeContent.isNotEmpty()) {
                append(beforeContent)
            }
            if (afterContent.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                append(afterContent)
            }
        }

        if (combinedContent.isNotEmpty()) {
            result.add(0, UIMessage.system(combinedContent).copy(isSynthetic = true))
        }
    }

    // 处理 TOP_OF_CHAT：在第一条用户消息之前插入
    val topInjections = byPosition[InjectionPosition.TOP_OF_CHAT]
    if (!topInjections.isNullOrEmpty()) {
        // 重新计算索引（因为可能插入了系统消息）
        var insertIndex = result.indexOfFirst { it.role == MessageRole.USER }
            .takeIf { it >= 0 } ?: result.size
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(topInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 BOTTOM_OF_CHAT：在最后一条消息之前插入
    val bottomInjections = byPosition[InjectionPosition.BOTTOM_OF_CHAT]
    if (!bottomInjections.isNullOrEmpty()) {
        var insertIndex = (result.size - 1).coerceAtLeast(0)
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(bottomInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 AT_DEPTH：在指定深度位置插入（从最新消息往前数）
    // 按 injectDepth 分组，相同深度的合并，按深度从大到小处理（避免索引变化问题）
    val atDepthInjections = byPosition[InjectionPosition.AT_DEPTH]
    if (!atDepthInjections.isNullOrEmpty()) {
        val byDepth = atDepthInjections.groupBy { it.injectDepth }
        byDepth.keys.sortedDescending().forEach { depth ->
            val injections = byDepth[depth] ?: return@forEach
            // 计算插入位置：result.size - depth，但要确保在有效范围内
            // depth=1 表示在最后一条消息之前，depth=2 表示在倒数第二条之前...
            var insertIndex = (result.size - depth.coerceAtLeast(1)).coerceIn(0, result.size)
            insertIndex = findSafeInsertIndex(result, insertIndex)
            createMergedInjectionMessages(injections).forEach { message ->
                result.add(insertIndex, message)
                insertIndex++
            }
        }
    }

    return result
}

/**
 * 将同一 role 的注入合并成消息列表
 * 按 role 分组后合并内容，返回合并后的消息列表
 */
private fun createMergedInjectionMessages(injections: List<PromptInjection>): List<UIMessage> {
    return injections
        .groupBy { it.role }
        .map { (role, grouped) ->
            val mergedContent = grouped.joinToString("\n") { it.content }
            when (role) {
                MessageRole.ASSISTANT -> UIMessage.assistant(mergedContent)
                else -> UIMessage.user(mergedContent)
            }.copy(
                isSynthetic = true,
            )
        }
}

/**
 * 查找安全的插入位置，避免注入到 USER → ASSISTANT(含Tool) 之间
 *
 * 某些提供商（如 deepseek）要求 USER 之后紧跟带工具的 ASSISTANT，
 * 在两者之间插入消息会导致报错或破坏推理连续性。
 */
internal fun findSafeInsertIndex(messages: List<UIMessage>, targetIndex: Int): Int {
    var index = targetIndex.coerceIn(0, messages.size)

    // 向前查找，直到找到一个安全的位置
    while (index > 0) {
        val prevMessage = messages.getOrNull(index - 1)
        val currentMessage = messages.getOrNull(index)

        // 不能插入到 USER → ASSISTANT(含Tool) 之间
        val isPrevUser = prevMessage?.role == MessageRole.USER
        val isCurrentAssistantWithTools = currentMessage?.role == MessageRole.ASSISTANT
            && currentMessage.getTools().isNotEmpty()

        if (isPrevUser && isCurrentAssistantWithTools) {
            index--
        } else {
            break
        }
    }

    return index
}
