package app.androidrik.rikkahub.data.datastore

import app.androidrik.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }

    @Test
    fun `default new api provider should use token usage endpoint for balance`() {
        val newApiProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "New API" }

        assertEquals(1, newApiProviders.size)

        val provider = newApiProviders.single()
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/api/usage/token/", provider.balanceOption.apiPath)
        assertEquals("(data.total_granted - data.total_used) / 500000", provider.balanceOption.resultPath)
        assertTrue(provider.balanceOption.sources.isEmpty())
    }

    @Test
    fun `default agent router provider should use token usage endpoint for balance`() {
        val agentRouterProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Agent Router" }

        assertEquals(1, agentRouterProviders.size)

        val provider = agentRouterProviders.single()
        assertEquals("https://agentrouter.org/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/api/usage/token/", provider.balanceOption.apiPath)
        assertEquals("(data.total_granted - data.total_used) / 500000", provider.balanceOption.resultPath)
    }
}
