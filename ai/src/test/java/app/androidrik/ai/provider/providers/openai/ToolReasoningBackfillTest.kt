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

    /**
     * 学习到的东西是**进程级**的（设计如此：不写盘、重启即失）。同进程里跑多个用例时
     * 会互相污染（本类就有用例断言「学之前不回填」），所以每条用例前清一次。
     */
    @org.junit.Before
    fun resetLearnedHosts() {
        resetLearnedReasoningTextValidatorsForTest()
    }

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

    // ---- 从错误自学习（2026-10-13 用户拍板）----

    @Test
    fun `识别真机上那条结构校验的 400`() {
        // 2026-10-13 真机原文（记忆整理时被网关拒掉的那条）
        val real = "Failed to get response: 400 {\"error\":{\"message\":\"The `reasoning_content` " +
            "in the thinking mode must be passed back to the API. (request_id: 312a342b-...)\"," +
            "\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"invalid_request_error\"}}"

        assertTrue("这就是要自学习的签名", isReasoningBackfillError(real))
    }

    @Test
    fun `别的 400 不能误判（否则一次无关错误会永久改变该 host）`() {
        assertFalse(isReasoningBackfillError(null))
        assertFalse(isReasoningBackfillError(""))
        // 只 400、没有签名
        assertFalse(isReasoningBackfillError("Failed to get response: 400 Bad Request"))
        // 上下文超限
        assertFalse(
            isReasoningBackfillError(
                "400 {\"error\":{\"message\":\"This model's maximum context length is 128000 tokens\"}}",
            ),
        )
        // 提到 reasoning_content 但不是「回传」这条（例如模型不支持该字段）
        assertFalse(
            isReasoningBackfillError(
                "400 {\"error\":{\"message\":\"Unsupported parameter: reasoning_content\"}}",
            ),
        )
        // 提到 passed back 但不是 reasoning_content（Responses 路径那条 reasoning_text 不在此列）
        assertFalse(
            isReasoningBackfillError(
                "400 {\"error\":{\"message\":\"The `reasoning_text` in the thinking mode must be passed back\"}}",
            ),
        )
    }

    @Test
    fun `学到之后该 host 就一律回填（即便 host 未知、模型也不标能力）`() {
        val host = "relay.example.com"
        val plainModel = model(ModelAbility.TOOL)

        assertFalse("学之前：host 未知、模型非思考 ⇒ 不回填", requiresToolReasoningBackfill(host, plainModel))
        assertFalse(isKnownReasoningTextValidator(host))

        learnReasoningTextValidator(host)

        assertTrue(
            "撞过一次该签名之后必须回填，否则每次都要再花一次 400",
            requiresToolReasoningBackfill(host, plainModel),
        )
        // 只影响这一个 host，不能连带放宽别的网关
        assertFalse(requiresToolReasoningBackfill("other.example.com", plainModel))
        // 静态名单语义不变
        assertFalse(isKnownReasoningTextValidator(host))
    }

    @Test
    fun `学习是幂等且忽略空 host`() {
        learnReasoningTextValidator("relay.example.com")
        learnReasoningTextValidator("relay.example.com")
        learnReasoningTextValidator("")

        assertTrue(requiresToolReasoningBackfill("relay.example.com", model()))
        assertFalse("空串不该被记成一个 host", requiresToolReasoningBackfill("", model()))
    }
}
