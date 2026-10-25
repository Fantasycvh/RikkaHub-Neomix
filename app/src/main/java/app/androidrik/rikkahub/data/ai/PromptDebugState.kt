package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.utils.estimateTokenCount

/**
 * 调试状态（借鉴华灯 `PromptDebugCache`，见 peer-apps-benchmark §八.1）。
 *
 * 缓存每次生成**实际发送**给模型的完整消息列表（按会话隔离，保留上一次与本次）：
 * 前缀缓存从第一个 token 起连续匹配，缓存长度停在某个值不再增长 ⇒ 该位置存在
 * 每轮变化的内容。对比上次与本次请求、定位第一条分叉消息并给出可命中前缀的
 * token 估算，把「缓存为什么断」变成可观察事实。
 *
 * 与拼装预览（PromptInspector，单次请求逐段归因）互补：本层做**跨请求差分**。
 * 调试用内存缓存，进程被杀即失效，无需持久化。
 *
 * **facade 结构**：按「会话调试现场」组织两个**独立淘汰**的 store——请求历史（大块、
 * 每生成步写、可丢，LRU）与段折叠状态（小、UI 偶发写、首帧同步读）。**不共享一个
 * LRU**：折叠读发生在组合/滚动期（高频），若刷新同一 recency 会把大块请求列表钉在
 * 内存（淘汰语义被污染）。跨导航存活由 store 保证；进程死亡由调用方的
 * `rememberSaveable` 叠加恢复（初值同源自 store，两不打架）。
 */
object PromptDebugState {
    // ---- store 1：请求历史（大块、每生成步写、可丢）——LRU 8，recency 只被写/显式读刷新 ----

    private data class DebugEntry(
        val current: List<UIMessage>,
        val previous: List<UIMessage>?,
    )

    /** 每条 entry 保存两份完整请求（含工具输出），不设上限会随会话数无限增长 */
    private const val MAX_REQUEST_ENTRIES = 8

    private val requests = object : LinkedHashMap<String, DebugEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DebugEntry>): Boolean =
            size > MAX_REQUEST_ENTRIES
    }

    /** 记录一次实际请求（工具循环的每一步都算一次请求；分叉诊断对步进同样成立）。 */
    @Synchronized
    fun recordRequest(key: String, messages: List<UIMessage>) {
        if (messages.isEmpty()) return
        val old = requests[key]
        requests[key] = DebugEntry(messages, old?.current)
    }

    /** 返回 (本次, 上一次)；上一次为 null 表示该会话只记录到一次请求。 */
    @Synchronized
    fun latestRequest(key: String): Pair<List<UIMessage>, List<UIMessage>?>? =
        requests[key]?.let { it.current to it.previous }

    // ---- store 2：段折叠状态（小、UI 偶发写、首帧同步读）——独立小表，不与请求 LRU 共享 recency ----

    private const val MAX_FOLD_ENTRIES = 8

    private val foldStates = object : LinkedHashMap<String, MutableMap<Int, Boolean>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<Int, Boolean>>): Boolean =
            size > MAX_FOLD_ENTRIES
    }

    /** 段折叠初值（组合期同步读）；缺省 false = 展开（2026-09-19 决策：折叠为手动动作）。 */
    @Synchronized
    fun foldState(key: String, segmentIndex: Int): Boolean =
        foldStates[key]?.get(segmentIndex) ?: false

    @Synchronized
    fun setFolded(key: String, segmentIndex: Int, folded: Boolean) {
        foldStates.getOrPut(key) { mutableMapOf() }[segmentIndex] = folded
    }

    // ---- store 3：净化 dry-run 报告（小、每生成步写、可丢）——独立 LRU（同 store 1 的淘汰语义） ----

    /** 一次净化 dry-run 的快照（P0 观察数据；见 `design-context-purification.md` §7） */
    data class PurgeSnapshot(
        val windowCount: Int,
        val savedChars: Int,
        val windowHash: Int,
        /** `rule@messageIndex:detail` 列表（预览卡与翻转对比用） */
        val decisions: List<String>,
    )

    private const val MAX_PURGE_ENTRIES = 8

    private val purges = object : LinkedHashMap<String, Pair<PurgeSnapshot, PurgeSnapshot?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<PurgeSnapshot, PurgeSnapshot?>>): Boolean =
            size > MAX_PURGE_ENTRIES
    }

    /**
     * 记录一次净化 dry-run（真实路径每步组装后调用）。
     *
     * @return 是否**翻转**：窗口内容指纹相同但决策不同——设计稿 §4.1「位置稳定 ≠ 字节稳定」
     *   的观察项，正常应恒为 false；true 时调用方应打告警日志（P0 统计翻转率）。
     */
    @Synchronized
    internal fun recordPurge(key: String, result: ContextPurifier.Result): Boolean {
        val snap = PurgeSnapshot(
            windowCount = result.windowCount,
            savedChars = result.savedChars,
            windowHash = result.windowHash,
            decisions = result.decisions.map { "${it.rule}@${it.messageIndex}:${it.detail}" },
        )
        val old = purges[key]
        purges[key] = snap to old?.first
        return old != null &&
            old.first.windowHash == snap.windowHash &&
            old.first.decisions != snap.decisions
    }

    /** 最近一次净化 dry-run 快照；null = 该会话本进程内尚未生成过（或被 LRU 淘汰）。 */
    @Synchronized
    fun latestPurge(key: String): PurgeSnapshot? = purges[key]?.first
}

