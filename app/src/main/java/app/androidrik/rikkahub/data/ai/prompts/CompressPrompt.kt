package app.androidrik.rikkahub.data.ai.prompts

/**
 * 检查点前导语：请求期构造检查点合成消息时注入开头，告知续接模型把摘要当既有背景、
 * 不复述不承认（对齐 deepseek-harness compaction-basic）。
 *
 * 设计见 `docs/design/design-auto-compaction.md` §2.2 ②：检查点替换 + prior 合并。
 * 不落库为消息节点——仅在 [app.androidrik.rikkahub.data.ai.GenerationHandler] 组装发送
 * 窗口时与摘要正文拼成合成消息。
 *
 * ## ⚠️ 修改此常量前必读
 *
 * 本常量是**纯客户端硬编码**（不经过模型，因此与所用模型无关），但它同时充当
 * [isCompactionCheckpointPreamble] 的**文本识别标记**——用于：
 * 1. 发送窗口分离（[partitionCheckpointMessages]）：避免检查点被 limitContext 截掉；
 * 2. 遗留数据自愈（`filterOutCheckpointMessages` / `repairCorruptedCandidates`）：
 *    识别并清理历史上被误持久化的检查点消息。
 *
 * 因此**改措辞会导致旧数据识别失效**（与「改输出格式必须同步递增缓存版本」同类教训）。
 * 若确需修改：**必须把旧措辞原文追加到 [LEGACY_COMPACTION_PREAMBLES]**，否则旧脏数据将无法自愈。
 */
internal const val COMPACTION_CHECKPOINT_PREAMBLE =
    "This is an automatically generated checkpoint condensing an earlier span of the conversation to free up context. " +
        "Treat the captured context as established background and build on it without restating it. " +
        "Continue the task directly from the messages that follow, without acknowledging this checkpoint."

/**
 * 历史前导语措辞（已废弃但仍需识别）。
 *
 * 修改 [COMPACTION_CHECKPOINT_PREAMBLE] 时，把**旧版本措辞原文**追加到这里，
 * 保证旧数据仍能被识别与自愈。当前为空（尚无措辞变更）。
 */
internal val LEGACY_COMPACTION_PREAMBLES: List<String> = emptyList()

internal const val COMPACTION_SUMMARY_OPEN = "<compressed-summary>"
internal const val COMPACTION_SUMMARY_CLOSE = "</compressed-summary>"

/** 检查点完整文本 = 前导语 + <compressed-summary> 正文；请求期构造，不落库为消息 */
internal fun buildCompactionCheckpointText(summaryContent: String): String = buildString {
    appendLine(COMPACTION_CHECKPOINT_PREAMBLE)
    appendLine()
    append(COMPACTION_SUMMARY_OPEN)
    appendLine()
    append(summaryContent)
    appendLine()
    append(COMPACTION_SUMMARY_CLOSE)
}

/**
 * 是否为自动压缩产生的检查点消息（UI 据此渲染为压缩流程行而非用户气泡）。
 *
 * Phase 1 后续：检查点合成消息是请求期构造的（isSynthetic），不落库；
 * 此函数保留给将来「检查点作为消息节点落库」的兼容形态识别（Yuihub 两代方案并存）。
 */
internal fun String.isCompactionCheckpointPreamble(): Boolean =
    startsWith(COMPACTION_CHECKPOINT_PREAMBLE) ||
        LEGACY_COMPACTION_PREAMBLES.any { legacy -> startsWith(legacy) }

/** 从检查点文本中抽取摘要正文（<compressed-summary> 块内内容） */
internal fun compactionCheckpointBody(text: String): String {
    val start = text.indexOf(COMPACTION_SUMMARY_OPEN)
    val end = text.indexOf(COMPACTION_SUMMARY_CLOSE)
    return if (start >= 0 && end > start) {
        text.substring(start + COMPACTION_SUMMARY_OPEN.length, end).trim()
    } else {
        text
    }
}

