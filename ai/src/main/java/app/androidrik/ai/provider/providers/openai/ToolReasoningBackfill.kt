package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility

/** Responses API 的回填写占位文本：它**拒绝空文本**，非空即可（内容无实义，仅满足结构校验）。 */
internal const val REASONING_BACKFILL_PLACEHOLDER = "…"

/**
 * 「思考模式下，带 `tool_calls` 的 assistant 消息**必须回传思维链**」——部分 OpenAI 兼容网关的
 * **结构性校验**（与内容无关，只看字段在不在）。
 *
 * 证据（`docs/references/turbo-PROGRESS.md` Bug #56，对 opencode `zen/go/v1` 的 curl 实测）：
 * - chat/completions：带 tool_calls 的 assistant 消息缺 `reasoning_content` → 400
 *   「reasoning_content must be passed back」（**空串可接受**）
 * - responses：缺 reasoning item → 400「reasoning_text must be passed back」
 *   （**空文本拒绝、非空即可、id 可选**）
 * - **不带 tool_calls 的消息不校验**
 *
 * 触发场景＝那条带工具调用的历史消息**没有思维链可回传**：开启思考之前产生的旧消息、
 * 上游只返回加密推理（本仓按 #1719 不回放明文）、用户关掉了「回传历史思考过程」、
 * 或消息被编辑/重新生成过。此前的行为是**直接省略该字段** ⇒ 整个请求被网关拒掉，
 * 用户侧表现为「这个模型突然用不了」。
 *
 * 门槛取「**host 属 opencode 系** 或 **模型自身带 REASONING 能力**」：前者是已知会校验的网关，
 * 后者是「思考模式」在本地唯一可判定的近似。非思考模型不受影响——不会凭空多出一个字段。
 */
internal fun requiresToolReasoningBackfill(host: String, model: Model): Boolean =
    host.endsWith("opencode.ai") || model.abilities.contains(ModelAbility.REASONING)
