package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「思考模式下带 tool_calls 的消息必须回传思维链」的**门槛**（见 [requiresToolReasoningBackfill]）。
 *
 * 门槛要同时满足两件事：覆盖已知会校验的网关（opencode 系）与本地可判定的「思考模式」（模型带
 * REASONING 能力）；同时**不能让非思考模型凭空多出一个字段**（那会带来另一类兼容风险）。
 */
class ToolReasoningBackfillTest {

    private fun model(vararg abilities: ModelAbility) = Model(
        modelId = "test-model",
        abilities = abilities.toList(),
    )

    @Test
    fun `opencode 系 host 一律回填（已知校验方）`() {
        assertTrue(requiresToolReasoningBackfill("opencode.ai", model()))
        assertTrue(requiresToolReasoningBackfill("api.opencode.ai", model()))
    }

    @Test
    fun `思考模型在其他 host 上也要回填（DeepSeek Responses 实测会校验）`() {
        assertTrue(requiresToolReasoningBackfill("api.deepseek.com", model(ModelAbility.REASONING, ModelAbility.TOOL)))
        assertTrue(requiresToolReasoningBackfill("relay.example.com", model(ModelAbility.REASONING)))
    }

    @Test
    fun `非思考模型不回填`() {
        assertFalse(requiresToolReasoningBackfill("api.openai.com", model(ModelAbility.TOOL)))
        assertFalse(requiresToolReasoningBackfill("api.deepseek.com", model(ModelAbility.TOOL)))
    }
}
