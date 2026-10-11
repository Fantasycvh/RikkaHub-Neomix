package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility

/** Responses API 的回填写占位文本：它**拒绝空文本**，非空即可（内容无实义，仅满足结构校验）。 */
internal const val REASONING_BACKFILL_PLACEHOLDER = "…"

/**
 * 「思考模式下，带 `tool_calls` 的 assistant 消息**必须回传思维链**」——部分 OpenAI 兼容网关的
 * **结构性校验**（与内容无关，只看字段在不在）。
 *
 * 证据（私有仓记录·进度 Bug #56，对 opencode `zen/go/v1` 的 curl 实测）：
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
    isKnownReasoningTextValidator(host) || model.abilities.contains(ModelAbility.REASONING)

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
 * 等通行的扩展字段，故那边放宽到「思考模型」即可，见 [requiresToolReasoningBackfill]。
 */
internal fun isKnownReasoningTextValidator(host: String): Boolean =
    host.endsWith("opencode.ai") || host.endsWith("deepseek.com")
