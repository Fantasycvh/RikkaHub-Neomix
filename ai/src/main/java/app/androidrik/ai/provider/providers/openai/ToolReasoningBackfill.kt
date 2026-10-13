package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility

/** Responses API 的回填写占位文本：它**拒绝空文本**，非空即可（内容无实义，仅满足结构校验）。 */
internal const val REASONING_BACKFILL_PLACEHOLDER = "…"

/**
 * 「思考模式下，带 `tool_calls` 的 assistant 消息**必须回传思维链**」——部分 OpenAI 兼容网关的
 * **结构性校验**（与内容无关，只看字段在不在）。
 *
 * 证据（内部进度记录（未随本仓发布） Bug #56，对 opencode `zen/go/v1` 的 curl 实测）：
 * - chat/completions：带 tool_calls 的 assistant 消息缺 `reasoning_content` → 400
 *   「reasoning_content must be passed back」（**空串可接受**）
 * - responses：缺 reasoning item → 400「reasoning_text must be passed back」
 *   （**空文本拒绝、非空即可、id 可选**）
 * - 不带 tool_calls 的消息不校验
 *
 * **⚠️ 2026-10-13 真机修正：「不带 tool_calls 的消息不校验」对 `api.deepseek.com` 不成立。**
 * 同一份 17KB 报文在设备上重放（同一把 key、同一命令，只换报文）得到的实测矩阵：
 *
 * | 变体 | thinking | 结果 |
 * |---|---|---|
 * | 原报文：末条是**普通 assistant**（无 tool_calls、无 reasoning_content） | enabled | **400** |
 * | 给末条补上 `reasoning_content`（**空串也行**，占位「…」也行） | enabled | **200** |
 * | 删掉带 tool_calls 那条的 `reasoning_content` | enabled | **400** |
 * | 删掉末条普通 assistant | enabled | **200** |
 * | `thinking:{"type":"disabled"}` | disabled | **200** |
 *
 * 即：**思考模式下每条 assistant 消息都要有 `reasoning_content`**（不只带工具调用的那些），
 * 且**空串被接受**。所以回填判据里不能再要求 `tools.isNotEmpty()`（见
 * `ChatCompletionsAPI.buildAssistantMessageJson`）。
 *
 * 触发场景＝那条 assistant 消息**没有思维链可回传**：开启思考之前产生的旧消息、
 * 上游只返回加密推理（本仓按 #1719 不回放明文）、用户关掉了「回传历史思考过程」、
 * 或消息被编辑/重新生成过。此前的行为是**直接省略该字段** ⇒ 整个请求被网关拒掉，
 * 用户侧表现为「这个模型突然用不了」。
 *
 * 门槛取「**host 是已知会校验的网关** 或 **模型自身带 REASONING 能力**」，且**前者优先**：
 * 判定方是网关，`host` 是我们唯一能观测到的网关特征。实测两例（turbo Bug #56 的 opencode zen/go、
 * 2026-10-03 的 DeepSeek Responses）都发生在输入里**完全没有** reasoning item 时仍然 400，说明
 * 网关的「思考模式」判据既不在请求的 reasoning 字段里，也不等于本地模型表的 REASONING 能力
 * （会话里换过模型时，历史仍带着思考轮次产生的 tool_calls）。故已知校验的 host **不看模型能力**。
 *
 * 模型能力只用于 host 未知的兜底：未知 host + 思考模型 ⇒ 回填（`reasoning_content` 是通行扩展
 * 字段）；未知 host + 非思考模型 ⇒ 不回填，不凭空多出一个字段（那会引入另一类兼容风险）。
 */
internal fun requiresToolReasoningBackfill(host: String, model: Model): Boolean =
    isKnownReasoningTextValidator(host) || learnedReasoningTextValidators.contains(host) ||
        model.abilities.contains(ModelAbility.REASONING)

