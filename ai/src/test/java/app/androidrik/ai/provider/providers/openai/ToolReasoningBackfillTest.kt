package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「思考模式下带 tool_calls 的消息必须回传思维链」的**门槛**（见 [requiresToolReasoningBackfill]）。
 *
 * 门槛的主判据是**网关**（host）：已知会校验的 host 一律回填。模型能力只在 host 未知时作为
 * 补充信号——未知 host + 思考模型也回填（`reasoning_content` 是通行扩展字段），而未知 host +
 * 非思考模型不回填，避免凭空多出一个字段带来的兼容风险。
 */
class ToolReasoningBackfillTest {

    private fun model(vararg abilities: ModelAbility) = Model(
        modelId = "test-model",
        abilities = abilities.toList(),
    )

    @Test
    fun `已知校验 host（opencode 系与 deepseek 系）一律回填`() {
        assertTrue(isKnownReasoningTextValidator("opencode.ai"))
        assertTrue(isKnownReasoningTextValidator("api.opencode.ai"))
        assertTrue(isKnownReasoningTextValidator("api.deepseek.com"))
        // Responses 路径据此判定（chat 路径另见 requiresToolReasoningBackfill）
        assertTrue(requiresToolReasoningBackfill("opencode.ai", model()))
        assertTrue(requiresToolReasoningBackfill("api.deepseek.com", model()))
    }

    @Test
    fun `思考模型在其他 host 上仍回填（chat 路径：reasoning_content 是通行扩展字段）`() {
        assertTrue(requiresToolReasoningBackfill("relay.example.com", model(ModelAbility.REASONING)))
        assertFalse(isKnownReasoningTextValidator("relay.example.com"))
    }

    @Test
    fun `未知 host 上的非思考模型不回填（不凭空多出字段）`() {
        assertFalse(requiresToolReasoningBackfill("api.openai.com", model(ModelAbility.TOOL)))
        assertFalse(requiresToolReasoningBackfill("relay.example.com", model(ModelAbility.TOOL)))
    }

    @Test
    fun `已知校验 host 不看模型能力（判定方是网关，不是本地模型表）`() {
        // 实测两例都发生在「输入里**完全没有** reasoning item」时仍然 400（turbo Bug #56 的
        // opencode zen/go 与 2026-10-03 的 DeepSeek Responses），说明网关的「思考模式」判据
        // 不在请求的 reasoning 字段里，也不在我们本地的 REASONING 能力上（会话里换过模型时，
        // 历史仍带着思考轮次的 tool_calls）⇒ 已知会校验的 host 一律回填。
        assertTrue(requiresToolReasoningBackfill("api.deepseek.com", model(ModelAbility.TOOL)))
        assertTrue(requiresToolReasoningBackfill("api.opencode.ai", model(ModelAbility.TOOL)))
    }
}
