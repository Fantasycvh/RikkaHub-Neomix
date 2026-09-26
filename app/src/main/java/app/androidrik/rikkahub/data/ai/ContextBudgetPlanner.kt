package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.utils.estimateTokenCount

/**
 * **上下文预算规划器**（设计 §十二，peer ② 的「usage 锚点估算」）。
 *
 * 估算口径**复用** `utils/ContextLength.kt` 的 `estimateTokenCount`/`estimateTokens`
 * （那两处的注释明确要求共用：各写一份启发式会让「记忆预算 / 占用条 / 本预算」三方量纲漂移）。
 *
 * 要解决的问题：无头 run 的轨迹是**单调增长**的（目标 + 每一步的工具输出），而它
 * **既没有压缩、也没有 `contextMessageLimit`**（合成 profile 里那个值是 0 = 不限制）——
 * 撞满上下文窗口时 provider 直接报错，一次长 run 就这样白跑。聊天路径有自动压缩兜底，
 * run 没有，所以它需要自己的刹车。
 *
 * **为什么叫"planner"而不是又一个 guard**：它算的不是"累计输出够不够多"，而是
 * **"这个窗口里还剩多少给下一步"**——即把窗口切分成「固定开销 + 轨迹 + 留给回答的余量」。
 * 判定依据优先取 **provider 真实回传的 `usage.promptTokens`**（上次请求实际发出去的
 * 完整上下文 token 数，比本地按字符估算准得多）——这就是"usage 锚点"；
 * 没锚点时退化为本地估算（见 [ContextBudget.anchored]）。
 *
 * 无 Android 依赖、纯函数 + 一个小状态类，可 JVM 单测。
 */
data class ContextBudget(
    /** 模型的上下文窗口（token）。 */
    val windowTokens: Int,
    /** 本次请求的上下文占用（锚点或本地估算）。 */
    val usedTokens: Int,
    /** 留给"本轮回答"的余量下限（低于它就没法产出有意义的回答）。 */
    val minReplyTokens: Int,
    /** [usedTokens] 是否来自 provider 的真实 usage（锚点）。 */
    val anchored: Boolean,
) {
    /** 距离窗口上限还剩多少 token（可能为负：已经超了）。 */
    val remainingTokens: Int get() = windowTokens - usedTokens

    /** 占用比例（0..1+；UI 与日志用）。 */
    val usedRatio: Float get() = if (windowTokens <= 0) 1f else usedTokens.toFloat() / windowTokens

    val verdict: Verdict
        get() = when {
            remainingTokens < minReplyTokens -> Verdict.Exhausted
            usedRatio >= CONTEXT_BUDGET_WARNING_RATIO -> Verdict.Warning
            else -> Verdict.Ok
        }

    sealed interface Verdict {
        /** 还有余量，照常跑。 */
        data object Ok : Verdict

        /** 接近上限（≥ 警告比例）：只记日志，不打断——打断反而会丢掉已经攒到的结果。 */
        data object Warning : Verdict

        /** 剩下的空间已经不够产出一轮回答：**优雅停止**（结果照常回填）。 */
        data object Exhausted : Verdict
    }

    /** 一行诊断（日志与 run 详情共用口径）。 */
    fun describe(): String = buildString {
        append("used=$usedTokens/$windowTokens")
        append(" (${(usedRatio * 100).toInt()}%)")
        append(if (anchored) " anchor" else " estimate")
        append(" remaining=$remainingTokens")
    }
}

/**
 * 规划一次请求的上下文预算（**纯函数**）。
 *
 * @param windowTokens 模型上下文窗口（调用方用 `Model.effectiveContextLength` 解析）
 * @param messages run 的完整轨迹（含工具输出）
 * @param overheadTokens 每次请求固定的开销（system 正文 + 工具 schema）；**有锚点时忽略**
 *   ——锚点已经把它们包含在内了，再加一遍会虚高
 * @param minReplyTokens 留给回答的余量下限（默认 4K：低于这个数模型基本只能吐半句话）
 */
fun planContextBudget(
    windowTokens: Int,
    messages: List<UIMessage>,
    overheadTokens: Int,
    minReplyTokens: Int = MIN_REPLY_TOKENS,
): ContextBudget {
    val anchoredUsed = messages.asReversed()
        .firstNotNullOfOrNull { message ->
            // 只看助手消息的 usage：那是"发出去这一整份上下文"的真实计数
            message.usage?.takeIf { message.role == MessageRole.ASSISTANT }?.promptTokens
        }
        ?.takeIf { it > 0 }

    val used = anchoredUsed ?: (estimateTokenCount(messages) + overheadTokens.coerceAtLeast(0))

    return ContextBudget(
        windowTokens = windowTokens,
        usedTokens = used,
        minReplyTokens = minReplyTokens,
        anchored = anchoredUsed != null,
    )
}

/**
 * 上下文预算守卫：按 run 持有窗口与余量，逐步评估（**状态类很薄，逻辑全在纯函数里**）。
 *
 * 与 [WorkspaceTurnBudget] / [ToolCallLoopGuard] 的分工：那两个防"行为失控"
 * （反复撞同一个、输出堆成山），本类防"上下文被轨迹吃满"——三者互补，不是同一个东西。
 */
class ContextBudgetGuard(
    private val windowTokens: Int,
    private val minReplyTokens: Int = MIN_REPLY_TOKENS,
) {
    /** 逐步评估：返回当前预算快照（判定由调用方按 [ContextBudget.verdict] 处置）。 */
    fun evaluate(messages: List<UIMessage>, overheadTokens: Int): ContextBudget =
        planContextBudget(
            windowTokens = windowTokens,
            messages = messages,
            overheadTokens = overheadTokens,
            minReplyTokens = minReplyTokens,
        )

}

/** 占用到窗口的这个比例就开始记警告日志（与聊天侧占用条的 80% 同口径）。 */
const val CONTEXT_BUDGET_WARNING_RATIO: Float = 0.8f

/**
 * 留给回答的余量下限：**4K token**（低于它就判定 [ContextBudget.Verdict.Exhausted]）。
 *
 * 取值的两面性：太小（比如 1K）会让模型在一次被截断的回答上浪费一整步；
 * 太大（比如 32K）会把还能用的 run 提前掐掉——而 run 的产物是「已经攒到的结论」，
 * 提前掐掉的代价比多跑一步大。4K 大致是「一句结论 + 一小段证据」的量级。
 */
const val MIN_REPLY_TOKENS: Int = 4_000