/**
 * 分离压缩检查点合成消息与普通窗口消息（发送前组装用）。
 *
 * 检查点必须固定在窗口最前且**不参与 limitContext 截断**——limitContext 保留尾部，
 * 若把检查点一起传入会被截掉，模型将看不到被压缩的历史。
 *
 * @return (检查点消息, 其余消息)，检查点按原相对顺序在前
 */
internal fun List<app.androidrik.ai.ui.UIMessage>.partitionCheckpointMessages():
    Pair<List<app.androidrik.ai.ui.UIMessage>, List<app.androidrik.ai.ui.UIMessage>> =
    partition { message ->
        message.isSynthetic &&
            message.role == app.androidrik.ai.core.MessageRole.USER &&
            message.parts.any { part ->
                part is app.androidrik.ai.ui.UIMessagePart.Text && part.text.isCompactionCheckpointPreamble()
            }
    }

/**
 * 默认压缩提示词：结构化 8 段（对齐 deepseek-harness compaction-basic）。
 *
 * 比 fork 旧版（自由文本 7 条要求）更能保住可续接的事实——固定分节强制模型保留
 * 路径/命令/错误串/标识符/数值/函数签名等精确信息。用户可在设置页覆盖此 prompt。
 *
 * 占位符（applyPlaceholders 替换）：{target_tokens} / {locale} / {additional_context} / {content}
 */
internal val DEFAULT_COMPRESS_PROMPT = """
    You are now acting as a compaction engine for this AI assistant. Condense the conversation ABOVE into a structured checkpoint that lets another model resume the work with no loss of essential context.

    Output EXACTLY the Markdown structure below: keep every section, in order. Use terse bullets, not prose paragraphs. Write "(none)" for an empty section — never drop a section.

    # Primary Request and Intent
    - [the user's original and evolving goals; quote verbatim where the exact wording matters]

    # Key Technical Concepts
    - [technologies, frameworks, patterns, and conventions in play]

    # Files and Code
    - [exact path: why it matters, key changes or snippets]

    # Errors and Fixes
    - [error: how it was resolved, plus any related user feedback]

    # Pending Jobs
    - [explicitly requested work not yet completed]

    # Current Work
    - [precisely what was in progress at this checkpoint]

    # Next Step
    - [the single next action, directly in line with the most recent request, or "(none)"]

    # Critical Context
    - [decisions and their rationale, constraints, user preferences, open questions, data needed to continue]

    Rules:
    - Preserve exact file paths, commands, error strings, identifiers, numeric values, function signatures.
    - Capture user feedback and explicit instructions faithfully, especially corrections.
    - Do NOT mention this summarization request or that the context was compacted.
    - Output only the checkpoint text: do not call any tool or take any other action.
    - A PRIOR CHECKPOINT may be provided in the additional context above: if so, do not copy it verbatim; keep still-true facts, drop stale ones, and merge newer information into a single consolidated checkpoint under the same structure.
    - Use {locale} language. Target approximately {target_tokens} tokens.

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()

/**
 * 清洗：剔除误当作消息持久化的检查点合成消息（历史 bug 产生的脏数据）。
 *
 * 早期版本把「发送窗口」（含检查点合成消息）当作回写基线，导致合成消息被追加为消息候选
 * （`selectIndex` 指向它，界面上只看到摘要）。修复回写基线后不再产生；此函数用于**自愈**
 * 已污染的数据。
 *
 * **判据只用文本前导语，不能依赖 `isSynthetic`**——该字段是 `@Transient`，持久化后丢失
 * （见 `UIMessage.isSynthetic`「仅在内存中使用」），DB 读回来恒为 false。前导语是一段独特的
 * 英文说明 + XML 标签，用户手输碰撞概率可忽略。
 */
internal fun List<app.androidrik.ai.ui.UIMessage>.filterOutCheckpointMessages():
    List<app.androidrik.ai.ui.UIMessage> = filterNot { message ->
    message.role == app.androidrik.ai.core.MessageRole.USER &&
        message.parts.any { part ->
            part is app.androidrik.ai.ui.UIMessagePart.Text && part.text.isCompactionCheckpointPreamble()
        }
}
