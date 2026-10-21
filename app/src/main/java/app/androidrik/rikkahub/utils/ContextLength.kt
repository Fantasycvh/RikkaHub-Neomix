package app.androidrik.rikkahub.utils

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.common.tokenizer.TokenizerRegistry
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

// 模型上下文长度的默认兜底值（未设置时的 256K）
const val DEFAULT_CONTEXT_LENGTH: Int = 256 * 1024

// 上下文占用警告阈值：占用达到模型窗口的 80% 时进度条变红（对齐 deepseek-harness compaction-basic
// 的触发阈值；此处仅作 UI 警告色，不触发任何压缩动作）
const val CONTEXT_USAGE_WARNING_RATIO: Float = 0.8f

// 获取模型上下文长度：customContextLength（用户手动）> contextLength（拉取/注册）> globalOverride（全局默认）> 256K 兜底
fun Model.effectiveContextLength(globalOverride: Int? = null): Int =
    customContextLength ?: contextLength ?: globalOverride ?: DEFAULT_CONTEXT_LENGTH

// 解析 "256k"/"1M"/"2.5m"/"262144" 等格式为 token 数；非法返回 null
fun parseContextLengthInput(text: String): Int? {
    val normalized = text.trim().lowercase().replace(" ", "")
    if (normalized.isEmpty()) return null
    return when {
        normalized.endsWith("m") -> ((normalized.dropLast(1).toDoubleOrNull() ?: return null) * 1_000_000).toLong()
        normalized.endsWith("k") -> ((normalized.dropLast(1).toDoubleOrNull() ?: return null) * 1_000).toLong()
        else -> normalized.toLongOrNull() ?: return null
    }.takeIf { it > 0 && it <= 100_000_000 }?.toInt()
}

// 格式化上下文长度：1_000_000 -> "1M"，256_000 -> "256K"，null -> 空串（UI 用 placeholder 提示默认值）
fun formatContextLength(tokens: Int?): String = when {
    tokens == null -> ""
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}

/**
 * 会话发送窗口的 token 占用估算（「已使用上下文」显示与自动压缩触发判断共用）。
 *
 * **估算对象是发送窗口**（[Conversation.requestWindowMessages]），不是全量历史——压缩落地后
 * 历史早期内容已由检查点替代，按全量算会系统性高估。
 *
 * **usage 优先但须判陈旧**：最后一条 assistant 消息的 `usage.promptTokens` 是「上次实际发给
 * 模型的完整上下文 token 数」，比本地估算准。但它反映的是**那次请求当时**的上下文——若期间发生了
 * 压缩，该值仍是压缩前的全量值，直接用会得出虚高的占用（表现为「手动压缩成功后占用条数字不动、
 * 像没生效」，或导入带检查点的长会话后首次回复前一直偏高）。故检查点比它新时判定为陈旧，
 * 回退本地估算（按压缩后窗口算）。
 *
 * @return 估算 token 数；无消息时 0。
 */
fun Conversation.estimateWindowTokens(model: Model?): Int {
    val window = requestWindowMessages()
    val lastAssistant = window.lastOrNull { it.role == MessageRole.ASSISTANT }
    val usageTokens = lastAssistant?.usage?.promptTokens ?: 0
    val usageIsFresh = usageTokens > 0 && lastAssistant != null && run {
        // displayCompression() 统一原生与导入检查点：两者都比旧 usage 新时判定陈旧，
        // 否则导入检查点的会话会拿压缩前的全量 usage 显示虚高占用。
        val checkpoint = displayCompression()
        if (checkpoint == null) return@run true
        // usage 是在消息**生成完成**时产生的，故以 finishedAt 为准（缺失时退回 createdAt）。
        // 它早于检查点创建 → 该 usage 计的是压缩前的上下文 → 陈旧。
        val usageAt = lastAssistant.finishedAt ?: lastAssistant.createdAt
        usageAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() >
            checkpoint.createdAt.toEpochMilli()
    }
    return if (usageIsFresh) usageTokens else estimateTokenCount(window)
}

/**
 * 消息列表的 token 数（无 usage 时的占用判断口径）。
 *
 * **真 tokenizer 优先**：装了 `cl100k_base` 词表就按真 BPE 计数；**未预热/装载失败**时退回字符启发式
 * （行为不中断，只是精度下降）。工具输出纳入估算（fork 工具输出上限 32KB，不纳入会低估 agent 对话占用）。
 */
fun estimateTokenCount(messages: List<UIMessage>): Int {
    val texts = messages.flatMap { message ->
        message.parts.mapNotNull { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Tool -> part.output.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }

                else -> null
            }
        }
    }

    // 真 tokenizer：逐段精确计数后相加（每段本身就是精确值，不存在取整问题）
    if (TokenizerRegistry.current != null) {
        return texts.sumOf { estimateTokens(it) }
    }

    // 兜底：**按整批字符统计**，而不是把逐段启发式相加——后者每段都会被 `toInt()` 向下取整一次，
    // 100 段就能凭空少算上百 token（正是本功能要治的"系统性偏低"）
    var cjk = 0
    var other = 0
    texts.forEach { text ->
        for (ch in text) {
            if (isCjk(ch)) cjk++ else other++
        }
    }
    return tokensFromCounts(cjk, other)
}

/**
 * **文本**的 token 数——**全仓估算口径的唯一出口**。
 *
 * 记忆注入的预算截断需要按 token 计（`docs/design/design-memory-scoring.md` §六），而记忆是纯字符串、
 * 没有 [UIMessage] 包装；[estimateTokenCount] 也走这里。共用本函数而非各写一份启发式，
 * 是为了让「记忆预算 / 上下文占用 / 压缩切分 / 子代理预算」始终保持**同一量纲**。
 *
 * **两个实现**（2026-09-26 起）：
 * 1. **真 tokenizer**：`common` 里的字节级 BPE（cl100k_base 词表，456KB gz，与 tiktoken 逐条一致），
 *    启动时后台预热；
 * 2. **字符启发式**（本文件下方）：预热完成前/装载失败时的兜底。
 *
 * 换真值的原因：用 44 条参考向量比对下来，启发式**系统性偏低**（启发式/真值中位数 0.64，
 * 区间 0.17–2.0），而调用它的地方全是预算判定——偏低意味着该压的时候不压、该停的时候不停。
 */
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    TokenizerRegistry.count(text)?.let { return it }
    return heuristicTokenCount(text)
}

/** 字符启发式（CJK 1.5 字符/token、其余 4 字符/token）——真 tokenizer 不可用时的兜底。 */
fun heuristicTokenCount(text: String): Int {
    var cjk = 0
    var other = 0
    for (ch in text) {
        if (isCjk(ch)) cjk++ else other++
    }
    return tokensFromCounts(cjk, other)
}

/** CJK（中日韩）字符判定：这些字符约 1.5 字符/token，其余约 4 字符/token。 */
private fun isCjk(ch: Char): Boolean =
    ch in '\u4e00'..'\u9fff' || ch in '\u3040'..'\u30ff' || ch in '\uac00'..'\ud7af'

private fun tokensFromCounts(cjk: Int, other: Int): Int =
    (cjk / 1.5 + other / 4.0).toInt().coerceAtLeast(0)
