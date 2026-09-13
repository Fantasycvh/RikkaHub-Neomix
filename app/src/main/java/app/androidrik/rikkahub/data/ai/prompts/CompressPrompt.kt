package app.androidrik.rikkahub.data.ai.prompts

/**
 * 检查点前导语：请求期构造检查点合成消息时注入开头，告知续接模型把摘要当既有背景、
 * 不复述不承认（对齐 deepseek-harness compaction-basic）。
 *
 * 设计见 `docs/design-auto-compaction.md` §2.2 ②：检查点替换 + prior 合并。
 * 不落库为消息节点——仅在 [app.androidrik.rikkahub.data.ai.GenerationHandler] 组装发送
 * 窗口时与摘要正文拼成合成消息。
 */
internal const val COMPACTION_CHECKPOINT_PREAMBLE =
    "This is an automatically generated checkpoint condensing an earlier span of the conversation to free up context. " +
        "Treat the captured context as established background and build on it without restating it. " +
        "Continue the task directly from the messages that follow, without acknowledging this checkpoint."

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
    startsWith(COMPACTION_CHECKPOINT_PREAMBLE)

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
