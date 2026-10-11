package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.ui.isOutputLimitTruncation

/**
 * 自动续写上限：单次生成内最多自动接着写几次（手动「继续生成」不受此限）。
 *
 * 每次续写都要重发整个上下文，「中转持续断流」若不设上限就是无上限重发（烧费）。
 */
const val CONTINUE_LIMIT = 3

/**
 * 续写决策（纯函数，便于单测固定策略）：
 * 给出「这次输出不完整时，是否自动续写、用哪条提示」，null = 不续写。
 *
 * 策略（成本权衡，见 私有仓记录·前期调研 §四.7 D）：
 * - **裸断流**（[finishIncomplete]）：网关/中转提前切连接，**非模型本意** ⇒ 恒自动续写。
 * - **撞输出上限**（[isOutputLimitTruncation]）：模型确实写满了配额。续写多半是用户想要的
 *   长文，但也可能只是想写一段就停 ⇒ **默认只提示 + 手动继续**，仅在用户显式开启
 *   `autoContinueOnLengthTruncation` 时才自动（连烧多轮的费用应由用户同意）。
 * - **两类都超上限** ⇒ 不再自动（转由 UI 的手动「继续生成」兜底）。
 *
 * 优先级：裸断流 > 撞上限。两者理论上可能同时成立（网关在模型撞上限后没发终止标记就断流），
 * 此时按裸断流续写是正确的——内容确实被切断了。
 */
fun autoContinueHintFor(
    finishIncomplete: Boolean,
    finishReason: String?,
    autoContinueOnLengthTruncation: Boolean,
    alreadyContinued: Int,
): String? {
    if (alreadyContinued >= CONTINUE_LIMIT) return null
    return when {
        finishIncomplete -> CONTINUE_INCOMPLETE_HINT
        isOutputLimitTruncation(finishReason) && autoContinueOnLengthTruncation -> CONTINUE_TRUNCATED_HINT
        else -> null
    }
}