/** 诊断结果：前缀一致段 + 分叉点信息。 */
data class PrefixDivergence(
    /** 两条请求从头一致的消息条数（这些内容可命中前缀缓存） */
    val commonPrefixMessages: Int,
    /** [commonPrefixMessages] 的 token 估算（视图打开时计算，调用方自担线程） */
    val commonPrefixTokens: Int,
    val previousSize: Int,
    val currentSize: Int,
    /** 本次请求中第一条不一致消息的预览（role + 首行）；null = 两条请求完全一致 */
    val divergedMessagePreview: String?,
)

/** 诊断：定位两条请求的第一条分叉消息。纯函数，token 估算由回调注入（便于单测）。 */
fun diagnosePrefixDivergence(
    previous: List<UIMessage>,
    current: List<UIMessage>,
    tokenEstimator: (List<UIMessage>) -> Int = ::estimateTokenCount,
): PrefixDivergence {
    val n = minOf(previous.size, current.size)
    var i = 0
    while (i < n && messageKey(previous[i]) == messageKey(current[i])) i++
    return PrefixDivergence(
        commonPrefixMessages = i,
        commonPrefixTokens = if (i == 0) 0 else tokenEstimator(previous.take(i)),
        previousSize = previous.size,
        currentSize = current.size,
        divergedMessagePreview = current.getOrNull(i)?.let { msg ->
            val firstLine = msg.parts.firstNotNullOfOrNull { part ->
                when (part) {
                    is app.androidrik.ai.ui.UIMessagePart.Text -> part.text.lineSequence().firstOrNull { it.isNotBlank() }
                    is app.androidrik.ai.ui.UIMessagePart.Tool -> "[工具输出] ${part.toolName}"
                    is app.androidrik.ai.ui.UIMessagePart.Reasoning -> "[思考]"
                    else -> null
                }
            }.orEmpty()
            "${roleLabel(msg.role)}：${firstLine.take(60)}"
        },
    )
}

private fun roleLabel(role: MessageRole): String = when (role) {
    MessageRole.SYSTEM -> "系统"
    MessageRole.USER -> "用户"
    MessageRole.ASSISTANT -> "助手"
    MessageRole.TOOL -> "工具"
}

/** 消息的稳定内容签名（role + parts 全量序列化；诊断用途，不追求可读）。 */
private fun messageKey(message: UIMessage): String =
    message.role.name + ":" + message.parts.joinToString("|") { it.toString() }