/**
 * **从错误自学习**的 host 集合（2026-10-13 用户拍板）。
 *
 * ## 为什么需要它
 *
 * 上面那个门槛是**静态白名单**：只有 `opencode.ai` / `deepseek.com` 两个已实测的 host，
 * 或者模型自己声明了 `REASONING` 能力。可现实里会校验这个字段的**第三方中转网关**远不止两个，
 * 而它们提供的模型（如 GLM 系经中转）在我们的模型表里未必带 `REASONING` 能力 ⇒ 门槛不成立
 * ⇒ 请求被 400 拒掉，用户看到「这个模型突然用不了」。
 *
 * 真机案例（2026-10-13）：做梦（记忆整理）是多步工具调用，第二轮就带上了 `tool_calls`，
 * 网关直接返回 `400 The reasoning_content in the thinking mode must be passed back to the API.`
 * ——正是本文件开头那条签名，但因为 host 不在白名单、模型没标能力，回填没触发。
 *
 * ## 语义
 *
 * 一旦某个 host **真的**返回了那条签名的 400，就把它记进来：之后的请求对它一律回填。
 * 这样任何没见过的网关都能自愈，不必每遇到一个就往静态名单里加一条（那种做法永远滞后）。
 *
 * 只活在**进程内**：换进程重来一次（多花一次 400，可忽略），避免为一个纯优化写盘。
 */
private val learnedReasoningTextValidators = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

/**
 * 这条错误是不是「思考模式必须回传思维链」的**结构校验**？
 *
 * 判据用报文里的两个特征串，缺一不可：
 * - `reasoning_content`：字段名（Responses 路径那条是 `reasoning_text`，不在此列）；
 * - `passed back`：各家措辞在这句上一致（`must be passed back to the API`）。
 *
 * **为什么不用 HTTP 400 单独判定**：400 的原因太多了（参数错、上下文超限、额度），
 * 只按状态码记住一个 host 会让一次无关的 400 永久改变该 host 的行为。
 * 宁可漏判（下次再学一遍），不要误判。
 */
internal fun isReasoningBackfillError(message: String?): Boolean {
    val text = message ?: return false
    return text.contains("reasoning_content") && text.contains("passed back")
}

/** 记下这个 host 会做该校验（幂等）。 */
internal fun learnReasoningTextValidator(host: String) {
    if (host.isNotBlank()) learnedReasoningTextValidators.add(host)
}

/** 仅供测试：清掉学习到的 host，避免用例之间互相影响。 */
internal fun resetLearnedReasoningTextValidatorsForTest() {
    learnedReasoningTextValidators.clear()
}

/**
 * **已知**会做「思考模式必须回传思维链」这一结构校验的 host（都有实测来源）：
 * - `opencode.ai`（turbo Bug #56 对 `zen/go/v1` 的 curl 实测：chat/completions 缺 `reasoning_content`、responses 缺 reasoning item 均 400）
 * - `deepseek.com`（2026-10-03 实测：Responses 请求里 reasoning item **只有 `encrypted_content`、
 *   没有规范必填的 `content[].reasoning_text`** 时 400「The `reasoning_text` in the thinking mode must be passed back」）
 *
 * **Responses 路径用它做门槛**（而不是用「模型带 REASONING」）：因为校验要求的是
 * **结构字段**（`content[].reasoning_text` 必须非空），而我们不知道别的 Responses 提供方
 * （OpenAI 官方等）接不接受「占位 content + encrypted_content」的组合——宽门槛会把未经
 * 验证的形态推给它们。chat/completions 路径则不同：`reasoning_content` 是 DeepSeek/GLM/Qwen/Kimi
 * 等通行的扩展字段，故那边放宽到「思考模型」+**自学习到的 host**，见 [requiresToolReasoningBackfill]。
 */
internal fun isKnownReasoningTextValidator(host: String): Boolean =
    host.endsWith("opencode.ai") || host.endsWith("deepseek.com")
