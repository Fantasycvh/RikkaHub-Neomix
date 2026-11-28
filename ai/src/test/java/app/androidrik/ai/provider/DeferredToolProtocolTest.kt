package app.androidrik.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class DeferredToolProtocolTest {

    private fun toolModel(
        modelId: String,
        tools: Set<BuiltInTools> = emptySet(),
        abilities: List<ModelAbility> = listOf(ModelAbility.TOOL),
    ) = Model(
        modelId = modelId,
        abilities = abilities,
        tools = tools,
    )

    @Test
    fun `deepseek-flash uses RESPONSES_TOOL_SEARCH when Responses API is enabled on official endpoint`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
            useResponseApi = true,
        )
        val model = toolModel("deepseek-flash")
        assertEquals(DeferredToolProtocol.RESPONSES_TOOL_SEARCH, model.deferredToolProtocol(provider))
    }

    @Test
    fun `deepseek-flash uses NONE when Chat Completions is selected`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
            useResponseApi = false,
        )
        val model = toolModel("deepseek-flash")
        assertEquals(DeferredToolProtocol.NONE, model.deferredToolProtocol(provider))
    }

    @Test
    fun `deepseek-flash uses ANTHROPIC_DEFER_LOADING on DeepSeek Anthropic endpoint`() {
        val provider = ProviderSetting.Claude(
            baseUrl = "https://api.deepseek.com/anthropic/v1",
        )
        val model = toolModel("deepseek-flash")
        assertEquals(DeferredToolProtocol.ANTHROPIC_DEFER_LOADING, model.deferredToolProtocol(provider))
    }

    @Test
    fun `deepseek-v4-pro does not enable deferred loading on Responses or Anthropic`() {
        val responsesProvider = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
            useResponseApi = true,
        )
        val claudeProvider = ProviderSetting.Claude(
            baseUrl = "https://api.deepseek.com/anthropic/v1",
        )
        val model = toolModel("deepseek-v4-pro")
        assertEquals(DeferredToolProtocol.NONE, model.deferredToolProtocol(responsesProvider))
        assertEquals(DeferredToolProtocol.NONE, model.deferredToolProtocol(claudeProvider))
    }

    @Test
    fun `deepseek web tools mutually exclude deferred loading`() {
        val responsesProvider = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
            useResponseApi = true,
        )
        val claudeProvider = ProviderSetting.Claude(
            baseUrl = "https://api.deepseek.com/anthropic/v1",
        )
        val withSearch = toolModel("deepseek-flash", tools = setOf(BuiltInTools.DeepSeekWebSearch))
        val withReadUrl = toolModel("deepseek-flash", tools = setOf(BuiltInTools.DeepSeekReadUrl))

        assertEquals(DeferredToolProtocol.NONE, withSearch.deferredToolProtocol(responsesProvider))
        assertEquals(DeferredToolProtocol.NONE, withReadUrl.deferredToolProtocol(responsesProvider))
        assertEquals(DeferredToolProtocol.NONE, withSearch.deferredToolProtocol(claudeProvider))
        assertEquals(DeferredToolProtocol.NONE, withReadUrl.deferredToolProtocol(claudeProvider))
    }

    @Test
    fun `non-official gateway host does not activate deferred loading`() {
        val gateway = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com.mirror.example/v1",
            useResponseApi = true,
        )
        assertEquals(DeferredToolProtocol.NONE, toolModel("deepseek-flash").deferredToolProtocol(gateway))
    }

    @Test
    fun `official Anthropic Claude 4_5+ and 5 models enable ANTHROPIC_DEFER_LOADING`() {
        val anthropic = ProviderSetting.Claude(baseUrl = "https://api.anthropic.com/v1")
        listOf(
            "claude-opus-4-8",
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            "claude-sonnet-4-5-20250929",
            "claude-haiku-4-5",
            "claude-fable-5",
            "claude-sonnet-5",
        ).forEach { id ->
            assertEquals(
                "Expected ANTHROPIC_DEFER_LOADING for $id",
                DeferredToolProtocol.ANTHROPIC_DEFER_LOADING,
                toolModel(id).deferredToolProtocol(anthropic),
            )
        }
    }

    @Test
    fun `older Anthropic Claude models do not enable ANTHROPIC_DEFER_LOADING`() {
        val anthropic = ProviderSetting.Claude(baseUrl = "https://api.anthropic.com/v1")
        listOf(
            "claude-3-7-sonnet-20250219",
            "claude-opus-4-1-20250805",
            "claude-sonnet-4-20250514",
        ).forEach { id ->
            assertEquals(
                "Expected NONE for $id",
                DeferredToolProtocol.NONE,
                toolModel(id).deferredToolProtocol(anthropic),
            )
        }
    }

    @Test
    fun `model without TOOL ability always returns NONE`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
            useResponseApi = true,
        )
        val model = toolModel("deepseek-flash", abilities = emptyList())
        assertEquals(DeferredToolProtocol.NONE, model.deferredToolProtocol(provider))
    }
}
