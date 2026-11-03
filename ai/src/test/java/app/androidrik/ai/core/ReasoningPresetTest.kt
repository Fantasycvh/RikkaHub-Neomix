package app.androidrik.ai.core

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReasoningPresetTest {
    private val low = ReasoningPreset(id = "low-custom", name = "快速", level = ReasoningLevel.LOW, effort = "minimal", budgetTokens = 128)
    private val high = ReasoningPreset(id = "high-custom", name = "深入", level = ReasoningLevel.HIGH, effort = "ultra", budgetTokens = 64000)
    private val model = Model(reasoningOverride = ReasoningOverride(presets = listOf(low, high)))

    @Test
    fun `named mappings survive serialization and retain stable identity`() {
        val encoded = Json.encodeToString(Model.serializer(), model)
        assertEquals(model, Json.decodeFromString(Model.serializer(), encoded))
        assertTrue(Json.decodeFromString<ReasoningOverride>("{}").presets.isEmpty())
    }

    @Test
    fun `each selection sends its own mapping without changing saved model`() {
        val first = resolveReasoningPreset(model, null, low.id)!!
        val second = resolveReasoningPreset(model, null, high.id)!!
        assertEquals(ReasoningLevel.LOW, first.level)
        assertEquals(ReasoningOverride("minimal", 128), model.withReasoningPreset(first).reasoningOverride)
        assertEquals(ReasoningOverride("ultra", 64000), model.withReasoningPreset(second).reasoningOverride)
        assertEquals(2, model.reasoningOverride!!.presets.size)
    }

    @Test
    fun `missing selection after deletion or model switch falls back safely`() {
        assertNull(resolveReasoningPreset(model, null, "deleted"))
        assertNull(resolveReasoningPreset(Model(), null, high.id))
        assertSame(model, model.withReasoningPreset(null))
    }

    @Test
    fun `provider presets are available and model wins duplicate ids`() {
        val provider = ProviderSetting.OpenAI(reasoningOverride = ReasoningOverride(
            effort = "legacy", presets = listOf(low.copy(name = "provider"), high)))
        val local = Model(reasoningOverride = ReasoningOverride(presets = listOf(low)))
        assertEquals(listOf(low, high), reasoningPresets(local, provider))
        assertEquals(low, resolveReasoningPreset(local, provider, low.id))
        assertEquals(high, resolveReasoningPreset(Model(), provider, high.id))
        assertEquals("legacy", resolveReasoningOverride(local, provider)?.effort)
    }

    @Test
    fun `blank preset values use built in mapping instead of legacy overrides`() {
        val preset = high.copy(effort = null, budgetTokens = null)
        val provider = ProviderSetting.OpenAI(reasoningOverride = ReasoningOverride("legacy", 42))
        assertEquals(ReasoningOverride(), resolveReasoningOverride(model.withReasoningPreset(preset), provider))
    }
}
