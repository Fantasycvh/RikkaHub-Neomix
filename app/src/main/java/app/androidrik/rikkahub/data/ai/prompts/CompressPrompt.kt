package app.androidrik.rikkahub.data.ai.prompts

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isExternalNotice
import app.androidrik.ai.ui.semanticRole
import app.androidrik.rikkahub.data.model.Conversation

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
 * 因此**改措辞会导致旧数据识别失效**（与「改 TikZ 渲染输出必须递增 CACHE_VERSION」同类教训）。
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
internal fun List<UIMessage>.partitionCheckpointMessages(): Pair<List<UIMessage>, List<UIMessage>> =
    partition { message -> message.isCompactionCheckpointMessage() }

/**
 * 中途压缩（一轮回复内部压缩，上游 `bcc649abc`）追加给摘要模型的指令。
 *
 * 判据：整轮压缩的摘要写给**下一轮**，中途压缩的摘要写给**同一条任务的下一步**——读它的
 * 还是同一个模型、刚干到一半，需要的不是对话复述，而是「已做到哪、下一步做什么」。
 * 因此在用户可覆盖的模板**之后**追加这段，而不改模板本身（用户手改过的提示词要保持原样）。
 */
internal const val MID_TURN_COMPRESS_INSTRUCTION: String =
    "MID-TURN COMPACTION: this summary is written in the middle of the assistant's work on the user's " +
        "latest request, because the context is almost full. Right after it the same assistant continues " +
        "that work with only this summary as context, and the user will not see or answer anything in " +
        "between. In \"Current Work\" state exactly which steps are already done and which remain, and in " +
        "\"Next Step\" the single immediate action, so the work resumes without repeating finished steps " +
        "or re-asking the user."

/**
 * 续跑提示：检查点之后**不是用户消息**时追加到检查点合成消息尾部（只进请求面，不落库）。
 *
 * 判据（上游 `bcc649abc` 同款）：检查点之后紧跟的是助手回复、或什么都没有（中途压缩刚发生），
 * 说明这一轮还没结束、接下来是同一个任务的下一步。此时摘要是「用户口径」的一条消息，模型容易
 * 把它当成用户刚发来的新请求去复述/确认，或者从头重做。提示只进请求面；检查点正文（会落库、
 * 会被后续压缩合并）保持干净，避免层层压缩时把提示抄进摘要。
 *
 * 为什么用「后面不是用户消息」而不是「在末尾」判断：一轮之后的请求里检查点仍带着这段话
 * （后面跟着那条续跑回复），前缀不变，不会每步作废提示词缓存。
 */
internal const val CONTINUATION_HINT: String =
    "The conversation history before this point was replaced by the summary above. " +
        "If a task was in progress, continue it from where it left off: do not reply to the summary " +
        "itself, and do not ask the user to repeat anything."

/** 是否为请求期构造的检查点合成消息（判据与 [partitionCheckpointMessages] 一致）。 */
internal fun UIMessage.isCompactionCheckpointMessage(): Boolean =
    isSynthetic &&
        role == MessageRole.USER &&
        parts.any { part -> part is UIMessagePart.Text && part.text.isCompactionCheckpointPreamble() }

/**
 * 请求里的「检查点」：本仓合成检查点，或导入的原版检查点（[isContextCheckpoint]）。
 *
 * 续跑判定两种都要算——导入的检查点同样是「历史被摘要替代」的边界，模型一样会把摘要
 * 当成用户新提的要求。而 [partitionCheckpointMessages] 只认原生合成消息（它决定的是
 * 「哪些消息要被提到窗口最前」，导入检查点必须留在原位置，二者目的不同）。
 */
internal fun UIMessage.isRequestCheckpoint(): Boolean =
    isCompactionCheckpointMessage() || isContextCheckpoint

/**
 * 发送窗口里「检查点后面不是用户消息」（见 [CONTINUATION_HINT] 注释）。
 *
 * 传入的必须是**窗口消息**（已应用压缩窗口）：装配产物尾部还有记忆数据/规则提醒两条合成 USER
 * 消息，拿它判断会被骗成「后面是用户消息」，续跑提示就永远不出现。
 *
 * 判据跳过**外部通知**（workspace/子代理结果）：它们以 USER 为载体、语义却是 SYSTEM
 * （[semanticRole]），不是用户发来的话；若紧跟在检查点后面就把真正的用户消息挡住，
 * 会把「下一轮用户提问」误判成还没结束的阶段。
 */
internal fun List<UIMessage>.hasCheckpointNotFollowedByUser(): Boolean {
    val index = indexOfLast { it.isRequestCheckpoint() }
    if (index < 0) return false
    val next = drop(index + 1).firstOrNull { !it.isExternalNotice }
    return next?.semanticRole != MessageRole.USER
}

/**
 * 需要时给压缩窗口的检查点消息追加 [CONTINUATION_HINT]（返回**同一实例**表示无需改动）。
 *
 * 落点选在窗口描述而不是装配产物：检查点合成消息由 [Conversation.CompressionWindow] 提供，
 * 装配侧（`buildSendWindowMessages`）只做窗口套用与截断、不掺判定；而判定需要「窗口里检查点
 * 之后是什么」，只有拿得到 live messages 的调用方（`GenerationHandler.generateInternal`）知道。
 */
internal fun Conversation.CompressionWindow.withContinuationHintFor(
    messages: List<UIMessage>,
): Conversation.CompressionWindow {
    if (!applyTo(messages).hasCheckpointNotFollowedByUser()) return this
    return copy(
        checkpointMessage = checkpointMessage.copy(
            parts = checkpointMessage.parts + UIMessagePart.Text(CONTINUATION_HINT),
        ),
    )
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
    - Write in first person from the assistant's perspective ("I said", "I decided", "the user asked me to...") — this checkpoint is the assistant's own memory of the conversation, NOT a third-person report about "the user and the assistant".
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
