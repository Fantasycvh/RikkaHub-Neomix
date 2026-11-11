package app.androidrik.ai.util

import app.androidrik.ai.provider.CustomBody
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningOverrideKeysTest {
    @Test
    fun `detects reasoning keys written by custom body`() {
        val keys = listOf(
            CustomBody("reasoning_effort", JsonPrimitive("high")),
            CustomBody("  thinking ", JsonPrimitive(true)),
            CustomBody("temperature", JsonPrimitive(0.3)),
        ).reasoningOverrideKeys()

        assertEquals(listOf("reasoning_effort", "thinking"), keys.toList())
    }

    @Test
    fun `ignores blank and unrelated keys`() {
        val keys = listOf(
            CustomBody("", JsonPrimitive("x")),
            CustomBody("  ", JsonPrimitive("x")),
            CustomBody("thinkingConfig", JsonPrimitive("x")),
            CustomBody("model", JsonPrimitive("x")),
        ).reasoningOverrideKeys()

        assertTrue(keys.isEmpty())
    }

    @Test
    fun `merge still overrides while conflict keys are reportable`() {
        val body = buildJsonObject { put("reasoning_effort", JsonPrimitive("low")) }
        val custom = listOf(CustomBody("reasoning_effort", JsonPrimitive("high")))

        val merged = body.mergeCustomBody(custom)

        assertEquals("high", merged["reasoning_effort"]!!.toString().trim('"'))
        assertEquals(setOf("reasoning_effort"), custom.reasoningOverrideKeys())
    }
}
