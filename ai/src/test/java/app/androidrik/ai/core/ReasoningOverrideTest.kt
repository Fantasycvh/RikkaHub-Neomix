package app.androidrik.ai.core

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 思考强度覆盖（[ReasoningOverride]）的**解析链**与**序列化兼容**单测。
 *
 * 解析链是 `模型级 → 提供商级 → 内置映射`，各 provider 只调用
 * [resolveReasoningOverride]，因此这段优先级只要有一个地方错了，所有方言一起错。
 * 序列化兼容性决定老用户升级后设置能否直接读（新增字段必须可缺省 = null）。
 */
class ReasoningOverrideTest {

    private fun model(override: ReasoningOverride?) = Model(
        modelId = "test-model",
        reasoningOverride = override,
    )

    private fun provider(override: ReasoningOverride?) =
        ProviderSetting.OpenAI(reasoningOverride = override)

    @Test
    fun `模型与提供商都无覆盖时返回 null`() {
        assertNull(resolveReasoningOverride(model(null), provider(null)))
    }

    @Test
    fun `提供商设置为 null 且模型无覆盖时返回 null`() {
        assertNull(resolveReasoningOverride(model(null), null))
    }

    @Test
    fun `只有模型级覆盖时用模型级`() {
        val override = ReasoningOverride(effort = "model-pro")
        assertEquals(override, resolveReasoningOverride(model(override), provider(null)))
        // ProviderSetting 为 null（如模型自带的 providerOverwrite 解析前）也只走模型级
        assertEquals(override, resolveReasoningOverride(model(override), null))
    }

    @Test
    fun `只有提供商级覆盖时用它兜底`() {
        val override = ReasoningOverride(budgetTokens = 12_345)
        assertEquals(override, resolveReasoningOverride(model(null), provider(override)))
    }

    @Test
    fun `两级都有时模型级优先`() {
        val modelOverride = ReasoningOverride(effort = "model-pro", budgetTokens = 1)
        val providerOverride = ReasoningOverride(effort = "provider-pro", budgetTokens = 2)
        assertEquals(modelOverride, resolveReasoningOverride(model(modelOverride), provider(providerOverride)))
    }

    @Test
    fun `旧数据缺字段时两个自由度都反序列化为 null`() {
        // 升级前保存的 JSON 没有这个对象/字段，必须回落到「跟随内置映射」
        val empty = Json.decodeFromString<ReasoningOverride>("{}")
        assertEquals(ReasoningOverride(), empty)
        assertNull(empty.effort)
        assertNull(empty.budgetTokens)
    }

    @Test
    fun `只填一个自由度时另一个保持 null`() {
        val effortOnly = Json.decodeFromString<ReasoningOverride>("""{"effort":"pro"}""")
        assertEquals("pro", effortOnly.effort)
        assertNull(effortOnly.budgetTokens)

        val budgetOnly = Json.decodeFromString<ReasoningOverride>("""{"budgetTokens":12345}""")
        assertNull(budgetOnly.effort)
        assertEquals(12_345, budgetOnly.budgetTokens)
    }

    @Test
    fun `序列化往返不丢字段`() {
        val original = ReasoningOverride(effort = "pro", budgetTokens = 12_345)
        val json = Json.encodeToString(ReasoningOverride.serializer(), original)
        assertEquals(original, Json.decodeFromString(ReasoningOverride.serializer(), json))
    }
}
